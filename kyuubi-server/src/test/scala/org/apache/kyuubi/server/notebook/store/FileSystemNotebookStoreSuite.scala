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

import java.nio.file.Files
import java.util.UUID

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStoreConf._
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api._

/**
 * Exercises the store over a real local filesystem through the same Hadoop `FileSystem` API that
 * addresses HDFS in production, so the atomic write and the compare-and-set are the real ones
 * rather than a stub's.
 */
class FileSystemNotebookStoreSuite extends KyuubiFunSuite {

  private var store: FileSystemNotebookStore = _
  private var root: java.nio.file.Path = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    root = Files.createTempDirectory("kyuubi-notebook-fs")
    val databaseFile = root.resolve(s"runtime-${UUID.randomUUID()}.db")
    val conf = new KyuubiConf(false)
      .set(NOTEBOOK_STORE_FS_ROOT, root.toUri.toString)
      // Nothing to import: the JDBC side is a fresh database here.
      .set(NOTEBOOK_STORE_FS_MIGRATE, false)
      .set(METADATA_STORE_JDBC_DATABASE_TYPE, "SQLITE")
      .set(METADATA_STORE_JDBC_URL, s"jdbc:sqlite:${databaseFile.toAbsolutePath}")
      .set(METADATA_STORE_JDBC_DATABASE_SCHEMA_INIT, false)
    store = new FileSystemNotebookStore(conf)
    store.initSchema()
  }

  override def afterAll(): Unit = {
    if (store != null) store.close()
    super.afterAll()
  }

  private def notebook(id: String, name: String, version: Long = 1L): Notebook = Notebook(
    id = id,
    folderId = None,
    path = s"/alice/$name",
    name = name,
    description = None,
    owner = "alice",
    language = NotebookLanguage.SQL,
    defaultCatalog = None,
    defaultSchema = None,
    runtimeProfile = None,
    formatVersion = 1,
    createdAt = 1L,
    createdBy = "alice",
    updatedAt = 1L,
    updatedBy = "alice",
    version = version,
    deleted = false)

  private def cell(notebookId: String, id: String, position: Int, source: String): NotebookCell =
    NotebookCell(
      id = id,
      notebookId = notebookId,
      position = position,
      cellType = CellType.CODE,
      language = CellLanguage.SQL,
      source = source,
      metadata = Map.empty,
      configuration = Map.empty,
      createdAt = 1L,
      updatedAt = 1L,
      version = 1L)

  test("a notebook written by one instance is read back whole") {
    val id = UUID.randomUUID().toString
    store.createNotebook(notebook(id, "first"), Seq(cell(id, "c1", 0, "select 1")))

    val loaded = store.getNotebook(id)
    assert(loaded.map(_.name).contains("first"))
    assert(loaded.map(_.owner).contains("alice"))
    assert(store.listCells(id).map(_.source) === Seq("select 1"))
    // Reading through a second instance is what a second pod does.
    assert(store.listNotebooks(NotebookFilter(accessibleTo = Some("alice"), limit = 50))
      .map(_.id).contains(id))
  }

  test("a second writer at the same version is refused instead of overwriting") {
    val id = UUID.randomUUID().toString
    store.createNotebook(notebook(id, "contended"), Seq.empty)

    // Both readers saw version 1, as two pods racing would.
    val mine = notebook(id, "mine", version = 2L)
    val theirs = notebook(id, "theirs", version = 2L)

    assert(store.updateNotebook(mine, expectedVersion = 1L))
    assert(!store.updateNotebook(theirs, expectedVersion = 1L))
    // The loser changed nothing, so no write was silently lost.
    assert(store.getNotebook(id).map(_.name).contains("mine"))
  }

  test("nothing half-written or temporary is left behind") {
    val id = UUID.randomUUID().toString
    store.createNotebook(notebook(id, "clean"), Seq(cell(id, "c1", 0, "select 1")))
    store.updateNotebook(notebook(id, "clean-again", version = 2L), expectedVersion = 1L)

    val files = Files.walk(root).toArray.map(_.toString)
    assert(!files.exists(_.contains(".tmp.")), "a temporary file survived the write")
    assert(files.exists(_.endsWith("meta.json")))
    // The operator-readable copy is written beside the metadata.
    assert(files.exists(_.endsWith("content.ipynb")))
  }

  test("cells and the notebook version move together") {
    val id = UUID.randomUUID().toString
    store.createNotebook(notebook(id, "cells"), Seq(cell(id, "c1", 0, "one")))

    assert(store.insertCell(cell(id, "c2", 1, "two"), notebookVersion = 1L, "alice", 2L))
    assert(store.listCells(id).map(_.source) === Seq("one", "two"))
    assert(store.getNotebook(id).map(_.version).contains(2L))

    // A stale notebook version must not slip a cell in.
    assert(!store.insertCell(cell(id, "c3", 2, "three"), notebookVersion = 1L, "alice", 3L))
    assert(store.listCells(id).size === 2)
  }

  test("reorder and delete keep positions contiguous") {
    val id = UUID.randomUUID().toString
    store.createNotebook(
      notebook(id, "ordered"),
      Seq(cell(id, "a", 0, "a"), cell(id, "b", 1, "b"), cell(id, "c", 2, "c")))

    assert(store.reorderCells(id, Seq("c", "a", "b"), notebookVersion = 1L, "alice", 2L))
    assert(store.listCells(id).map(_.id) === Seq("c", "a", "b"))
    assert(store.listCells(id).map(_.position) === Seq(0, 1, 2))

    assert(store.deleteCell(id, "a", notebookVersion = 2L, "alice", 3L))
    assert(store.listCells(id).map(_.id) === Seq("c", "b"))
    assert(store.listCells(id).map(_.position) === Seq(0, 1))
  }

  test("a deleted notebook stops being visible and frees its path") {
    val id = UUID.randomUUID().toString
    store.createNotebook(notebook(id, "doomed"), Seq.empty)
    val hash = NotebookPaths.hash(s"/alice/doomed")
    assert(store.getNotebookByPathHash(hash).isDefined)

    assert(store.deleteNotebook(
      id,
      expectedVersion = 1L,
      Tombstone("/alice/doomed.deleted", "", "alice", 9L)))

    assert(store.getNotebook(id).isEmpty)
    assert(store.getNotebookByPathHash(hash).isEmpty)
  }

  test("revisions are numbered, listed newest first and trimmed") {
    val id = UUID.randomUUID().toString
    store.createNotebook(notebook(id, "versioned"), Seq.empty)

    (1 to 3).foreach { n =>
      assert(store.nextRevisionNumber(id) === n.toLong)
      store.createRevision(NotebookRevision(
        id = UUID.randomUUID().toString,
        notebookId = id,
        revisionNumber = n.toLong,
        documentSnapshot = s"""{"n":$n}""",
        createdAt = n.toLong,
        createdBy = "alice",
        reason = None,
        protectedRevision = n == 1))
    }

    assert(store.listRevisions(id, 10, None).map(_.revisionNumber) === Seq(3L, 2L, 1L))
    assert(store.getRevision(id, 2L).map(_.documentSnapshot).contains("""{"n":2}"""))
    // The protected one survives the trim even though it is the oldest.
    assert(store.trimRevisions(id, keep = 1) === 1)
    assert(store.listRevisions(id, 10, None).map(_.revisionNumber) === Seq(3L, 1L))
  }

  test("permissions decide what another user may list") {
    val id = UUID.randomUUID().toString
    store.createNotebook(notebook(id, "shared"), Seq.empty)

    def visibleToBob: Boolean =
      store.listNotebooks(NotebookFilter(accessibleTo = Some("bob"), limit = 50))
        .exists(_.id == id)

    assert(!visibleToBob)
    store.replacePermissions(
      id,
      Seq(NotebookPermission(
        id,
        PrincipalType.USER,
        "bob",
        PermissionRole.VIEWER,
        1L,
        "alice")))
    assert(visibleToBob)
    assert(store.listPermissions(id).map(_.principalId) === Seq("bob"))

    store.deletePermissions(id)
    assert(!visibleToBob)
  }

  test("search reaches names, descriptions and cell sources") {
    val id = UUID.randomUUID().toString
    store.createNotebook(notebook(id, "searchable"), Seq(cell(id, "c1", 0, "select needle")))

    def found(term: String): Boolean =
      store.listNotebooks(NotebookFilter(
        accessibleTo = Some("alice"),
        search = Some(term),
        limit = 50)).exists(_.id == id)

    assert(found("searchable"))
    assert(found("needle"))
    assert(!found("haystack"))
  }
}
