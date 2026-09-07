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

package org.apache.kyuubi.server.notebook.store

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.service.IpynbCodec

/**
 * On-disk shape of what the filesystem store keeps.
 *
 * These mirror the domain models with one difference that matters: every enumeration is carried
 * as a plain string. Jackson has no faithful representation of a Scala `Enumeration` value, and
 * the JDBC store already treats these as text in its columns, so writing them as text here keeps
 * both stores agreeing and keeps the files readable.
 */
case class FolderRecord(
    id: String,
    parentId: Option[String],
    name: String,
    path: String,
    owner: String,
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long,
    deleted: Boolean) {

  def toModel: NotebookFolder = NotebookFolder(
    id,
    parentId,
    name,
    path,
    owner,
    createdAt,
    createdBy,
    updatedAt,
    updatedBy,
    version,
    deleted)
}

object FolderRecord {
  def apply(folder: NotebookFolder): FolderRecord = FolderRecord(
    folder.id,
    folder.parentId,
    folder.name,
    folder.path,
    folder.owner,
    folder.createdAt,
    folder.createdBy,
    folder.updatedAt,
    folder.updatedBy,
    folder.version,
    folder.deleted)
}

case class NotebookMeta(
    id: String,
    folderId: Option[String],
    path: String,
    name: String,
    description: Option[String],
    owner: String,
    language: String,
    defaultCatalog: Option[String],
    defaultSchema: Option[String],
    runtimeProfile: Option[String],
    formatVersion: Int,
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long,
    deleted: Boolean)

case class CellRecord(
    id: String,
    notebookId: String,
    position: Int,
    cellType: String,
    language: String,
    source: String,
    metadata: Map[String, String],
    configuration: Map[String, String],
    createdAt: Long,
    updatedAt: Long,
    version: Long)

case class PermissionRecord(
    notebookId: String,
    principalType: String,
    principalId: String,
    role: String,
    createdAt: Long,
    createdBy: String)

case class ScheduleRecord(
    id: String,
    notebookId: String,
    cronExpression: String,
    timezone: String,
    enabled: Boolean,
    runtimeProfile: Option[String],
    failurePolicy: String,
    overlapPolicy: String,
    lastRunAt: Option[Long],
    nextRunAt: Option[Long],
    createdAt: Long,
    createdBy: String,
    updatedAt: Long,
    updatedBy: String,
    version: Long)

/**
 * Everything one notebook owns, held in a single file.
 *
 * Cells, permissions and the schedule live here rather than beside the notebook because the trait
 * requires several of their updates to happen together with a notebook version bump - inserting a
 * cell, for instance. One file means one write, and one write on this filesystem is a rename,
 * which is the only atomicity available.
 */
case class NotebookRecord(
    notebook: NotebookMeta,
    cells: Seq[CellRecord],
    permissions: Seq[PermissionRecord],
    schedule: Option[ScheduleRecord]) {

  def toNotebook: Notebook = Notebook(
    notebook.id,
    notebook.folderId,
    notebook.path,
    notebook.name,
    notebook.description,
    notebook.owner,
    NotebookLanguage.parse(notebook.language).getOrElse(NotebookLanguage.default),
    notebook.defaultCatalog,
    notebook.defaultSchema,
    notebook.runtimeProfile,
    notebook.formatVersion,
    notebook.createdAt,
    notebook.createdBy,
    notebook.updatedAt,
    notebook.updatedBy,
    notebook.version,
    notebook.deleted)

  def toCells: Seq[NotebookCell] = cells.map { cell =>
    NotebookCell(
      cell.id,
      cell.notebookId,
      cell.position,
      CellType.withName(cell.cellType),
      CellLanguage.withName(cell.language),
      cell.source,
      cell.metadata,
      cell.configuration,
      cell.createdAt,
      cell.updatedAt,
      cell.version)
  }

  def toPermissions: Seq[NotebookPermission] = permissions.map { permission =>
    NotebookPermission(
      permission.notebookId,
      PrincipalType.withName(permission.principalType),
      permission.principalId,
      PermissionRole.withName(permission.role),
      permission.createdAt,
      permission.createdBy)
  }

  def toSchedule: Option[NotebookSchedule] = schedule.map { s =>
    NotebookSchedule(
      s.id,
      s.notebookId,
      s.cronExpression,
      s.timezone,
      s.enabled,
      s.runtimeProfile,
      FailurePolicy.withName(s.failurePolicy),
      OverlapPolicy.withName(s.overlapPolicy),
      s.lastRunAt,
      s.nextRunAt,
      s.createdAt,
      s.createdBy,
      s.updatedAt,
      s.updatedBy,
      s.version)
  }

  /** The same cells as a notebook file an operator can open, written beside the metadata. */
  def toIpynb: String = IpynbCodec.toIpynb(NotebookDocument(
    formatVersion = NotebookDocument.CURRENT_FORMAT_VERSION,
    name = notebook.name,
    description = notebook.description,
    language = Some(notebook.language),
    defaultCatalog = notebook.defaultCatalog,
    defaultSchema = notebook.defaultSchema,
    runtimeProfile = notebook.runtimeProfile,
    cells = cells.sortBy(_.position).map { cell =>
      NotebookDocumentCell(
        cell.position,
        cell.cellType,
        cell.language,
        cell.source,
        cell.metadata,
        cell.configuration)
    }))

  def withNotebook(updated: Notebook): NotebookRecord =
    copy(notebook = NotebookRecord.metaOf(updated))

  def withCells(updated: Seq[NotebookCell]): NotebookRecord =
    copy(cells = updated.map(NotebookRecord.cellOf))

  def withPermissions(updated: Seq[NotebookPermission]): NotebookRecord =
    copy(permissions = updated.map(NotebookRecord.permissionOf))

  def withSchedule(updated: Option[NotebookSchedule]): NotebookRecord =
    copy(schedule = updated.map(NotebookRecord.scheduleOf))
}

object NotebookRecord {

  def apply(
      notebook: Notebook,
      cells: Seq[NotebookCell],
      permissions: Seq[NotebookPermission],
      schedule: Option[NotebookSchedule]): NotebookRecord = NotebookRecord(
    metaOf(notebook),
    cells.map(cellOf),
    permissions.map(permissionOf),
    schedule.map(scheduleOf))

  def metaOf(notebook: Notebook): NotebookMeta = NotebookMeta(
    notebook.id,
    notebook.folderId,
    notebook.path,
    notebook.name,
    notebook.description,
    notebook.owner,
    notebook.language.toString,
    notebook.defaultCatalog,
    notebook.defaultSchema,
    notebook.runtimeProfile,
    notebook.formatVersion,
    notebook.createdAt,
    notebook.createdBy,
    notebook.updatedAt,
    notebook.updatedBy,
    notebook.version,
    notebook.deleted)

  def cellOf(cell: NotebookCell): CellRecord = CellRecord(
    cell.id,
    cell.notebookId,
    cell.position,
    cell.cellType.toString,
    cell.language.toString,
    cell.source,
    cell.metadata,
    cell.configuration,
    cell.createdAt,
    cell.updatedAt,
    cell.version)

  def permissionOf(permission: NotebookPermission): PermissionRecord = PermissionRecord(
    permission.notebookId,
    permission.principalType.toString,
    permission.principalId,
    permission.role.toString,
    permission.createdAt,
    permission.createdBy)

  def scheduleOf(schedule: NotebookSchedule): ScheduleRecord = ScheduleRecord(
    schedule.id,
    schedule.notebookId,
    schedule.cronExpression,
    schedule.timezone,
    schedule.enabled,
    schedule.runtimeProfile,
    schedule.failurePolicy.toString,
    schedule.overlapPolicy.toString,
    schedule.lastRunAt,
    schedule.nextRunAt,
    schedule.createdAt,
    schedule.createdBy,
    schedule.updatedAt,
    schedule.updatedBy,
    schedule.version)
}
