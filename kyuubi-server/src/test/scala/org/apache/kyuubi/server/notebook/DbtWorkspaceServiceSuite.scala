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

package org.apache.kyuubi.server.notebook

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.compute.{ComputeAllocationState, ComputeMode, ComputeTriggerType}
import org.apache.kyuubi.server.dbt._
import org.apache.kyuubi.server.engineprofile.EngineProfilePrincipal
import org.apache.kyuubi.server.notebook.NotebookConf.DBT_MANAGED_CLI_ENABLED
import org.apache.kyuubi.server.notebook.api.{NotebookErrorCode, UpsertEngineProfileRequest}

class DbtWorkspaceServiceSuite extends NotebookTestBase {

  private def profile(owner: String, id: String, memory: String): Unit = {
    val request = new UpsertEngineProfileRequest
    request.setSparkConfig(Map("spark.driver.memory" -> memory).asJava)
    manager.engineProfiles.upsert(id, EngineProfilePrincipal(owner, admin = false), request)
  }

  test("workspace and job authorize Engine Profile ownership") {
    profile("alice", "alice-small", "1g")
    profile("bob", "bob-large", "4g")

    val workspace = manager.dbt.createWorkspace(alice, "project", "project-1", "alice-small")
    assert(workspace.owner === "alice")
    assert(workspace.selectedEngineProfileId === "alice-small")

    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      manager.dbt.createWorkspace(bob, "bad", "project-2", "alice-small")
    }
    interceptNotebook(NotebookErrorCode.DBT_RESOURCE_NOT_FOUND) {
      manager.dbt.getWorkspace(bob, workspace.id)
    }
  }

  test("job inherits workspace profile and snapshots it when submitted") {
    profile("alice", "alice-snapshot", "1g")
    val workspace = manager.dbt.createWorkspace(alice, "project", "project-1", "alice-snapshot")
    val job = manager.dbt.createJob(alice, workspace.id, "build", "RUN_PROJECT", None, None)
    var submittedRequest: Option[DbtRunRequest] = None
    val runner = new DbtRunner {
      override def submit(request: DbtRunRequest): DbtRunnerSubmission = {
        submittedRequest = Some(request)
        DbtRunnerSubmission("runner-1")
      }
      override def findSubmission(runId: String): Option[DbtRunnerSubmission] = None
      override def status(runnerRunId: String): DbtRunnerStatus =
        DbtRunnerStatus(DbtRunState.SUCCEEDED)
      override def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage =
        DbtRunnerLogPage("", offset, endOfStream = true)
      override def cancel(runnerRunId: String): Unit = ()
      override def cleanup(runId: String): Unit = ()
    }

    val run = manager.dbt.submitJob(alice, job.id, runner)
    assert(run.profile.profileId === "alice-snapshot")
    assert(run.profile.sparkConfig === Map("spark.driver.memory" -> "1g"))
    assert(run.effectiveKyuubiUser === "alice")
    assert(run.computeMode === ComputeMode.JOB)
    assert(run.computeAllocationId.nonEmpty)
    assert(run.computeBindingId.contains(submittedRequest.get.binding.bindingId))
    assert(submittedRequest.get.binding.allocationId === run.computeAllocationId.get)
    assert(submittedRequest.get.binding.token.nonEmpty)
    assert(run.state === DbtRunState.SUBMITTED)

    assert(manager.dbt.getRun(alice, run.id).profile.sparkConfig ===
      Map("spark.driver.memory" -> "1g"))
    manager.computeAllocations.requestRelease(run.computeAllocationId.get, "test completed")
    manager.computeAllocations.completeRelease(run.computeAllocationId.get)
  }

  test("disabled runner refuses submission before it creates a run") {
    profile("alice", "alice-small", "1g")
    val workspace = manager.dbt.createWorkspace(alice, "project", "project-1", "alice-small")
    val job = manager.dbt.createJob(alice, workspace.id, "build", "RUN_PROJECT", None, None)

    intercept[DbtRunnerNotConfiguredException] {
      manager.dbt.submitJob(alice, job.id, DisabledDbtRunner)
    }
  }

  test("DBT submit idempotency creates one attempt and rejects a different payload") {
    profile("alice", "alice-idempotent", "1g")
    val workspace = manager.dbt.createWorkspace(
      alice,
      "idempotent",
      "project-idempotent",
      "alice-idempotent")
    val firstJob = manager.dbt.createJob(
      alice,
      workspace.id,
      "first",
      "RUN_PROJECT",
      None,
      None)
    val secondJob = manager.dbt.createJob(
      alice,
      workspace.id,
      "second",
      "RUN_PROJECT",
      None,
      None)
    val submissions = new AtomicInteger(0)
    val runner = new DbtRunner {
      override def submit(request: DbtRunRequest): DbtRunnerSubmission = {
        DbtRunnerSubmission("runner-" + submissions.incrementAndGet())
      }
      override def findSubmission(runId: String): Option[DbtRunnerSubmission] = None
      override def status(runnerRunId: String): DbtRunnerStatus =
        DbtRunnerStatus(DbtRunState.RUNNING)
      override def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage =
        DbtRunnerLogPage("", offset, endOfStream = false)
      override def cancel(runnerRunId: String): Unit = ()
      override def cleanup(runId: String): Unit = ()
    }

    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.dbt.submitScheduledJob(alice, firstJob.id, None, runner)
    }

    val secondStore = new JDBCDbtStore(conf)
    val secondReplica = new DbtWorkspaceService(
      conf,
      secondStore,
      manager.engineProfiles,
      manager.computeAllocations,
      manager.computeBindings,
      runner)
    val ready = new CountDownLatch(2)
    val start = new CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    val calls = Seq(manager.dbt, secondReplica).map { service =>
      executor.submit(new Callable[DbtJobRun] {
        override def call(): DbtJobRun = {
          ready.countDown()
          assert(start.await(10, TimeUnit.SECONDS))
          service.submitScheduledJob(
            alice,
            firstJob.id,
            Some("scheduled-occurrence-1"),
            runner)
        }
      })
    }
    val (first, retried) =
      try {
        assert(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        calls.head.get(20, TimeUnit.SECONDS) -> calls(1).get(20, TimeUnit.SECONDS)
      } finally {
        start.countDown()
        executor.shutdownNow()
        secondStore.close()
      }
    assert(retried.id === first.id)
    assert(retried.attemptId === first.attemptId)
    assert(retried.computeAllocationId === first.computeAllocationId)
    assert(submissions.get() === 1)
    val status = manager.dbt.status(alice, first.id)
    assert(status.mode === "JOB")
    assert(status.workloadType === "DBT")
    assert(status.triggerType === "SCHEDULE")
    assert(status.runId === first.id)
    assert(status.engineIdentity.nonEmpty)
    assert(!status.toString.toLowerCase.contains("token"))

    interceptNotebook(NotebookErrorCode.VERSION_CONFLICT) {
      manager.dbt.submitScheduledJob(
        alice,
        secondJob.id,
        Some("scheduled-occurrence-1"),
        runner)
    }
    val distinct = manager.dbt.submitScheduledJob(
      alice,
      firstJob.id,
      Some("scheduled-occurrence-2"),
      runner)
    assert(distinct.id !== first.id)
    assert(distinct.attemptId !== first.attemptId)
    assert(distinct.computeAllocationId !== first.computeAllocationId)
    assert(submissions.get() === 2)
    Seq(first, distinct).foreach { run =>
      manager.computeBindings.revokeAllocation(run.computeAllocationId.get)
      manager.computeAllocations.requestRelease(run.computeAllocationId.get, "test completed")
      manager.computeAllocations.completeRelease(run.computeAllocationId.get)
    }
  }

  test("two managed DBT runs on one profile receive different job allocations") {
    profile("alice", "alice-parallel", "2g")
    val workspace = manager.dbt.createWorkspace(alice, "parallel", "project-1", "alice-parallel")
    val firstJob = manager.dbt.createJob(
      alice,
      workspace.id,
      "first",
      "RUN_PROJECT",
      None,
      None)
    val secondJob = manager.dbt.createJob(
      alice,
      workspace.id,
      "second",
      "RUN_PROJECT",
      None,
      None)
    var submissions = 0
    val runner = new DbtRunner {
      override def submit(request: DbtRunRequest): DbtRunnerSubmission = {
        submissions += 1
        DbtRunnerSubmission(s"runner-$submissions")
      }
      override def findSubmission(runId: String): Option[DbtRunnerSubmission] = None
      override def status(runnerRunId: String): DbtRunnerStatus =
        DbtRunnerStatus(DbtRunState.RUNNING)
      override def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage =
        DbtRunnerLogPage("", offset, endOfStream = false)
      override def cancel(runnerRunId: String): Unit = ()
      override def cleanup(runId: String): Unit = ()
    }

    val first = manager.dbt.submitJob(alice, firstJob.id, runner)
    val second = manager.dbt.submitJob(alice, secondJob.id, runner)

    assert(first.computeAllocationId.nonEmpty)
    assert(second.computeAllocationId.nonEmpty)
    assert(first.computeAllocationId !== second.computeAllocationId)
    Seq(first, second).foreach { run =>
      manager.computeBindings.revokeAllocation(run.computeAllocationId.get)
      manager.computeAllocations.requestRelease(run.computeAllocationId.get, "test completed")
      manager.computeAllocations.completeRelease(run.computeAllocationId.get)
    }
  }

  test("reconciliation persists DBT logs and protects their byte offset with CAS") {
    val artifact = """{"metadata":{"invocation_id":"dbt-invocation-1"}}"""
    val encodedArtifact = Base64.getEncoder.encodeToString(
      artifact.getBytes(StandardCharsets.UTF_8))
    val nonAsciiLog = Seq(0x0111, 0x00E3, 0x20, 0x63, 0x68, 0x1EA1, 0x79)
      .map(_.toChar).mkString
    val logContent = s"$nonAsciiLog\n" +
      s"KYUUBI_DBT_ARTIFACT_V1 run_results.json 0 1 $encodedArtifact\n"
    profile("alice", "alice-logs", "1g")
    val workspace = manager.dbt.createWorkspace(alice, "project", "project-1", "alice-logs")
    val job = manager.dbt.createJob(alice, workspace.id, "build", "RUN_PROJECT", None, None)
    val runner = new DbtRunner {
      override def submit(request: DbtRunRequest): DbtRunnerSubmission =
        DbtRunnerSubmission("runner-logs")
      override def findSubmission(runId: String): Option[DbtRunnerSubmission] = None
      override def status(runnerRunId: String): DbtRunnerStatus =
        DbtRunnerStatus(DbtRunState.SUCCEEDED, finishedAt = Some(2L))
      override def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage =
        DbtRunnerLogPage(logContent, 10L, endOfStream = true)
      override def cancel(runnerRunId: String): Unit = ()
      override def cleanup(runId: String): Unit = ()
    }
    val store = new JDBCDbtStore(conf)
    val service = new DbtWorkspaceService(
      conf,
      store,
      manager.engineProfiles,
      manager.computeAllocations,
      manager.computeBindings,
      runner)
    try {
      val submitted = service.submitJob(alice, job.id)
      assert(service.listRunsForWorkspace(alice, workspace.id, 50).map(_.id) === Seq(submitted.id))
      assert(service.listRunsForJob(alice, job.id, 50).map(_.id) === Seq(submitted.id))
      interceptNotebook(NotebookErrorCode.DBT_RESOURCE_NOT_FOUND) {
        service.listRunsForWorkspace(bob, workspace.id, 50)
      }
      service.reconcileActiveRuns()

      val completed = service.getRun(alice, submitted.id)
      assert(completed.state === DbtRunState.SUCCEEDED)
      assert(completed.runnerResourcesCleaned)
      assert(completed.runnerCleanupError.isEmpty)
      assert(completed.dbtInvocationId.contains("dbt-invocation-1"))
      assert(store.getArtifact(completed.id, "run_results.json").exists(_.content == artifact))
      assert(service.artifact(alice, completed.id, "run_results.json").content === artifact)
      interceptNotebook(NotebookErrorCode.DBT_RESOURCE_NOT_FOUND) {
        service.artifact(bob, completed.id, "run_results.json")
      }
      assert(manager.computeStore.getAllocation(completed.computeAllocationId.get).exists(
        _.state == ComputeAllocationState.RELEASING))
      assert(manager.computeStore.getBinding(completed.computeBindingId.get).exists(
        _.revokedAt.nonEmpty))
      assert(completed.logNextOffset === logContent.getBytes("UTF-8").length)
      assert(!store.appendLog(submitted, "duplicated", 3L))

      val log = service.logs(alice, submitted.id, 0L, Some(1024))
      assert(log.content === logContent)
      assert(log.endOfStream)
      manager.computeAllocations.completeRelease(completed.computeAllocationId.get)
    } finally {
      store.close()
    }
  }

  test("cancelling a missing runner Job records a clean terminal cancellation") {
    profile("alice", "alice-cancel", "1g")
    val workspace = manager.dbt.createWorkspace(alice, "project", "project-1", "alice-cancel")
    val job = manager.dbt.createJob(alice, workspace.id, "build", "RUN_PROJECT", None, None)
    val runner = new DbtRunner {
      override def submit(request: DbtRunRequest): DbtRunnerSubmission =
        DbtRunnerSubmission("runner-cancelled")
      override def findSubmission(runId: String): Option[DbtRunnerSubmission] = None
      override def status(runnerRunId: String): DbtRunnerStatus =
        DbtRunnerStatus(DbtRunState.FAILED, errorSummary = Some("DBT Kubernetes Job was not found"))
      override def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage =
        DbtRunnerLogPage("", offset, endOfStream = true)
      override def cancel(runnerRunId: String): Unit = ()
      override def cleanup(runId: String): Unit = ()
    }
    val store = new JDBCDbtStore(conf)
    val service = new DbtWorkspaceService(
      conf,
      store,
      manager.engineProfiles,
      manager.computeAllocations,
      manager.computeBindings,
      runner)
    try {
      val submitted = service.submitJob(alice, job.id)
      service.cancel(alice, submitted.id)
      service.reconcileActiveRuns()

      val cancelled = service.getRun(alice, submitted.id)
      assert(cancelled.state === DbtRunState.CANCELLED)
      assert(cancelled.runnerResourcesCleaned)
      assert(cancelled.finishedAt.nonEmpty)
      assert(cancelled.errorSummary.isEmpty)
      assert(manager.computeStore.getAllocation(cancelled.computeAllocationId.get).exists(
        _.state == ComputeAllocationState.RELEASING))
      manager.computeAllocations.completeRelease(cancelled.computeAllocationId.get)
    } finally {
      store.close()
    }
  }

  test("terminal DBT run retries runner resource cleanup independently") {
    profile("alice", "alice-cleanup-retry", "1g")
    val workspace = manager.dbt.createWorkspace(
      alice,
      "cleanup-retry",
      "project-1",
      "alice-cleanup-retry")
    val job = manager.dbt.createJob(alice, workspace.id, "build", "RUN_PROJECT", None, None)
    var cleanupAttempts = 0
    val runner = new DbtRunner {
      override def submit(request: DbtRunRequest): DbtRunnerSubmission =
        DbtRunnerSubmission("runner-cleanup-retry")
      override def findSubmission(runId: String): Option[DbtRunnerSubmission] = None
      override def status(runnerRunId: String): DbtRunnerStatus =
        DbtRunnerStatus(DbtRunState.SUCCEEDED, finishedAt = Some(2L))
      override def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage =
        DbtRunnerLogPage("", offset, endOfStream = true)
      override def cancel(runnerRunId: String): Unit = ()
      override def cleanup(runId: String): Unit = {
        cleanupAttempts += 1
        if (cleanupAttempts == 1) throw new RuntimeException("temporary Kubernetes failure")
      }
    }
    val store = new JDBCDbtStore(conf)
    val service = new DbtWorkspaceService(
      conf,
      store,
      manager.engineProfiles,
      manager.computeAllocations,
      manager.computeBindings,
      runner)
    try {
      val submitted = service.submitJob(alice, job.id)
      service.reconcileActiveRuns()
      val pending = service.getRun(alice, submitted.id)
      assert(pending.state === DbtRunState.SUCCEEDED)
      assert(!pending.runnerResourcesCleaned)
      assert(pending.runnerCleanupError.exists(_.contains("temporary Kubernetes failure")))

      service.reconcileActiveRuns()
      val cleaned = service.getRun(alice, submitted.id)
      assert(cleaned.runnerResourcesCleaned)
      assert(cleaned.runnerCleanupError.isEmpty)
      assert(cleanupAttempts === 2)
      manager.computeAllocations.completeRelease(cleaned.computeAllocationId.get)
    } finally {
      store.close()
    }
  }

  test("managed CLI reserves scheduled compute and returns its binding only once") {
    conf.set(DBT_MANAGED_CLI_ENABLED, true)
    try {
      profile("alice", "alice-local-cli", "2g")
      val workspace = manager.dbt.createWorkspace(
        alice,
        "local-cli",
        "project-local",
        "alice-local-cli")
      val job = manager.dbt.createJob(
        alice,
        workspace.id,
        "scheduled-local",
        "RUN_PROJECT",
        None,
        None)

      val launch = manager.dbt.prepareManagedCli(alice, job.id)
      val run = manager.dbt.getRun(alice, launch.runId)
      val allocation = manager.computeStore.getAllocation(launch.allocationId).get
      val binding = manager.computeStore.getBinding(launch.bindingId).get

      assert(run.executionTransport === DbtExecutionTransport.LOCAL_CLI)
      assert(run.state === DbtRunState.RUNNING)
      assert(run.runnerRunId.isEmpty)
      assert(run.runnerResourcesCleaned)
      assert(allocation.triggerType === ComputeTriggerType.SCHEDULE)
      assert(allocation.mode === ComputeMode.JOB)
      assert(binding.tokenHash !== launch.bindingToken)
      assert(!run.toString.contains(launch.bindingToken))
      assert(launch.commandArguments === Seq("run"))

      manager.dbt.heartbeatManagedCli(alice, run.id)
      val completed = manager.dbt.completeManagedCli(
        alice,
        run.id,
        "SUCCEEDED",
        0,
        None,
        Some("local-invocation"))
      assert(completed.state === DbtRunState.SUCCEEDED)
      assert(completed.dbtInvocationId.contains("local-invocation"))
      assert(manager.computeStore.getBinding(launch.bindingId).exists(_.revokedAt.nonEmpty))
      assert(manager.computeStore.getAllocation(launch.allocationId).exists(
        _.state == ComputeAllocationState.RELEASING))
      val cleanupStatus = manager.dbt.retryCleanup(alice, run.id)
      assert(cleanupStatus.cleanupState === "REQUESTED")
      interceptNotebook(NotebookErrorCode.DBT_RESOURCE_NOT_FOUND) {
        manager.dbt.retryCleanup(bob, run.id)
      }
      manager.computeAllocations.completeRelease(launch.allocationId)
      assert(manager.dbt.retryCleanup(alice, run.id).cleanupState === "COMPLETED")
    } finally {
      conf.set(DBT_MANAGED_CLI_ENABLED, false)
    }
  }

  test("managed CLI authorization and cancellation never fall back to the Kubernetes runner") {
    conf.set(DBT_MANAGED_CLI_ENABLED, true)
    try {
      profile("alice", "alice-local-cancel", "1g")
      val workspace = manager.dbt.createWorkspace(
        alice,
        "local-cancel",
        "project-local",
        "alice-local-cancel")
      val job = manager.dbt.createJob(
        alice,
        workspace.id,
        "scheduled-local",
        "RUN_MODEL",
        Some("model_a"),
        None)

      interceptNotebook(NotebookErrorCode.DBT_RESOURCE_NOT_FOUND) {
        manager.dbt.prepareManagedCli(bob, job.id)
      }
      val launch = manager.dbt.prepareManagedCli(alice, job.id)
      val cancelled = manager.dbt.cancel(alice, launch.runId)
      assert(cancelled.state === DbtRunState.CANCELLED)
      assert(cancelled.executionTransport === DbtExecutionTransport.LOCAL_CLI)
      assert(manager.computeStore.getBinding(launch.bindingId).exists(_.revokedAt.nonEmpty))
      assert(manager.computeStore.getAllocation(launch.allocationId).exists(
        _.state == ComputeAllocationState.RELEASING))
      manager.computeAllocations.completeRelease(launch.allocationId)
    } finally {
      conf.set(DBT_MANAGED_CLI_ENABLED, false)
    }
  }

  test("managed CLI reconciliation fails a run whose heartbeat allocation was released") {
    conf.set(DBT_MANAGED_CLI_ENABLED, true)
    try {
      profile("alice", "alice-local-orphan", "1g")
      val workspace = manager.dbt.createWorkspace(
        alice,
        "local-orphan",
        "project-local",
        "alice-local-orphan")
      val job = manager.dbt.createJob(
        alice,
        workspace.id,
        "scheduled-local",
        "RUN_PROJECT",
        None,
        None)
      val launch = manager.dbt.prepareManagedCli(alice, job.id)

      manager.computeAllocations.requestRelease(launch.allocationId, "heartbeat expired")
      manager.dbt.reconcileActiveRuns()

      val failed = manager.dbt.getRun(alice, launch.runId)
      assert(failed.state === DbtRunState.FAILED)
      assert(failed.errorSummary.exists(_.contains("heartbeat expired")))
      assert(manager.computeStore.getBinding(launch.bindingId).exists(_.revokedAt.nonEmpty))
      manager.computeAllocations.completeRelease(launch.allocationId)
    } finally {
      conf.set(DBT_MANAGED_CLI_ENABLED, false)
    }
  }
}
