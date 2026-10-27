/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.compute

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, UUID}

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api.NotebookException

/**
 * Issues scoped credentials for managed runners and authorizes each physical session attach.
 * The bearer token is returned once; only its SHA-256 hash is persisted.
 */
class ComputeBindingService(
    conf: KyuubiConf,
    store: ComputeStore,
    allocations: ComputeAllocationService,
    clock: () => Long = () => System.currentTimeMillis()) {

  private val trustedRunnerUsers = conf.get(COMPUTE_BINDING_TRUSTED_RUNNER_USERS)
  private val bindingTtl = conf.get(COMPUTE_BINDING_TTL)
  private val reservationTtl = conf.get(COMPUTE_ATTACH_RESERVATION_TTL)
  private val secureRandom = new SecureRandom()

  def issue(allocationId: String, runnerUser: String): IssuedComputeBinding =
    issue(allocationId, runnerUser, bindingTtl)

  private[compute] def issue(
      allocationId: String,
      runnerUser: String,
      ttlMillis: Long): IssuedComputeBinding = {
    requireTrustedRunner(runnerUser)
    require(ttlMillis > 0, "compute binding TTL must be positive")
    val allocation = usableJobAllocation(allocationId)
    val runId = allocation.runId.get
    val attemptId = allocation.attemptId.get
    val now = clock()
    val token = newToken()
    val binding = ComputeBinding(
      id = UUID.randomUUID().toString,
      allocationId = allocation.id,
      runnerUser = runnerUser,
      effectiveUser = allocation.effectiveUser,
      profileId = allocation.profileId,
      runId = runId,
      attemptId = attemptId,
      tokenHash = hashToken(token),
      issuedAt = now,
      expiresAt = safeExpiry(now, ttlMillis),
      revokedAt = None,
      version = 1L)
    if (!store.createBinding(binding)) {
      throw new KyuubiException("Could not persist a new compute binding")
    }
    IssuedComputeBinding(binding.id, allocation.id, token, binding.expiresAt)
  }

  def rotate(bindingId: String, runnerUser: String): IssuedComputeBinding = {
    requireTrustedRunner(runnerUser)
    val existing = activeBinding(bindingId)
    if (existing.runnerUser != runnerUser) {
      denyAttach()
    }
    val replacement = issue(existing.allocationId, runnerUser)
    if (!store.revokeBinding(existing.id, existing.version, clock())) {
      val created = store.getBinding(replacement.bindingId).get
      store.revokeBinding(created.id, created.version, clock())
      throw NotebookException.versionConflict("Compute binding changed while it was rotated")
    }
    replacement
  }

  def revoke(bindingId: String): Boolean =
    store.getBinding(bindingId).exists { binding =>
      binding.revokedAt.nonEmpty || store.revokeBinding(binding.id, binding.version, clock())
    }

  def revokeAllocation(allocationId: String): Int =
    store.revokeBindingsForAllocation(allocationId, clock())

  def reserveAttach(
      bindingId: String,
      token: String,
      allocationId: String,
      authenticatedRunner: String,
      effectiveUser: String,
      profileId: String,
      runId: String,
      attemptId: String): AuthorizedComputeAttach = {
    requireTrustedRunner(authenticatedRunner)
    val now = clock()
    val binding = activeBinding(bindingId, now)
    val scopeMatches = binding.allocationId == allocationId &&
      binding.runnerUser == authenticatedRunner && binding.effectiveUser == effectiveUser &&
      binding.profileId == profileId && binding.runId == runId &&
      binding.attemptId == attemptId
    if (!scopeMatches || !tokenMatches(binding.tokenHash, token)) {
      denyAttach()
    }
    val allocation = usableJobAllocation(allocationId)
    if (allocation.effectiveUser != effectiveUser || allocation.profileId != profileId ||
      !allocation.runId.contains(runId) || !allocation.attemptId.contains(attemptId)) {
      denyAttach()
    }
    val reservation = ComputeAttachReservation(
      id = UUID.randomUUID().toString,
      bindingId = binding.id,
      allocationId = allocation.id,
      runnerUser = authenticatedRunner,
      effectiveUser = effectiveUser,
      profileId = profileId,
      runId = runId,
      attemptId = attemptId,
      state = ComputeAttachState.RESERVED,
      expiresAt = math.min(binding.expiresAt, safeExpiry(now, reservationTtl)),
      createdAt = now,
      confirmedAt = None,
      releasedAt = None,
      version = 1L)
    if (!store.createAttachReservation(reservation, binding.tokenHash, now)) {
      denyAttach()
    }
    AuthorizedComputeAttach(reservation, allocation, allocations.routingConfiguration(allocation))
  }

  /** A false result means the caller must close the session it just opened. */
  def confirmAttach(reservationId: String): Boolean =
    store.confirmAttachReservation(reservationId, clock())

  def releaseAttach(reservationId: String): Boolean =
    store.releaseAttachReservation(reservationId, clock())

  private def activeBinding(id: String, now: Long = clock()): ComputeBinding =
    store.getBinding(id).filter(binding => binding.revokedAt.isEmpty && binding.expiresAt > now)
      .getOrElse(denyAttach())

  private def usableJobAllocation(id: String): ComputeAllocation =
    store.getAllocation(id).filter { allocation =>
      allocation.mode == ComputeMode.JOB &&
      Set(
        ComputeAllocationState.RESERVED,
        ComputeAllocationState.STARTING,
        ComputeAllocationState.ACTIVE).contains(allocation.state) &&
      allocation.runId.exists(_.nonEmpty) && allocation.attemptId.exists(_.nonEmpty)
    }.getOrElse(denyAttach())

  private def requireTrustedRunner(runnerUser: String): Unit = {
    if (runnerUser.trim.isEmpty || !trustedRunnerUsers.contains(runnerUser)) {
      denyAttach()
    }
  }

  private def newToken(): String = {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def hashToken(token: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(Option(token).getOrElse("").getBytes(StandardCharsets.UTF_8))
    digest.map(byte => f"${byte & 0xFF}%02x").mkString
  }

  private def tokenMatches(expectedHash: String, token: String): Boolean =
    MessageDigest.isEqual(
      expectedHash.getBytes(StandardCharsets.UTF_8),
      hashToken(token).getBytes(StandardCharsets.UTF_8))

  private def safeExpiry(now: Long, ttlMillis: Long): Long =
    try Math.addExact(now, ttlMillis)
    catch {
      case _: ArithmeticException => throw NotebookException.invalid("Compute binding TTL overflow")
    }

  private def denyAttach(): Nothing =
    throw NotebookException.accessDenied("Managed compute binding is invalid or no longer usable")
}
