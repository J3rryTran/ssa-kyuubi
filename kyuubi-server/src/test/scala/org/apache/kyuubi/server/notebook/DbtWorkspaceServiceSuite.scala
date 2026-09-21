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

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.dbt._
import org.apache.kyuubi.server.engineprofile.EngineProfilePrincipal
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
    profile("alice", "alice-small", "1g")
    val workspace = manager.dbt.createWorkspace(alice, "project", "project-1", "alice-small")
    val job = manager.dbt.createJob(alice, workspace.id, "build", "RUN_PROJECT", None, None)
    val runner = new DbtRunner {
      override def submit(request: DbtRunRequest): DbtRunnerSubmission =
        DbtRunnerSubmission("runner-1")
      override def findSubmission(runId: String): Option[DbtRunnerSubmission] = None
      override def status(runnerRunId: String): DbtRunnerStatus =
        DbtRunnerStatus(DbtRunState.SUCCEEDED)
      override def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage =
        DbtRunnerLogPage("", offset, endOfStream = true)
      override def cancel(runnerRunId: String): Unit = ()
      override def cleanup(runId: String): Unit = ()
    }

    val run = manager.dbt.submitJob(alice, job.id, runner)
    assert(run.profile.profileId === "alice-small")
    assert(run.profile.sparkConfig === Map("spark.driver.memory" -> "1g"))
    assert(run.effectiveKyuubiUser === "alice")
    assert(run.state === DbtRunState.SUBMITTED)

    profile("alice", "alice-small", "2g")
    assert(manager.dbt.getRun(alice, run.id).profile.sparkConfig ===
      Map("spark.driver.memory" -> "1g"))
  }

  test("disabled runner refuses submission before it creates a run") {
    profile("alice", "alice-small", "1g")
    val workspace = manager.dbt.createWorkspace(alice, "project", "project-1", "alice-small")
    val job = manager.dbt.createJob(alice, workspace.id, "build", "RUN_PROJECT", None, None)

    intercept[DbtRunnerNotConfiguredException] {
      manager.dbt.submitJob(alice, job.id, DisabledDbtRunner)
    }
  }

  test("reconciliation persists DBT logs and protects their byte offset with CAS") {
    val logContent = "\\u0111\\u00e3 ch\\u1ea1y\\n"
    profile("alice", "alice-small", "1g")
    val workspace = manager.dbt.createWorkspace(alice, "project", "project-1", "alice-small")
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
    val service = new DbtWorkspaceService(conf, store, manager.engineProfiles, runner)
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
      assert(completed.logNextOffset === logContent.getBytes("UTF-8").length)
      assert(!store.appendLog(submitted, "duplicated", 3L))

      val log = service.logs(alice, submitted.id, 0L, Some(1024))
      assert(log.content === logContent)
      assert(log.endOfStream)
    } finally {
      store.close()
    }
  }

  test("cancelling a missing runner Job records a clean terminal cancellation") {
    profile("alice", "alice-small", "1g")
    val workspace = manager.dbt.createWorkspace(alice, "project", "project-1", "alice-small")
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
    val service = new DbtWorkspaceService(conf, store, manager.engineProfiles, runner)
    try {
      val submitted = service.submitJob(alice, job.id)
      service.cancel(alice, submitted.id)
      service.reconcileActiveRuns()

      val cancelled = service.getRun(alice, submitted.id)
      assert(cancelled.state === DbtRunState.CANCELLED)
      assert(cancelled.finishedAt.nonEmpty)
      assert(cancelled.errorSummary.isEmpty)
    } finally {
      store.close()
    }
  }
}
