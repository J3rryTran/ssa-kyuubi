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

import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStoreConf._
import org.apache.kyuubi.server.notebook.api.CreateNotebookRequest
import org.apache.kyuubi.server.notebook.service.NotebookPrincipal

/**
 * Reproduces an upgrade rather than a fresh install: the `notebook` table already exists without
 * the `language` column, exactly as a database created by the previous release has it.
 *
 * This is the path a running deployment takes, and the one a fresh-schema test cannot exercise -
 * the schema files are `CREATE TABLE IF NOT EXISTS`, so on an existing table they add nothing and
 * the column has to come from the migration instead.
 */
class NotebookSchemaUpgradeSuite extends KyuubiFunSuite {

  /** The notebook table exactly as the previous release shipped it: no `language`. */
  private val previousReleaseDdl =
    """CREATE TABLE notebook(
      |    id TEXT PRIMARY KEY NOT NULL,
      |    folder_id TEXT,
      |    path TEXT NOT NULL,
      |    path_hash TEXT NOT NULL UNIQUE,
      |    name TEXT NOT NULL,
      |    description TEXT,
      |    owner TEXT NOT NULL,
      |    default_catalog TEXT,
      |    default_schema TEXT,
      |    runtime_profile TEXT,
      |    format_version INTEGER NOT NULL,
      |    created_at INTEGER NOT NULL,
      |    created_by TEXT NOT NULL,
      |    updated_at INTEGER NOT NULL,
      |    updated_by TEXT NOT NULL,
      |    version INTEGER NOT NULL,
      |    deleted INTEGER NOT NULL DEFAULT 0
      |)""".stripMargin

  /** A database left behind by the previous release, with no `language` column. */
  private def previousReleaseDatabase(): String = {
    val databaseFile = Files.createTempDirectory("kyuubi-notebook-upgrade")
      .resolve(s"notebook-${UUID.randomUUID()}.db")
    val url = s"jdbc:sqlite:${databaseFile.toAbsolutePath}"
    val connection = DriverManager.getConnection(url)
    try {
      val statement = connection.createStatement()
      try statement.execute(previousReleaseDdl)
      finally statement.close()
    } finally connection.close()
    url
  }

  private def started(url: String): NotebookManager = {
    val conf = new KyuubiConf(false)
      .set(METADATA_STORE_JDBC_DATABASE_TYPE, "SQLITE")
      .set(METADATA_STORE_JDBC_URL, url)
      .set(METADATA_STORE_JDBC_DATABASE_SCHEMA_INIT, false)
    val manager = new NotebookManager(
      () => throw new IllegalStateException("the backend service must not be used here"),
      () => "test-instance")
    manager.initialize(conf)
    manager.start()
    manager
  }

  private def listNames(manager: NotebookManager, principal: NotebookPrincipal): Seq[String] =
    manager.documents.listNotebooks(
      principal,
      owner = None,
      folderId = None,
      nameContains = None,
      search = None,
      language = None,
      cursor = None,
      limit = Some(200)).items.map(_.name)

  test("a database from the previous release gains the language column and stays usable") {
    val url = previousReleaseDatabase()
    val manager = started(url)

    try {
      val alice = NotebookPrincipal("alice", admin = false)
      val request = new CreateNotebookRequest
      request.setName("upgraded")
      val (created, _) = manager.documents.createNotebook(alice, request)

      // The report that prompted this test was "it is created but never appears in the list",
      // so listing is what has to be asserted, not just that the create returned.
      assert(created.name === "upgraded")
      assert(listNames(manager, alice).contains("upgraded"))
    } finally {
      manager.stop()
    }
  }

  test("restarting on an already-migrated database neither fails nor loses anything") {
    val url = previousReleaseDatabase()
    val alice = NotebookPrincipal("alice", admin = false)

    val first = started(url)
    try {
      val request = new CreateNotebookRequest
      request.setName("survives-restart")
      first.documents.createNotebook(alice, request)
    } finally first.stop()

    // The column now exists, so the migration must recognise that and skip the ALTER rather
    // than run it a second time and fail the whole start-up on a duplicate column.
    val second = started(url)
    try {
      assert(listNames(second, alice).contains("survives-restart"))
    } finally second.stop()
  }
}
