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

import java.util

import scala.collection.JavaConverters._

import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.compute.{ComputeAllocation, ComputeTriggerType, ComputeWorkloadType}
import org.apache.kyuubi.server.notebook.NotebookConf.{PYTHON_ENVIRONMENT_BASE_IMAGE, PYTHON_ENVIRONMENT_ENABLED, PYTHON_ENVIRONMENT_KUBERNETES_BUILDER_IMAGE, PYTHON_ENVIRONMENT_RECONCILE_INTERVAL}
import org.apache.kyuubi.server.notebook.NotebookTestBase
import org.apache.kyuubi.server.notebook.api.{EngineProfile, NotebookException, PythonEnvironmentChangeRequest, PythonEnvironmentRevision, UpsertEngineProfileRequest}

class PythonEnvironmentServiceSuite extends NotebookTestBase {

  override protected def configureTest(testConf: KyuubiConf): Unit = {
    testConf
      .set(PYTHON_ENVIRONMENT_ENABLED, true)
      .set(PYTHON_ENVIRONMENT_BASE_IMAGE, "test-image")
      .set(PYTHON_ENVIRONMENT_KUBERNETES_BUILDER_IMAGE, "test-builder")
      .set(PYTHON_ENVIRONMENT_RECONCILE_INTERVAL, 86400000L)
  }

  private def createProfile(): EngineProfile = {
    val request = new UpsertEngineProfileRequest
    request.setName(s"python-environment-cleanup-${util.UUID.randomUUID().toString.take(8)}")
    request.setSparkConfig(new util.HashMap(Map("spark.driver.memory" -> "1g").asJava))
    manager.engineProfiles.create(EngineProfilePrincipal(alice.user, admin = false), request)
  }

  private def environment(
      profileId: String,
      id: String,
      revision: Long,
      state: String,
      requirementsLock: Option[String] = None): PythonEnvironmentRevision = {
    val now = System.currentTimeMillis()
    PythonEnvironmentRevision(
      id = id,
      profileId = profileId,
      revision = revision,
      pvcName = PythonEnvironmentService.pvcName(profileId),
      relativePath = PythonEnvironmentService.relativePath(revision),
      state = state,
      requirementsLock = requirementsLock,
      metadata = None,
      contentChecksum = None,
      baseImage = "test-image",
      createdAt = now,
      readyAt = None,
      retiredAt = None)
  }

  private def release(allocation: ComputeAllocation): Unit = {
    manager.computeAllocations.requestRelease(allocation.id, "test completed")
    manager.computeAllocations.completeRelease(allocation.id)
  }

  test("deleting a terminal profile environment removes its Kubernetes resources and metadata") {
    val profile = createProfile()
    val ready = environment(
      profile.profileId,
      "ready-environment",
      1L,
      PythonEnvironmentState.READY)
    val failed = environment(
      profile.profileId,
      "failed-environment",
      2L,
      PythonEnvironmentState.FAILED)
    manager.store.createPythonEnvironmentRevision(ready)
    manager.store.createPythonEnvironmentRevision(failed)
    val request = PythonEnvironmentChangeRequest(
      id = "terminal-request",
      profileId = profile.profileId,
      requestedBy = alice.user,
      operation = PythonEnvironmentOperation.INSTALL,
      requestedPackages = Seq("seaborn"),
      expectedProfileVersion = profile.version,
      state = PythonEnvironmentState.READY,
      hotInstallState = "SUCCEEDED",
      resultingEnvironmentRevisionId = Some(ready.id),
      errorSummary = None,
      createdAt = ready.createdAt,
      updatedAt = ready.createdAt)
    manager.store.createPythonEnvironmentChangeRequest(request)
    val resources = new RecordingPythonEnvironmentResources
    val service = new PythonEnvironmentService(
      conf,
      manager.store,
      manager.engineProfiles,
      resources = Some(resources))

    service.deleteProfileEnvironment(profile.profileId)

    assert(resources.jobs.toSet === Set(
      PythonEnvironmentService.resourceName("kyuubi-python-build", ready.id),
      PythonEnvironmentService.resourceName("kyuubi-python-build", failed.id)))
    assert(resources.configMaps.toSet === Set(
      PythonEnvironmentService.resourceName("kyuubi-python-env", ready.id),
      PythonEnvironmentService.resourceName("kyuubi-python-env", failed.id)))
    assert(resources.pvcs === Seq(ready.pvcName))
    assert(manager.store.listPythonEnvironmentRevisions(profile.profileId).isEmpty)
    assert(manager.store.getPythonEnvironmentChangeRequest(request.id).isEmpty)
  }

  test("deleting a profile environment rejects a builder that can still write to its PVC") {
    val profile = createProfile()
    val building = environment(
      profile.profileId,
      "building-environment",
      1L,
      PythonEnvironmentState.BUILDING)
    manager.store.createPythonEnvironmentRevision(building)
    val resources = new RecordingPythonEnvironmentResources
    val service = new PythonEnvironmentService(
      conf,
      manager.store,
      manager.engineProfiles,
      resources = Some(resources))

    intercept[NotebookException] {
      service.deleteProfileEnvironment(profile.profileId)
    }

    assert(resources.jobs.isEmpty)
    assert(resources.configMaps.isEmpty)
    assert(resources.pvcs.isEmpty)
    assert(manager.store.getPythonEnvironmentRevision(building.id).contains(building))
    assert(manager.store.updatePythonEnvironmentRevision(
      building.copy(state = PythonEnvironmentState.FAILED)))
  }

  test("one successful execution records its package intent exactly once") {
    val profile = createProfile()
    val principal = EngineProfilePrincipal(alice.user, admin = false)

    val first = manager.pythonEnvironments.requestPackageChange(
      principal,
      profile.profileId,
      PythonEnvironmentOperation.INSTALL,
      Seq("pandas==2.2.3"),
      hotInstallSucceeded = true,
      requestId = Some("execution-idempotency-test"))
    val replay = manager.pythonEnvironments.requestPackageChange(
      principal,
      profile.profileId,
      PythonEnvironmentOperation.INSTALL,
      Seq("pandas==2.2.3"),
      hotInstallSucceeded = true,
      requestId = Some("execution-idempotency-test"))

    assert(replay === first)
    assert(manager.store.listPendingPythonEnvironmentChangeRequests()
      .count(_.profileId == profile.profileId) === 1)
    assert(manager.store.updatePythonEnvironmentChangeRequest(
      first.copy(state = PythonEnvironmentState.READY)))
  }

  test("an unchanged requirements lock skips the environment builder") {
    val profile = createProfile()
    val ready = environment(
      profile.profileId,
      "ready-no-op-environment",
      1L,
      PythonEnvironmentState.READY,
      requirementsLock = Some("pandas==2.2.3"))
    manager.store.createPythonEnvironmentRevision(ready)
    manager.engineProfiles.publishPythonEnvironment(profile.profileId, ready.id)
    val request = manager.pythonEnvironments.requestPackageChange(
      EngineProfilePrincipal(alice.user, admin = false),
      profile.profileId,
      PythonEnvironmentOperation.INSTALL,
      Seq("pandas==2.2.3"),
      hotInstallSucceeded = true,
      requestId = Some("execution-no-op-test"))

    manager.pythonEnvironments.reconcile()

    assert(manager.store.listPythonEnvironmentRevisions(profile.profileId) === Seq(ready))
    val settled = manager.store.getPythonEnvironmentChangeRequest(request.id).get
    assert(settled.state === PythonEnvironmentState.READY)
    assert(settled.resultingEnvironmentRevisionId.contains(ready.id))
  }

  test("an open compute allocation keeps package changes collecting") {
    val profile = createProfile()
    val request = manager.pythonEnvironments.requestPackageChange(
      EngineProfilePrincipal(alice.user, admin = false),
      profile.profileId,
      PythonEnvironmentOperation.INSTALL,
      Seq("numpy==2.1.3"),
      hotInstallSucceeded = true,
      requestId = Some("execution-open-allocation-test"))
    manager.pythonEnvironments.onHasOpenComputeAllocation(_ => true)

    try {
      manager.pythonEnvironments.reconcile()

      val collecting = manager.store.getPythonEnvironmentChangeRequest(request.id).get
      assert(collecting.state === PythonEnvironmentState.COLLECTING)
      assert(manager.store.listPythonEnvironmentRevisions(profile.profileId).isEmpty)
    } finally {
      manager.pythonEnvironments.onHasOpenComputeAllocation(
        manager.computeAllocations.hasOpenAllocationForProfile)
      manager.store.updatePythonEnvironmentChangeRequest(
        request.copy(state = PythonEnvironmentState.READY))
    }
  }

  test("a compute allocation pins one Python environment ID PVC and path") {
    val profile = createProfile()
    val firstEnvironment = environment(
      profile.profileId,
      "pinned-environment-1",
      1L,
      PythonEnvironmentState.READY,
      requirementsLock = Some("pandas==2.2.3"))
    manager.store.createPythonEnvironmentRevision(firstEnvironment)
    manager.engineProfiles.publishPythonEnvironment(profile.profileId, firstEnvironment.id)

    val first = manager.computeAllocations.reserveJob(
      EngineProfilePrincipal(alice.user, admin = false),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK,
      ComputeTriggerType.API,
      util.UUID.randomUUID().toString,
      util.UUID.randomUUID().toString)
    val firstRouting = manager.computeAllocations.routingConfiguration(first)
    release(first)

    val secondEnvironment = environment(
      profile.profileId,
      "pinned-environment-2",
      2L,
      PythonEnvironmentState.READY,
      requirementsLock = Some("pandas==2.2.3\nseaborn==0.13.2"))
    manager.store.createPythonEnvironmentRevision(secondEnvironment)
    manager.engineProfiles.publishPythonEnvironment(profile.profileId, secondEnvironment.id)
    val second = manager.computeAllocations.reserveJob(
      EngineProfilePrincipal(alice.user, admin = false),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK,
      ComputeTriggerType.API,
      util.UUID.randomUUID().toString,
      util.UUID.randomUUID().toString)
    val secondRouting = manager.computeAllocations.routingConfiguration(second)

    assert(first.launchSnapshot.pythonEnvironmentRevisionId.contains(firstEnvironment.id))
    assert(firstRouting(
      "spark.kubernetes.driver.volumes.persistentVolumeClaim." +
        "engine-profile-python-env.options.claimName") === firstEnvironment.pvcName)
    assert(firstRouting("spark.pyspark.python").contains(firstEnvironment.relativePath))
    assert(second.launchSnapshot.pythonEnvironmentRevisionId.contains(secondEnvironment.id))
    assert(secondRouting(
      "spark.kubernetes.driver.volumes.persistentVolumeClaim." +
        "engine-profile-python-env.options.claimName") === secondEnvironment.pvcName)
    assert(secondRouting("spark.pyspark.python").contains(secondEnvironment.relativePath))
    assert(manager.computeAllocations.routingConfiguration(first) === firstRouting)
    release(second)
  }

  test("Python environment storage survives until allocation cleanup completes") {
    val profile = createProfile()
    val ready = environment(
      profile.profileId,
      "cleanup-guard-environment",
      1L,
      PythonEnvironmentState.READY)
    manager.store.createPythonEnvironmentRevision(ready)
    val allocation = manager.computeAllocations.reserveJob(
      EngineProfilePrincipal(alice.user, admin = false),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      util.UUID.randomUUID().toString,
      util.UUID.randomUUID().toString)
    val resources = new RecordingPythonEnvironmentResources
    val service = new PythonEnvironmentService(
      conf,
      manager.store,
      manager.engineProfiles,
      resources = Some(resources))
    service.onHasOpenComputeAllocation(manager.computeAllocations.hasOpenAllocationForProfile)

    manager.computeAllocations.requestRelease(allocation.id, "workload completed")
    intercept[NotebookException] {
      service.deleteProfileEnvironment(profile.profileId)
    }
    assert(resources.pvcs.isEmpty)
    assert(manager.store.getPythonEnvironmentRevision(ready.id).contains(ready))

    manager.computeAllocations.completeRelease(allocation.id)
    service.deleteProfileEnvironment(profile.profileId)
    assert(resources.pvcs === Seq(ready.pvcName))
    assert(manager.store.getPythonEnvironmentRevision(ready.id).isEmpty)
  }

  test("an existing PVC is validated but never mutated for a new storage policy") {
    val pvc = new PersistentVolumeClaimBuilder()
      .withNewMetadata()
      .withName("pyenv-policy-test")
      .endMetadata()
      .withNewSpec()
      .withAccessModes("ReadWriteOnce")
      .withStorageClassName("local-path")
      .endSpec()
      .build()

    PythonEnvironmentService.validateExistingPvc(pvc, "ReadWriteOnce", "local-path")
    intercept[KyuubiException] {
      PythonEnvironmentService.validateExistingPvc(pvc, "ReadWriteMany", "local-path")
    }
    intercept[KyuubiException] {
      PythonEnvironmentService.validateExistingPvc(pvc, "ReadWriteOnce", "shared-rwx")
    }
    assert(pvc.getSpec.getAccessModes.asScala.toSeq === Seq("ReadWriteOnce"))
    assert(pvc.getSpec.getStorageClassName === "local-path")
  }
}

private class RecordingPythonEnvironmentResources extends PythonEnvironmentResources {
  var jobs: Seq[String] = Seq.empty
  var configMaps: Seq[String] = Seq.empty
  var pvcs: Seq[String] = Seq.empty

  override def deleteBuilderJob(name: String): Unit = jobs :+= name

  override def deleteRequirementsConfigMap(name: String): Unit = configMaps :+= name

  override def deletePersistentVolumeClaim(name: String): Unit = pvcs :+= name
}
