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

import java.util
import java.util.UUID

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.engineprofile.EngineProfilePrincipal
import org.apache.kyuubi.server.notebook.NotebookTestBase
import org.apache.kyuubi.server.notebook.api.{EngineProfile, NotebookErrorCode, UpsertEngineProfileRequest}

class ComputeBindingServiceSuite extends NotebookTestBase {

  private def createProfile(): EngineProfile = {
    val request = new UpsertEngineProfileRequest
    request.setName(s"binding-${UUID.randomUUID().toString.take(8)}")
    request.setSparkConfig(new util.HashMap(Map("spark.driver.memory" -> "3g").asJava))
    manager.engineProfiles.create(
      EngineProfilePrincipal(alice.user, alice.admin),
      request)
  }

  private def reserveJob(profile: EngineProfile): ComputeAllocation =
    manager.computeAllocations.reserveJob(
      EngineProfilePrincipal(alice.user, alice.admin),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)

  private def reserveAttach(
      service: ComputeBindingService,
      issued: IssuedComputeBinding,
      allocation: ComputeAllocation,
      token: String = null,
      effectiveUser: String = alice.user,
      profileId: String = null,
      attemptId: String = null,
      authenticatedRunner: String = "dbt-runner"): AuthorizedComputeAttach =
    service.reserveAttach(
      bindingId = issued.bindingId,
      token = Option(token).getOrElse(issued.token),
      allocationId = allocation.id,
      authenticatedRunner = authenticatedRunner,
      effectiveUser = effectiveUser,
      profileId = Option(profileId).getOrElse(allocation.profileId),
      runId = allocation.runId.get,
      attemptId = Option(attemptId).getOrElse(allocation.attemptId.get))

  private def release(allocation: ComputeAllocation): Unit = {
    val current = manager.computeStore.getAllocation(allocation.id).get
    val now = math.max(System.currentTimeMillis(), current.updatedAt + 1)
    val releasing = current.copy(
      state = ComputeAllocationState.RELEASING,
      updatedAt = now,
      version = current.version + 1)
    assert(manager.computeStore.updateAllocation(releasing, current.version, Set(current.state)))
    val released = releasing.copy(
      state = ComputeAllocationState.RELEASED,
      updatedAt = now + 1,
      releasedAt = Some(now + 1),
      version = releasing.version + 1)
    assert(manager.computeStore.updateAllocation(
      released,
      releasing.version,
      Set(ComputeAllocationState.RELEASING)))
  }

  test("persists only a token hash and authorizes attach across store instances") {
    val allocation = reserveJob(createProfile())
    val issued = manager.computeBindings.issue(allocation.id, "dbt-runner")
    val stored = manager.computeStore.getBinding(issued.bindingId).get

    assert(stored.tokenHash !== issued.token)
    assert(stored.tokenHash.length === 64)
    val peerStore = new JDBCComputeStore(conf)
    val peer = new ComputeBindingService(conf, peerStore, manager.computeAllocations)
    try {
      val attach = reserveAttach(peer, issued, allocation)
      assert(attach.allocation.id === allocation.id)
      assert(attach.routingConfiguration("spark.driver.memory") === "3g")
      assert(attach.routingConfiguration("kyuubi.engine.share.level.subdomain") ===
        allocation.subdomain)
      assert(peer.confirmAttach(attach.reservation.id))
      assert(peerStore.getAttachReservation(attach.reservation.id).get.state ===
        ComputeAttachState.ATTACHED)
      assert(peer.releaseAttach(attach.reservation.id))
    } finally {
      peerStore.close()
      release(allocation)
    }
  }

  test("rejects an untrusted runner and every mismatched binding scope") {
    val allocation = reserveJob(createProfile())
    val issued = manager.computeBindings.issue(allocation.id, "dbt-runner")
    try {
      interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
        manager.computeBindings.issue(allocation.id, "alice")
      }
      interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
        reserveAttach(manager.computeBindings, issued, allocation, token = "wrong-token")
      }
      interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
        reserveAttach(
          manager.computeBindings,
          issued,
          allocation,
          authenticatedRunner = "notebook-runner")
      }
      interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
        reserveAttach(manager.computeBindings, issued, allocation, effectiveUser = bob.user)
      }
      interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
        reserveAttach(manager.computeBindings, issued, allocation, profileId = "other-profile")
      }
      interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
        reserveAttach(manager.computeBindings, issued, allocation, attemptId = "other-attempt")
      }
    } finally {
      release(allocation)
    }
  }

  test("expired and rotated credentials cannot be replayed") {
    val allocation = reserveJob(createProfile())
    var now = System.currentTimeMillis()
    val service =
      new ComputeBindingService(conf, manager.computeStore, manager.computeAllocations, () => now)
    val expiring = service.issue(allocation.id, "dbt-runner", ttlMillis = 10)
    now += 11
    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      reserveAttach(service, expiring, allocation)
    }

    val current = service.issue(allocation.id, "dbt-runner", ttlMillis = 1000)
    val existingAttach = reserveAttach(service, current, allocation)
    assert(service.confirmAttach(existingAttach.reservation.id))
    val replacement = service.rotate(current.bindingId, "dbt-runner")
    assert(manager.computeStore.getAttachReservation(existingAttach.reservation.id).get.state ===
      ComputeAttachState.ATTACHED)
    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      reserveAttach(service, current, allocation)
    }
    val attach = reserveAttach(service, replacement, allocation)
    assert(service.confirmAttach(attach.reservation.id))
    release(allocation)
  }

  test("allocation release revokes bindings and wins an attach confirmation race") {
    val allocation = reserveJob(createProfile())
    val issued = manager.computeBindings.issue(allocation.id, "dbt-runner")
    val attach = reserveAttach(manager.computeBindings, issued, allocation)

    release(allocation)

    assert(!manager.computeBindings.confirmAttach(attach.reservation.id))
    assert(manager.computeStore.getBinding(issued.bindingId).get.revokedAt.nonEmpty)
    assert(manager.computeStore.getAttachReservation(attach.reservation.id).get.state ===
      ComputeAttachState.RELEASED)
    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      reserveAttach(manager.computeBindings, issued, allocation)
    }
  }
}
