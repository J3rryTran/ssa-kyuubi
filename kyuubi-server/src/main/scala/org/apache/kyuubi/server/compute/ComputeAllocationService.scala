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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

import org.apache.kyuubi.{KYUUBI_VERSION, KyuubiException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.ENGINE_IDLE_TIMEOUT
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_NAMESPACE
import org.apache.kyuubi.ha.client.zookeeper.ZookeeperClientProvider
import org.apache.kyuubi.server.engineprofile.{EngineProfilePrincipal, EngineProfileService, EngineProfileSnapshot, PythonEnvironmentService}
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api.{NotebookErrorCode, NotebookException}
import org.apache.kyuubi.shaded.curator.framework.CuratorFramework
import org.apache.kyuubi.shaded.curator.framework.recipes.locks.InterProcessMutex

/** Cross-replica serialization boundary for quota check plus JOB allocation insert. */
trait ComputeAllocationCoordinator extends AutoCloseable {
  def initialize(): Unit
  def withJobReservationLock[T](body: => T): T
}

private[compute] class ZooKeeperComputeAllocationCoordinator(
    client: CuratorFramework,
    namespace: String,
    timeoutMillis: Long) extends ComputeAllocationCoordinator {

  require(timeoutMillis > 0, "compute allocation ZooKeeper lock timeout must be positive")

  override def initialize(): Unit = {
    client.start()
    val timeout = Math.toIntExact(timeoutMillis)
    if (!client.blockUntilConnected(timeout, TimeUnit.MILLISECONDS)) {
      throw new KyuubiException("Could not connect to ZooKeeper for compute allocation")
    }
  }

  override def withJobReservationLock[T](body: => T): T = {
    val lock = new InterProcessMutex(client, s"/$namespace/compute-allocation/job-reservation")
    val acquired =
      try lock.acquire(timeoutMillis, TimeUnit.MILLISECONDS)
      catch {
        case error: Exception =>
          throw new KyuubiException("Could not acquire the JOB allocation lock", error)
      }
    if (!acquired) {
      throw new KyuubiException(
        s"Timed out after ${timeoutMillis}ms waiting for the JOB allocation lock")
    }
    try body
    finally {
      try lock.release()
      catch {
        case error: Exception =>
          throw new KyuubiException("Could not release the JOB allocation lock", error)
      }
    }
  }

  override def close(): Unit = client.close()
}

/**
 * Resolves an authorized Engine Profile into a durable interactive or job-scoped allocation.
 * Reserving an allocation never launches an engine; session integration is a separate step.
 */
class ComputeAllocationService(
    conf: KyuubiConf,
    store: ComputeStore,
    profiles: EngineProfileService,
    pythonEnvironments: Option[PythonEnvironmentService],
    coordinator: ComputeAllocationCoordinator) extends AutoCloseable with Logging {

  private val maxJobsPerUser = conf.get(COMPUTE_JOB_MAX_CONCURRENT_PER_USER)
  private val maxJobsGlobal = conf.get(COMPUTE_JOB_MAX_CONCURRENT_GLOBAL)
  private val discoveryNamespace =
    s"${conf.get(HA_NAMESPACE)}_${KYUUBI_VERSION}_USER_SPARK_SQL"
  private val runtimeImageReference = profiles.runtimeImageReference

  def initialize(): Unit = coordinator.initialize()

  override def close(): Unit = coordinator.close()

  def acquireInteractive(
      principal: EngineProfilePrincipal,
      profileId: String,
      workloadType: ComputeWorkloadType.Value,
      triggerType: ComputeTriggerType.Value = ComputeTriggerType.MANUAL): ComputeAllocation = {
    require(
      triggerType != ComputeTriggerType.SCHEDULE,
      "scheduled workloads require a JOB allocation")
    prepareAuthorizedProfile(principal, profileId)
    profiles.withAllocationSnapshot(profileId, principal) { profileSnapshot =>
      val engineKey = engineIdentity(principal.user, profileSnapshot.subdomain)
      store.getOpenAllocationByEngineKey(engineKey).map { existing =>
        requireCompatibleInteractive(existing, principal, profileSnapshot)
        existing
      }.getOrElse {
        val allocation = allocationOf(
          principal,
          profileSnapshot,
          ComputeMode.INTERACTIVE,
          workloadType,
          triggerType,
          None,
          None,
          profileSnapshot.subdomain,
          engineKey)
        if (store.createAllocation(allocation)) {
          info(s"Reserved interactive compute ${ComputeLogContext(allocation)}")
          allocation
        } else {
          store.getOpenAllocationByEngineKey(engineKey).map { existing =>
            requireCompatibleInteractive(existing, principal, profileSnapshot)
            existing
          }.getOrElse {
            throw new KyuubiException("Interactive allocation reservation lost without a winner")
          }
        }
      }
    }
  }

  def reserveJob(
      principal: EngineProfilePrincipal,
      profileId: String,
      workloadType: ComputeWorkloadType.Value,
      triggerType: ComputeTriggerType.Value,
      runId: String,
      attemptId: String): ComputeAllocation = {
    require(
      Set(ComputeWorkloadType.NOTEBOOK, ComputeWorkloadType.DBT).contains(workloadType),
      "dedicated JOB allocation supports only NOTEBOOK and DBT workloads")
    require(runId.trim.nonEmpty, "JOB run ID must not be empty")
    require(attemptId.trim.nonEmpty, "JOB attempt ID must not be empty")
    existingJob(principal, profileId, workloadType, triggerType, runId, attemptId).getOrElse {
      prepareAuthorizedProfile(principal, profileId)
      coordinator.withJobReservationLock {
        existingJob(
          principal,
          profileId,
          workloadType,
          triggerType,
          runId,
          attemptId).getOrElse {
          profiles.withAllocationSnapshot(profileId, principal) { profileSnapshot =>
            enforceJobCapacity(principal.user)
            val allocationId = UUID.randomUUID().toString
            val subdomain = s"job-${allocationId.replace("-", "").take(24)}"
            val allocation = allocationOf(
              principal,
              profileSnapshot,
              ComputeMode.JOB,
              workloadType,
              triggerType,
              Some(runId),
              Some(attemptId),
              subdomain,
              engineIdentity(principal.user, subdomain),
              allocationId)
            if (store.createAllocation(allocation)) {
              info(s"Reserved managed JOB compute ${ComputeLogContext(allocation)}")
              allocation
            } else existingJob(
              principal,
              profileId,
              workloadType,
              triggerType,
              runId,
              attemptId).getOrElse {
              throw new KyuubiException("JOB allocation reservation lost without a winner")
            }
          }
        }
      }
    }
  }

  /**
   * Extends workload liveness without extending the hard server-owned deadline. Task-specific
   * runner integration calls this endpoint; merely opening or closing a Thrift session does not.
   */
  def heartbeatJob(
      principal: EngineProfilePrincipal,
      allocationId: String,
      runId: String,
      attemptId: String): Unit = {
    val allocation = store.getAllocation(allocationId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.RUNTIME_NOT_FOUND,
        s"compute allocation '$allocationId' was not found")
    }
    if (!principal.admin && (allocation.owner != principal.user ||
        allocation.effectiveUser != principal.user)) {
      throw NotebookException.notFound(
        NotebookErrorCode.RUNTIME_NOT_FOUND,
        s"compute allocation '$allocationId' was not found")
    }
    if (allocation.mode != ComputeMode.JOB || !allocation.runId.contains(runId) ||
      !allocation.attemptId.contains(attemptId)) {
      throw NotebookException.invalid("workload heartbeat does not match its JOB allocation")
    }
    val now = math.max(System.currentTimeMillis(), allocation.updatedAt + 1)
    if (!store.heartbeatJobAllocation(allocationId, runId, attemptId, now)) {
      throw NotebookException.versionConflict(
        s"compute allocation '$allocationId' no longer accepts workload heartbeats")
    }
  }

  def routingConfiguration(allocation: ComputeAllocation): Map[String, String] = {
    val launch = allocation.launchSnapshot
    val profileSnapshot = EngineProfileSnapshot(
      profileId = launch.profileId,
      owner = launch.profileOwner,
      subdomain = allocation.subdomain,
      sparkConfig = launch.sparkConfig,
      capturedAt = launch.capturedAt,
      notebookRuntimeIdleTimeout = launch.notebookRuntimeIdleTimeout,
      engineIdleTimeout = launch.engineIdleTimeout,
      pythonEnvironmentRevisionId = launch.pythonEnvironmentRevisionId,
      profileVersion = launch.profileVersion)
    val jobLifecycleConfig = if (allocation.mode == ComputeMode.JOB) {
      // A managed attempt owns cleanup. Do not let Spark disappear between models/cells merely
      // because every client session is temporarily closed.
      Map("kyuubi.session.engine.idle.timeout" -> "0")
    } else {
      Map.empty[String, String]
    }
    val allocationLabels = launch.sparkConfig.get("spark.master")
      .orElse(conf.getOption("spark.master"))
      .filter(_.toLowerCase(java.util.Locale.ROOT).startsWith("k8s"))
      .map { _ =>
        val allocationLabel = Map(
          "spark.kubernetes.driver.label.kyuubi-compute-allocation-id" -> allocation.id,
          "spark.kubernetes.executor.label.kyuubi-compute-allocation-id" -> allocation.id)
        val attemptLabel = allocation.attemptId.map { attemptId =>
          val value = sha256(attemptId).take(63)
          Map(
            "spark.kubernetes.driver.label.kyuubi-compute-attempt-id" -> value,
            "spark.kubernetes.executor.label.kyuubi-compute-attempt-id" -> value)
        }.getOrElse(Map.empty)
        val runLabel = allocation.runId.map { runId =>
          val value = sha256(runId).take(63)
          Map(
            "spark.kubernetes.driver.label.kyuubi-compute-run-id" -> value,
            "spark.kubernetes.executor.label.kyuubi-compute-run-id" -> value)
        }.getOrElse(Map.empty)
        val workload = allocation.workloadType.toString.toLowerCase(java.util.Locale.ROOT)
        allocationLabel ++ attemptLabel ++ runLabel ++ Map(
          "spark.kubernetes.driver.label.kyuubi-compute-workload" -> workload,
          "spark.kubernetes.executor.label.kyuubi-compute-workload" -> workload)
      }.getOrElse(Map.empty)
    launch.sparkConfig ++
      launch.pythonEnvironmentConfig ++
      EngineProfileSnapshot.engineSessionConfig(profileSnapshot) ++
      EngineProfileSnapshot.clientSessionCleanupConfig(
        profileSnapshot,
        conf.get(ENGINE_IDLE_TIMEOUT)) ++
      jobLifecycleConfig ++
      allocationLabels ++
      Map(
        ComputeAllocationService.InternalAllocationIdKey -> allocation.id,
        "kyuubi.engine.type" -> allocation.engineType,
        "kyuubi.engine.share.level" -> allocation.shareLevel,
        "kyuubi.engine.share.level.subdomain" -> allocation.subdomain,
        "kyuubi.engine.share.level.sub.domain" -> allocation.subdomain)
  }

  /**
   * Loads a server-issued allocation for a Notebook runtime without re-reading the mutable
   * Engine Profile. This is the boundary that keeps every session of a managed Notebook job on
   * the launch snapshot captured at dispatch time.
   */
  def requireNotebookAllocation(
      principal: EngineProfilePrincipal,
      allocationId: String): ComputeAllocation = {
    val allocation = getNotebookAllocation(principal, allocationId)
    if (!Set(
        ComputeAllocationState.RESERVED,
        ComputeAllocationState.STARTING,
        ComputeAllocationState.ACTIVE).contains(allocation.state)) {
      throw NotebookException.invalid(
        s"compute allocation '$allocationId' is not attachable: ${allocation.state}")
    }
    allocation
  }

  /** Loads the allocation for recovery policy checks, including a terminal allocation. */
  def getNotebookAllocation(
      principal: EngineProfilePrincipal,
      allocationId: String): ComputeAllocation = {
    getOwnedAllocation(principal, allocationId, ComputeWorkloadType.NOTEBOOK)
  }

  /** Authorization boundary shared by credential-free Notebook and DBT status projections. */
  def getOwnedAllocation(
      principal: EngineProfilePrincipal,
      allocationId: String,
      expectedWorkloadType: ComputeWorkloadType.Value): ComputeAllocation = {
    val allocation = store.getAllocation(allocationId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.RUNTIME_NOT_FOUND,
        s"compute allocation '$allocationId' was not found")
    }
    if (!principal.admin && (allocation.owner != principal.user ||
        allocation.effectiveUser != principal.user)) {
      throw NotebookException.notFound(
        NotebookErrorCode.RUNTIME_NOT_FOUND,
        s"compute allocation '$allocationId' was not found")
    }
    if (allocation.workloadType != expectedWorkloadType) {
      throw NotebookException.invalid(
        s"compute allocation '$allocationId' is not a $expectedWorkloadType allocation")
    }
    allocation
  }

  def managedStatus(
      principal: EngineProfilePrincipal,
      workloadType: ComputeWorkloadType.Value,
      runId: String,
      attemptId: Option[String],
      allocationId: Option[String],
      workloadState: String,
      errorMessage: Option[String]): ManagedComputeStatus = allocationId match {
    case Some(id) =>
      val allocation = getOwnedAllocation(principal, id, workloadType)
      if (!allocation.runId.contains(runId) || allocation.attemptId != attemptId) {
        throw NotebookException.invalid(
          s"compute allocation '$id' does not belong to workload run '$runId'")
      }
      val now = System.currentTimeMillis()
      val cleanupRequestedAt = allocation.releaseRequestedAt.orElse {
        if (allocation.state == ComputeAllocationState.RELEASING) {
          Some(allocation.updatedAt)
        } else {
          None
        }
      }
      val cleanupDeadlineAt = cleanupRequestedAt.map(
        _ + conf.get(COMPUTE_LIFECYCLE_CLEANUP_DEADLINE))
      val cleanupOverdue = allocation.state == ComputeAllocationState.RELEASING &&
        cleanupDeadlineAt.exists(_ <= now)
      val cleanupObservedAt = allocation.releasedAt.getOrElse(now)
      ManagedComputeStatus(
        allocation.mode.toString,
        allocation.workloadType.toString,
        allocation.triggerType.toString,
        runId,
        allocation.attemptId,
        Some(allocation.id),
        Some(allocation.subdomain),
        workloadState,
        Some(allocation.state.toString),
        if (cleanupOverdue) {
          "OVERDUE"
        } else allocation.state match {
          case ComputeAllocationState.RELEASING => "REQUESTED"
          case ComputeAllocationState.RELEASED => "COMPLETED"
          case _ => "NOT_REQUESTED"
        },
        ManagedComputeStatus.safeError(errorMessage, allocation.lastError),
        createdAt = Some(allocation.createdAt),
        updatedAt = Some(allocation.updatedAt),
        activatedAt = allocation.activatedAt,
        launchLatencyMillis = allocation.activatedAt.map(_ - allocation.createdAt),
        workloadDeadlineAt = allocation.workloadDeadlineAt,
        workloadHeartbeatAt = allocation.workloadHeartbeatAt,
        cleanupRequestedAt = cleanupRequestedAt,
        cleanupAgeMillis = cleanupRequestedAt.map(cleanupObservedAt - _),
        cleanupDeadlineAt = cleanupDeadlineAt,
        cleanupOverdue = cleanupOverdue,
        cleanupAttempts = allocation.cleanupAttempts)
    case None =>
      ManagedComputeStatus(
        ComputeMode.JOB.toString,
        workloadType.toString,
        ComputeTriggerType.SCHEDULE.toString,
        runId,
        attemptId,
        None,
        None,
        workloadState,
        None,
        "NOT_ALLOCATED",
        ManagedComputeStatus.safeError(errorMessage))
  }

  /** Fences a stale controller claim and makes pending cleanup immediately claimable again. */
  def retryCleanup(
      principal: EngineProfilePrincipal,
      allocationId: String,
      workloadType: ComputeWorkloadType.Value): ComputeAllocation = {
    val current = getOwnedAllocation(principal, allocationId, workloadType)
    current.state match {
      case ComputeAllocationState.RELEASED => current
      case ComputeAllocationState.RELEASING =>
        val now = math.max(System.currentTimeMillis(), current.updatedAt + 1)
        val retried =
          if (store.requestAllocationRelease(
              current.id,
              current.version,
              Set(ComputeAllocationState.RELEASING),
              now,
              None)) {
            store.getAllocation(allocationId).get
          } else {
            store.getAllocation(allocationId).filter(allocation =>
              Set(
                ComputeAllocationState.RELEASING,
                ComputeAllocationState.RELEASED).contains(allocation.state)).getOrElse {
              throw NotebookException.versionConflict(
                s"compute allocation '$allocationId' changed while retrying cleanup")
            }
          }
        info(s"Compute cleanup retry requested ${ComputeLogContext(retried)}")
        retried
      case state =>
        throw NotebookException.invalid(
          s"compute allocation '$allocationId' has no cleanup to retry: $state")
    }
  }

  /** Moves an interactive allocation out of service before an explicit engine Stop. */
  def beginInteractiveRelease(
      principal: EngineProfilePrincipal,
      profileId: String): Option[ComputeAllocation] = {
    profiles.withAllocationSnapshot(profileId, principal) { snapshot =>
      store.getOpenAllocationByEngineKey(engineIdentity(principal.user, snapshot.subdomain))
        .map { allocation =>
          if (allocation.owner != principal.user ||
            allocation.effectiveUser != principal.user ||
            allocation.profileId != snapshot.profileId ||
            allocation.mode != ComputeMode.INTERACTIVE) {
            throw new KyuubiException(
              "Open engine identity belongs to an incompatible allocation")
          }
          allocation.state match {
            case ComputeAllocationState.RELEASING => allocation
            case state =>
              val now = math.max(System.currentTimeMillis(), allocation.updatedAt + 1)
              if (store.requestAllocationRelease(
                  allocation.id,
                  allocation.version,
                  Set(state),
                  now,
                  None)) {
                val releasing = store.getAllocation(allocation.id).get
                info(s"Interactive compute cleanup requested ${ComputeLogContext(releasing)}")
                releasing
              } else {
                store.getAllocation(allocation.id).filter(
                  _.state == ComputeAllocationState.RELEASING).getOrElse {
                  throw NotebookException.versionConflict(
                    s"compute allocation '${allocation.id}' changed while stopping")
                }
              }
          }
        }
    }
  }

  /**
   * Persists the exact Kyuubi engine reference created by OpenSession. The reference is the
   * application-manager tag used for cleanup; it is never inferred from a profile name.
   */
  def recordEngineReference(allocationId: String, engineReferenceId: String): Unit = {
    require(engineReferenceId.trim.nonEmpty, "engine reference ID must not be empty")
    var recorded = false
    while (!recorded) {
      val current = store.getAllocation(allocationId).getOrElse {
        throw new KyuubiException(s"Compute allocation '$allocationId' was not found")
      }
      val releaseStarted = Set(
        ComputeAllocationState.RELEASING,
        ComputeAllocationState.RELEASED).contains(current.state)
      current.engineReferenceId match {
        // Concurrent dbt threads open different Kyuubi sessions for the same allocation. Their
        // session handles differ, but they still route to one engine. The first reference is only
        // a bootstrap hint; discovery later replaces it with the actual engine reference.
        case Some(_) => recorded = true
        case None if releaseStarted =>
          throw NotebookException.invalid(
            s"compute allocation '$allocationId' was released before its engine attached")
        case None if current.controllerId.nonEmpty =>
          throw NotebookException.versionConflict(
            s"compute allocation '$allocationId' is currently owned by a lifecycle controller")
        case None =>
          val now = math.max(System.currentTimeMillis(), current.updatedAt + 1)
          val nextState = current.state match {
            case ComputeAllocationState.RESERVED => ComputeAllocationState.STARTING
            case state => state
          }
          val attached = current.copy(
            state = nextState,
            engineReferenceId = Some(engineReferenceId),
            updatedAt = now,
            version = current.version + 1)
          // A peer session or heartbeat may win this CAS. Reload and accept its reference instead
          // of rejecting another connection belonging to the same authorized allocation.
          recorded = store.updateAllocation(attached, current.version, Set(current.state))
      }
    }
    store.getAllocation(allocationId).foreach { allocation =>
      info(s"Compute engine attached ${ComputeLogContext(allocation)}")
    }
  }

  /** Durable terminal/cancel intent. Physical cleanup is owned by the reconciler. */
  def requestRelease(allocationId: String, reason: String): ComputeAllocation = {
    val current = store.getAllocation(allocationId).getOrElse {
      throw new KyuubiException(s"Compute allocation '$allocationId' was not found")
    }
    current.state match {
      case ComputeAllocationState.RELEASED | ComputeAllocationState.RELEASING => current
      case state =>
        val now = math.max(System.currentTimeMillis(), current.updatedAt + 1)
        val releaseReason = Option(reason).map(_.trim).filter(_.nonEmpty)
        if (store.requestAllocationRelease(
            current.id,
            current.version,
            Set(state),
            now,
            releaseReason)) {
          val releasing = store.getAllocation(allocationId).get
          info(s"Compute cleanup requested ${ComputeLogContext(releasing)}")
          releasing
        } else {
          store.getAllocation(allocationId).filter(allocation =>
            Set(
              ComputeAllocationState.RELEASING,
              ComputeAllocationState.RELEASED).contains(allocation.state)).getOrElse {
            throw NotebookException.versionConflict(
              s"compute allocation '$allocationId' changed while requesting cleanup")
          }
        }
    }
  }

  /** Completes an explicit release after the engine resource has been terminated. */
  def completeRelease(allocationId: String): Unit = {
    store.getAllocation(allocationId).foreach { allocation =>
      if (allocation.state == ComputeAllocationState.RELEASING) {
        val now = math.max(System.currentTimeMillis(), allocation.updatedAt + 1)
        val released = allocation.copy(
          state = ComputeAllocationState.RELEASED,
          updatedAt = now,
          releasedAt = Some(now),
          version = allocation.version + 1)
        if (!store.updateAllocation(
            released,
            allocation.version,
            Set(ComputeAllocationState.RELEASING))) {
          throw NotebookException.versionConflict(
            s"compute allocation '$allocationId' changed while completing release")
        }
      }
    }
  }

  /** Read-only launch/attach fence used around asynchronous engine bootstrap. */
  def isAttachable(allocationId: String): Boolean =
    store.getAllocation(allocationId).exists(allocation =>
      Set(
        ComputeAllocationState.RESERVED,
        ComputeAllocationState.STARTING,
        ComputeAllocationState.ACTIVE).contains(allocation.state))

  def rejectReservedClientConfiguration(configuration: Map[String, String]): Unit = {
    val rejected = configuration.keys.filter(isReservedClientKey).toSeq.sorted
    if (rejected.nonEmpty) {
      throw NotebookException.invalid(
        s"client cannot override server-owned compute configuration: ${rejected.mkString(", ")}")
    }
  }

  def requireNoOpenAllocations(profileId: String, action: String): Unit = {
    val open = store.listOpenAllocationsForProfile(profileId)
    if (open.nonEmpty) {
      throw new NotebookException(
        NotebookErrorCode.COMPUTE_ACTIVE,
        s"Engine Profile '$profileId' has open compute allocations; cannot proceed with $action",
        details = Map(
          "profileId" -> profileId,
          "allocationIds" -> open.map(_.id).mkString(","),
          "modes" -> open.map(_.mode.toString).distinct.sorted.mkString(",")))
    }
  }

  def hasOpenAllocationForProfile(profileId: String): Boolean =
    store.listOpenAllocationsForProfile(profileId).nonEmpty

  /** Authorize before environment reconciliation can cause any profile-side effect. */
  private def prepareAuthorizedProfile(
      principal: EngineProfilePrincipal,
      profileId: String): Unit = {
    profiles.snapshotForUse(profileId, principal)
    pythonEnvironments.foreach(_.prepareForEngineLaunch(profileId))
  }

  private def allocationOf(
      principal: EngineProfilePrincipal,
      profileSnapshot: EngineProfileSnapshot,
      mode: ComputeMode.Value,
      workloadType: ComputeWorkloadType.Value,
      triggerType: ComputeTriggerType.Value,
      runId: Option[String],
      attemptId: Option[String],
      subdomain: String,
      engineKey: String,
      id: String = UUID.randomUUID().toString): ComputeAllocation = {
    val now = System.currentTimeMillis()
    val pythonEnvironment = pythonEnvironments.flatMap(
      _.resolveLaunchEnvironment(profileSnapshot))
    ComputeAllocation(
      id = id,
      owner = principal.user,
      effectiveUser = principal.user,
      profileId = profileSnapshot.profileId,
      mode = mode,
      workloadType = workloadType,
      triggerType = triggerType,
      runId = runId,
      attemptId = attemptId,
      engineType = "SPARK_SQL",
      shareLevel = "USER",
      subdomain = subdomain,
      discoveryNamespace = discoveryNamespace,
      engineKey = engineKey,
      launchSnapshot = ComputeLaunchSnapshot(
        profileId = profileSnapshot.profileId,
        profileOwner = profileSnapshot.owner,
        profileVersion = profileSnapshot.profileVersion,
        sparkConfig = profileSnapshot.sparkConfig,
        capturedAt = profileSnapshot.capturedAt,
        notebookRuntimeIdleTimeout = profileSnapshot.notebookRuntimeIdleTimeout,
        engineIdleTimeout = profileSnapshot.engineIdleTimeout,
        pythonEnvironmentRevisionId = pythonEnvironment.map(_.id),
        runtimeImageReference = runtimeImageReference,
        pythonEnvironmentConfig = pythonEnvironment.map(_.configuration).getOrElse(Map.empty)),
      state = ComputeAllocationState.RESERVED,
      controllerId = None,
      controllerGeneration = 0L,
      claimExpiresAt = None,
      heartbeatAt = None,
      workloadHeartbeatAt = if (mode == ComputeMode.JOB) Some(now) else None,
      workloadDeadlineAt = if (mode == ComputeMode.JOB) {
        Some(now + conf.get(COMPUTE_JOB_TIMEOUT))
      } else {
        None
      },
      engineReferenceId = None,
      engineApplicationId = None,
      engineResourceUid = None,
      cleanupAttempts = 0,
      lastError = None,
      createdAt = now,
      updatedAt = now,
      releasedAt = None,
      version = 1L)
  }

  private def existingJob(
      principal: EngineProfilePrincipal,
      profileId: String,
      workloadType: ComputeWorkloadType.Value,
      triggerType: ComputeTriggerType.Value,
      runId: String,
      attemptId: String): Option[ComputeAllocation] =
    store.getAllocationByAttempt(attemptId).map { existing =>
      val matches = existing.owner == principal.user && existing.effectiveUser == principal.user &&
        existing.profileId == profileId && existing.mode == ComputeMode.JOB &&
        existing.workloadType == workloadType && existing.triggerType == triggerType &&
        existing.runId.contains(runId)
      if (!matches) {
        throw NotebookException.versionConflict(
          "JOB attempt ID is already bound to a different compute allocation")
      }
      existing
    }

  private def requireCompatibleInteractive(
      existing: ComputeAllocation,
      principal: EngineProfilePrincipal,
      snapshot: EngineProfileSnapshot): Unit = {
    if (existing.owner != principal.user || existing.effectiveUser != principal.user ||
      existing.profileId != snapshot.profileId || existing.mode != ComputeMode.INTERACTIVE) {
      throw new KyuubiException("Open engine identity belongs to an incompatible allocation")
    }
    if (!Set(
        ComputeAllocationState.RESERVED,
        ComputeAllocationState.STARTING,
        ComputeAllocationState.ACTIVE).contains(existing.state)) {
      throw NotebookException.invalid(
        s"interactive compute allocation '${existing.id}' is ${existing.state}")
    }
  }

  private def enforceJobCapacity(owner: String): Unit = {
    val ownerCount = store.countOpenJobAllocations(Some(owner))
    val globalCount = store.countOpenJobAllocations(None)
    if (ownerCount >= maxJobsPerUser || globalCount >= maxJobsGlobal) {
      throw new NotebookException(
        NotebookErrorCode.RATE_LIMITED,
        "Dedicated compute capacity is currently exhausted",
        retryable = true,
        details = Map(
          "ownerOpenJobs" -> ownerCount.toString,
          "ownerLimit" -> maxJobsPerUser.toString,
          "globalOpenJobs" -> globalCount.toString,
          "globalLimit" -> maxJobsGlobal.toString))
    }
  }

  private def engineIdentity(effectiveUser: String, subdomain: String): String = {
    val value = s"$discoveryNamespace|$effectiveUser|USER|SPARK_SQL|$subdomain"
    sha256(value)
  }

  private def sha256(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
    digest.map(byte => f"${byte & 0xFF}%02x").mkString
  }

  private def isReservedClientKey(key: String): Boolean = {
    val normalized = key.trim.toLowerCase(java.util.Locale.ROOT)
    normalized.startsWith("spark.") && !normalized.startsWith("spark.sql.") ||
    normalized.startsWith("kyuubi.compute.") ||
    normalized == ComputeAllocationService.InternalAllocationIdKey ||
    normalized == "hive.server2.proxy.user" || normalized == "kyuubi.engine.type" ||
    normalized == "kyuubi.engine.share.level" ||
    normalized == "kyuubi.engine.share.level.subdomain" ||
    normalized == "kyuubi.engine.share.level.sub.domain" ||
    normalized == "kyuubi.session.engine.idle.timeout" ||
    normalized == "kyuubi.session.idle.timeout" ||
    normalized == "kyuubi.operation.idle.timeout"
  }
}

object ComputeAllocationService {
  val InternalAllocationIdKey = "kyuubi.internal.compute.allocation.id"

  def create(
      conf: KyuubiConf,
      store: ComputeStore,
      profiles: EngineProfileService,
      pythonEnvironments: Option[PythonEnvironmentService]): ComputeAllocationService =
    new ComputeAllocationService(
      conf,
      store,
      profiles,
      pythonEnvironments,
      new ZooKeeperComputeAllocationCoordinator(
        ZookeeperClientProvider.buildZookeeperClient(conf),
        conf.get(HA_NAMESPACE),
        conf.get(COMPUTE_ALLOCATION_ZK_LOCK_TIMEOUT)))
}
