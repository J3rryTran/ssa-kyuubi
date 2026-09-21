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
import org.apache.kyuubi.server.notebook.api.{EngineProfile, NotebookException, PythonEnvironmentChangeRequest, PythonEnvironmentRevision}
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
   * Persists an allowlisted package change before dispatching its builder Job.
   *
   * Requests for one profile are serialized: an active request is returned instead of creating
   * another writer for the same PVC. The current profile revision keeps serving live engines
   * until the new environment is fully built and can safely be promoted for a new engine.
   */
  def requestPackageChange(
      principal: EngineProfilePrincipal,
      profileId: String,
      operation: String,
      packages: Seq[String]): PythonEnvironmentChangeRequest = synchronized {
    requireEnabled()
    val profile = engineProfiles.getOwned(profileId, principal)
    val normalized = PythonEnvironmentService.normalizePackages(packages)
    val normalizedOperation = PythonEnvironmentOperation.normalize(operation)
    val active = store.listPendingPythonEnvironmentChangeRequests().find(_.profileId == profileId)
    active.getOrElse {
      val now = System.currentTimeMillis()
      val nextRevision = store.listPythonEnvironmentRevisions(profileId)
        .map(_.revision).foldLeft(0L)(math.max) + 1L
      val environment = PythonEnvironmentRevision(
        id = UUID.randomUUID().toString,
        profileId = profileId,
        revision = nextRevision,
        pvcName = PythonEnvironmentService.pvcName(profileId),
        relativePath = PythonEnvironmentService.relativePath(nextRevision),
        state = PythonEnvironmentState.PENDING,
        requirementsLock = None,
        metadata = None,
        contentChecksum = None,
        baseImage = requiredBaseImage,
        createdAt = now,
        readyAt = None,
        retiredAt = None)
      val request = PythonEnvironmentChangeRequest(
        id = UUID.randomUUID().toString,
        profileId = profileId,
        requestedBy = principal.user,
        operation = normalizedOperation,
        requestedPackages = normalized,
        expectedProfileRevision = profile.revision,
        state = PythonEnvironmentState.PENDING,
        hotInstallState = "NOT_REQUESTED",
        resultingEnvironmentRevisionId = Some(environment.id),
        errorSummary = None,
        createdAt = now,
        updatedAt = now)
      store.createPythonEnvironmentRevision(environment)
      store.createPythonEnvironmentChangeRequest(request)
      dispatch(request, environment)
      store.getPythonEnvironmentChangeRequest(request.id).getOrElse(request)
    }
  }

  /** Reattaches durable builder Jobs after a Kyuubi restart without submitting duplicates. */
  def reconcile(): Unit = {
    if (enabled) {
      store.listPendingPythonEnvironmentChangeRequests().foreach { request =>
        request.resultingEnvironmentRevisionId.flatMap(store.getPythonEnvironmentRevision).foreach {
          environment =>
            if (request.state == PythonEnvironmentState.PENDING) dispatch(request, environment)
            else if (request.state == PythonEnvironmentState.BUILDING) {
              reconcileBuild(request, environment)
            }
        }
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

  private def dispatch(
      request: PythonEnvironmentChangeRequest,
      environment: PythonEnvironmentRevision): Unit = {
    try {
      ensurePvc(environment)
      val requirements = requirementsFor(request)
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
      kubernetes.configMaps().inNamespace(namespace).resource(configMap).createOrReplace()
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
      kubernetes.batch().v1().jobs().inNamespace(namespace).resource(job).createOrReplace()
      store.updatePythonEnvironmentRevision(
        environment.copy(state = PythonEnvironmentState.BUILDING))
      store.updatePythonEnvironmentChangeRequest(request.copy(
        state = PythonEnvironmentState.BUILDING,
        updatedAt = System.currentTimeMillis()))
    } catch {
      case NonFatal(error) => fail(request, environment, error.getMessage)
    }
  }

  private def reconcileBuild(
      request: PythonEnvironmentChangeRequest,
      environment: PythonEnvironmentRevision): Unit = {
    val jobName = PythonEnvironmentService.resourceName("kyuubi-python-build", environment.id)
    val job = kubernetes.batch().v1().jobs().inNamespace(namespace).withName(jobName).get()
    val conditions = Option(job).flatMap(value => Option(value.getStatus))
      .flatMap(status => Option(status.getConditions)).map(_.asScala).getOrElse(Seq.empty)
    if (conditions.exists(condition =>
        condition.getType == "Complete" && condition.getStatus == "True")) {
      val now = System.currentTimeMillis()
      val ready = environment.copy(
        state = PythonEnvironmentState.READY,
        relativePath = PythonEnvironmentService.relativePath(environment.revision),
        requirementsLock = Some(requirementsFor(request).mkString("\n")),
        readyAt = Some(now))
      store.updatePythonEnvironmentRevision(ready)
      store.updatePythonEnvironmentChangeRequest(request.copy(
        state = PythonEnvironmentState.READY,
        updatedAt = now))
    } else if (conditions.exists(c => c.getType == "Failed" && c.getStatus == "True")) {
      val detail = conditions.find(_.getType == "Failed").flatMap(c => Option(c.getMessage))
        .getOrElse("Python environment builder Job failed")
      fail(request, environment, detail)
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
      claims.resource(builder.endSpec().build()).create()
    }
  }

  private def requirementsFor(request: PythonEnvironmentChangeRequest): Seq[String] = {
    val previous = store.listPythonEnvironmentRevisions(request.profileId)
      .find(_.state == PythonEnvironmentState.READY)
      .flatMap(_.requirementsLock)
      .map(_.split("\\r?\\n").toSeq.filter(_.nonEmpty))
      .getOrElse(Seq.empty)
    request.operation match {
      case PythonEnvironmentOperation.INSTALL =>
        (previous ++ request.requestedPackages).distinct.sorted
      case PythonEnvironmentOperation.UNINSTALL =>
        previous.filterNot(value =>
          request.requestedPackages.exists(name => value.startsWith(name)))
    }
  }

  private def fail(
      request: PythonEnvironmentChangeRequest,
      environment: PythonEnvironmentRevision,
      detail: String): Unit = {
    val now = System.currentTimeMillis()
    store.updatePythonEnvironmentRevision(environment.copy(state = PythonEnvironmentState.FAILED))
    store.updatePythonEnvironmentChangeRequest(request.copy(
      state = PythonEnvironmentState.FAILED,
      errorSummary = Option(detail).filter(_.nonEmpty),
      updatedAt = now))
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

  /** PVC identity derives from immutable profile ID, never owner or mutable display name. */
  def pvcName(profileId: String): String =
    s"pyenv-${profileId.toLowerCase.replace("-", "").take(48)}"

  def relativePath(revision: Long): String = s"environments/env-$revision"

  private[engineprofile] def resourceName(prefix: String, id: String): String =
    s"$prefix-${id.replace("-", "").take(24)}"

  private[engineprofile] def profileHash(profileId: String): String =
    Integer.toHexString(profileId.hashCode).replace('-', '0')
}
