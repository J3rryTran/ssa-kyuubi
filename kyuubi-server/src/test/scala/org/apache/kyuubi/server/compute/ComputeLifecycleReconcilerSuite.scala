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

import scala.collection.JavaConverters._

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.server.engineprofile.EngineProfilePrincipal
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.NotebookTestBase
import org.apache.kyuubi.server.notebook.api.UpsertEngineProfileRequest

class ComputeLifecycleReconcilerSuite extends NotebookTestBase {

  private def principal = EngineProfilePrincipal(alice.user, admin = false)

  private def allocation(name: String): ComputeAllocation = {
    val request = new UpsertEngineProfileRequest
    request.setName(name)
    request.setSparkConfig(new util.HashMap(Map("spark.driver.memory" -> "2g").asJava))
    val profile = manager.engineProfiles.create(principal, request)
    val created = manager.computeAllocations.acquireInteractive(
      principal,
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK)
    manager.computeAllocations.recordEngineReference(created.id, s"engine-${created.id}")
    manager.computeStore.getAllocation(created.id).get
  }

  private def reconciler(
      controllerId: String,
      engine: RecordingEngineController,
      clock: () => Long): ComputeLifecycleReconciler = {
    conf.set(COMPUTE_LIFECYCLE_CLAIM_TTL, 1000L)
    conf.set(COMPUTE_LIFECYCLE_LATE_LAUNCH_GRACE, 100L)
    conf.set(COMPUTE_ENGINE_REGISTRATION_TIMEOUT, 1000L)
    conf.set(COMPUTE_JOB_HEARTBEAT_TIMEOUT, 1000L)
    new ComputeLifecycleReconciler(
      conf,
      manager.computeStore,
      engine,
      controllerId,
      clock)
  }

  test("active allocation is observed and terminal intent is cleaned exactly once") {
    var now = System.currentTimeMillis() + 10000L
    val created = allocation("lifecycle-success")
    val engine = new RecordingEngineController
    val controller = reconciler("replica-a", engine, () => now)

    controller.reconcile()
    assert(manager.computeStore.getAllocation(created.id).exists(
      _.state == ComputeAllocationState.ACTIVE))

    manager.computeAllocations.requestRelease(created.id, "workload completed")
    engine.state = ComputeEngineResourceState.RUNNING
    now += 2000L
    controller.reconcile()

    val released = manager.computeStore.getAllocation(created.id).get
    assert(released.state === ComputeAllocationState.RELEASED)
    assert(released.releasedAt.nonEmpty)
    assert(released.controllerId.isEmpty)
    assert(engine.fenceCalls >= 1)
    assert(engine.terminateCalls === 1)
    controller.reconcile()
    assert(engine.terminateCalls === 1)
  }

  test("expired cleanup claim is taken over and a transient failure is retained") {
    var now = System.currentTimeMillis() + 10000L
    val created = allocation("lifecycle-takeover")
    manager.computeAllocations.requestRelease(created.id, "cancelled")
    val engine = new RecordingEngineController
    engine.failuresRemaining = 1
    val first = reconciler("replica-a", engine, () => now)
    val second = reconciler("replica-b", engine, () => now)

    first.reconcile()
    val failed = manager.computeStore.getAllocation(created.id).get
    assert(failed.state === ComputeAllocationState.RELEASING)
    assert(failed.cleanupAttempts === 1)
    assert(failed.lastError.exists(_.contains("temporary resource-manager failure")))

    second.reconcile()
    assert(manager.computeStore.getAllocation(created.id).exists(
      _.state == ComputeAllocationState.RELEASING))
    now = failed.claimExpiresAt.get + 2000L
    second.reconcile()
    assert(manager.computeStore.getAllocation(created.id).exists(
      _.state == ComputeAllocationState.RELEASED))
    assert(engine.terminateCalls === 2)
  }

  test("unknown application state never becomes released") {
    var now = System.currentTimeMillis() + 10000L
    val created = allocation("lifecycle-unknown")
    manager.computeAllocations.requestRelease(created.id, "cancelled")
    val engine = new RecordingEngineController
    engine.state = ComputeEngineResourceState.UNKNOWN
    engine.terminationSucceeds = false
    val controller = reconciler("replica-a", engine, () => now)

    controller.reconcile()
    val pending = manager.computeStore.getAllocation(created.id).get
    assert(pending.state === ComputeAllocationState.RELEASING)
    assert(pending.lastError.nonEmpty)
    now = pending.claimExpiresAt.get + 2000L
    controller.reconcile()
    assert(manager.computeStore.getAllocation(created.id).exists(
      _.state == ComputeAllocationState.RELEASING))
  }

  test("an absent late launch remains fenced until its grace period expires") {
    val created = allocation("lifecycle-late-launch")
    var now = created.createdAt + 50L
    manager.computeAllocations.requestRelease(created.id, "cancelled before registration")
    val engine = new RecordingEngineController
    engine.state = ComputeEngineResourceState.ABSENT
    val controller = reconciler("replica-a", engine, () => now)

    controller.reconcile()
    assert(manager.computeStore.getAllocation(created.id).exists(
      _.state == ComputeAllocationState.RELEASING))
    assert(engine.terminateCalls === 0)

    now = created.createdAt + 2000L
    controller.reconcile()
    assert(manager.computeStore.getAllocation(created.id).exists(
      _.state == ComputeAllocationState.RELEASED))
    assert(engine.terminateCalls === 0)
  }

  test("an expired job heartbeat creates durable cleanup intent") {
    val request = new UpsertEngineProfileRequest
    request.setName("lifecycle-job-heartbeat")
    request.setSparkConfig(new util.HashMap(Map("spark.driver.memory" -> "2g").asJava))
    val profile = manager.engineProfiles.create(principal, request)
    val created = manager.computeAllocations.reserveJob(
      principal,
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      "run-heartbeat",
      "attempt-heartbeat")
    manager.computeAllocations.recordEngineReference(created.id, s"engine-${created.id}")
    var now = created.workloadHeartbeatAt.get + 2000L
    val engine = new RecordingEngineController
    val controller = reconciler("replica-a", engine, () => now)

    controller.reconcile()
    val releasing = manager.computeStore.getAllocation(created.id).get
    assert(releasing.state === ComputeAllocationState.RELEASING)
    assert(releasing.lastError.contains("managed workload heartbeat expired"))

    now += 2000L
    controller.reconcile()
    assert(manager.computeStore.getAllocation(created.id).exists(
      _.state == ComputeAllocationState.RELEASED))
    assert(engine.terminateCalls === 1)
  }
}

private class RecordingEngineController extends ComputeEngineController {
  var state: ComputeEngineResourceState.Value = ComputeEngineResourceState.RUNNING
  var failuresRemaining: Int = 0
  var fenceCalls: Int = 0
  var terminateCalls: Int = 0
  var terminationSucceeds: Boolean = true

  override def fenceLocalSessions(allocation: ComputeAllocation): Unit = fenceCalls += 1

  override def observe(allocation: ComputeAllocation): ComputeEngineObservation =
    ComputeEngineObservation(
      state,
      allocation.engineReferenceId,
      Some(s"application-${allocation.id}"))

  override def terminate(
      allocation: ComputeAllocation,
      observation: ComputeEngineObservation): ComputeEngineObservation = {
    terminateCalls += 1
    if (failuresRemaining > 0) {
      failuresRemaining -= 1
      throw new KyuubiException("temporary resource-manager failure")
    }
    if (terminationSucceeds) {
      state = ComputeEngineResourceState.TERMINATED
    }
    observe(allocation)
  }
}
