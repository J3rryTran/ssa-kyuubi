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

object ComputeMode extends Enumeration {
  type ComputeMode = Value
  val INTERACTIVE, JOB = Value
}

object ComputeTriggerType extends Enumeration {
  type ComputeTriggerType = Value
  val MANUAL, SCHEDULE, API = Value
}

object ComputeWorkloadType extends Enumeration {
  type ComputeWorkloadType = Value
  val NOTEBOOK, DBT, SQL = Value
}

object ComputeAllocationState extends Enumeration {
  type ComputeAllocationState = Value
  val RESERVED, STARTING, ACTIVE, RELEASING, RELEASED = Value

  val open: Set[Value] = Set(RESERVED, STARTING, ACTIVE, RELEASING)

  def canTransition(from: Value, to: Value): Boolean = from == to || ((from, to) match {
    case (RESERVED, STARTING) | (RESERVED, ACTIVE) | (RESERVED, RELEASING) => true
    case (STARTING, ACTIVE) | (STARTING, RELEASING) => true
    case (ACTIVE, RELEASING) => true
    case (RELEASING, RELEASED) => true
    case _ => false
  })
}

object ComputeAttachState extends Enumeration {
  type ComputeAttachState = Value
  val RESERVED, ATTACHED, RELEASED = Value
}

/**
 * Server-owned launch configuration captured when an allocation is reserved.
 *
 * `profileVersion` is an internal compare-and-set generation of the current revisionless profile;
 * it is not an Engine Profile revision and is never exposed as one. Secrets are represented only
 * by server-side references outside this document.
 */
case class ComputeLaunchSnapshot(
    profileId: String,
    profileOwner: String,
    profileVersion: Long,
    sparkConfig: Map[String, String],
    capturedAt: Long,
    notebookRuntimeIdleTimeout: Option[String],
    engineIdleTimeout: Option[String],
    pythonEnvironmentRevisionId: Option[String],
    runtimeImageReference: Option[String],
    pythonEnvironmentConfig: Map[String, String] = Map.empty)

/** Fencing token held by the replica that successfully claimed an allocation. */
case class ComputeControllerClaim(controllerId: String, generation: Long)

/** Durable identity and lifecycle record for one interactive or job-scoped Spark engine. */
case class ComputeAllocation(
    id: String,
    owner: String,
    effectiveUser: String,
    profileId: String,
    mode: ComputeMode.Value,
    workloadType: ComputeWorkloadType.Value,
    triggerType: ComputeTriggerType.Value,
    runId: Option[String],
    attemptId: Option[String],
    engineType: String,
    shareLevel: String,
    subdomain: String,
    discoveryNamespace: String,
    engineKey: String,
    launchSnapshot: ComputeLaunchSnapshot,
    state: ComputeAllocationState.Value,
    controllerId: Option[String],
    controllerGeneration: Long,
    claimExpiresAt: Option[Long],
    heartbeatAt: Option[Long],
    workloadHeartbeatAt: Option[Long],
    workloadDeadlineAt: Option[Long],
    engineReferenceId: Option[String],
    engineApplicationId: Option[String],
    engineResourceUid: Option[String],
    cleanupAttempts: Int,
    lastError: Option[String],
    createdAt: Long,
    updatedAt: Long,
    releasedAt: Option[Long],
    version: Long,
    activatedAt: Option[Long] = None,
    releaseRequestedAt: Option[Long] = None)

/** Credential-free lifecycle projection safe to return from headless workload status APIs. */
case class ManagedComputeStatus(
    mode: String,
    workloadType: String,
    triggerType: String,
    runId: String,
    attemptId: Option[String],
    allocationId: Option[String],
    engineIdentity: Option[String],
    workloadState: String,
    allocationState: Option[String],
    cleanupState: String,
    errorMessage: Option[String],
    createdAt: Option[Long] = None,
    updatedAt: Option[Long] = None,
    activatedAt: Option[Long] = None,
    launchLatencyMillis: Option[Long] = None,
    workloadDeadlineAt: Option[Long] = None,
    workloadHeartbeatAt: Option[Long] = None,
    cleanupRequestedAt: Option[Long] = None,
    cleanupAgeMillis: Option[Long] = None,
    cleanupDeadlineAt: Option[Long] = None,
    cleanupOverdue: Boolean = false,
    cleanupAttempts: Int = 0)

/** Minimal reference returned after a headless workload has been accepted. */
case class ManagedComputeSubmission(
    runId: String,
    attemptId: String,
    allocationId: String)

object ManagedComputeStatus {
  private val GenericError =
    "workload or compute cleanup reported an error; inspect the authorized logs"

  /** Status is metadata-only; raw backend exceptions may contain credentials or internal paths. */
  def safeError(messages: Option[String]*): Option[String] =
    if (messages.exists(message => message.exists(_.trim.nonEmpty))) Some(GenericError) else None
}

/** Correlation and redaction shared by compute lifecycle logs. */
object ComputeLogContext {
  private val SecretAssignment =
    "(?i)(token|password|secret|credential|access[._-]?key)(\\s*[:=]\\s*)([^,\\s]+)".r
  private val Bearer = "(?i)(bearer\\s+)([^,\\s]+)".r

  def apply(allocation: ComputeAllocation): String = Seq(
    "owner" -> allocation.owner,
    "profile" -> allocation.profileId,
    "run" -> allocation.runId.getOrElse("-"),
    "attempt" -> allocation.attemptId.getOrElse("-"),
    "allocation" -> allocation.id,
    "engine" -> allocation.engineApplicationId
      .orElse(allocation.engineReferenceId).getOrElse(allocation.subdomain))
    .map { case (key, value) => s"$key=${singleLine(value)}" }.mkString(" ")

  def session(allocation: ComputeAllocation, sessionId: String): String =
    s"${apply(allocation)} session=${singleLine(sessionId)}"

  def safeFailure(error: Throwable): String = {
    val message = Option(error.getMessage).filter(_.trim.nonEmpty)
      .getOrElse(error.getClass.getSimpleName)
    redact(s"${error.getClass.getSimpleName}: $message").take(2048)
  }

  private def redact(value: String): String = {
    val assignments = SecretAssignment.replaceAllIn(
      value,
      matched => s"${matched.group(1)}${matched.group(2)}<redacted>")
    Bearer.replaceAllIn(assignments, matched => s"${matched.group(1)}<redacted>")
  }

  private def singleLine(value: String): String =
    value.replaceAll("[\\r\\n\\t]", "_").take(255)
}

object ComputeAllocation {

  private val SensitiveKeyFragments = Seq(
    "access.key",
    "secret",
    "password",
    "credential",
    "token",
    "private.key")

  def validateForCreate(allocation: ComputeAllocation): Unit = {
    require(allocation.id.trim.nonEmpty, "compute allocation ID must not be empty")
    require(allocation.owner.trim.nonEmpty, "compute allocation owner must not be empty")
    require(allocation.effectiveUser.trim.nonEmpty, "effective user must not be empty")
    require(allocation.profileId.trim.nonEmpty, "Engine Profile ID must not be empty")
    require(allocation.engineType.trim.nonEmpty, "engine type must not be empty")
    require(allocation.shareLevel.trim.nonEmpty, "engine share level must not be empty")
    require(allocation.subdomain.trim.nonEmpty, "engine subdomain must not be empty")
    require(
      allocation.discoveryNamespace.trim.nonEmpty,
      "engine discovery namespace must not be empty")
    require(allocation.engineKey.trim.nonEmpty, "engine key must not be empty")
    require(
      allocation.state == ComputeAllocationState.RESERVED,
      "a compute allocation must be created in RESERVED state")
    require(allocation.version == 1L, "a compute allocation must be created at version 1")
    require(
      allocation.controllerGeneration == 0L,
      "a compute allocation must be created without a controller generation")
    require(
      allocation.launchSnapshot.profileId == allocation.profileId,
      "launch snapshot profile ID must match its allocation")
    require(
      allocation.launchSnapshot.profileOwner.trim.nonEmpty,
      "launch snapshot profile owner must not be empty")
    require(
      allocation.launchSnapshot.profileVersion > 0,
      "launch snapshot profile version must be positive")
    require(allocation.controllerId.isEmpty, "a new allocation must not have a controller")
    require(allocation.claimExpiresAt.isEmpty, "a new allocation must not have a claim expiry")
    require(allocation.heartbeatAt.isEmpty, "a new allocation must not have a heartbeat")
    require(
      allocation.engineReferenceId.isEmpty,
      "a new allocation must not have an engine reference ID")
    require(
      allocation.engineApplicationId.isEmpty,
      "a new allocation must not have an engine application ID")
    require(
      allocation.engineResourceUid.isEmpty,
      "a new allocation must not have an engine resource UID")
    require(allocation.cleanupAttempts == 0, "a new allocation must not have cleanup attempts")
    require(allocation.lastError.isEmpty, "a new allocation must not have a lifecycle error")
    require(allocation.releasedAt.isEmpty, "a new allocation must not have a release time")
    require(allocation.activatedAt.isEmpty, "a new allocation must not have an activation time")
    require(
      allocation.releaseRequestedAt.isEmpty,
      "a new allocation must not have a cleanup request time")
    require(
      allocation.createdAt > 0 && allocation.updatedAt >= allocation.createdAt,
      "a new allocation must have valid timestamps")
    if (allocation.mode == ComputeMode.JOB) {
      require(allocation.runId.exists(_.trim.nonEmpty), "a job allocation requires a run ID")
      require(
        allocation.attemptId.exists(_.trim.nonEmpty),
        "a job allocation requires an attempt ID")
      require(
        allocation.workloadHeartbeatAt.nonEmpty,
        "a job allocation requires an initial workload heartbeat")
      require(
        allocation.workloadDeadlineAt.exists(
          _ > allocation.workloadHeartbeatAt.get),
        "a job allocation deadline must follow its initial heartbeat")
    } else {
      require(
        allocation.workloadHeartbeatAt.isEmpty,
        "an interactive allocation must not have a workload heartbeat")
      require(
        allocation.workloadDeadlineAt.isEmpty,
        "an interactive allocation must not have a workload deadline")
    }
    val sensitive = allocation.launchSnapshot.sparkConfig.keys.filter { key =>
      val normalized = key.toLowerCase(java.util.Locale.ROOT)
      SensitiveKeyFragments.exists(normalized.contains)
    }.toSeq.sorted
    require(
      sensitive.isEmpty,
      s"launch snapshot must not persist sensitive Spark config keys: ${sensitive.mkString(", ")}")
  }
}

/** Stored credential scope. Only `tokenHash`, never the issued bearer token, is persisted. */
case class ComputeBinding(
    id: String,
    allocationId: String,
    runnerUser: String,
    effectiveUser: String,
    profileId: String,
    runId: String,
    attemptId: String,
    tokenHash: String,
    issuedAt: Long,
    expiresAt: Long,
    revokedAt: Option[Long],
    version: Long)

/** One-time response returned to the trusted dispatcher that creates a runner credential. */
case class IssuedComputeBinding(
    bindingId: String,
    allocationId: String,
    token: String,
    expiresAt: Long) {
  override def toString: String =
    s"IssuedComputeBinding($bindingId,$allocationId,<redacted>,$expiresAt)"
}

/** Durable handshake around one physical session opened inside a managed job allocation. */
case class ComputeAttachReservation(
    id: String,
    bindingId: String,
    allocationId: String,
    runnerUser: String,
    effectiveUser: String,
    profileId: String,
    runId: String,
    attemptId: String,
    state: ComputeAttachState.Value,
    expiresAt: Long,
    createdAt: Long,
    confirmedAt: Option[Long],
    releasedAt: Option[Long],
    version: Long)

/** Authorized attach plus the immutable allocation routing selected by the server. */
case class AuthorizedComputeAttach(
    reservation: ComputeAttachReservation,
    allocation: ComputeAllocation,
    routingConfiguration: Map[String, String])
