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

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.service.CronExpression

class NotebookScheduleSuite extends NotebookTestBase {

  private def scheduleRequest(
      cron: String = "0 3 * * *",
      timezone: String = "Asia/Ho_Chi_Minh"): SetScheduleRequest = {
    val request = new SetScheduleRequest
    request.setCronExpression(cron)
    request.setTimezone(timezone)
    request
  }

  test("a schedule is stored with explicit defaults") {
    val (notebook, _) = createNotebook(alice, "scheduled")
    val stored = schedules.set(documents.loadNotebook(notebook.id), alice, scheduleRequest())
    assert(stored.cronExpression === "0 3 * * *")
    assert(stored.timezone === "Asia/Ho_Chi_Minh")
    assert(stored.enabled)
    assert(stored.failurePolicy === "STOP_ON_ERROR")
    assert(stored.overlapPolicy === "SKIP_IF_RUNNING")
    assert(schedules.get(documents.loadNotebook(notebook.id), alice).map(_.version).contains(1L))
  }

  test("updating a schedule bumps its version and enforces the expected one") {
    val (notebook, _) = createNotebook(alice, "schedule-versioned")
    schedules.set(documents.loadNotebook(notebook.id), alice, scheduleRequest())
    val second = scheduleRequest(cron = "30 4 * * 1")
    second.setVersion(java.lang.Long.valueOf(1L))
    assert(schedules.set(documents.loadNotebook(notebook.id), alice, second).version === 2L)

    val stale = scheduleRequest(cron = "0 5 * * *")
    stale.setVersion(java.lang.Long.valueOf(1L))
    interceptNotebook(NotebookErrorCode.VERSION_CONFLICT) {
      schedules.set(documents.loadNotebook(notebook.id), alice, stale)
    }
  }

  test("schedule keeps its pinned source until an explicit repin") {
    val (notebook, createdCells) = createNotebook(
      alice,
      "pinned-source",
      cells = Seq(("CODE", "SQL", "select 'source-a'")))
    val first = schedules.set(documents.loadNotebook(notebook.id), alice, scheduleRequest())
    assert(first.sourceRevisionId.nonEmpty)
    assert(first.sourceRevisionNumber.nonEmpty)
    assert(first.sourceChecksum.exists(_.length == 64))
    assert(schedules.pinnedDocument(schedules.requireModel(notebook.id))._2.cells.head.source ===
      "select 'source-a'")

    val update = new UpdateCellRequest
    update.setSource("select 'source-b'")
    update.setVersion(java.lang.Long.valueOf(createdCells.head.version))
    documents.updateCell(alice, notebook.id, createdCells.head.id, update)

    val cronOnly = scheduleRequest(cron = "15 4 * * *")
    cronOnly.setVersion(java.lang.Long.valueOf(first.version))
    val retained = schedules.set(documents.loadNotebook(notebook.id), alice, cronOnly)
    assert(retained.sourceRevisionId === first.sourceRevisionId)
    assert(schedules.pinnedDocument(schedules.requireModel(notebook.id))._2.cells.head.source ===
      "select 'source-a'")

    val repinned = schedules.pinCurrent(
      documents.loadNotebook(notebook.id),
      alice,
      Some(retained.version))
    assert(repinned.sourceRevisionId !== retained.sourceRevisionId)
    assert(schedules.pinnedDocument(schedules.requireModel(notebook.id))._2.cells.head.source ===
      "select 'source-b'")
  }

  test("a revision pinned by a schedule cannot be deleted") {
    val (notebook, _) = createNotebook(
      alice,
      "protected-schedule-source",
      cells = Seq(("CODE", "SQL", "select 1")))
    val schedule = schedules.set(documents.loadNotebook(notebook.id), alice, scheduleRequest())
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      revisions.delete(notebook.id, schedule.sourceRevisionNumber.get)
    }
  }

  test("dispatch source verification rejects an incomplete or tampered pin") {
    val (notebook, _) = createNotebook(
      alice,
      "verified-schedule-source",
      cells = Seq(("CODE", "SQL", "select 1")))
    schedules.set(documents.loadNotebook(notebook.id), alice, scheduleRequest())
    val stored = schedules.requireModel(notebook.id)

    val tampered = stored.copy(sourceChecksum = Some("0" * 64), version = stored.version + 1)
    assert(manager.store.upsertSchedule(tampered, Some(stored.version)))
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      schedules.pinnedDocument(tampered)
    }

    val incomplete = tampered.copy(
      sourceRevisionId = None,
      sourceRevisionNumber = None,
      sourceChecksum = None,
      version = tampered.version + 1)
    assert(manager.store.upsertSchedule(incomplete, Some(tampered.version)))
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      schedules.pinnedDocument(incomplete)
    }
  }

  test("timezone must be explicit and known") {
    val (notebook, _) = createNotebook(alice, "schedule-timezone")
    val missing = new SetScheduleRequest
    missing.setCronExpression("0 3 * * *")
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      schedules.set(documents.loadNotebook(notebook.id), alice, missing)
    }
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      schedules.set(
        documents.loadNotebook(notebook.id),
        alice,
        scheduleRequest(timezone = "Mars/Olympus"))
    }
  }

  test("only the owner sets or removes a schedule") {
    val (notebook, _) = createNotebook(alice, "schedule-owner")
    interceptNotebook(NotebookErrorCode.NOTEBOOK_NOT_FOUND) {
      schedules.set(documents.loadNotebook(notebook.id), bob, scheduleRequest())
    }
  }

  test("valid cron expressions are accepted") {
    Seq(
      "* * * * *",
      "0 3 * * *",
      "*/15 * * * *",
      "0 0 1 1 *",
      "0,30 8-18 * * 1-5").foreach { expression =>
      assert(CronExpression.validate(expression) === expression)
    }
  }

  test("malformed cron expressions are rejected at write time") {
    Seq(
      "",
      "0 3 * *",
      "0 3 * * * *",
      "60 3 * * *",
      "0 24 * * *",
      "0 3 32 * *",
      "0 3 * 13 *",
      "0 3 * * 8",
      "*/0 * * * *",
      "a * * * *").foreach { expression =>
      interceptNotebook(NotebookErrorCode.INVALID_REQUEST)(CronExpression.validate(expression))
    }
  }

  test("deleting a notebook removes its schedule") {
    val (notebook, _) = createNotebook(alice, "schedule-cascade")
    schedules.set(documents.loadNotebook(notebook.id), alice, scheduleRequest())
    documents.deleteNotebook(alice, notebook.id, None)
    val (replacement, _) = createNotebook(alice, "schedule-cascade")
    assert(schedules.get(documents.loadNotebook(replacement.id), alice).isEmpty)
  }
}
