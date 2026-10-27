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

import scala.concurrent.duration._

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.compute.{ComputeMode, ComputeTriggerType, ComputeWorkloadType}
import org.apache.kyuubi.server.notebook.NotebookConf.{NOTEBOOK_JOB_POLL_INTERVAL, NOTEBOOK_JOB_RUNNER_ENABLED}
import org.apache.kyuubi.server.notebook.api._

class NotebookRunServiceSuite extends NotebookTestBase {

  override protected def configureTest(testConf: KyuubiConf): Unit = {
    testConf
      .set(NOTEBOOK_JOB_RUNNER_ENABLED, true)
      .set(NOTEBOOK_JOB_POLL_INTERVAL, 10L)
  }

  test("scheduled dispatch claims pinned source and dedicated JOB compute idempotently") {
    val (notebook, createdCells) = createNotebook(
      alice,
      "scheduled-run",
      cells = Seq(("CODE", "SQL", "select 'source-a'")))
    val scheduleRequest = new SetScheduleRequest
    scheduleRequest.setCronExpression("0 3 * * *")
    scheduleRequest.setTimezone("Asia/Ho_Chi_Minh")
    schedules.set(documents.loadNotebook(notebook.id), alice, scheduleRequest)

    val update = new UpdateCellRequest
    update.setSource("select 'source-b'")
    update.setVersion(java.lang.Long.valueOf(createdCells.head.version))
    documents.updateCell(alice, notebook.id, createdCells.head.id, update)

    val dispatch = new DispatchScheduledNotebookRequest
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.runs.dispatch(alice, notebook.id, dispatch)
    }
    dispatch.setClientRequestId("scheduled-notebook-occurrence-1")
    val run = manager.runs.dispatch(alice, notebook.id, dispatch)
    val replay = manager.runs.dispatch(alice, notebook.id, dispatch)

    assert(replay.id === run.id)
    assert(!manager.sessions.list(alice, notebook.id).exists(_.id == run.notebookSessionId))
    assert(run.sourceRevisionId.nonEmpty)
    assert(run.sourceChecksum.exists(_.length == 64))
    assert(run.sourceSnapshot.exists(_.contains("source-a")))
    assert(!run.sourceSnapshot.exists(_.contains("source-b")))

    val allocation = manager.computeStore.getAllocation(run.computeAllocationId.get).get
    assert(allocation.mode === ComputeMode.JOB)
    assert(allocation.workloadType === ComputeWorkloadType.NOTEBOOK)
    assert(allocation.triggerType === ComputeTriggerType.SCHEDULE)
    assert(allocation.runId.contains(run.id))
    assert(allocation.subdomain.startsWith("job-"))
    val status = manager.runs.status(alice, run.id)
    assert(status.mode === "JOB")
    assert(status.workloadType === "NOTEBOOK")
    assert(status.triggerType === "SCHEDULE")
    assert(status.runId === run.id)
    assert(status.attemptId === run.attemptId)
    assert(status.allocationId === run.computeAllocationId)
    assert(status.engineIdentity.contains(allocation.subdomain))
    assert(!status.toString.toLowerCase.contains("token"))
    interceptNotebook(NotebookErrorCode.NOTEBOOK_NOT_FOUND) {
      manager.runs.status(bob, run.id)
    }

    eventually(timeout(10.seconds), interval(20.millis)) {
      val terminal = manager.runs.get(alice, run.id)
      assert(RunState.terminal.contains(terminal.state))
      assert(terminal.computeAllocationId.contains(allocation.id))
      assert(Set("REQUESTED", "COMPLETED").contains(
        manager.runs.status(alice, run.id).cleanupState))
    }
    val cleanupStatus = manager.runs.retryCleanup(alice, run.id)
    assert(Set("REQUESTED", "COMPLETED").contains(cleanupStatus.cleanupState))
    interceptNotebook(NotebookErrorCode.NOTEBOOK_NOT_FOUND) {
      manager.runs.retryCleanup(bob, run.id)
    }
    val firstCancel = manager.runs.cancel(alice, run.id)
    val repeatedCancel = manager.runs.cancel(alice, run.id)
    assert(repeatedCancel.id === firstCancel.id)
    assert(repeatedCancel.state === firstCancel.state)
  }
}
