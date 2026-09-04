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

import java.io.{ByteArrayOutputStream, FileNotFoundException, IOException}
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.kyuubi.{KyuubiException, Logging}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api._

/**
 * Keeps notebook documents on a shared filesystem so that every server replica sees the same
 * ones.
 *
 * The problem this exists for is narrow and worth stating: notebooks, folders, cells and
 * revisions were held in a SQLite file on one pod's disk, so a notebook created on pod A did not
 * exist for pod B. Those four are what moves here.
 *
 * The runtime bookkeeping in the same trait - sessions, runtimes, executions, events, runs - is
 * delegated to a local [[JDBCNotebookStore]] and deliberately stays per-pod. A Kyuubi session
 * handle only means anything on the process that opened it, so copying the row to shared storage
 * would not let another pod use it; the request is routed to the owning pod instead. Keeping it
 * local also keeps the per-poll writes off the shared filesystem.
 *
 * Layout, chosen so an operator can read it with `hdfs dfs -cat`:
 * {{{
 *   <root>/folders/<folder-id>.json
 *   <root>/notebooks/<notebook-id>/meta.json        # notebook + cells + permissions + schedule
 *   <root>/notebooks/<notebook-id>/content.ipynb    # the same cells, human readable
 *   <root>/notebooks/<notebook-id>/revisions/<n>.json
 *   <root>/.trash/...                               # soft-deleted, never hard deleted
 * }}}
 *
 * Everything a notebook owns lives in one `meta.json`. That is what makes "insert a cell and bump
 * the notebook version" a single file write, which the trait requires to be one transaction and a
 * filesystem cannot otherwise provide.
 */
class FileSystemNotebookStore(conf: KyuubiConf) extends NotebookStore with Logging {

  import FileSystemNotebookStore._

  /** Runtime state stays where the process that owns it runs. */
  private val runtime: NotebookStore = new JDBCNotebookStore(conf)

  private val hadoopConf = new Configuration()
  private val root = new Path(conf.get(NOTEBOOK_STORE_FS_ROOT))
  private val fs: FileSystem = root.getFileSystem(hadoopConf)
  private val listCacheTtl = conf.get(NOTEBOOK_STORE_FS_LIST_CACHE_TTL)

  private val notebooksDir = new Path(root, "notebooks")
  private val foldersDir = new Path(root, "folders")
  private val trashDir = new Path(root, ".trash")

  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)

  /** Whole-directory snapshots, so listing does not walk the filesystem on every request. */
  private val notebookCache =
    new AtomicReference[CachedListing[NotebookRecord]](CachedListing.empty)
  private val folderCache = new AtomicReference[CachedListing[FolderRecord]](CachedListing.empty)

  override def initSchema(): Unit = {
    Seq(notebooksDir, foldersDir, trashDir).foreach { dir =>
      if (!fs.exists(dir)) fs.mkdirs(dir)
    }
    runtime.initSchema()
    if (conf.get(NOTEBOOK_STORE_FS_MIGRATE)) migrateFromJdbc()
  }

  override def healthCheck(): Unit = {
    fs.getFileStatus(root)
    runtime.healthCheck()
  }

  override def close(): Unit = {
    try runtime.close()
    finally {
      // The FileSystem instance is shared by the Hadoop cache; closing it here would pull it out
      // from under everything else in this JVM that resolved the same URI.
    }
  }

  // -----------------------------------------------------------------------------------------
  // Reading and writing one file
  // -----------------------------------------------------------------------------------------

  private def readJson[T](path: Path, clazz: Class[T]): Option[T] =
    try {
      val stream = fs.open(path)
      try {
        val buffer = new ByteArrayOutputStream()
        val chunk = new Array[Byte](8192)
        var read = stream.read(chunk)
        while (read > 0) {
          buffer.write(chunk, 0, read)
          read = stream.read(chunk)
        }
        Some(mapper.readValue(new String(buffer.toByteArray, StandardCharsets.UTF_8), clazz))
      } finally stream.close()
    } catch {
      case _: FileNotFoundException => None
      case NonFatal(e) =>
        throw new KyuubiException(s"Could not read the notebook file $path", e)
    }

  /**
   * Writes through a temporary file and a rename.
   *
   * A reader therefore sees either the previous content or the new one, never a half-written
   * file. The destination is removed first because a plain `rename` refuses an existing target.
   */
  private def writeAtomic(path: Path, body: String): Unit = {
    val tmp = new Path(path.getParent, s".${path.getName}.tmp.${UUID.randomUUID()}")
    try {
      val out = fs.create(tmp, true)
      try out.write(body.getBytes(StandardCharsets.UTF_8))
      finally out.close()
      fs.delete(path, false)
      if (!fs.rename(tmp, path)) {
        throw new IOException(s"Could not rename $tmp onto $path")
      }
    } catch {
      case NonFatal(e) =>
        try fs.delete(tmp, false)
        catch { case NonFatal(_) => () }
        throw new KyuubiException(s"Could not write the notebook file $path", e)
    }
  }

  private def notebookDir(id: String): Path = new Path(notebooksDir, id)
  private def notebookMeta(id: String): Path = new Path(notebookDir(id), "meta.json")
  private def notebookIpynb(id: String): Path = new Path(notebookDir(id), "content.ipynb")
  private def revisionDir(id: String): Path = new Path(notebookDir(id), "revisions")
  private def folderFile(id: String): Path = new Path(foldersDir, s"$id.json")

  // -----------------------------------------------------------------------------------------
  // Cached listings
  // -----------------------------------------------------------------------------------------

  private def now(): Long = System.currentTimeMillis()

  private def allNotebooks(): Seq[NotebookRecord] = {
    val cached = notebookCache.get()
    if (cached.freshAt + listCacheTtl > now()) cached.values
    else {
      val loaded = listDirs(notebooksDir).flatMap { dir =>
        readJson(new Path(dir, "meta.json"), classOf[NotebookRecord])
      }
      notebookCache.set(CachedListing(loaded, now()))
      loaded
    }
  }

  private def allFolders(): Seq[FolderRecord] = {
    val cached = folderCache.get()
    if (cached.freshAt + listCacheTtl > now()) cached.values
    else {
      val loaded = listFiles(foldersDir).flatMap(readJson(_, classOf[FolderRecord]))
      folderCache.set(CachedListing(loaded, now()))
      loaded
    }
  }

  /** A pod must see its own writes at once; only another pod's may lag by the TTL. */
  private def invalidate(): Unit = {
    notebookCache.set(CachedListing.empty)
    folderCache.set(CachedListing.empty)
  }

  private def listDirs(dir: Path): Seq[Path] =
    try fs.listStatus(dir).filter(_.isDirectory).map(_.getPath).toSeq
    catch { case _: FileNotFoundException => Seq.empty }

  private def listFiles(dir: Path): Seq[Path] =
    try {
      fs.listStatus(dir).filter(_.isFile).map(_.getPath)
        .filter(_.getName.endsWith(".json")).toSeq
    } catch { case _: FileNotFoundException => Seq.empty }

  private def loadRecord(id: String): Option[NotebookRecord] =
    readJson(notebookMeta(id), classOf[NotebookRecord])

  /**
   * Writes a notebook back only when it is still at the version the caller read.
   *
   * This is the compare-and-set the trait asks for. It narrows the window rather than closing it:
   * two pods that read the same version at the same instant can both pass the check, because a
   * filesystem offers no way to make the read and the rename one step. Every mutation goes
   * through here so there is a single place to harden if that ever has to be closed.
   */
  private def casWrite(record: NotebookRecord, expectedVersion: Long): Boolean = {
    val current = loadRecord(record.notebook.id)
    if (!current.exists(_.notebook.version == expectedVersion)) false
    else {
      persist(record)
      true
    }
  }

  private def persist(record: NotebookRecord): Unit = {
    val dir = notebookDir(record.notebook.id)
    if (!fs.exists(dir)) fs.mkdirs(dir)
    writeAtomic(notebookMeta(record.notebook.id), mapper.writeValueAsString(record))
    writeAtomic(notebookIpynb(record.notebook.id), record.toIpynb)
    invalidate()
  }

  // -----------------------------------------------------------------------------------------
  // Folders
  // -----------------------------------------------------------------------------------------

  override def createFolder(folder: NotebookFolder): Unit = {
    writeAtomic(folderFile(folder.id), mapper.writeValueAsString(FolderRecord(folder)))
    invalidate()
  }

  override def getFolder(id: String): Option[NotebookFolder] =
    readJson(folderFile(id), classOf[FolderRecord]).map(_.toModel).filterNot(_.deleted)

  override def getFolderByPathHash(pathHash: String): Option[NotebookFolder] =
    allFolders().map(_.toModel).find(f => !f.deleted && NotebookPaths.hash(f.path) == pathHash)

  override def listFolders(filter: FolderFilter): Seq[NotebookFolder] =
    allFolders().map(_.toModel)
      .filter(f => !f.deleted && f.owner == filter.owner)
      .filter(f => !filter.parentIdSpecified || f.parentId == filter.parentId)
      .sortBy(_.path)

  override def listFoldersUnder(pathPrefix: String, owner: String): Seq[NotebookFolder] =
    allFolders().map(_.toModel)
      .filter(f => !f.deleted && f.owner == owner && f.path.startsWith(pathPrefix + "/"))
      .sortBy(_.path)

  override def updateFolder(folder: NotebookFolder, expectedVersion: Long): Boolean = {
    val current = readJson(folderFile(folder.id), classOf[FolderRecord]).map(_.toModel)
    if (!current.exists(_.version == expectedVersion)) false
    else {
      createFolder(folder)
      true
    }
  }

  override def deleteFolder(id: String, expectedVersion: Long, tombstone: Tombstone): Boolean = {
    val current = readJson(folderFile(id), classOf[FolderRecord]).map(_.toModel)
    current match {
      case Some(folder) if folder.version == expectedVersion =>
        trashFolder(folder, tombstone)
        true
      case _ => false
    }
  }

  private def trashFolder(folder: NotebookFolder, tombstone: Tombstone): Unit = {
    val buried = folder.copy(
      path = tombstone.path,
      updatedBy = tombstone.updatedBy,
      updatedAt = tombstone.updatedAt,
      deleted = true,
      version = folder.version + 1)
    writeAtomic(
      new Path(trashDir, s"folder-${folder.id}.json"),
      mapper.writeValueAsString(FolderRecord(buried)))
    fs.delete(folderFile(folder.id), false)
    invalidate()
  }

  /**
   * A filesystem has no multi-file transaction, so the rewrite is applied file by file after the
   * folder itself moves. An interruption can therefore leave descendants on their old path; they
   * are still reachable by id, and re-running the same move repairs them.
   */
  override def moveFolderSubtree(
      folder: NotebookFolder,
      expectedVersion: Long,
      folderPathUpdates: Seq[PathUpdate],
      notebookPathUpdates: Seq[PathUpdate]): Boolean = {
    if (!updateFolder(folder, expectedVersion)) false
    else {
      folderPathUpdates.foreach { update =>
        readJson(folderFile(update.id), classOf[FolderRecord]).map(_.toModel).foreach { existing =>
          createFolder(existing.copy(path = update.path, version = existing.version + 1))
        }
      }
      notebookPathUpdates.foreach { update =>
        loadRecord(update.id).foreach { record =>
          persist(record.withNotebook(record.toNotebook.copy(
            path = update.path,
            version = record.notebook.version + 1)))
        }
      }
      invalidate()
      true
    }
  }

  override def deleteFolderSubtree(
      folder: NotebookFolder,
      expectedVersion: Long,
      descendantFolders: Seq[NotebookFolder],
      descendantNotebooks: Seq[Notebook],
      updatedBy: String,
      now: Long): Boolean = {
    val current = readJson(folderFile(folder.id), classOf[FolderRecord]).map(_.toModel)
    if (!current.exists(_.version == expectedVersion)) false
    else {
      descendantNotebooks.foreach { notebook =>
        trashNotebook(
          notebook.id,
          Tombstone(deletedPath(notebook.path, notebook.id), "", updatedBy, now))
      }
      descendantFolders.foreach { descendant =>
        trashFolder(
          descendant,
          Tombstone(
            deletedPath(descendant.path, descendant.id),
            "",
            updatedBy,
            now))
      }
      trashFolder(folder, Tombstone(deletedPath(folder.path, folder.id), "", updatedBy, now))
      true
    }
  }

  // -----------------------------------------------------------------------------------------
  // Notebooks
  // -----------------------------------------------------------------------------------------

  override def createNotebook(notebook: Notebook, cells: Seq[NotebookCell]): Unit =
    persist(NotebookRecord(notebook, cells, Seq.empty, None))

  override def getNotebook(id: String): Option[Notebook] =
    loadRecord(id).map(_.toNotebook).filterNot(_.deleted)

  override def getNotebookByPathHash(pathHash: String): Option[Notebook] =
    allNotebooks().map(_.toNotebook)
      .find(n => !n.deleted && NotebookPaths.hash(n.path) == pathHash)

  override def listNotebooks(filter: NotebookFilter): Seq[Notebook] = {
    val records = allNotebooks()
    val permitted = records.filter { record =>
      val notebook = record.toNotebook
      !notebook.deleted && filter.accessibleTo.forall { user =>
        notebook.owner == user ||
        record.permissions.exists(p => p.principalType == "USER" && p.principalId == user)
      }
    }
    permitted.map(record => (record, record.toNotebook))
      .filter { case (record, n) =>
        filter.owner.forall(_ == n.owner) &&
        filter.folderId.forall(id => n.folderId.contains(id)) &&
        filter.nameContains.forall(t => n.name.toLowerCase.contains(t.toLowerCase)) &&
        filter.language.forall(_.equalsIgnoreCase(n.language.toString)) &&
        filter.afterPath.forall(n.path > _) &&
        filter.search.forall { term =>
          val t = term.toLowerCase
          n.name.toLowerCase.contains(t) ||
          n.description.exists(_.toLowerCase.contains(t)) ||
          record.cells.exists(_.source.toLowerCase.contains(t))
        }
      }
      .map(_._2)
      .sortBy(_.path)
      .take(filter.limit)
  }

  override def listNotebooksUnder(pathPrefix: String, owner: String): Seq[Notebook] =
    allNotebooks().map(_.toNotebook)
      .filter(n => !n.deleted && n.owner == owner && n.path.startsWith(pathPrefix + "/"))
      .sortBy(_.path)

  override def updateNotebook(notebook: Notebook, expectedVersion: Long): Boolean =
    loadRecord(notebook.id).exists { record =>
      casWrite(record.withNotebook(notebook), expectedVersion)
    }

  override def deleteNotebook(id: String, expectedVersion: Long, tombstone: Tombstone): Boolean =
    loadRecord(id).exists { record =>
      if (record.notebook.version != expectedVersion) false
      else {
        trashNotebook(id, tombstone)
        true
      }
    }

  private def trashNotebook(id: String, tombstone: Tombstone): Unit = {
    loadRecord(id).foreach { record =>
      val buried = record.withNotebook(record.toNotebook.copy(
        path = tombstone.path,
        updatedBy = tombstone.updatedBy,
        updatedAt = tombstone.updatedAt,
        deleted = true,
        version = record.notebook.version + 1))
      writeAtomic(
        new Path(trashDir, s"notebook-$id.json"),
        mapper.writeValueAsString(buried))
      fs.delete(notebookDir(id), true)
      invalidate()
    }
  }

  private def deletedPath(path: String, id: String): String = s"$path.deleted.$id"

  // -----------------------------------------------------------------------------------------
  // Cells
  // -----------------------------------------------------------------------------------------

  override def listCells(notebookId: String): Seq[NotebookCell] =
    loadRecord(notebookId).map(_.toCells).getOrElse(Seq.empty).sortBy(_.position)

  override def getCell(notebookId: String, cellId: String): Option[NotebookCell] =
    listCells(notebookId).find(_.id == cellId)

  override def countCells(notebookId: String): Int = listCells(notebookId).size

  override def insertCell(
      cell: NotebookCell,
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean =
    mutateCells(cell.notebookId, notebookVersion, updatedBy, now) { cells =>
      val (before, after) = cells.sortBy(_.position).splitAt(cell.position)
      renumber(before :+ cell) ++ renumber(after, cell.position + 1)
    }

  override def updateCell(
      cell: NotebookCell,
      expectedVersion: Long,
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean = {
    val existing = getCell(cell.notebookId, cell.id)
    if (!existing.exists(_.version == expectedVersion)) false
    else {
      mutateCells(cell.notebookId, notebookVersion, updatedBy, now) { cells =>
        cells.map(c => if (c.id == cell.id) cell else c)
      }
    }
  }

  override def deleteCell(
      notebookId: String,
      cellId: String,
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean =
    mutateCells(notebookId, notebookVersion, updatedBy, now) { cells =>
      renumber(cells.sortBy(_.position).filterNot(_.id == cellId))
    }

  override def reorderCells(
      notebookId: String,
      orderedCellIds: Seq[String],
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean =
    mutateCells(notebookId, notebookVersion, updatedBy, now) { cells =>
      val byId = cells.map(c => c.id -> c).toMap
      renumber(orderedCellIds.flatMap(byId.get))
    }

  override def replaceCells(
      notebookId: String,
      cells: Seq[NotebookCell],
      notebook: Notebook,
      expectedVersion: Long): Boolean =
    loadRecord(notebookId).exists { record =>
      casWrite(record.withNotebook(notebook).withCells(renumber(cells)), expectedVersion)
    }

  private def renumber(cells: Seq[NotebookCell], from: Int = 0): Seq[NotebookCell] =
    cells.zipWithIndex.map { case (cell, index) => cell.copy(position = from + index) }

  /** Cells and the notebook version move together, which one file write makes atomic. */
  private def mutateCells(
      notebookId: String,
      notebookVersion: Long,
      updatedBy: String,
      now: Long)(f: Seq[NotebookCell] => Seq[NotebookCell]): Boolean =
    loadRecord(notebookId).exists { record =>
      val bumped = record.toNotebook.copy(
        updatedAt = now,
        updatedBy = updatedBy,
        version = notebookVersion + 1)
      casWrite(record.withCells(f(record.toCells)).withNotebook(bumped), notebookVersion)
    }

  // -----------------------------------------------------------------------------------------
  // Revisions
  // -----------------------------------------------------------------------------------------

  override def createRevision(revision: NotebookRevision): Unit = {
    val dir = revisionDir(revision.notebookId)
    if (!fs.exists(dir)) fs.mkdirs(dir)
    writeAtomic(
      new Path(dir, s"${revision.revisionNumber}.json"),
      mapper.writeValueAsString(revision))
  }

  override def listRevisions(
      notebookId: String,
      limit: Int,
      afterRevisionNumber: Option[Long]): Seq[NotebookRevision] =
    allRevisions(notebookId)
      .filter(r => afterRevisionNumber.forall(r.revisionNumber < _))
      .sortBy(-_.revisionNumber)
      .take(limit)

  override def getRevision(notebookId: String, revisionNumber: Long): Option[NotebookRevision] =
    readJson(
      new Path(revisionDir(notebookId), s"$revisionNumber.json"),
      classOf[NotebookRevision])

  override def nextRevisionNumber(notebookId: String): Long =
    allRevisions(notebookId).map(_.revisionNumber).reduceOption(_ max _).getOrElse(0L) + 1L

  override def deleteRevision(notebookId: String, revisionNumber: Long): Boolean =
    fs.delete(new Path(revisionDir(notebookId), s"$revisionNumber.json"), false)

  override def trimRevisions(notebookId: String, keep: Int): Int = {
    val unprotected = allRevisions(notebookId)
      .filterNot(_.protectedRevision)
      .sortBy(-_.revisionNumber)
    val doomed = unprotected.drop(keep)
    doomed.foreach(r => deleteRevision(notebookId, r.revisionNumber))
    doomed.size
  }

  private def allRevisions(notebookId: String): Seq[NotebookRevision] =
    listFiles(revisionDir(notebookId)).flatMap(readJson(_, classOf[NotebookRevision]))

  // -----------------------------------------------------------------------------------------
  // Permissions and schedules, both owned by their notebook's file
  // -----------------------------------------------------------------------------------------

  override def listPermissions(notebookId: String): Seq[NotebookPermission] =
    loadRecord(notebookId).map(_.toPermissions).getOrElse(Seq.empty)

  override def replacePermissions(
      notebookId: String,
      permissions: Seq[NotebookPermission]): Unit =
    loadRecord(notebookId).foreach { record =>
      persist(record.withPermissions(permissions))
    }

  override def deletePermissions(notebookId: String): Unit =
    replacePermissions(notebookId, Seq.empty)

  override def getSchedule(notebookId: String): Option[NotebookSchedule] =
    loadRecord(notebookId).flatMap(_.toSchedule)

  override def upsertSchedule(
      schedule: NotebookSchedule,
      expectedVersion: Option[Long]): Boolean =
    loadRecord(schedule.notebookId).exists { record =>
      val current = record.toSchedule
      if (!expectedVersion.forall(v => current.exists(_.version == v))) false
      else {
        persist(record.withSchedule(Some(schedule)))
        true
      }
    }

  override def deleteSchedule(notebookId: String): Boolean =
    loadRecord(notebookId).exists { record =>
      val had = record.schedule.isDefined
      if (had) persist(record.withSchedule(None))
      had
    }

  override def listEnabledSchedules(): Seq[NotebookSchedule] =
    allNotebooks().flatMap(_.toSchedule).filter(_.enabled)

  // -----------------------------------------------------------------------------------------
  // One-time import of what the JDBC store already holds
  // -----------------------------------------------------------------------------------------

  /**
   * Copies notebooks out of the JDBC store the first time this store starts empty.
   *
   * It never deletes from the source, and it does nothing once anything is present here, so a
   * restart or a second replica running it at the same time cannot damage anything.
   */
  private def migrateFromJdbc(): Unit = {
    if (listDirs(notebooksDir).nonEmpty || listFiles(foldersDir).nonEmpty) return
    try {
      val source = new JDBCNotebookStore(conf)
      try {
        val notebooks = source.listNotebooks(NotebookFilter(limit = Int.MaxValue))
        val folders = notebooks.map(_.owner).distinct
          .flatMap(owner => source.listFolders(FolderFilter(owner)))
        if (notebooks.isEmpty && folders.isEmpty) return
        folders.foreach(createFolder)
        notebooks.foreach { notebook =>
          persist(NotebookRecord(
            notebook,
            source.listCells(notebook.id),
            source.listPermissions(notebook.id),
            source.getSchedule(notebook.id)))
          source.listRevisions(notebook.id, Int.MaxValue, None).foreach(createRevision)
        }
        info(s"Imported ${notebooks.size} notebook(s) and ${folders.size} folder(s) " +
          s"from the JDBC store into $root")
      } finally source.close()
    } catch {
      case NonFatal(e) =>
        // A failed import must not stop the server: the shared store is still usable, just empty.
        warn("Could not import notebooks from the JDBC store; starting with an empty store", e)
    }
  }

  // -----------------------------------------------------------------------------------------
  // Runtime side - per pod, see the class comment
  // -----------------------------------------------------------------------------------------

  override def createSession(session: NotebookSession): Unit = runtime.createSession(session)
  override def getSession(id: String): Option[NotebookSession] = runtime.getSession(id)
  override def listSessions(notebookId: String): Seq[NotebookSession] =
    runtime.listSessions(notebookId)
  override def listLiveSessions(): Seq[NotebookSession] = runtime.listLiveSessions()
  override def updateSession(session: NotebookSession, expectedVersion: Long): Boolean =
    runtime.updateSession(session, expectedVersion)

  override def createRuntime(rt: NotebookRuntime): Unit = runtime.createRuntime(rt)
  override def getRuntime(id: String): Option[NotebookRuntime] = runtime.getRuntime(id)
  override def listRuntimes(notebookSessionId: String): Seq[NotebookRuntime] =
    runtime.listRuntimes(notebookSessionId)
  override def updateRuntime(rt: NotebookRuntime, expectedVersion: Long): Boolean =
    runtime.updateRuntime(rt, expectedVersion)

  override def createExecution(execution: CellExecution): Unit = runtime.createExecution(execution)
  override def getExecution(id: String): Option[CellExecution] = runtime.getExecution(id)
  override def findExecutionByRequestId(
      submittedBy: String,
      clientRequestId: String): Option[CellExecution] =
    runtime.findExecutionByRequestId(submittedBy, clientRequestId)
  override def listExecutions(filter: ExecutionFilter): Seq[CellExecution] =
    runtime.listExecutions(filter)
  override def updateExecution(execution: CellExecution, expectedVersion: Long): Boolean =
    runtime.updateExecution(execution, expectedVersion)

  override def appendEvent(event: ExecutionEvent): Unit = runtime.appendEvent(event)
  override def nextEventSequence(executionId: String): Long =
    runtime.nextEventSequence(executionId)
  override def listEvents(
      executionId: String,
      afterSequence: Long,
      limit: Int): Seq[ExecutionEvent] =
    runtime.listEvents(executionId, afterSequence, limit)

  override def createRun(run: NotebookRun): Unit = runtime.createRun(run)
  override def getRun(id: String): Option[NotebookRun] = runtime.getRun(id)
  override def listRuns(notebookId: String, limit: Int): Seq[NotebookRun] =
    runtime.listRuns(notebookId, limit)
  override def updateRun(run: NotebookRun, expectedVersion: Long): Boolean =
    runtime.updateRun(run, expectedVersion)

  // ---------------------------------------------------------------------------------------------
  // Engine profiles are delegated to the embedded JDBC store so that all replicas see the same
  // profiles regardless of which pod handles the request.
  // ---------------------------------------------------------------------------------------------

  override def upsertEngineProfile(profile: EngineProfile): Unit =
    runtime.upsertEngineProfile(profile)

  override def getEngineProfile(subdomain: String): Option[EngineProfile] =
    runtime.getEngineProfile(subdomain)

  override def listEngineProfiles(owner: String): Seq[EngineProfile] =
    runtime.listEngineProfiles(owner)

  override def deleteEngineProfile(subdomain: String, owner: String): Boolean =
    runtime.deleteEngineProfile(subdomain, owner)
}

object FileSystemNotebookStore {

  case class CachedListing[T](values: Seq[T], freshAt: Long)

  object CachedListing {
    def empty[T]: CachedListing[T] = CachedListing(Seq.empty[T], 0L)
  }
}
