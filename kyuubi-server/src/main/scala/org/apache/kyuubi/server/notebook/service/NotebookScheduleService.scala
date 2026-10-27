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

package org.apache.kyuubi.server.notebook.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.store.NotebookStore

/**
 * Persistence and validation of notebook schedules.
 *
 * Storing a schedule does not calculate cron occurrences. A scheduler or the manual test client
 * dispatches an occurrence through NotebookRunService, which always reads the immutable source
 * pinned here.
 */
class NotebookScheduleService(
    store: NotebookStore,
    permissions: NotebookPermissionService,
    revisions: NotebookRevisionService) {

  def get(notebook: Notebook, principal: NotebookPrincipal): Option[NotebookScheduleView] = {
    permissions.requireRead(notebook, principal)
    store.getSchedule(notebook.id).map(NotebookScheduleView.apply)
  }

  def set(
      notebook: Notebook,
      principal: NotebookPrincipal,
      request: SetScheduleRequest): NotebookScheduleView = {
    permissions.requireOwner(notebook, principal)
    val cron = CronExpression.validate(request.getCronExpression)
    val timezone = validateTimezone(request.getTimezone)
    val failurePolicy = parse(FailurePolicy, request.getFailurePolicy, FailurePolicy.STOP_ON_ERROR)
    val overlapPolicy =
      parse(OverlapPolicy, request.getOverlapPolicy, OverlapPolicy.SKIP_IF_RUNNING)
    val enabled = Option(request.getEnabled).forall(_.booleanValue())
    val now = System.currentTimeMillis()
    val existing = store.getSchedule(notebook.id)
    val expectedVersion = existing.map { current =>
      Option(request.getVersion).map(_.longValue()) match {
        case Some(requested) if requested != current.version =>
          throw NotebookException.versionConflict("the schedule was modified since it was read")
        case _ => current.version
      }
    }
    val refreshSource = existing.isEmpty || existing.exists(_.sourceRevisionId.isEmpty) ||
      Option(request.getRefreshSource).exists(_.booleanValue())
    val pinnedRevision = if (refreshSource) {
      Some(revisions.create(
        notebook,
        store.listCells(notebook.id),
        principal.user,
        Some("source pinned by notebook schedule"),
        protectedRevision = true))
    } else {
      None
    }
    val sourceRevisionId = pinnedRevision.map(_.id).orElse(existing.flatMap(_.sourceRevisionId))
    val sourceRevisionNumber = pinnedRevision.map(_.revisionNumber)
      .orElse(existing.flatMap(_.sourceRevisionNumber))
    val sourceChecksum = pinnedRevision.map(value => checksum(value.documentSnapshot))
      .orElse(existing.flatMap(_.sourceChecksum))
    val schedule = NotebookSchedule(
      id = existing.map(_.id).getOrElse(UUID.randomUUID().toString),
      notebookId = notebook.id,
      cronExpression = cron,
      timezone = timezone,
      enabled = enabled,
      runtimeProfile = Option(request.getRuntimeProfile).map(_.trim).filter(_.nonEmpty),
      failurePolicy = failurePolicy,
      overlapPolicy = overlapPolicy,
      lastRunAt = existing.flatMap(_.lastRunAt),
      nextRunAt = None,
      createdAt = existing.map(_.createdAt).getOrElse(now),
      createdBy = existing.map(_.createdBy).getOrElse(principal.user),
      updatedAt = now,
      updatedBy = principal.user,
      version = existing.map(_.version + 1).getOrElse(1L),
      sourceRevisionId = sourceRevisionId,
      sourceRevisionNumber = sourceRevisionNumber,
      sourceChecksum = sourceChecksum)
    if (!store.upsertSchedule(schedule, expectedVersion)) {
      pinnedRevision.foreach(value => store.deleteRevision(notebook.id, value.revisionNumber))
      throw NotebookException.versionConflict("the schedule was modified concurrently")
    }
    NotebookScheduleView(schedule)
  }

  /** Explicitly deploys the Notebook's current document as the source of future occurrences. */
  def pinCurrent(
      notebook: Notebook,
      principal: NotebookPrincipal,
      expectedVersion: Option[Long]): NotebookScheduleView = {
    permissions.requireOwner(notebook, principal)
    val current = requireModel(notebook.id)
    expectedVersion.foreach { value =>
      if (value != current.version) {
        throw NotebookException.versionConflict("the schedule was modified since it was read")
      }
    }
    val revision = revisions.create(
      notebook,
      store.listCells(notebook.id),
      principal.user,
      Some("source repinned by notebook schedule"),
      protectedRevision = true)
    val updated = current.copy(
      sourceRevisionId = Some(revision.id),
      sourceRevisionNumber = Some(revision.revisionNumber),
      sourceChecksum = Some(checksum(revision.documentSnapshot)),
      updatedAt = System.currentTimeMillis(),
      updatedBy = principal.user,
      version = current.version + 1)
    if (!store.upsertSchedule(updated, Some(current.version))) {
      store.deleteRevision(notebook.id, revision.revisionNumber)
      throw NotebookException.versionConflict("the schedule was modified concurrently")
    }
    NotebookScheduleView(updated)
  }

  def requireModel(notebookId: String): NotebookSchedule =
    store.getSchedule(notebookId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"notebook $notebookId has no schedule")
    }

  def pinnedDocument(schedule: NotebookSchedule): (NotebookRevision, NotebookDocument) = {
    val revisionId = schedule.sourceRevisionId.getOrElse {
      throw NotebookException.invalid(
        "the notebook schedule has no pinned source; pin the current version before running it")
    }
    val revisionNumber = schedule.sourceRevisionNumber.getOrElse {
      throw NotebookException.invalid("the notebook schedule has an incomplete source pin")
    }
    val expectedChecksum = schedule.sourceChecksum.getOrElse {
      throw NotebookException.invalid("the notebook schedule has an incomplete source pin")
    }
    val revision = revisions.load(schedule.notebookId, revisionNumber)
    if (revision.id != revisionId || checksum(revision.documentSnapshot) != expectedChecksum) {
      throw NotebookException.invalid("the notebook schedule source revision failed verification")
    }
    revision -> NotebookJson.readDocument(revision.documentSnapshot)
  }

  def delete(notebook: Notebook, principal: NotebookPrincipal): Unit = {
    permissions.requireOwner(notebook, principal)
    if (!store.deleteSchedule(notebook.id)) {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"notebook ${notebook.id} has no schedule")
    }
  }

  private def validateTimezone(raw: String): String = {
    val value = Option(raw).map(_.trim).filter(_.nonEmpty).getOrElse {
      // Required rather than defaulted: a cron without an explicit zone silently follows the
      // server's, which changes meaning when the server moves.
      throw NotebookException.invalid("timezone must be set explicitly")
    }
    if (!ZoneId.getAvailableZoneIds.contains(value)) {
      throw NotebookException.invalid(s"unknown timezone: $value")
    }
    value
  }

  private def parse[E <: Enumeration](
      enumeration: E,
      raw: String,
      default: E#Value): E#Value =
    Option(raw).map(_.trim).filter(_.nonEmpty) match {
      case None => default
      case Some(value) => enumeration.values.find(_.toString.equalsIgnoreCase(value)).getOrElse {
          throw NotebookException.invalid(
            s"value must be one of ${enumeration.values.mkString(", ")}")
        }
    }

  private def checksum(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xFF}%02x")
      .mkString
}

/**
 * Validation of the 5-field cron dialect (minute, hour, day-of-month, month, day-of-week).
 *
 * Validation is structural only; it rejects a malformed expression at write time so a broken
 * schedule cannot be stored and then fail silently at trigger time.
 */
object CronExpression {

  private val ranges = Seq((0, 59), (0, 23), (1, 31), (1, 12), (0, 7))

  def validate(raw: String): String = {
    val expression = Option(raw).map(_.trim).filter(_.nonEmpty).getOrElse {
      throw NotebookException.invalid("cronExpression must not be empty")
    }
    val fields = expression.split("\\s+")
    if (fields.length != 5) {
      throw NotebookException.invalid(
        "cronExpression must have 5 fields: minute hour day-of-month month day-of-week")
    }
    fields.zip(ranges).foreach { case (field, (min, max)) =>
      field.split(",").foreach(part => validatePart(part, min, max, expression))
    }
    expression
  }

  private def validatePart(part: String, min: Int, max: Int, expression: String): Unit = {
    val (range, step) = part.split("/", 2) match {
      case Array(r) => (r, None)
      case Array(r, s) => (r, Some(s))
      case _ => throw invalid(expression)
    }
    step.foreach { value =>
      if (!value.forall(_.isDigit) || value.toInt <= 0) throw invalid(expression)
    }
    if (range != "*") {
      range.split("-", 2) match {
        case Array(single) => requireInRange(single, min, max, expression)
        case Array(from, to) =>
          requireInRange(from, min, max, expression)
          requireInRange(to, min, max, expression)
        case _ => throw invalid(expression)
      }
    }
  }

  private def requireInRange(value: String, min: Int, max: Int, expression: String): Unit = {
    if (!value.forall(_.isDigit) || value.isEmpty) throw invalid(expression)
    val parsed = value.toInt
    if (parsed < min || parsed > max) throw invalid(expression)
  }

  private def invalid(expression: String): NotebookException =
    NotebookException.invalid(s"cronExpression is not a valid 5-field cron: $expression")
}
