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

package org.apache.kyuubi.server.engineprofile

import org.apache.kyuubi.server.compute.{ComputeAllocationService, ComputeBindingService, ComputeWorkloadType}
import org.apache.kyuubi.server.notebook.api.NotebookException

/**
 * Resolves an Engine Profile selected by an authenticated Thrift client into the server-owned
 * configuration used to launch or reuse a Spark engine.
 *
 * Interactive clients supply only [[ProfileIdKey]]. A managed runner supplies a scoped binding
 * plus allocation/run/attempt identity. Resource settings, engine sharing and Python PVC settings
 * always come from server-side profile or allocation snapshots after authorization.
 */
class EngineProfileSessionConfigResolver(
    profiles: EngineProfileService,
    pythonEnvironments: PythonEnvironmentService,
    runSnapshots: EngineProfileRunSnapshotService,
    computeBindings: ComputeBindingService,
    computeAllocations: ComputeAllocationService) {

  import EngineProfileSessionConfigResolver._

  def resolve(
      sessionUser: String,
      clientConfiguration: Map[String, String]): Map[String, String] =
    resolve(sessionUser, sessionUser, clientConfiguration)

  def resolve(
      authenticatedUser: String,
      sessionUser: String,
      clientConfiguration: Map[String, String]): Map[String, String] = {
    if (clientConfiguration.contains(ComputeAllocationService.InternalAllocationIdKey)) {
      throw NotebookException.invalid(
        s"${ComputeAllocationService.InternalAllocationIdKey} is server-owned")
    }
    if (clientConfiguration.contains(AttachReservationIdKey)) {
      throw NotebookException.invalid(s"$AttachReservationIdKey is server-owned")
    }
    if (ManagedBindingSelectorKeys.exists(clientConfiguration.contains)) {
      return resolveManagedAttach(authenticatedUser, sessionUser, clientConfiguration)
    }
    clientConfiguration.get(ProfileIdKey).map(_.trim) match {
      case Some("") =>
        throw NotebookException.invalid(s"$ProfileIdKey must not be empty")
      case Some(id) =>
        if (clientConfiguration.contains(LegacyProfileRevisionKey)) {
          throw NotebookException.invalid(
            s"$LegacyProfileRevisionKey is a removed legacy parameter; select only " +
              s"$ProfileIdKey")
        }
        rejectRawLaunchConfiguration(clientConfiguration)

        val principal = EngineProfilePrincipal(sessionUser, admin = false)
        val runId = clientConfiguration.get(RunIdKey).map(_.trim)
        runId match {
          case Some("") =>
            throw NotebookException.invalid(s"$RunIdKey must not be empty")
          case Some(runId) =>
            runSnapshots.resolve(runId, id, principal) {
              // Promote a completed Python environment only while establishing a new run pin.
              // A later session of the same run must retain its original immutable snapshot.
              profiles.snapshotForUse(id, principal)
              pythonEnvironments.prepareForEngineLaunch(id)
              profiles.snapshotForUse(id, principal)
            }
          case None =>
            // Existing non-DBT Thrift clients retain the current-profile behavior.
            profiles.snapshotForUse(id, principal)
            pythonEnvironments.prepareForEngineLaunch(id)
            profiles.snapshotForUse(id, principal)
        }
        val workloadType = if (runId.nonEmpty) {
          ComputeWorkloadType.DBT
        } else {
          ComputeWorkloadType.SQL
        }
        val allocation = computeAllocations.acquireInteractive(
          principal,
          id,
          workloadType)
        val remainingClientConfiguration =
          clientConfiguration - ProfileIdKey - LegacyProfileRevisionKey - RunIdKey
        remainingClientConfiguration ++ computeAllocations.routingConfiguration(allocation)
      case None if clientConfiguration.contains(RunIdKey) =>
        throw NotebookException.invalid(s"$RunIdKey requires $ProfileIdKey")
      case None => clientConfiguration
    }
  }

  private def resolveManagedAttach(
      authenticatedUser: String,
      sessionUser: String,
      clientConfiguration: Map[String, String]): Map[String, String] = {
    if (clientConfiguration.contains(LegacyProfileRevisionKey)) {
      throw NotebookException.invalid(s"$LegacyProfileRevisionKey is a removed legacy parameter")
    }
    val values = ManagedBindingKeys.map(key => key -> required(clientConfiguration, key)).toMap
    clientConfiguration.get(ProxyUserKey).map(_.trim).filter(_ != sessionUser).foreach { _ =>
      throw NotebookException.accessDenied(
        "Managed compute effective user does not match the authenticated proxy session")
    }
    rejectRawLaunchConfiguration(clientConfiguration -- ManagedBindingKeys)
    val authorized = computeBindings.reserveAttach(
      bindingId = values(BindingIdKey),
      token = values(BindingTokenKey),
      allocationId = values(AllocationIdKey),
      authenticatedRunner = authenticatedUser,
      effectiveUser = sessionUser,
      profileId = values(ProfileIdKey),
      runId = values(RunIdKey),
      attemptId = values(AttemptIdKey))
    val remainingClientConfiguration = clientConfiguration -- ManagedBindingKeys
    remainingClientConfiguration ++ authorized.routingConfiguration ++ Map(
      AttachReservationIdKey -> authorized.reservation.id)
  }

  private def required(configuration: Map[String, String], key: String): String =
    configuration.get(key).map(_.trim).filter(_.nonEmpty).getOrElse {
      throw NotebookException.invalid(s"Managed compute attach requires $key")
    }

  private def rejectRawLaunchConfiguration(configuration: Map[String, String]): Unit = {
    val rejected = configuration.keys.filter { key =>
      key == EngineShareLevelKey ||
      key == EngineShareSubdomainKey ||
      key == LegacyEngineShareSubdomainKey ||
      key == EngineTypeKey ||
      key == EngineIdleTimeoutKey ||
      key == SessionIdleTimeoutKey ||
      key == OperationIdleTimeoutKey ||
      key.startsWith("kyuubi.compute.") ||
      key.startsWith("spark.") && !key.startsWith("spark.sql.")
    }.toSeq.sorted
    if (rejected.nonEmpty) {
      throw NotebookException.invalid(
        s"Engine Profile sessions cannot override server-owned settings: " +
          rejected.mkString(", "))
    }
  }
}

object EngineProfileSessionConfigResolver {
  val ProfileIdKey = "kyuubi.engine.profile.id"
  val LegacyProfileRevisionKey = "kyuubi.engine.profile.revision"
  val RunIdKey = "kyuubi.engine.profile.run.id"
  val BindingIdKey = "kyuubi.compute.binding.id"
  val BindingTokenKey = "kyuubi.compute.binding.token"
  val AllocationIdKey = "kyuubi.compute.allocation.id"
  val AttemptIdKey = "kyuubi.compute.attempt.id"
  val AttachReservationIdKey = "kyuubi.compute.attach.reservation.id"

  val ManagedBindingKeys: Set[String] = Set(
    ProfileIdKey,
    RunIdKey,
    BindingIdKey,
    BindingTokenKey,
    AllocationIdKey,
    AttemptIdKey)

  private val ManagedBindingSelectorKeys: Set[String] = Set(
    BindingIdKey,
    BindingTokenKey,
    AllocationIdKey,
    AttemptIdKey)

  private val ProxyUserKey = "hive.server2.proxy.user"
  private val EngineShareLevelKey = "kyuubi.engine.share.level"
  private val EngineShareSubdomainKey = "kyuubi.engine.share.level.subdomain"
  private val LegacyEngineShareSubdomainKey = "kyuubi.engine.share.level.sub.domain"
  private val EngineTypeKey = "kyuubi.engine.type"
  private val EngineIdleTimeoutKey = "kyuubi.session.engine.idle.timeout"
  private val SessionIdleTimeoutKey = "kyuubi.session.idle.timeout"
  private val OperationIdleTimeoutKey = "kyuubi.operation.idle.timeout"
}
