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

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.kyuubi.{KyuubiException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiReservedKeys
import org.apache.kyuubi.engine.{ApplicationManagerInfo, ApplicationState}
import org.apache.kyuubi.ha.client.DiscoveryClientProvider.withDiscoveryClient
import org.apache.kyuubi.ha.client.DiscoveryPaths
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.service.BackendService

object ComputeEngineResourceState extends Enumeration {
  type ComputeEngineResourceState = Value
  val ABSENT, STARTING, RUNNING, TERMINATED, UNKNOWN = Value
}

case class ComputeEngineObservation(
    state: ComputeEngineResourceState.Value,
    engineReferenceId: Option[String] = None,
    applicationId: Option[String] = None,
    resourceUid: Option[String] = None)

/** Resource-manager boundary kept small enough for deterministic lifecycle tests. */
trait ComputeEngineController {

  /** Replica-local fence; safe to invoke concurrently on every server. */
  def fenceLocalSessions(allocation: ComputeAllocation): Unit = {}
  def observe(allocation: ComputeAllocation): ComputeEngineObservation
  def terminate(
      allocation: ComputeAllocation,
      observation: ComputeEngineObservation): ComputeEngineObservation
}

/**
 * Uses the exact Kyuubi engine reference captured from OpenSession. Discovery is only used to
 * recover that immutable reference after a replica crash; profile names are never used as a kill
 * selector. Application-manager state, rather than disappearance of a discovery node, decides
 * whether cleanup is complete.
 */
class KyuubiComputeEngineController(
    conf: KyuubiConf,
    backendService: () => BackendService) extends ComputeEngineController with Logging {

  override def fenceLocalSessions(allocation: ComputeAllocation): Unit =
    closeLocalSessions(allocation)

  override def observe(allocation: ComputeAllocation): ComputeEngineObservation = {
    val nodes = discoveryNodes(allocation)
    val references = nodes.flatMap(_.engineRefId).distinct
    if (references.size > 1) {
      throw new KyuubiException(
        s"Allocation ${allocation.id} resolved to multiple engine references")
    }
    // The first OpenSession records a launch hint. The discovery node is authoritative because a
    // concurrent session may have won the engine-launch lock for the same allocation/subdomain.
    val reference = references.headOption.orElse(allocation.engineReferenceId)
    reference match {
      case None => observeByAllocationLabel(allocation).getOrElse(
          ComputeEngineObservation(ComputeEngineResourceState.ABSENT))
      case Some(engineReferenceId) =>
        val node = nodes.find(_.engineRefId.contains(engineReferenceId))
        val managerInfo = node.flatMap(_.attributes.get(
          KyuubiReservedKeys.KYUUBI_ENGINE_APP_MGR_INFO_KEY))
          .map(ApplicationManagerInfo.deserialize)
          .getOrElse(managerInfoFromSnapshot(allocation))
        val applicationInfo = backendService().sessionManager
          .asInstanceOf[org.apache.kyuubi.session.KyuubiSessionManager]
          .applicationManager.getApplicationInfo(
            managerInfo,
            engineReferenceId,
            Some(allocation.effectiveUser),
            Some(allocation.createdAt))
        applicationInfo match {
          case None =>
            observeByAllocationLabel(allocation).getOrElse(
              ComputeEngineObservation(
                ComputeEngineResourceState.UNKNOWN,
                Some(engineReferenceId)))
          case Some(info) =>
            val state = info.state match {
              // NOT_FOUND is not proof that an asynchronous launch cannot still register. Keep
              // the allocation visible for the late-launch grace period and scan its immutable
              // Kubernetes label before allowing RELEASED.
              case ApplicationState.NOT_FOUND => ComputeEngineResourceState.ABSENT
              case value if ApplicationState.isTerminated(value) =>
                ComputeEngineResourceState.TERMINATED
              case ApplicationState.RUNNING => ComputeEngineResourceState.RUNNING
              case ApplicationState.PENDING => ComputeEngineResourceState.STARTING
              case _ => ComputeEngineResourceState.UNKNOWN
            }
            val tagged = ComputeEngineObservation(
              state,
              Some(engineReferenceId),
              Option(info.id).filter(_.nonEmpty),
              None)
            if (Set(
                ComputeEngineResourceState.ABSENT,
                ComputeEngineResourceState.TERMINATED).contains(state)) {
              observeByAllocationLabel(allocation).getOrElse(tagged)
            } else {
              tagged
            }
        }
    }
  }

  override def terminate(
      allocation: ComputeAllocation,
      observation: ComputeEngineObservation): ComputeEngineObservation = {
    fenceLocalSessions(allocation)
    val reference = observation.engineReferenceId.orElse(allocation.engineReferenceId)
    if (reference.isEmpty) {
      deleteByAllocationLabel(allocation)
      return observe(allocation)
    }
    val engineReferenceId = reference.get
    val node = discoveryNodes(allocation).find(_.engineRefId.contains(engineReferenceId))
    val managerInfo = node.flatMap(_.attributes.get(
      KyuubiReservedKeys.KYUUBI_ENGINE_APP_MGR_INFO_KEY))
      .map(ApplicationManagerInfo.deserialize)
      .getOrElse(managerInfoFromSnapshot(allocation))
    val applicationManager = backendService().sessionManager
      .asInstanceOf[org.apache.kyuubi.session.KyuubiSessionManager].applicationManager
    val (killed, message) = applicationManager.killApplication(
      managerInfo,
      engineReferenceId,
      Some(allocation.effectiveUser))
    val after = observe(allocation.copy(engineReferenceId = Some(engineReferenceId)))
    val stopped = Set(
      ComputeEngineResourceState.ABSENT,
      ComputeEngineResourceState.TERMINATED).contains(after.state)
    if (!killed && !stopped) {
      throw new KyuubiException(
        s"Application manager did not terminate allocation ${allocation.id}: $message")
    }
    if (stopped) {
      deleteByAllocationLabel(allocation)
      deleteDiscoveryNodes(allocation, engineReferenceId)
      observe(allocation.copy(engineReferenceId = Some(engineReferenceId)))
    } else {
      after
    }
  }

  private def managerInfoFromSnapshot(allocation: ComputeAllocation): ApplicationManagerInfo = {
    def setting(key: String): Option[String] =
      allocation.launchSnapshot.sparkConfig.get(key).orElse(conf.getOption(key))
        .map(_.trim).filter(_.nonEmpty)
    ApplicationManagerInfo(
      setting("spark.master"),
      setting("spark.kubernetes.context"),
      setting("spark.kubernetes.namespace"))
  }

  private def observeByAllocationLabel(
      allocation: ComputeAllocation): Option[ComputeEngineObservation] = {
    val managerInfo = managerInfoFromSnapshot(allocation)
    if (!isKubernetes(managerInfo)) {
      return None
    }
    kubernetesOperation.flatMap(
      _.getApplicationByLabel(
        managerInfo.kubernetesInfo,
        AllocationLabel,
        allocation.id)).map { resource =>
      val state = resource.state match {
        case value if ApplicationState.isTerminated(value) =>
          ComputeEngineResourceState.TERMINATED
        case ApplicationState.RUNNING => ComputeEngineResourceState.RUNNING
        case ApplicationState.PENDING => ComputeEngineResourceState.STARTING
        case _ => ComputeEngineResourceState.UNKNOWN
      }
      ComputeEngineObservation(
        state,
        resource.engineReferenceId,
        resource.applicationId,
        resource.resourceUid)
    }
  }

  private def deleteByAllocationLabel(allocation: ComputeAllocation): Unit = {
    val managerInfo = managerInfoFromSnapshot(allocation)
    if (isKubernetes(managerInfo)) {
      kubernetesOperation.foreach(_.deleteApplicationByLabel(
        managerInfo.kubernetesInfo,
        AllocationLabel,
        allocation.id))
    }
  }

  private def isKubernetes(managerInfo: ApplicationManagerInfo): Boolean =
    managerInfo.resourceManager.exists(
      _.toLowerCase(java.util.Locale.ROOT).startsWith("k8s"))

  /**
   * Stop local operations and prevent another statement from racing the application kill. A
   * different replica may still own another frontend session; revoking durable bindings stops
   * new attaches and terminating the exact engine interrupts those remote sessions as well.
   */
  private def closeLocalSessions(allocation: ComputeAllocation): Unit = {
    val sessionManager = backendService().sessionManager
    sessionManager.allSessions().filter(
      _.conf.get(ComputeAllocationService.InternalAllocationIdKey)
        .contains(allocation.id)).toSeq.foreach { session =>
      try sessionManager.closeSession(session.handle)
      catch {
        case NonFatal(error) =>
          warn(
            s"Failed to close compute session ${ComputeLogContext.session(
                allocation,
                session.handle.toString)} cause=${ComputeLogContext.safeFailure(error)}")
      }
    }
  }

  private def kubernetesOperation = backendService().sessionManager
    .asInstanceOf[org.apache.kyuubi.session.KyuubiSessionManager]
    .applicationManager.getKubernetesApplicationOperation

  private def discoveryNodes(allocation: ComputeAllocation) =
    withDiscoveryClient(conf) { client =>
      client.getServiceNodesInfo(engineSpace(allocation), silent = true)
    }

  private def deleteDiscoveryNodes(allocation: ComputeAllocation, reference: String): Unit = {
    val nodes = discoveryNodes(allocation).filter(_.engineRefId.contains(reference))
    withDiscoveryClient(conf) { client =>
      nodes.foreach { node =>
        client.delete(s"${engineSpace(allocation)}/${node.nodeName}")
      }
    }
  }

  private def engineSpace(allocation: ComputeAllocation): String =
    DiscoveryPaths.makePath(
      allocation.discoveryNamespace,
      allocation.effectiveUser,
      allocation.subdomain)

  private val AllocationLabel = "kyuubi-compute-allocation-id"
}

/**
 * Durable allocation lifecycle controller. A replica may act only while it owns the exact
 * controller generation. Failed cleanup remains RELEASING and is retried after bounded backoff;
 * expiry of the claim allows another replica to take over.
 */
class ComputeLifecycleReconciler(
    conf: KyuubiConf,
    store: ComputeStore,
    engineController: ComputeEngineController,
    controllerId: String,
    clock: () => Long = () => System.currentTimeMillis()) extends Logging {

  require(controllerId.trim.nonEmpty, "compute lifecycle controller ID must not be empty")

  private val claimTtl = conf.get(COMPUTE_LIFECYCLE_CLAIM_TTL)
  private val batchSize = conf.get(COMPUTE_LIFECYCLE_BATCH_SIZE)
  private val lateLaunchGrace = conf.get(COMPUTE_LIFECYCLE_LATE_LAUNCH_GRACE)
  private val cleanupRetryInitial =
    conf.get(COMPUTE_LIFECYCLE_CLEANUP_RETRY_INITIAL_BACKOFF)
  private val cleanupRetryMax = conf.get(COMPUTE_LIFECYCLE_CLEANUP_RETRY_MAX_BACKOFF)
  private val cleanupDeadline = conf.get(COMPUTE_LIFECYCLE_CLEANUP_DEADLINE)
  private val registrationTimeout = conf.get(COMPUTE_ENGINE_REGISTRATION_TIMEOUT)
  private val jobHeartbeatTimeout = conf.get(COMPUTE_JOB_HEARTBEAT_TIMEOUT)
  private val owned = mutable.Set.empty[String]

  def reconcile(): Unit = synchronized {
    // Every replica closes the sessions it owns. Only the CAS-claimed replica performs the
    // resource-manager side effect below.
    store.listOpenAllocations(batchSize)
      .filter(_.state == ComputeAllocationState.RELEASING)
      .foreach { allocation =>
        try engineController.fenceLocalSessions(allocation)
        catch {
          case NonFatal(error) =>
            warn(
              s"Failed to fence local compute sessions ${ComputeLogContext(allocation)} " +
                s"cause=${ComputeLogContext.safeFailure(error)}")
        }
      }
    owned.toSeq.foreach(reconcileOwned)
    val now = clock()
    val activeCandidates = store.listClaimableAllocations(
      Set(
        ComputeAllocationState.STARTING,
        ComputeAllocationState.ACTIVE,
        ComputeAllocationState.RELEASING),
      now,
      batchSize)
    val claimableReserved = store.listClaimableAllocations(
      Set(ComputeAllocationState.RESERVED),
      now,
      batchSize)
    val reservedCandidates = claimableReserved.filter { allocation =>
      now - allocation.createdAt >= registrationTimeout ||
      workloadExpiryReason(allocation, now).nonEmpty
    }
    (activeCandidates ++ reservedCandidates).take(batchSize).foreach { allocation =>
      if (store.claimAllocation(
          allocation.id,
          allocation.version,
          controllerId,
          now,
          now + claimTtl)) {
        owned += allocation.id
        reconcileOwned(allocation.id)
      }
    }
  }

  private def reconcileOwned(allocationId: String): Unit = {
    val loaded = store.getAllocation(allocationId)
    if (loaded.isEmpty || loaded.exists(_.state == ComputeAllocationState.RELEASED) ||
      loaded.exists(value => !value.controllerId.contains(controllerId))) {
      owned -= allocationId
      return
    }
    val current = loaded.get
    val now = clock()
    if (current.claimExpiresAt.forall(_ <= now) || !store.renewAllocationClaim(
        current.id,
        current.version,
        controllerId,
        current.controllerGeneration,
        now,
        now + claimTtl)) {
      owned -= allocationId
      return
    }
    val claimed = store.getAllocation(allocationId).get
    val claim = ComputeControllerClaim(controllerId, claimed.controllerGeneration)
    try {
      claimed.state match {
        case ComputeAllocationState.RESERVED | ComputeAllocationState.STARTING |
            ComputeAllocationState.ACTIVE =>
          reconcileServing(claimed, claim)
        case ComputeAllocationState.RELEASING => reconcileCleanup(claimed, claim)
        case _ =>
      }
    } catch {
      case NonFatal(error) => recordFailure(claimed, claim, error)
    }
  }

  private def reconcileServing(
      allocation: ComputeAllocation,
      claim: ComputeControllerClaim): Unit = {
    workloadExpiryReason(allocation, clock()) match {
      case Some(reason) =>
        updateClaimed(
          allocation,
          claim,
          ComputeAllocationState.RELEASING,
          ComputeEngineObservation(
            ComputeEngineResourceState.UNKNOWN,
            allocation.engineReferenceId,
            allocation.engineApplicationId,
            allocation.engineResourceUid),
          lastError = Some(reason))
      case None => reconcileEngineState(allocation, claim)
    }
  }

  private def reconcileEngineState(
      allocation: ComputeAllocation,
      claim: ComputeControllerClaim): Unit = {
    val observation = engineController.observe(allocation)
    observation.state match {
      case ComputeEngineResourceState.RUNNING =>
        updateClaimed(
          allocation,
          claim,
          ComputeAllocationState.ACTIVE,
          observation,
          lastError = None)
      case ComputeEngineResourceState.TERMINATED =>
        updateClaimed(
          allocation,
          claim,
          ComputeAllocationState.RELEASING,
          observation,
          lastError = Some("engine terminated while its allocation was active"))
      case ComputeEngineResourceState.ABSENT
          if allocation.state == ComputeAllocationState.ACTIVE =>
        updateClaimed(
          allocation,
          claim,
          ComputeAllocationState.RELEASING,
          observation,
          lastError = Some("engine disappeared while its allocation was active"))
      case ComputeEngineResourceState.ABSENT
          if clock() - allocation.createdAt >= registrationTimeout =>
        updateClaimed(
          allocation,
          claim,
          ComputeAllocationState.RELEASING,
          observation,
          lastError = Some("engine did not register before the startup timeout"))
      case _ => updateIdentity(allocation, claim, observation)
    }
  }

  private def workloadExpiryReason(
      allocation: ComputeAllocation,
      now: Long): Option[String] = {
    if (allocation.mode != ComputeMode.JOB) {
      None
    } else if (allocation.workloadDeadlineAt.exists(_ <= now)) {
      Some("managed workload exceeded its server-owned deadline")
    } else if (allocation.workloadHeartbeatAt.exists(_ + jobHeartbeatTimeout <= now)) {
      Some("managed workload heartbeat expired")
    } else {
      None
    }
  }

  private def reconcileCleanup(
      allocation: ComputeAllocation,
      claim: ComputeControllerClaim): Unit = {
    if (allocation.cleanupAttempts > 0 && allocation.lastError.nonEmpty &&
      clock() - allocation.updatedAt < retryBackoff(allocation.cleanupAttempts)) {
      return
    }
    val observed = engineController.observe(allocation)
    val after = observed.state match {
      // Even a terminated driver can leave discovery metadata or labeled executors behind.
      // Termination is idempotent and performs that exact-allocation cleanup before verification.
      case ComputeEngineResourceState.TERMINATED =>
        engineController.terminate(allocation, observed)
      case ComputeEngineResourceState.ABSENT
          if clock() - allocation.createdAt >= lateLaunchGrace => observed
      case ComputeEngineResourceState.ABSENT =>
        updateIdentity(allocation, claim, observed)
        return
      case _ => engineController.terminate(allocation, observed)
    }
    after.state match {
      case ComputeEngineResourceState.TERMINATED | ComputeEngineResourceState.ABSENT =>
        val now = math.max(clock(), allocation.updatedAt + 1)
        val released = withObservation(allocation, after).copy(
          state = ComputeAllocationState.RELEASED,
          controllerId = None,
          claimExpiresAt = None,
          heartbeatAt = None,
          cleanupAttempts = allocation.cleanupAttempts + 1,
          lastError = None,
          updatedAt = now,
          releasedAt = Some(now),
          version = allocation.version + 1)
        if (store.updateClaimedAllocation(
            released,
            allocation.version,
            Set(ComputeAllocationState.RELEASING),
            claim)) {
          val cleanupAge = released.releaseRequestedAt.map(now - _).getOrElse(0L)
          info(
            s"Compute cleanup completed ${ComputeLogContext(released)} " +
              s"cleanupAttempts=${released.cleanupAttempts} cleanupAgeMs=$cleanupAge")
          owned -= allocation.id
        }
      case _ =>
        throw new KyuubiException(
          s"Engine resource for allocation ${allocation.id} has not terminated")
    }
  }

  private def updateIdentity(
      allocation: ComputeAllocation,
      claim: ComputeControllerClaim,
      observation: ComputeEngineObservation): Unit = {
    val observed = withObservation(allocation, observation)
    if (observed.engineReferenceId != allocation.engineReferenceId ||
      observed.engineApplicationId != allocation.engineApplicationId ||
      observed.engineResourceUid != allocation.engineResourceUid) {
      val now = math.max(clock(), allocation.updatedAt + 1)
      store.updateClaimedAllocation(
        observed.copy(updatedAt = now, version = allocation.version + 1),
        allocation.version,
        Set(allocation.state),
        claim)
    }
  }

  private def updateClaimed(
      allocation: ComputeAllocation,
      claim: ComputeControllerClaim,
      state: ComputeAllocationState.Value,
      observation: ComputeEngineObservation,
      lastError: Option[String]): Unit = {
    val now = math.max(clock(), allocation.updatedAt + 1)
    store.updateClaimedAllocation(
      withObservation(allocation, observation).copy(
        state = state,
        lastError = lastError,
        updatedAt = now,
        version = allocation.version + 1),
      allocation.version,
      Set(allocation.state),
      claim)
  }

  private def recordFailure(
      allocation: ComputeAllocation,
      claim: ComputeControllerClaim,
      error: Throwable): Unit = {
    val latest = store.getAllocation(allocation.id).filter(value =>
      value.controllerId.contains(claim.controllerId) &&
        value.controllerGeneration == claim.generation).getOrElse(return
    )
    val now = math.max(clock(), latest.updatedAt + 1)
    val message = ComputeLogContext.safeFailure(error)
    if (!store.updateClaimedAllocation(
        latest.copy(
          cleanupAttempts = latest.cleanupAttempts + 1,
          lastError = Some(message),
          updatedAt = now,
          version = latest.version + 1),
        latest.version,
        Set(latest.state),
        claim)) {
      owned -= allocation.id
    }
    val cleanupAge = latest.releaseRequestedAt.map(now - _)
    val overdue = cleanupAge.exists(_ >= cleanupDeadline)
    warn(
      s"Compute lifecycle reconciliation failed ${ComputeLogContext(latest)} " +
        s"state=${latest.state} cleanupAttempts=${latest.cleanupAttempts + 1} " +
        s"cleanupAgeMs=${cleanupAge.getOrElse(0L)} cleanupOverdue=$overdue cause=$message")
  }

  private def withObservation(
      allocation: ComputeAllocation,
      observation: ComputeEngineObservation): ComputeAllocation = allocation.copy(
    engineReferenceId = observation.engineReferenceId.orElse(allocation.engineReferenceId),
    engineApplicationId = observation.applicationId.orElse(allocation.engineApplicationId),
    engineResourceUid = observation.resourceUid.orElse(allocation.engineResourceUid))

  private def retryBackoff(attempts: Int): Long = {
    val exponent = math.min(math.max(attempts - 1, 0), 6)
    math.min(cleanupRetryMax, cleanupRetryInitial * (1L << exponent))
  }
}
