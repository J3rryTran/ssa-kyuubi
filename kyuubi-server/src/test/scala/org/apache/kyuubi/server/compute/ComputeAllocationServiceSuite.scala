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
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

import org.apache.kyuubi.server.engineprofile.EngineProfilePrincipal
import org.apache.kyuubi.server.notebook.NotebookConf.COMPUTE_LIFECYCLE_CLEANUP_DEADLINE
import org.apache.kyuubi.server.notebook.NotebookTestBase
import org.apache.kyuubi.server.notebook.api.{EngineProfile, NotebookErrorCode, NotebookException, UpsertEngineProfileRequest}
import org.apache.kyuubi.server.notebook.service.NotebookPrincipal

class ComputeAllocationServiceSuite extends NotebookTestBase {

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private def profilePrincipal(principal: NotebookPrincipal): EngineProfilePrincipal =
    EngineProfilePrincipal(principal.user, principal.admin)

  private def createProfile(
      principal: NotebookPrincipal,
      name: String,
      memory: String = "2g"): EngineProfile = {
    val request = new UpsertEngineProfileRequest
    request.setName(name)
    request.setSparkConfig(new util.HashMap(Map("spark.driver.memory" -> memory).asJava))
    request.setNotebookRuntimeIdleTimeout("PT10M")
    request.setEngineIdleTimeout("PT30M")
    manager.engineProfiles.create(profilePrincipal(principal), request)
  }

  private def release(allocation: ComputeAllocation): Unit = {
    val now = math.max(System.currentTimeMillis(), allocation.updatedAt + 1)
    val releasing = allocation.copy(
      state = ComputeAllocationState.RELEASING,
      updatedAt = now,
      version = allocation.version + 1)
    assert(manager.computeStore.updateAllocation(
      releasing,
      allocation.version,
      Set(allocation.state)))
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

  private def releaseCurrent(allocationId: String): Unit =
    manager.computeStore.getAllocation(allocationId)
      .filter(allocation => ComputeAllocationState.open.contains(allocation.state))
      .foreach(release)

  test("compute failure logging redacts credentials") {
    val safe = ComputeLogContext.safeFailure(new IllegalStateException(
      "token=token-value password: password-value Bearer bearer-value"))
    assert(safe.contains("<redacted>"))
    assert(!safe.contains("token-value"))
    assert(!safe.contains("password-value"))
    assert(!safe.contains("bearer-value"))
  }

  test("interactive allocation reuses one stable engine identity") {
    val profile = createProfile(alice, "interactive-allocation")
    val first = manager.computeAllocations.acquireInteractive(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK)
    val second = manager.computeAllocations.acquireInteractive(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.SQL)

    assert(second.id === first.id)
    assert(first.mode === ComputeMode.INTERACTIVE)
    assert(first.subdomain === profile.subdomain)
    assert(first.launchSnapshot.profileVersion === profile.version)
    assert(first.launchSnapshot.sparkConfig("spark.driver.memory") === "2g")
    release(first)
  }

  test("explicit interactive release fences new sessions until completion") {
    val profile = createProfile(alice, "interactive-release")
    val first = manager.computeAllocations.acquireInteractive(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.SQL)

    val releasing = manager.computeAllocations.beginInteractiveRelease(
      profilePrincipal(alice),
      profile.profileId).get
    assert(releasing.id === first.id)
    assert(releasing.state === ComputeAllocationState.RELEASING)
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.computeAllocations.acquireInteractive(
        profilePrincipal(alice),
        profile.profileId,
        ComputeWorkloadType.NOTEBOOK)
    }

    manager.computeAllocations.completeRelease(first.id)
    val replacement = manager.computeAllocations.acquireInteractive(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK)
    assert(replacement.id !== first.id)
    release(replacement)
  }

  test("job attempts are idempotent and isolated on dedicated engine identities") {
    val profile = createProfile(alice, "job-allocation")
    val runId = UUID.randomUUID().toString
    val attemptOne = UUID.randomUUID().toString
    val first = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      runId,
      attemptOne)
    val replay = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      runId,
      attemptOne)
    interceptNotebook(NotebookErrorCode.VERSION_CONFLICT) {
      manager.computeAllocations.reserveJob(
        profilePrincipal(alice),
        profile.profileId,
        ComputeWorkloadType.DBT,
        ComputeTriggerType.MANUAL,
        runId,
        attemptOne)
    }
    val second = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      runId,
      UUID.randomUUID().toString)

    assert(replay.id === first.id)
    assert(second.id !== first.id)
    assert(second.subdomain !== first.subdomain)
    assert(second.engineKey !== first.engineKey)
    assert(first.subdomain.startsWith("job-"))
    assert(first.workloadHeartbeatAt.nonEmpty)
    assert(first.workloadDeadlineAt.exists(_ > first.workloadHeartbeatAt.get))
    val safeStatus = manager.computeAllocations.managedStatus(
      profilePrincipal(alice),
      ComputeWorkloadType.DBT,
      runId,
      Some(attemptOne),
      Some(first.id),
      "FAILED",
      Some("token=must-not-leave-the-server"))
    assert(safeStatus.errorMessage.nonEmpty)
    assert(!safeStatus.toString.contains("must-not-leave-the-server"))
    assert(safeStatus.createdAt.contains(first.createdAt))
    assert(safeStatus.cleanupState === "NOT_REQUESTED")
    assert(!safeStatus.cleanupOverdue)
    assert(manager.computeAllocations.routingConfiguration(first)(
      "kyuubi.session.engine.idle.timeout") === "0")
    release(first)
    release(second)
  }

  test("managed status exposes cleanup timing and cleanup retry is idempotent") {
    val profile = createProfile(alice, "observable-cleanup")
    val runId = UUID.randomUUID().toString
    val attemptId = UUID.randomUUID().toString
    val allocation = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      runId,
      attemptId)
    val releasing = manager.computeAllocations.requestRelease(allocation.id, "test completed")

    val pending = manager.computeAllocations.managedStatus(
      profilePrincipal(alice),
      ComputeWorkloadType.DBT,
      runId,
      Some(attemptId),
      Some(allocation.id),
      "SUCCEEDED",
      None)
    assert(pending.cleanupState === "REQUESTED")
    assert(pending.cleanupRequestedAt.nonEmpty)
    assert(pending.cleanupDeadlineAt.exists(_ > pending.cleanupRequestedAt.get))
    assert(pending.cleanupAgeMillis.exists(_ >= 0L))

    val retried = manager.computeAllocations.retryCleanup(
      profilePrincipal(alice),
      allocation.id,
      ComputeWorkloadType.DBT)
    assert(retried.state === ComputeAllocationState.RELEASING)
    assert(retried.version > releasing.version)
    val repeated = manager.computeAllocations.retryCleanup(
      profilePrincipal(alice),
      allocation.id,
      ComputeWorkloadType.DBT)
    assert(repeated.state === ComputeAllocationState.RELEASING)
    assert(repeated.releaseRequestedAt === retried.releaseRequestedAt)
    manager.computeAllocations.completeRelease(allocation.id)
    val completed = manager.computeAllocations.managedStatus(
      profilePrincipal(alice),
      ComputeWorkloadType.DBT,
      runId,
      Some(attemptId),
      Some(allocation.id),
      "SUCCEEDED",
      None)
    assert(completed.cleanupState === "COMPLETED")
  }

  test("managed status keeps overdue cleanup visible") {
    val profile = createProfile(alice, "overdue-cleanup")
    val runId = UUID.randomUUID().toString
    val attemptId = UUID.randomUUID().toString
    val allocation = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      runId,
      attemptId)
    val requestedAt = System.currentTimeMillis() -
      conf.get(COMPUTE_LIFECYCLE_CLEANUP_DEADLINE) - 1
    val releasing = allocation.copy(
      state = ComputeAllocationState.RELEASING,
      releaseRequestedAt = Some(requestedAt),
      updatedAt = math.max(System.currentTimeMillis(), allocation.updatedAt + 1),
      version = allocation.version + 1)
    assert(manager.computeStore.updateAllocation(
      releasing,
      allocation.version,
      Set(ComputeAllocationState.RESERVED)))

    val status = manager.computeAllocations.managedStatus(
      profilePrincipal(alice),
      ComputeWorkloadType.DBT,
      runId,
      Some(attemptId),
      Some(allocation.id),
      "SUCCEEDED",
      None)
    assert(status.cleanupState === "OVERDUE")
    assert(status.cleanupOverdue)
    assert(status.cleanupAgeMillis.exists(_ > conf.get(COMPUTE_LIFECYCLE_CLEANUP_DEADLINE)))
    assert(manager.computeStore.getAllocation(allocation.id).exists(
      _.state == ComputeAllocationState.RELEASING))
    manager.computeAllocations.completeRelease(allocation.id)
  }

  test("concurrent sessions attach one JOB allocation idempotently") {
    val profile = createProfile(alice, "concurrent-job-attach")
    val allocation = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)
    val workers = 8
    val executor = Executors.newFixedThreadPool(workers)
    val attachExecutionContext: ExecutionContext =
      ExecutionContext.fromExecutorService(executor)
    val ready = new CountDownLatch(workers)
    val start = new CountDownLatch(1)
    try {
      val attempts = (1 to workers).map { index =>
        Future {
          ready.countDown()
          assert(start.await(10, TimeUnit.SECONDS))
          Try(manager.computeAllocations.recordEngineReference(
            allocation.id,
            s"session-$index"))
        }(attachExecutionContext)
      }
      assert(ready.await(10, TimeUnit.SECONDS))
      start.countDown()
      val results = attempts.map(Await.result(_, 20.seconds))

      assert(results.forall(_.isSuccess))
      val attached = manager.computeStore.getAllocation(allocation.id).get
      assert(attached.state === ComputeAllocationState.STARTING)
      assert(attached.engineReferenceId.exists(_.startsWith("session-")))
      release(attached)
    } finally {
      start.countDown()
      executor.shutdownNow()
    }
  }

  test("allocation snapshot stays immutable after a later profile update") {
    val profile = createProfile(alice, "immutable-allocation")
    val original = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK,
      ComputeTriggerType.API,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)
    release(original)

    val request = new UpsertEngineProfileRequest
    request.setName(profile.name)
    request.setSparkConfig(new util.HashMap(Map("spark.driver.memory" -> "4g").asJava))
    val updated = manager.engineProfiles.update(
      profile.profileId,
      profilePrincipal(alice),
      request)
    val next = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK,
      ComputeTriggerType.API,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)

    assert(original.launchSnapshot.sparkConfig("spark.driver.memory") === "2g")
    assert(original.launchSnapshot.profileVersion === profile.version)
    assert(next.launchSnapshot.sparkConfig("spark.driver.memory") === "4g")
    assert(next.launchSnapshot.profileVersion === updated.version)
    release(next)
  }

  test("profile update and JOB reservation cannot cross the owner-lock boundary") {
    val profile = createProfile(alice, "reservation-update-race")
    val update = new UpsertEngineProfileRequest
    update.setName(profile.name)
    update.setSparkConfig(new util.HashMap(Map("spark.driver.memory" -> "4g").asJava))

    val reservation = Future(Try(manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.API,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)))
    val mutation = Future(Try(manager.engineProfiles.update(
      profile.profileId,
      profilePrincipal(alice),
      update)))
    val allocation = Await.result(reservation, 20.seconds).get
    val updateResult = Await.result(mutation, 20.seconds)

    try {
      updateResult.fold(
        error => {
          assert(error.asInstanceOf[NotebookException].code === NotebookErrorCode.COMPUTE_ACTIVE)
          assert(allocation.launchSnapshot.sparkConfig("spark.driver.memory") === "2g")
        },
        updated => {
          assert(updated.sparkConfig("spark.driver.memory") === "4g")
          assert(allocation.launchSnapshot.sparkConfig("spark.driver.memory") === "4g")
        })
    } finally releaseCurrent(allocation.id)
  }

  test("profile deletion and JOB reservation have one serialized winner") {
    val profile = createProfile(alice, "reservation-delete-race")
    val reservation = Future(Try(manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK,
      ComputeTriggerType.API,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)))
    val deletion = Future(Try(manager.engineProfiles.delete(
      profile.profileId,
      profilePrincipal(alice))))
    val allocationResult = Await.result(reservation, 20.seconds)
    val deletionResult = Await.result(deletion, 20.seconds)

    try {
      assert(allocationResult.isSuccess !== deletionResult.isSuccess)
      allocationResult.foreach { allocation =>
        assert(deletionResult.failed.get.asInstanceOf[NotebookException]
          .code === NotebookErrorCode.COMPUTE_ACTIVE)
        releaseCurrent(allocation.id)
        manager.engineProfiles.delete(profile.profileId, profilePrincipal(alice))
      }
      deletionResult.foreach { _ =>
        assert(allocationResult.failed.get.asInstanceOf[NotebookException]
          .code === NotebookErrorCode.NOTEBOOK_NOT_FOUND)
      }
    } finally {
      allocationResult.foreach(allocation => releaseCurrent(allocation.id))
    }
  }

  test("open allocation blocks profile mutation deletion and environment publication") {
    val profile = createProfile(alice, "guarded-allocation")
    val allocation = manager.computeAllocations.acquireInteractive(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK)
    val update = new UpsertEngineProfileRequest
    update.setName(profile.name)
    update.setSparkConfig(new util.HashMap(Map("spark.driver.memory" -> "4g").asJava))

    try {
      interceptNotebook(NotebookErrorCode.COMPUTE_ACTIVE) {
        manager.engineProfiles.update(profile.profileId, profilePrincipal(alice), update)
      }
      interceptNotebook(NotebookErrorCode.COMPUTE_ACTIVE) {
        manager.engineProfiles.delete(profile.profileId, profilePrincipal(alice))
      }
      interceptNotebook(NotebookErrorCode.COMPUTE_ACTIVE) {
        manager.engineProfiles.publishPythonEnvironment(profile.profileId, "environment-ready")
      }
      val stopped = manager.engineProfileRuntimes.stop(
        profilePrincipal(alice),
        profile.profileId)
      assert(stopped.state === "STOPPED")
      assert(manager.computeStore.getAllocation(allocation.id).exists(
        _.state == ComputeAllocationState.RELEASED))
    } finally releaseCurrent(allocation.id)
  }

  test("a JOB waiting for cleanup keeps every profile and environment guard closed") {
    val profile = createProfile(alice, "releasing-job-guard")
    val allocation = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK,
      ComputeTriggerType.SCHEDULE,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)
    val releasing = manager.computeAllocations.requestRelease(
      allocation.id,
      "test cleanup pending")
    assert(releasing.state === ComputeAllocationState.RELEASING)
    val update = new UpsertEngineProfileRequest
    update.setName(profile.name)
    update.setSparkConfig(new util.HashMap(Map("spark.driver.memory" -> "4g").asJava))

    try {
      interceptNotebook(NotebookErrorCode.COMPUTE_ACTIVE) {
        manager.engineProfiles.update(profile.profileId, profilePrincipal(alice), update)
      }
      interceptNotebook(NotebookErrorCode.COMPUTE_ACTIVE) {
        manager.engineProfiles.delete(profile.profileId, profilePrincipal(alice))
      }
      interceptNotebook(NotebookErrorCode.COMPUTE_ACTIVE) {
        manager.engineProfiles.publishPythonEnvironment(profile.profileId, "environment-ready")
      }

      manager.computeAllocations.completeRelease(allocation.id)
      val updated = manager.engineProfiles.update(
        profile.profileId,
        profilePrincipal(alice),
        update)
      assert(updated.sparkConfig("spark.driver.memory") === "4g")
    } finally releaseCurrent(allocation.id)
  }

  test("profile authorization and built-in default apply to allocation") {
    val profile = createProfile(alice, "private-allocation")
    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      manager.computeAllocations.acquireInteractive(
        profilePrincipal(bob),
        profile.profileId,
        ComputeWorkloadType.NOTEBOOK)
    }

    val default = manager.computeAllocations.acquireInteractive(
      profilePrincipal(bob),
      "default",
      ComputeWorkloadType.SQL)
    assert(default.owner === bob.user)
    assert(default.profileId === "default")
    assert(default.subdomain === "default")
    assert(default.launchSnapshot.profileVersion === 1L)
    release(default)
  }

  test("job capacity is enforced before another allocation is inserted") {
    val profile = createProfile(alice, "limited-allocation")
    val first = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.API,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)
    var second: Option[ComputeAllocation] = None
    try {
      second = Some(manager.computeAllocations.reserveJob(
        profilePrincipal(alice),
        profile.profileId,
        ComputeWorkloadType.DBT,
        ComputeTriggerType.API,
        UUID.randomUUID().toString,
        UUID.randomUUID().toString))

      val error = interceptNotebook(NotebookErrorCode.RATE_LIMITED) {
        manager.computeAllocations.reserveJob(
          profilePrincipal(alice),
          profile.profileId,
          ComputeWorkloadType.DBT,
          ComputeTriggerType.API,
          UUID.randomUUID().toString,
          UUID.randomUUID().toString)
      }
      assert(error.retryable)
    } finally {
      releaseCurrent(first.id)
      second.foreach(allocation => releaseCurrent(allocation.id))
    }
  }

  test("two service replicas serialize the final per-user JOB capacity slot") {
    val profile = createProfile(alice, "replica-capacity")
    val first = manager.computeAllocations.reserveJob(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.API,
      UUID.randomUUID().toString,
      UUID.randomUUID().toString)
    val peerStore = new JDBCComputeStore(conf)
    val peer = ComputeAllocationService.create(
      conf,
      peerStore,
      manager.engineProfiles,
      None)
    peer.initialize()
    var winners = Seq.empty[ComputeAllocation]
    try {
      val attempts = Seq(manager.computeAllocations, peer).map { service =>
        Future(Try(service.reserveJob(
          profilePrincipal(alice),
          profile.profileId,
          ComputeWorkloadType.DBT,
          ComputeTriggerType.API,
          UUID.randomUUID().toString,
          UUID.randomUUID().toString)))
      }
      val results = Await.result(Future.sequence(attempts), 20.seconds)
      winners = results.flatMap(_.toOption)
      val errors = results.flatMap(_.failed.toOption)

      assert(winners.size === 1)
      assert(errors.size === 1)
      assert(errors.head.asInstanceOf[NotebookException]
        .code === NotebookErrorCode.RATE_LIMITED)
    } finally {
      releaseCurrent(first.id)
      winners.foreach(allocation => releaseCurrent(allocation.id))
      peer.close()
      peerStore.close()
    }
  }

  test("routing configuration is server owned and rejects raw launch overrides") {
    val profile = createProfile(alice, "routing-allocation")
    val allocation = manager.computeAllocations.acquireInteractive(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK)
    val routing = manager.computeAllocations.routingConfiguration(allocation)

    assert(routing("spark.driver.memory") === "2g")
    assert(routing("kyuubi.engine.share.level") === "USER")
    assert(routing("kyuubi.engine.share.level.subdomain") === profile.subdomain)
    assert(routing("kyuubi.session.engine.idle.timeout") === "1800000")
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.computeAllocations.rejectReservedClientConfiguration(Map(
        "spark.driver.memory" -> "64g",
        "kyuubi.engine.share.level.subdomain" -> "other-engine"))
    }
    manager.computeAllocations.rejectReservedClientConfiguration(Map(
      "kyuubi.operation.language" -> "PYTHON"))
    release(allocation)
  }

  test("profile mutation guard exposes a stable compute-active error code") {
    val profile = createProfile(alice, "active-profile-guard")
    val allocation = manager.computeAllocations.acquireInteractive(
      profilePrincipal(alice),
      profile.profileId,
      ComputeWorkloadType.NOTEBOOK)

    interceptNotebook(NotebookErrorCode.COMPUTE_ACTIVE) {
      manager.computeAllocations.requireNoOpenAllocations(profile.profileId, "update it")
    }
    release(allocation)
  }
}
