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

import java.util.UUID

import org.apache.kyuubi.server.notebook.api._

class NotebookRunStoreSuite extends NotebookTestBase {

  test("scheduled run request IDs are unique and allocation metadata survives CAS updates") {
    val (notebook, _) = createNotebook(alice, "run-store")
    val session = manager.sessions.create(alice, notebook.id, new CreateSessionRequest)
    val run = NotebookRun(
      id = UUID.randomUUID().toString,
      notebookId = notebook.id,
      notebookSessionId = session.id,
      state = RunState.QUEUED,
      submittedAt = System.currentTimeMillis(),
      startedAt = None,
      finishedAt = None,
      submittedBy = alice.user,
      requestedCellIds = Seq("position:0"),
      currentCellId = None,
      failurePolicy = FailurePolicy.STOP_ON_ERROR,
      version = 1L,
      scheduleId = Some(UUID.randomUUID().toString),
      sourceRevisionId = Some(UUID.randomUUID().toString),
      sourceRevisionNumber = Some(1L),
      sourceChecksum = Some("a" * 64),
      sourceSnapshot = Some("{}"),
      attemptId = Some(UUID.randomUUID().toString),
      clientRequestId = Some("scheduled-occurrence-1"))
    manager.store.createRun(run)

    val updated = run.copy(
      computeAllocationId = Some("allocation-1"),
      version = run.version + 1)
    assert(manager.store.updateRun(updated, run.version))
    assert(manager.store.getRun(run.id).flatMap(_.computeAllocationId).contains("allocation-1"))
    assert(manager.store.findRunByRequestId(alice.user, "scheduled-occurrence-1").exists(
      _.id == run.id))

    val duplicate = run.copy(id = UUID.randomUUID().toString)
    intercept[Exception] {
      manager.store.createRun(duplicate)
    }
    assert(manager.store.findRunByRequestId(alice.user, "scheduled-occurrence-1").exists(
      _.id == run.id))
  }
}
