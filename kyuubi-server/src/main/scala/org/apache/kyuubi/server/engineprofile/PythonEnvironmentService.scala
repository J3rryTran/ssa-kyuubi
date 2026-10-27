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

import java.util.UUID

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, PersistentVolumeClaim, PersistentVolumeClaimBuilder, Quantity, VolumeResourceRequirementsBuilder}
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.{Config, ConfigBuilder, KubernetesClient, KubernetesClientBuilder}

import org.apache.kyuubi.{KYUUBI_VERSION, KyuubiException}
import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_NAMESPACE
import org.apache.kyuubi.ha.client.DiscoveryClientProvider.withDiscoveryClient
import org.apache.kyuubi.ha.client.DiscoveryPaths
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api.{EngineProfile, NotebookErrorCode, NotebookException, PythonEnvironmentChangeRequest, PythonEnvironmentRevision}
import org.apache.kyuubi.server.notebook.store.NotebookStore

/**
 * Resolves a READY Python environment into the immutable Spark launch overlay.
 *
 * PVC names, mount paths and interpreter paths never come from an HTTP request. A builder is
 * the only component allowed to make an environment READY; Spark driver/executor pods mount it
 * read-only and only after this service has verified its profile ownership and image identity.
 */
class PythonEnvironmentService(
    conf: KyuubiConf,
    store: NotebookStore,
    engineProfiles: EngineProfileService,
    client: Option[KubernetesClient] = None,
    resources: Option[PythonEnvironmentResources] = None) extends Logging {
  private val enabled = conf.get(PYTHON_ENVIRONMENT_ENABLED)
  private val mountPath = conf.get(PYTHON_ENVIRONMENT_MOUNT_PATH).stripSuffix("/")
  private val expectedBaseImage = conf.get(PYTHON_ENVIRONMENT_BASE_IMAGE).trim
  private val namespace = conf.get(PYTHON_ENVIRONMENT_KUBERNETES_NAMESPACE)
  private val builderImage = conf.get(PYTHON_ENVIRONMENT_KUBERNETES_BUILDER_IMAGE).trim
  private val serviceAccount = conf.get(PYTHON_ENVIRONMENT_KUBERNETES_SERVICE_ACCOUNT)
  private val storageClass = conf.get(PYTHON_ENVIRONMENT_PVC_STORAGE_CLASS).trim
  private val pvcAccessMode = conf.get(PYTHON_ENVIRONMENT_PVC_ACCESS_MODE)
  private val pvcSize = conf.get(PYTHON_ENVIRONMENT_PVC_SIZE)
  private lazy val kubernetes = client.getOrElse(PythonEnvironmentService.client())
  private lazy val kubernetesResources = resources.getOrElse(
    new KubernetesPythonEnvironmentResources(kubernetes, namespace))
  @volatile private var hasOpenComputeAllocation: (String => Boolean) =
    (_: String) => false

  def isEnabled: Boolean = enabled

  /** Installed after ComputeAllocationService is initialized to avoid a construction cycle. */
  def onHasOpenComputeAllocation(callback: String => Boolean): Unit = {
    hasOpenComputeAllocation = callback
  }

  /**
   * Deletes terminal builder resources and the profile-scoped Python environment PVC.
   *
   * A PVC is shared by every environment generation of one profile, so it is deleted once only
   * after all associated Builder Jobs are known terminal. A failed Kubernetes deletion aborts
   * profile deletion before its S3 document is removed, leaving durable metadata for a retry.
   */
  def deleteProfileEnvironment(profileId: String): Unit = synchronized {
    if (hasOpenComputeAllocation(profileId)) {
      throw NotebookException.invalid(
        s"cannot delete Python environment storage while Engine Profile '$profileId' has " +
          "open compute allocations")
    }
    val environments = store.listPythonEnvironmentRevisions(profileId)
    val active = environments.filter(environment =>
      environment.state == PythonEnvironmentState.PENDING ||
        environment.state == PythonEnvironmentState.BUILDING)
    if (active.nonEmpty) {
      throw NotebookException.invalid(
        s"cannot delete an Engine Profile while Python environment builds are active: " +
          active.map(_.id).mkString(", "))
    }

    environments.foreach { environment =>
      kubernetesResources.deleteBuilderJob(
        PythonEnvironmentService.resourceName("kyuubi-python-build", environment.id))
      kubernetesResources.deleteRequirementsConfigMap(
        PythonEnvironmentService.resourceName("kyuubi-python-env", environment.id))
    }
    environments.map(_.pvcName).distinct.foreach { pvcName =>
      kubernetesResources.deletePersistentVolumeClaim(pvcName)
    }
    store.deletePythonEnvironmentState(profileId)
  }

  private[server] def resolveLaunchEnvironment(
      snapshot: EngineProfileSnapshot): Option[PythonEnvironmentLaunch] =
    if (!enabled) None
    else {
      snapshot.pythonEnvironmentRevisionId.flatMap(store.getPythonEnvironmentRevision)
        .filter(environment => isUsable(snapshot, environment))
        .map { environment =>
          PythonEnvironmentLaunch(
            environment.id,
            mountConfig(environment))
        }
    }

  def launchConfig(snapshot: EngineProfileSnapshot): Map[String, String] =
    resolveLaunchEnvironment(snapshot).map(_.configuration).getOrElse(Map.empty)

  def environmentRevisionId(snapshot: EngineProfileSnapshot): Option[String] =
    if (!enabled) None
    else resolveLaunchEnvironment(snapshot).map(_.id)

  /**
   * Records a package intent while the current engine is alive.
   *
   * The Python worker performs the hot install immediately. Durable work intentionally waits for
   * the Driver to disappear, so all changes made during one engine lifetime become one immutable
   * environment instead of one Builder Job per `%pip` cell.
   */
  def requestPackageChange(
      principal: EngineProfilePrincipal,
      profileId: String,
      operation: String,
      packages: Seq[String],
      hotInstallSucceeded: Boolean = false,
      requestId: Option[String] = None): PythonEnvironmentChangeRequest = synchronized {
    requireEnabled()
    val profile = engineProfiles.getOwned(profileId, principal)
    val normalized = PythonEnvironmentService.normalizePackages(packages)
    val normalizedOperation = PythonEnvironmentOperation.normalize(operation)
    val changeId = requestId.map(_.trim).filter(_.nonEmpty).getOrElse(UUID.randomUUID().toString)
    if (changeId.length > 64) {
      throw NotebookException.invalid(
        "Python environment request ID must not exceed 64 characters")
    }
    store.getPythonEnvironmentChangeRequest(changeId).foreach { existing =>
      requireSameChange(existing, profileId, principal.user, normalizedOperation, normalized)
      return existing
    }
    val now = System.currentTimeMillis()
    val request = PythonEnvironmentChangeRequest(
      id = changeId,
      profileId = profileId,
      requestedBy = principal.user,
      operation = normalizedOperation,
      requestedPackages = normalized,
      expectedProfileVersion = profile.version,
      state = PythonEnvironmentState.COLLECTING,
      hotInstallState = if (hotInstallSucceeded) "SUCCEEDED" else "NOT_REQUESTED",
      resultingEnvironmentRevisionId = None,
      errorSummary = None,
      createdAt = now,
      updatedAt = now)
    try {
      store.createPythonEnvironmentChangeRequest(request)
      request
    } catch {
      case NonFatal(error) =>
        store.getPythonEnvironmentChangeRequest(changeId) match {
          case Some(existing) =>
            requireSameChange(existing, profileId, principal.user, normalizedOperation, normalized)
            existing
          case None => throw error
        }
    }
  }

  /** Reconciles engine-stop sealing and durable Builder Jobs after a Kyuubi restart. */
  def reconcile(): Unit = {
    if (enabled) {
      (store.listPendingPythonEnvironmentChangeRequests().map(_.profileId) ++
        store.listBuildingPythonEnvironmentProfileIds()).distinct.foreach {
        reconcileProfile
      }
    }
  }

  /**
   * Called before a new Kyuubi session can launch an engine. A pending durable environment is a
   * correctness boundary: launching from env-N after the live Driver died would make packages
   * that were just installed silently disappear.
   */
  def prepareForEngineLaunch(profileId: String): Unit = synchronized {
    if (enabled && profileId != EngineProfileService.DefaultProfileId) {
      reconcileProfile(profileId)
      recoverCompletedBuilds(profileId)
      promoteReadyEnvironmentIfEngineStopped(profileId)
      val profile = engineProfiles.find(profileId)
      val unsettled = pendingRequests(profileId)
      if (profile.exists(current => unsettled.nonEmpty && !hasLiveEngine(current))) {
        throw new NotebookException(
          NotebookErrorCode.PYTHON_ENVIRONMENT_RESTORING,
          "Restoring Python environment before starting a new Spark engine",
          retryable = true,
          details = Map(
            "profileId" -> profileId,
            "requestIds" -> unsettled.map(_.id).mkString(","),
            "state" -> unsettled.map(_.state).distinct.mkString(",")))
      }
    }
  }

  /**
   * Makes the most recent READY environment the profile's next immutable launch revision only
   * after Kyuubi no longer advertises the current profile's Spark engine.
   *
   * A READY environment is intentionally not promoted from [[reconcileBuild]]. A promotion
   * changes the profile subdomain, and doing that while Kyuubi still has the old driver would
   * make the next notebook create a second driver instead of sharing it. The current driver
   * still exposes `%pip install` packages through its session-local directory; this method only
   * controls durable PVC-backed packages for the next engine generation.
   */
  def promoteReadyEnvironmentIfEngineStopped(profileId: String): Unit = synchronized {
    val current = engineProfiles.find(profileId)
    if (enabled && profileId != EngineProfileService.DefaultProfileId &&
      current.exists(profile => !hasLiveEngine(profile))) {
      val latestReady = store.listPythonEnvironmentRevisions(profileId)
        .filter(_.state == PythonEnvironmentState.READY)
        .sortBy(_.revision)
        .lastOption
      latestReady.filter(environment =>
        !current.exists(_.pythonEnvironmentRevisionId.contains(environment.id))).foreach {
        environment =>
          engineProfiles.publishPythonEnvironment(profileId, environment.id)
      }
    }
  }

  private def reconcileProfile(profileId: String): Unit = synchronized {
    val profile = engineProfiles.find(profileId)
    profile.foreach { current =>
      val requests = pendingRequests(profileId)
      if (requests.exists(_.state == PythonEnvironmentState.COLLECTING) && !hasLiveEngine(
          current)) {
        claimCollectingRequests(current)
      }
      val groupedRequests = pendingRequests(profileId).groupBy(_.resultingEnvironmentRevisionId)
      groupedRequests.foreach {
        case (Some(environmentId), grouped) =>
          val existingEnvironment = store.getPythonEnvironmentRevision(environmentId)
          val requirements = requirementsFor(profileId, grouped)
          if (isNoOpBuild(profileId, existingEnvironment, requirements)) {
            settleNoOpBuild(profileId, existingEnvironment, grouped)
          } else {
            existingEnvironment.orElse(ensureClaimedEnvironment(current, environmentId)).foreach {
              environment =>
                environment.state match {
                  case PythonEnvironmentState.PENDING =>
                    dispatch(environment, grouped, requirements)
                  case PythonEnvironmentState.BUILDING =>
                    reconcileBuild(environment, grouped, requirements)
                  case PythonEnvironmentState.READY =>
                    updateRequests(grouped, PythonEnvironmentState.READY)
                  case _ =>
                }
            }
          }
        case _ =>
      }
      val claimedEnvironmentIds = groupedRequests.keySet.collect { case Some(id) => id }.toSet
      retireOrphanedBuildingEnvironments(profileId, claimedEnvironmentIds)
    }
  }

  /**
   * Claims requests in the shared database before allocating an environment revision. A JVM
   * `synchronized` block does not protect two Kyuubi replicas, whereas this conditional update
   * makes exactly one replica the builder owner for a batch of collecting requests.
   */
  private def claimCollectingRequests(profile: EngineProfile): Unit = {
    val now = System.currentTimeMillis()
    val environmentId = UUID.randomUUID().toString
    val claimed = store.claimCollectingPythonEnvironmentChangeRequests(
      profile.profileId,
      environmentId,
      now)
    if (claimed) {
      info(
        s"Claimed collecting Python environment requests for profile ${profile.profileId} " +
          s"as environment $environmentId")
    } else {
      info(
        s"Python environment requests for profile ${profile.profileId} were claimed by " +
          "another Kyuubi replica")
    }
  }

  /**
   * A replica can crash after its database claim and before inserting the environment row. The
   * claim already gives every replica the same environment ID, so a later reconciliation can
   * safely finish that work without opening a second Builder Job for the same request batch.
   */
  private def ensureClaimedEnvironment(
      profile: EngineProfile,
      environmentId: String): Option[PythonEnvironmentRevision] = {
    val now = System.currentTimeMillis()
    val nextRevision = store.listPythonEnvironmentRevisions(profile.profileId)
      .map(_.revision).foldLeft(0L)(math.max) + 1L
    val environment = PythonEnvironmentRevision(
      id = environmentId,
      profileId = profile.profileId,
      revision = nextRevision,
      pvcName = PythonEnvironmentService.pvcName(profile.profileId),
      relativePath = PythonEnvironmentService.relativePath(nextRevision),
      state = PythonEnvironmentState.PENDING,
      requirementsLock = None,
      metadata = None,
      contentChecksum = None,
      baseImage = requiredBaseImage,
      createdAt = now,
      readyAt = None,
      retiredAt = None)
    try {
      store.createPythonEnvironmentRevision(environment)
      Some(environment)
    } catch {
      case NonFatal(error) =>
        store.getPythonEnvironmentRevision(environmentId).orElse {
          // A different claimed batch can win the next numeric revision concurrently. Leave this
          // request in PENDING: the following reconciliation recomputes a free revision instead
          // of changing its immutable environment ID or dispatching a duplicate Job.
          warn(
            s"Could not create claimed Python environment $environmentId for profile " +
              s"${profile.profileId}; reconciliation will retry",
            error)
          None
        }
    }
  }

  private def dispatch(
      environment: PythonEnvironmentRevision,
      requests: Seq[PythonEnvironmentChangeRequest],
      requirements: Seq[String]): Unit = {
    try {
      ensurePvc(environment)
      val configMapName = PythonEnvironmentService.resourceName("kyuubi-python-env", environment.id)
      val labels = Map(
        "app.kubernetes.io/managed-by" -> "kyuubi-python-environment",
        "kyuubi.apache.org/engine-profile-id" -> environment.profileId,
        "kyuubi.apache.org/python-environment-id" -> environment.id)
      val configMap = new ConfigMapBuilder()
        .withNewMetadata()
        .withName(configMapName)
        .addToLabels(labels.asJava)
        .endMetadata()
        .addToData("requirements.txt", requirements.mkString("\n") + "\n")
        .build()
      createIfAbsent(s"ConfigMap $configMapName") {
        kubernetes.configMaps().inNamespace(namespace).resource(configMap).create()
      }
      val jobName = PythonEnvironmentService.resourceName("kyuubi-python-build", environment.id)
      val job = new JobBuilder()
        .withNewMetadata()
        .withName(jobName)
        .addToLabels(labels.asJava)
        .endMetadata()
        .withNewSpec()
        .withBackoffLimit(0)
        .withTtlSecondsAfterFinished(3600)
        .withNewTemplate()
        .withNewMetadata()
        .addToLabels(labels.asJava)
        .endMetadata()
        .withNewSpec()
        .withRestartPolicy("Never")
        .withServiceAccountName(serviceAccount)
        .addNewVolume()
        .withName("python-env")
        .withNewPersistentVolumeClaim()
        .withClaimName(environment.pvcName)
        .endPersistentVolumeClaim()
        .endVolume()
        .addNewVolume()
        .withName("requirements")
        .withNewConfigMap()
        .withName(configMapName)
        .endConfigMap()
        .endVolume()
        .addNewContainer()
        .withName("build")
        .withImage(requiredBuilderImage)
        .withCommand("/opt/kyuubi/python-env-builder/build-environment.sh")
        .addNewEnv().withName("KYUUBI_ENVIRONMENT_PATH")
        .withValue(s"environments/env-${environment.revision}").endEnv()
        .addNewVolumeMount().withName("python-env").withMountPath(mountPath).endVolumeMount()
        .addNewVolumeMount().withName("requirements").withMountPath("/input").withReadOnly(true)
        .endVolumeMount()
        .endContainer()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build()
      createIfAbsent(s"Job $jobName") {
        kubernetes.batch().v1().jobs().inNamespace(namespace).resource(job).create()
      }
      store.updatePythonEnvironmentRevision(
        environment.copy(state = PythonEnvironmentState.BUILDING))
      updateRequests(requests, PythonEnvironmentState.BUILDING)
    } catch {
      case NonFatal(error) => fail(environment, requests, error.getMessage)
    }
  }

  private def reconcileBuild(
      environment: PythonEnvironmentRevision,
      requests: Seq[PythonEnvironmentChangeRequest],
      requirements: Seq[String]): Unit = {
    val jobName = PythonEnvironmentService.resourceName("kyuubi-python-build", environment.id)
    val job = kubernetes.batch().v1().jobs().inNamespace(namespace).withName(jobName).get()
    if (isCompleted(job)) {
      val now = System.currentTimeMillis()
      val ready = environment.copy(
        state = PythonEnvironmentState.READY,
        relativePath = PythonEnvironmentService.relativePath(environment.revision),
        requirementsLock = Some(requirements.mkString("\n")),
        readyAt = Some(now))
      store.updatePythonEnvironmentRevision(ready)
      updateRequests(requests, PythonEnvironmentState.READY, now)
    } else if (isFailed(job)) {
      val detail = jobConditions(job).find(_.getType == "Failed").flatMap(c => Option(c.getMessage))
        .getOrElse("Python environment builder Job failed")
      fail(environment, requests, detail)
    } else if (job == null) {
      fail(
        environment,
        requests,
        s"Python environment builder Job $jobName is no longer present")
    }
  }

  /**
   * A BUILDING row with neither a pending request nor a Kubernetes Job cannot ever become READY.
   * It is retired rather than retried so it does not consume a revision or confuse the lifecycle
   * UI after a race/crash that happened before the claim was fully persisted.
   */
  private def retireOrphanedBuildingEnvironments(
      profileId: String,
      claimedEnvironmentIds: Set[String]): Unit = {
    store.listPythonEnvironmentRevisions(profileId)
      .filter(_.state == PythonEnvironmentState.BUILDING)
      .filterNot(environment => claimedEnvironmentIds.contains(environment.id))
      .foreach { environment =>
        val job = builderJob(environment)
        if (job == null || isCompleted(job) || isFailed(job)) {
          val retired = environment.copy(
            state = PythonEnvironmentState.RETIRED,
            retiredAt = Some(System.currentTimeMillis()))
          store.updatePythonEnvironmentRevision(retired)
          warn(
            s"Retired orphaned Python environment ${environment.id}: its builder Job is " +
              "missing or terminal and no pending request claims it")
        }
      }
  }

  /**
   * Recovers only the narrow failure mode where another Kyuubi replica completed the builder
   * Job while this replica failed an idempotent resource creation. A completed Job proves that
   * the builder wrote COMPLETE and atomically published the environment directory; a genuinely
   * failed Job is never revived.
   */
  private def recoverCompletedBuilds(profileId: String): Unit = {
    store.listPythonEnvironmentRevisions(profileId)
      .filter(_.state == PythonEnvironmentState.FAILED)
      .filter(environment => isCompleted(builderJob(environment)))
      .foreach { environment =>
        val requirements = builderRequirements(environment)
        val ready = environment.copy(
          state = PythonEnvironmentState.READY,
          relativePath = PythonEnvironmentService.relativePath(environment.revision),
          requirementsLock = Some(requirements.mkString("\n")),
          readyAt = Some(System.currentTimeMillis()))
        store.updatePythonEnvironmentRevision(ready)
        warn(
          s"Recovered Python environment ${environment.id}: its Kubernetes builder Job " +
            "completed after a prior reconciliation failure")
      }
  }

  private def ensurePvc(environment: PythonEnvironmentRevision): Unit = {
    val claims = kubernetes.persistentVolumeClaims().inNamespace(namespace)
    val existing = claims.withName(environment.pvcName).get()
    if (existing == null) {
      val builder = new PersistentVolumeClaimBuilder()
        .withNewMetadata()
        .withName(environment.pvcName)
        .addToLabels(Map(
          "app.kubernetes.io/managed-by" -> "kyuubi-python-environment",
          "kyuubi.apache.org/engine-profile" ->
            PythonEnvironmentService.profileHash(environment.profileId)).asJava)
        .endMetadata()
        .withNewSpec()
        .withAccessModes(pvcAccessMode)
        .withResources(new VolumeResourceRequirementsBuilder()
          .addToRequests("storage", new Quantity(pvcSize))
          .build())
      if (storageClass.nonEmpty) builder.withStorageClassName(storageClass)
      createIfAbsent(s"PVC ${environment.pvcName}") {
        claims.resource(builder.endSpec().build()).create()
      }
    } else {
      PythonEnvironmentService.validateExistingPvc(existing, pvcAccessMode, storageClass)
    }
  }

  private def builderJob(environment: PythonEnvironmentRevision) = {
    val name = PythonEnvironmentService.resourceName("kyuubi-python-build", environment.id)
    kubernetes.batch().v1().jobs().inNamespace(namespace).withName(name).get()
  }

  private def jobConditions(job: io.fabric8.kubernetes.api.model.batch.v1.Job) =
    Option(job).flatMap(value => Option(value.getStatus))
      .flatMap(status => Option(status.getConditions)).map(_.asScala).getOrElse(Seq.empty)

  private def isCompleted(job: io.fabric8.kubernetes.api.model.batch.v1.Job): Boolean =
    jobConditions(job).exists(condition =>
      condition.getType == "Complete" && condition.getStatus == "True")

  private def isFailed(job: io.fabric8.kubernetes.api.model.batch.v1.Job): Boolean =
    jobConditions(job).exists(condition =>
      condition.getType == "Failed" && condition.getStatus == "True")

  private def builderRequirements(environment: PythonEnvironmentRevision): Seq[String] = {
    val name = PythonEnvironmentService.resourceName("kyuubi-python-env", environment.id)
    Option(kubernetes.configMaps().inNamespace(namespace).withName(name).get())
      .flatMap(configMap => Option(configMap.getData))
      .flatMap(data => Option(data.get("requirements.txt")))
      .map(_.split("\\r?\\n").toSeq.filter(_.nonEmpty))
      .getOrElse(Seq.empty)
  }

  private def createIfAbsent(resource: String)(create: => Unit): Unit = {
    try {
      create
    } catch {
      case error: io.fabric8.kubernetes.client.KubernetesClientException
          if error.getCode == 409 =>
        info(s"$resource was created concurrently by another Kyuubi replica")
    }
  }

  private def pendingRequests(profileId: String): Seq[PythonEnvironmentChangeRequest] =
    store.listPendingPythonEnvironmentChangeRequests().filter(_.profileId == profileId)

  /**
   * A successful execution may be polled concurrently. Its deterministic request ID makes the
   * package intent exactly-once even when two pollers observe the same terminal transition.
   */
  private def requireSameChange(
      existing: PythonEnvironmentChangeRequest,
      profileId: String,
      requestedBy: String,
      operation: String,
      packages: Seq[String]): Unit = {
    if (existing.profileId != profileId || existing.requestedBy != requestedBy ||
      existing.operation != operation || existing.requestedPackages != packages) {
      throw NotebookException.versionConflict(
        s"Python environment request '${existing.id}' was already used for different packages")
    }
  }

  /**
   * A claimed batch that produces the current lockfile needs no PVC write or Builder Job.
   * Only a PENDING placeholder may be retired here; once BUILDING starts, its Job owns the path.
   */
  private def isNoOpBuild(
      profileId: String,
      claimed: Option[PythonEnvironmentRevision],
      requirements: Seq[String]): Boolean = {
    val safeToSkip = claimed.forall(_.state == PythonEnvironmentState.PENDING)
    val baseline = latestReadyEnvironment(profileId)
    val compatibleBase = baseline.forall(environment =>
      expectedBaseImage.isEmpty || environment.baseImage == expectedBaseImage)
    safeToSkip && compatibleBase && requirements == requirementsOf(baseline)
  }

  private def settleNoOpBuild(
      profileId: String,
      claimed: Option[PythonEnvironmentRevision],
      requests: Seq[PythonEnvironmentChangeRequest]): Unit = {
    val now = System.currentTimeMillis()
    val baseline = latestReadyEnvironment(profileId)
    claimed.foreach { environment =>
      store.updatePythonEnvironmentRevision(environment.copy(
        state = PythonEnvironmentState.RETIRED,
        retiredAt = Some(now)))
    }
    requests.foreach { request =>
      store.updatePythonEnvironmentChangeRequest(request.copy(
        state = PythonEnvironmentState.READY,
        resultingEnvironmentRevisionId = baseline.map(_.id),
        errorSummary = None,
        updatedAt = now))
    }
    info(
      s"Skipped Python environment build for profile $profileId because its requirements " +
        "did not change")
  }

  private def latestReadyEnvironment(profileId: String): Option[PythonEnvironmentRevision] =
    store.listPythonEnvironmentRevisions(profileId)
      .filter(_.state == PythonEnvironmentState.READY)
      .sortBy(_.revision)
      .lastOption

  private def requirementsOf(
      environment: Option[PythonEnvironmentRevision]): Seq[String] =
    environment.flatMap(_.requirementsLock)
      .map(_.split("\\r?\\n").toSeq.filter(_.nonEmpty))
      .getOrElse(Seq.empty)

  /**
   * Applies ordered intents to the last published environment and returns a standalone lockfile.
   */
  private def requirementsFor(
      profileId: String,
      requests: Seq[PythonEnvironmentChangeRequest]): Seq[String] = {
    val previous = requirementsOf(latestReadyEnvironment(profileId))
    requests.sortBy(_.createdAt).foldLeft(previous) { (requirements, request) =>
      request.operation match {
        case PythonEnvironmentOperation.INSTALL =>
          request.requestedPackages.foldLeft(requirements) { (current, requirement) =>
            current.filterNot(PythonEnvironmentService.requirementName(_) ==
              PythonEnvironmentService.requirementName(requirement)) :+ requirement
          }
        case PythonEnvironmentOperation.UNINSTALL =>
          requirements.filterNot(value =>
            request.requestedPackages.exists(name =>
              PythonEnvironmentService.requirementName(value) ==
                PythonEnvironmentService.requirementName(name)))
      }
    }.distinct.sortBy(PythonEnvironmentService.requirementName)
  }

  private def fail(
      environment: PythonEnvironmentRevision,
      requests: Seq[PythonEnvironmentChangeRequest],
      detail: String): Unit = {
    val now = System.currentTimeMillis()
    store.updatePythonEnvironmentRevision(environment.copy(state = PythonEnvironmentState.FAILED))
    requests.foreach { request =>
      store.updatePythonEnvironmentChangeRequest(request.copy(
        state = PythonEnvironmentState.FAILED,
        errorSummary = Option(detail).filter(_.nonEmpty),
        updatedAt = now))
    }
  }

  private def updateRequests(
      requests: Seq[PythonEnvironmentChangeRequest],
      state: String,
      now: Long = System.currentTimeMillis()): Unit = {
    requests.foreach(request =>
      store.updatePythonEnvironmentChangeRequest(request.copy(state = state, updatedAt = now)))
  }

  private def requireEnabled(): Unit = {
    if (!enabled) throw new KyuubiException(
      s"${PYTHON_ENVIRONMENT_ENABLED.key} must be true to build a persistent Python environment")
  }

  private def requiredBuilderImage: String = {
    requireEnabled()
    if (builderImage.isEmpty) throw new KyuubiException(
      s"${PYTHON_ENVIRONMENT_KUBERNETES_BUILDER_IMAGE.key} must be set")
    builderImage
  }

  private def requiredBaseImage: String = {
    if (expectedBaseImage.isEmpty) throw new KyuubiException(
      s"${PYTHON_ENVIRONMENT_BASE_IMAGE.key} must be set")
    expectedBaseImage
  }

  private def isUsable(
      snapshot: EngineProfileSnapshot,
      environment: PythonEnvironmentRevision): Boolean = {
    environment.profileId == snapshot.profileId &&
    environment.state == PythonEnvironmentState.READY &&
    (expectedBaseImage.isEmpty || expectedBaseImage == environment.baseImage)
  }

  /**
   * Discovery is Kyuubi's source of truth for a reusable engine. If it is temporarily
   * unavailable, preserve the current revision: creating another driver is worse than delaying
   * durable-environment promotion until the next request.
   */
  private def hasLiveEngine(profile: EngineProfile): Boolean = {
    val engineSpace = DiscoveryPaths.makePath(
      s"${conf.get(HA_NAMESPACE)}_${KYUUBI_VERSION}_USER_SPARK_SQL",
      profile.owner,
      profile.subdomain)
    try {
      hasOpenComputeAllocation(profile.profileId) ||
      withDiscoveryClient(conf)(_.getServiceNodesInfo(engineSpace, silent = true).nonEmpty)
    } catch {
      case NonFatal(error) =>
        warn(
          s"Cannot determine whether Engine Profile ${profile.profileId} has live compute",
          error)
        true
    }
  }

  private def mountConfig(environment: PythonEnvironmentRevision): Map[String, String] = {
    val volume = "engine-profile-python-env"
    val pvcPrefix = "spark.kubernetes.%s.volumes.persistentVolumeClaim." + volume
    val driverPrefix = pvcPrefix.format("driver")
    val executorPrefix = pvcPrefix.format("executor")
    val relativePath = Option(environment.relativePath).filter(_.nonEmpty)
      .getOrElse(PythonEnvironmentService.relativePath(environment.revision))
    val python = s"$mountPath/$relativePath/bin/python"
    Map(
      s"$driverPrefix.options.claimName" -> environment.pvcName,
      s"$driverPrefix.mount.path" -> mountPath,
      s"$driverPrefix.mount.readOnly" -> "true",
      s"$executorPrefix.options.claimName" -> environment.pvcName,
      s"$executorPrefix.mount.path" -> mountPath,
      s"$executorPrefix.mount.readOnly" -> "true",
      "spark.pyspark.driver.python" -> python,
      "spark.pyspark.python" -> python,
      "spark.kubernetes.driverEnv.PYSPARK_PYTHON" -> python,
      "spark.executorEnv.PYSPARK_PYTHON" -> python)
  }
}

object PythonEnvironmentState {
  val COLLECTING = "COLLECTING"
  val PENDING = "PENDING"
  val BUILDING = "BUILDING"
  val READY = "READY"
  val FAILED = "FAILED"
  val RETIRED = "RETIRED"
}

object PythonEnvironmentOperation {
  val INSTALL = "INSTALL"
  val UNINSTALL = "UNINSTALL"

  def normalize(value: String): String = Option(value).map(_.trim.toUpperCase) match {
    case Some(INSTALL) => INSTALL
    case Some(UNINSTALL) => UNINSTALL
    case _ => throw new KyuubiException("Python environment operation must be INSTALL or UNINSTALL")
  }
}

object PythonEnvironmentService {
  private val Requirement = "^[A-Za-z0-9][A-Za-z0-9_.-]*(==[A-Za-z0-9_.+!~-]+)?$".r

  def client(): KubernetesClient = {
    val config: Config = new ConfigBuilder(Config.autoConfigure(null)).build()
    new KubernetesClientBuilder().withConfig(config).build()
  }

  def normalizePackages(packages: Seq[String]): Seq[String] = {
    val normalized = packages.map(_.trim).filter(_.nonEmpty).distinct.sorted
    if (normalized.isEmpty || normalized.exists(value =>
        !Requirement.pattern.matcher(value).matches())) {
      throw NotebookException.invalid(
        "Python packages must be simple package names or exact pinned requirements; " +
          "pip options are not allowed")
    }
    normalized
  }

  /** Package identity ignores an exact-version suffix when intents replace or remove a package. */
  private[engineprofile] def requirementName(requirement: String): String =
    requirement.takeWhile(_ != '=').toLowerCase

  /** PVC identity derives from immutable profile ID, never owner or mutable display name. */
  def pvcName(profileId: String): String =
    s"pyenv-${profileId.toLowerCase.replace("-", "").take(48)}"

  def relativePath(revision: Long): String = s"environments/env-$revision"

  private[engineprofile] def resourceName(prefix: String, id: String): String =
    s"$prefix-${id.replace("-", "").take(24)}"

  private[engineprofile] def profileHash(profileId: String): String =
    Integer.toHexString(profileId.hashCode).replace('-', '0')

  /**
   * An operator may change the desired StorageClass or access mode only for new claims. Mutating
   * the live claim would risk detaching it from engines that still use a captured environment.
   */
  private[engineprofile] def validateExistingPvc(
      pvc: PersistentVolumeClaim,
      requiredAccessMode: String,
      requiredStorageClass: String): Unit = {
    val name = Option(pvc.getMetadata).flatMap(metadata => Option(metadata.getName))
      .getOrElse("<unknown>")
    val spec = Option(pvc.getSpec).getOrElse {
      throw new KyuubiException(s"Python environment PVC $name has no spec")
    }
    val actualModes = Option(spec.getAccessModes).map(_.asScala.toSet).getOrElse(Set.empty)
    if (!actualModes.contains(requiredAccessMode)) {
      throw new KyuubiException(
        s"Python environment PVC $name uses access modes " +
          s"${actualModes.toSeq.sorted.mkString(",")} but the server requires " +
          s"$requiredAccessMode; migrate the PVC explicitly instead of changing it in place")
    }
    val actualStorageClass = Option(spec.getStorageClassName).map(_.trim).getOrElse("")
    if (requiredStorageClass.nonEmpty && actualStorageClass != requiredStorageClass) {
      throw new KyuubiException(
        s"Python environment PVC $name uses StorageClass '$actualStorageClass' but the server " +
          s"requires '$requiredStorageClass'; migrate the PVC explicitly instead of changing " +
          "it in place")
    }
  }
}

private[server] case class PythonEnvironmentLaunch(
    id: String,
    configuration: Map[String, String])

/** Kubernetes resources created for one profile environment lifecycle. */
private[engineprofile] trait PythonEnvironmentResources {
  def deleteBuilderJob(name: String): Unit
  def deleteRequirementsConfigMap(name: String): Unit
  def deletePersistentVolumeClaim(name: String): Unit
}

private[engineprofile] class KubernetesPythonEnvironmentResources(
    kubernetes: KubernetesClient,
    namespace: String) extends PythonEnvironmentResources {

  override def deleteBuilderJob(name: String): Unit =
    deleteIgnoringNotFound(s"Python environment Builder Job $name") {
      kubernetes.batch().v1().jobs().inNamespace(namespace).withName(name).delete()
    }

  override def deleteRequirementsConfigMap(name: String): Unit =
    deleteIgnoringNotFound(s"Python environment requirements ConfigMap $name") {
      kubernetes.configMaps().inNamespace(namespace).withName(name).delete()
    }

  override def deletePersistentVolumeClaim(name: String): Unit =
    deleteIgnoringNotFound(s"Python environment PVC $name") {
      kubernetes.persistentVolumeClaims().inNamespace(namespace).withName(name).delete()
    }

  private def deleteIgnoringNotFound(resource: String)(delete: => Any): Unit = {
    try {
      delete
      ()
    } catch {
      case error: io.fabric8.kubernetes.client.KubernetesClientException
          if error.getCode == 404 =>
        ()
      case error: io.fabric8.kubernetes.client.KubernetesClientException =>
        throw new KyuubiException(s"Could not delete $resource", error)
    }
  }
}
