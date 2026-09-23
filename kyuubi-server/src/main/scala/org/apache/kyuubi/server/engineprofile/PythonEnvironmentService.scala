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

import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, PersistentVolumeClaimBuilder, Quantity, VolumeResourceRequirementsBuilder}
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
    client: Option[KubernetesClient] = None) extends Logging {
  private val enabled = conf.get(PYTHON_ENVIRONMENT_ENABLED)
  private val mountPath = conf.get(PYTHON_ENVIRONMENT_MOUNT_PATH).stripSuffix("/")
  private val expectedBaseImage = conf.get(PYTHON_ENVIRONMENT_BASE_IMAGE).trim
  private val namespace = conf.get(PYTHON_ENVIRONMENT_KUBERNETES_NAMESPACE)
  private val builderImage = conf.get(PYTHON_ENVIRONMENT_KUBERNETES_BUILDER_IMAGE).trim
  private val serviceAccount = conf.get(PYTHON_ENVIRONMENT_KUBERNETES_SERVICE_ACCOUNT)
  private val storageClass = conf.get(PYTHON_ENVIRONMENT_PVC_STORAGE_CLASS).trim
  private val pvcSize = conf.get(PYTHON_ENVIRONMENT_PVC_SIZE)
  private lazy val kubernetes = client.getOrElse(PythonEnvironmentService.client())

  def isEnabled: Boolean = enabled

  def launchConfig(snapshot: EngineProfileSnapshot): Map[String, String] = {
    if (!enabled) {
      Map.empty
    } else {
      snapshot.pythonEnvironmentRevisionId.flatMap(store.getPythonEnvironmentRevision) match {
        case Some(environment) if isUsable(snapshot, environment) => mountConfig(environment)
        case _ => Map.empty
      }
    }
  }

  def environmentRevisionId(snapshot: EngineProfileSnapshot): Option[String] =
    if (!enabled) None
    else {
      snapshot.pythonEnvironmentRevisionId.flatMap(store.getPythonEnvironmentRevision)
        .filter(environment => isUsable(snapshot, environment))
        .map(_.id)
    }

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
      hotInstallSucceeded: Boolean = false): PythonEnvironmentChangeRequest = synchronized {
    requireEnabled()
    val profile = engineProfiles.getOwned(profileId, principal)
    val normalized = PythonEnvironmentService.normalizePackages(packages)
    val normalizedOperation = PythonEnvironmentOperation.normalize(operation)
    val now = System.currentTimeMillis()
    val request = PythonEnvironmentChangeRequest(
      id = UUID.randomUUID().toString,
      profileId = profileId,
      requestedBy = principal.user,
      operation = normalizedOperation,
      requestedPackages = normalized,
      expectedProfileRevision = profile.revision,
      state = PythonEnvironmentState.COLLECTING,
      hotInstallState = if (hotInstallSucceeded) "SUCCEEDED" else "NOT_REQUESTED",
      resultingEnvironmentRevisionId = None,
      errorSummary = None,
      createdAt = now,
      updatedAt = now)
    store.createPythonEnvironmentChangeRequest(request)
    request
  }

  /** Reconciles engine-stop sealing and durable Builder Jobs after a Kyuubi restart. */
  def reconcile(): Unit = {
    if (enabled) {
      store.listPendingPythonEnvironmentChangeRequests().map(_.profileId).distinct.foreach {
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
      val profile = store.getEngineProfile(profileId)
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
   * after Kyuubi no longer advertises the current profile revision's Spark engine.
   *
   * A READY environment is intentionally not promoted from [[reconcileBuild]]. A promotion
   * changes the profile subdomain, and doing that while Kyuubi still has the old driver would
   * make the next notebook create a second driver instead of sharing it. The current driver
   * still exposes `%pip install` packages through its session-local directory; this method only
   * controls durable PVC-backed packages for the next engine generation.
   */
  def promoteReadyEnvironmentIfEngineStopped(profileId: String): Unit = synchronized {
    val current = store.getEngineProfile(profileId)
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
    val profile = store.getEngineProfile(profileId)
    profile.foreach { current =>
      val requests = pendingRequests(profileId)
      if (requests.exists(_.state == PythonEnvironmentState.COLLECTING) && !hasLiveEngine(
          current)) {
        sealAndDispatch(current, requests.filter(_.state == PythonEnvironmentState.COLLECTING))
      }
      pendingRequests(profileId).groupBy(_.resultingEnvironmentRevisionId).foreach {
        case (Some(environmentId), grouped) =>
          store.getPythonEnvironmentRevision(environmentId).foreach { environment =>
            val requirements = requirementsFor(profileId, grouped)
            environment.state match {
              case PythonEnvironmentState.PENDING => dispatch(environment, grouped, requirements)
              case PythonEnvironmentState.BUILDING =>
                reconcileBuild(environment, grouped, requirements)
              case _ =>
            }
          }
        case _ =>
      }
    }
  }

  private def sealAndDispatch(
      profile: EngineProfile,
      requests: Seq[PythonEnvironmentChangeRequest]): Unit = {
    val now = System.currentTimeMillis()
    val nextRevision = store.listPythonEnvironmentRevisions(profile.profileId)
      .map(_.revision).foldLeft(0L)(math.max) + 1L
    val environment = PythonEnvironmentRevision(
      id = UUID.randomUUID().toString,
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
    store.createPythonEnvironmentRevision(environment)
    val sealedRequests = requests.map { request =>
      val sealedRequest = request.copy(
        state = PythonEnvironmentState.PENDING,
        resultingEnvironmentRevisionId = Some(environment.id),
        updatedAt = now)
      store.updatePythonEnvironmentChangeRequest(sealedRequest)
      sealedRequest
    }
    dispatch(environment, sealedRequests, requirementsFor(profile.profileId, sealedRequests))
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
    if (claims.withName(environment.pvcName).get() == null) {
      val builder = new PersistentVolumeClaimBuilder()
        .withNewMetadata()
        .withName(environment.pvcName)
        .addToLabels(Map(
          "app.kubernetes.io/managed-by" -> "kyuubi-python-environment",
          "kyuubi.apache.org/engine-profile" ->
            PythonEnvironmentService.profileHash(environment.profileId)).asJava)
        .endMetadata()
        .withNewSpec()
        .withAccessModes("ReadWriteOnce")
        .withResources(new VolumeResourceRequirementsBuilder()
          .addToRequests("storage", new Quantity(pvcSize))
          .build())
      if (storageClass.nonEmpty) builder.withStorageClassName(storageClass)
      createIfAbsent(s"PVC ${environment.pvcName}") {
        claims.resource(builder.endSpec().build()).create()
      }
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
   * Applies ordered intents to the last published environment and returns a standalone lockfile.
   */
  private def requirementsFor(
      profileId: String,
      requests: Seq[PythonEnvironmentChangeRequest]): Seq[String] = {
    val previous = store.listPythonEnvironmentRevisions(profileId)
      .filter(_.state == PythonEnvironmentState.READY)
      .sortBy(_.revision)
      .lastOption
      .flatMap(_.requirementsLock)
      .map(_.split("\\r?\\n").toSeq.filter(_.nonEmpty))
      .getOrElse(Seq.empty)
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
      withDiscoveryClient(conf)(_.getServiceNodesInfo(engineSpace, silent = true).nonEmpty)
    } catch {
      case NonFatal(error) =>
        warn(
          s"Cannot determine whether Engine Profile ${profile.profileId} has a live engine",
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
      "spark.pyspark.python" -> python)
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
}
