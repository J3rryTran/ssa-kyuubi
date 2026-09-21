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

package org.apache.kyuubi.server.dbt

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}
import java.util.stream.Collectors

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import org.apache.kyuubi.{KyuubiException, Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot
import org.apache.kyuubi.server.metadata.jdbc.DatabaseType
import org.apache.kyuubi.server.metadata.jdbc.DatabaseType._
import org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStoreConf._
import org.apache.kyuubi.util.JdbcUtils
import org.apache.kyuubi.util.reflect.ReflectUtils

/** DBT metadata shares Kyuubi's configured JDBC metadata database. */
class JDBCDbtStore(conf: KyuubiConf) extends DbtStore with Logging {
  private val dbType = DatabaseType.withName(conf.get(METADATA_STORE_JDBC_DATABASE_TYPE))
  private val driverClass = {
    val configured = conf.get(METADATA_STORE_JDBC_DRIVER)
    dbType match {
      case SQLITE => configured.getOrElse("org.sqlite.JDBC")
      case MYSQL => configured.getOrElse {
          if (ReflectUtils.isClassLoadable("com.mysql.cj.jdbc.Driver")) {
            "com.mysql.cj.jdbc.Driver"
          } else "com.mysql.jdbc.Driver"
        }
      case POSTGRESQL => configured.getOrElse("org.postgresql.Driver")
      case CUSTOM => configured.getOrElse(throw new IllegalArgumentException("No jdbc driver"))
    }
  }
  private val hikariConfig = {
    val value = new HikariConfig(getMetadataStoreJDBCDataSourceProperties(conf))
    value.setDriverClassName(driverClass)
    value.setJdbcUrl(getMetadataStoreJdbcUrl(conf))
    value.setUsername(conf.get(METADATA_STORE_JDBC_USER))
    value.setPassword(conf.get(METADATA_STORE_JDBC_PASSWORD))
    value.setPoolName("jdbc-dbt-store-pool")
    value
  }
  implicit private val dataSource: HikariDataSource = new HikariDataSource(hikariConfig)
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)

  override def initSchema(): Unit = {
    val resource = s"sql/notebook/${dbType.toString.toLowerCase}/dbt-schema-1.0.0." +
      s"${dbType.toString.toLowerCase}.sql"
    val stream = Utils.getContextOrKyuubiClassLoader.getResourceAsStream(resource)
    if (stream == null) throw new KyuubiException(s"DBT schema resource not found: $resource")
    val ddl =
      try {
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .lines().collect(Collectors.joining("\n"))
      } finally stream.close()
    val statements = ddl.split("\\n").filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty)
    // Existing Phase C/D databases do not have the Phase E columns. Add them before
    // evaluating the new indexes below, which reference state_updated_at.
    if (hasTable("dbt_job_run")) {
      addMissingColumns()
    }
    JdbcUtils.withConnection { connection =>
      statements.foreach { statement =>
        JdbcUtils.withCloseable(connection.prepareStatement(statement))(_.execute())
      }
    }
    addMissingColumns()
  }

  override def close(): Unit = dataSource.close()

  private def workspaceMapper(rs: ResultSet): DbtWorkspace = DbtWorkspace(
    rs.getString("id"),
    rs.getString("owner"),
    rs.getString("name"),
    rs.getString("project_ref"),
    rs.getString("engine_profile_id"),
    rs.getLong("created_at"),
    rs.getLong("updated_at"),
    rs.getLong("version"))

  override def createWorkspace(workspace: DbtWorkspace): Unit =
    JdbcUtils.executeUpdate(
      """INSERT INTO dbt_workspace(id, owner, name, project_ref,
        | engine_profile_id, created_at, updated_at, version)
        | VALUES(?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin) {
      s =>
        s.setString(1, workspace.id); s.setString(2, workspace.owner);
        s.setString(3, workspace.name)
        s.setString(4, workspace.projectRef); s.setString(5, workspace.selectedEngineProfileId)
        s.setLong(6, workspace.createdAt); s.setLong(7, workspace.updatedAt);
        s.setLong(8, workspace.version)
    }

  override def getWorkspace(id: String): Option[DbtWorkspace] = queryOne(
    "SELECT * FROM dbt_workspace WHERE id = ?",
    _.setString(1, id))(workspaceMapper)

  override def listWorkspaces(owner: String): Seq[DbtWorkspace] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM dbt_workspace WHERE owner = ? ORDER BY updated_at DESC")(_.setString(
      1,
      owner))(
      workspaceMapper)

  override def updateWorkspace(workspace: DbtWorkspace, expectedVersion: Long): Boolean =
    JdbcUtils.executeUpdate(
      """UPDATE dbt_workspace SET name = ?, project_ref = ?,
        | engine_profile_id = ?, updated_at = ?, version = ?
        | WHERE id = ? AND version = ?""".stripMargin) {
      s =>
        s.setString(1, workspace.name); s.setString(2, workspace.projectRef)
        s.setString(3, workspace.selectedEngineProfileId); s.setLong(4, workspace.updatedAt)
        s.setLong(5, workspace.version); s.setString(6, workspace.id); s.setLong(7, expectedVersion)
    } == 1

  override def deleteWorkspace(id: String, owner: String, expectedVersion: Long): Boolean =
    JdbcUtils.executeUpdate(
      "DELETE FROM dbt_workspace WHERE id = ? AND owner = ? AND version = ?") { s =>
      s.setString(1, id); s.setString(2, owner); s.setLong(3, expectedVersion)
    } == 1

  private def jobMapper(rs: ResultSet): DbtJob = DbtJob(
    rs.getString("id"),
    rs.getString("workspace_id"),
    rs.getString("owner"),
    rs.getString("name"),
    DbtAction.withName(rs.getString("action")),
    Option(rs.getString("selector")),
    Option(rs.getString("engine_profile_id_override")),
    rs.getLong("created_at"),
    rs.getLong("updated_at"),
    rs.getLong("version"))

  override def createJob(job: DbtJob): Unit =
    JdbcUtils.executeUpdate(
      """INSERT INTO dbt_job(id, workspace_id, owner, name, action, selector,
        | engine_profile_id_override, created_at, updated_at, version)
        | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin) {
      s =>
        s.setString(1, job.id); s.setString(2, job.workspaceId); s.setString(3, job.owner)
        s.setString(4, job.name); s.setString(5, job.action.toString);
        s.setString(6, job.selector.orNull)
        s.setString(7, job.engineProfileIdOverride.orNull); s.setLong(8, job.createdAt)
        s.setLong(9, job.updatedAt); s.setLong(10, job.version)
    }

  override def getJob(id: String): Option[DbtJob] =
    queryOne("SELECT * FROM dbt_job WHERE id = ?", _.setString(1, id))(jobMapper)

  override def listJobs(workspaceId: String): Seq[DbtJob] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM dbt_job WHERE workspace_id = ? ORDER BY updated_at DESC")(
      _.setString(1, workspaceId))(jobMapper)

  override def updateJob(job: DbtJob, expectedVersion: Long): Boolean =
    JdbcUtils.executeUpdate(
      """UPDATE dbt_job SET name = ?, action = ?, selector = ?,
        | engine_profile_id_override = ?, updated_at = ?, version = ?
        | WHERE id = ? AND version = ?""".stripMargin) {
      s =>
        s.setString(1, job.name); s.setString(2, job.action.toString);
        s.setString(3, job.selector.orNull)
        s.setString(4, job.engineProfileIdOverride.orNull); s.setLong(5, job.updatedAt)
        s.setLong(6, job.version); s.setString(7, job.id); s.setLong(8, expectedVersion)
    } == 1

  override def deleteJob(id: String, owner: String, expectedVersion: Long): Boolean =
    JdbcUtils.executeUpdate("DELETE FROM dbt_job WHERE id = ? AND owner = ? AND version = ?") { s =>
      s.setString(1, id); s.setString(2, owner); s.setLong(3, expectedVersion)
    } == 1

  private def runMapper(rs: ResultSet): DbtJobRun = {
    val config =
      mapper.readValue(rs.getString("profile_config"), classOf[java.util.Map[String, String]])
        .asScala.toMap
    DbtJobRun(
      rs.getString("id"),
      rs.getString("workspace_id"),
      Option(rs.getString("job_id")),
      rs.getString("submitted_by"),
      rs.getString("effective_kyuubi_user"),
      DbtAction.withName(rs.getString("action")),
      Option(rs.getString("selector")),
      rs.getString("project_ref"),
      EngineProfileSnapshot(
        rs.getString("profile_id"),
        rs.getString("profile_owner"),
        rs.getString("profile_subdomain"),
        config,
        rs.getLong("profile_captured_at"),
        Option(rs.getString("profile_notebook_runtime_idle_timeout")),
        Option(rs.getString("profile_engine_idle_timeout")),
        Option(rs.getString("profile_python_environment_revision_id")),
        rs.getLong("profile_revision")),
      DbtRunState.withName(rs.getString("state")),
      Option(rs.getString("runner_run_id")),
      Option(rs.getString("dispatch_token")),
      Option(rs.getString("runner_job_uid")),
      rs.getLong("created_at"),
      nullableLong(rs, "started_at"),
      nullableLong(rs, "finished_at"),
      Option(rs.getString("error_summary")),
      rs.getLong("state_updated_at"),
      rs.getLong("log_next_offset"),
      rs.getBoolean("log_truncated"),
      rs.getLong("version"))
  }

  override def createRun(run: DbtJobRun): Unit = {
    val profile = run.profile
    JdbcUtils.executeUpdate(
      """INSERT INTO dbt_job_run(id, workspace_id, job_id, submitted_by,
        | effective_kyuubi_user, action, selector, project_ref, profile_id,
        | profile_owner, profile_subdomain, profile_config, profile_captured_at,
        | profile_notebook_runtime_idle_timeout, profile_engine_idle_timeout,
        | profile_python_environment_revision_id, profile_revision,
        | state, runner_run_id, dispatch_token, runner_job_uid, created_at, started_at,
        | finished_at, error_summary, state_updated_at, log_next_offset, log_truncated, version)
        | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        | ?, ?)
        |""".stripMargin) {
      s =>
        s.setString(1, run.id); s.setString(2, run.workspaceId); s.setString(3, run.jobId.orNull)
        s.setString(4, run.submittedBy); s.setString(5, run.effectiveKyuubiUser)
        s.setString(6, run.action.toString); s.setString(7, run.selector.orNull);
        s.setString(8, run.projectRef)
        s.setString(9, profile.profileId); s.setString(10, profile.owner);
        s.setString(11, profile.subdomain)
        s.setString(12, mapper.writeValueAsString(profile.sparkConfig));
        s.setLong(13, profile.capturedAt)
        s.setString(14, profile.notebookRuntimeIdleTimeout.orNull)
        s.setString(15, profile.engineIdleTimeout.orNull)
        s.setString(16, profile.pythonEnvironmentRevisionId.orNull)
        s.setLong(17, profile.revision)
        s.setString(18, run.state.toString); s.setString(19, run.runnerRunId.orNull)
        s.setString(20, run.dispatchToken.orNull); s.setString(21, run.runnerJobUid.orNull)
        s.setLong(22, run.createdAt)
        setNullableLong(s, 23, run.startedAt); setNullableLong(s, 24, run.finishedAt)
        s.setString(25, run.errorSummary.orNull); s.setLong(26, run.stateUpdatedAt)
        s.setLong(27, run.logNextOffset); s.setBoolean(28, run.logTruncated)
        s.setLong(29, run.version)
    }
  }

  override def getRun(id: String): Option[DbtJobRun] =
    queryOne("SELECT * FROM dbt_job_run WHERE id = ?", _.setString(1, id))(runMapper)

  override def listRunsForWorkspace(workspaceId: String, limit: Int): Seq[DbtJobRun] =
    listRuns("workspace_id = ?", _.setString(1, workspaceId), limit)

  override def listRunsForJob(jobId: String, limit: Int): Seq[DbtJobRun] =
    listRuns("job_id = ?", _.setString(1, jobId), limit)

  override def updateRun(run: DbtJobRun, expectedStates: Set[DbtRunState.Value]): Boolean = {
    require(expectedStates.nonEmpty, "expected DBT run states must not be empty")
    val statePlaceholders = List.fill(expectedStates.size)("?").mkString(", ")
    JdbcUtils.executeUpdate(
      s"""UPDATE dbt_job_run SET state = ?, runner_run_id = ?, dispatch_token = ?,
         | runner_job_uid = ?, started_at = ?, finished_at = ?, error_summary = ?,
         | state_updated_at = ?, log_next_offset = ?, log_truncated = ?, version = ?
         | WHERE id = ? AND version = ? AND state IN ($statePlaceholders)""".stripMargin) { s =>
      s.setString(1, run.state.toString)
      s.setString(2, run.runnerRunId.orNull)
      s.setString(3, run.dispatchToken.orNull)
      s.setString(4, run.runnerJobUid.orNull)
      setNullableLong(s, 5, run.startedAt)
      setNullableLong(s, 6, run.finishedAt)
      s.setString(7, run.errorSummary.orNull)
      s.setLong(8, run.stateUpdatedAt)
      s.setLong(9, run.logNextOffset)
      s.setBoolean(10, run.logTruncated)
      s.setLong(11, run.version)
      s.setString(12, run.id)
      s.setLong(13, run.version - 1)
      expectedStates.toSeq.sortBy(_.toString).zipWithIndex.foreach { case (state, index) =>
        s.setString(14 + index, state.toString)
      }
    } == 1
  }

  override def claimDispatch(id: String, expectedVersion: Long, token: String, now: Long): Boolean =
    JdbcUtils.executeUpdate(
      """UPDATE dbt_job_run SET state = ?, dispatch_token = ?, state_updated_at = ?, version = ?
        | WHERE id = ? AND version = ? AND state = ?""".stripMargin) { s =>
      s.setString(1, DbtRunState.DISPATCHING.toString)
      s.setString(2, token)
      s.setLong(3, now)
      s.setLong(4, expectedVersion + 1)
      s.setString(5, id)
      s.setLong(6, expectedVersion)
      s.setString(7, DbtRunState.QUEUED.toString)
    } == 1

  override def appendLog(run: DbtJobRun, content: String, now: Long): Boolean = {
    if (content.isEmpty) return true
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    inTransaction { connection =>
      val claimed = update(
        connection,
        """UPDATE dbt_job_run SET log_next_offset = ?, version = ?
          | WHERE id = ? AND version = ? AND log_next_offset = ?""".stripMargin) { s =>
        s.setLong(1, run.logNextOffset + bytes.length)
        s.setLong(2, run.version + 1)
        s.setString(3, run.id)
        s.setLong(4, run.version)
        s.setLong(5, run.logNextOffset)
      } == 1
      claimed && update(
        connection,
        """INSERT INTO dbt_job_run_log(run_id, sequence, start_offset, end_offset, content,
          | created_at) VALUES(?, ?, ?, ?, ?, ?)""".stripMargin) { s =>
        s.setString(1, run.id)
        s.setLong(2, run.logNextOffset)
        s.setLong(3, run.logNextOffset)
        s.setLong(4, run.logNextOffset + bytes.length)
        s.setString(5, content)
        s.setLong(6, now)
      } == 1
    }
  }

  override def readLogs(runId: String, offset: Long, limit: Int): DbtRunnerLogPage = {
    require(offset >= 0, "DBT log offset must not be negative")
    require(limit > 0, "DBT log limit must be positive")
    val chunks = JdbcUtils.executeQueryWithRowMapper(
      """SELECT start_offset, end_offset, content FROM dbt_job_run_log
        | WHERE run_id = ? AND end_offset > ?
        | ORDER BY sequence""".stripMargin) { s =>
      s.setString(1, runId)
      s.setLong(2, offset)
    } { rs =>
      (rs.getLong("start_offset"), rs.getLong("end_offset"), rs.getString("content"))
    }
    val page = new StringBuilder
    var remaining = limit
    var nextOffset = offset
    chunks.foreach { case (start, end, content) =>
      if (remaining > 0 && end > nextOffset) {
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        val skip = math.max(0L, nextOffset - start).toInt
        val take = math.min(remaining, bytes.length - skip)
        if (take > 0) {
          page.append(new String(bytes.slice(skip, skip + take), StandardCharsets.UTF_8))
          remaining -= take
          nextOffset += take
        }
      }
    }
    val run = getRun(runId).getOrElse(throw new KyuubiException("DBT run was not found"))
    DbtRunnerLogPage(
      page.toString,
      nextOffset,
      endOfStream = nextOffset >= run.logNextOffset,
      truncated = run.logTruncated)
  }

  override def listActiveRuns(): Seq[DbtJobRun] =
    JdbcUtils.executeQueryWithRowMapper("SELECT * FROM dbt_job_run WHERE state IN " +
      "('QUEUED', 'DISPATCHING', 'SUBMITTED', 'RUNNING', 'CANCELLING')")(_ => ())(runMapper)

  override def listActiveRunsForProfile(profileId: String): Seq[DbtJobRun] =
    JdbcUtils.executeQueryWithRowMapper("SELECT * FROM dbt_job_run WHERE profile_id = ? " +
      "AND state IN ('QUEUED', 'DISPATCHING', 'SUBMITTED', 'RUNNING', 'CANCELLING')")(_.setString(
      1,
      profileId))(
      runMapper)

  override def listActiveRunsForWorkspace(workspaceId: String): Seq[DbtJobRun] =
    JdbcUtils.executeQueryWithRowMapper("SELECT * FROM dbt_job_run WHERE workspace_id = ? " +
      "AND state IN ('QUEUED', 'DISPATCHING', 'SUBMITTED', 'RUNNING', 'CANCELLING')")(_.setString(
      1,
      workspaceId))(
      runMapper)

  private def listRuns(
      predicate: String,
      bind: java.sql.PreparedStatement => Unit,
      limit: Int): Seq[DbtJobRun] =
    JdbcUtils.executeQueryWithRowMapper(
      s"SELECT * FROM dbt_job_run WHERE $predicate ORDER BY created_at DESC LIMIT ?") { statement =>
      bind(statement)
      statement.setInt(2, limit)
    }(runMapper)

  private def addMissingColumns(): Unit = JdbcUtils.withConnection { connection =>
    JDBCDbtStore.MISSING_COLUMN_MIGRATIONS.foreach { case (table, column, ddl) =>
      if (!hasColumn(connection, table, column)) {
        try {
          JdbcUtils.withCloseable(connection.prepareStatement(ddl))(_.execute())
          info(s"Added missing DBT column $table.$column")
        } catch {
          case error: SQLException if duplicateColumn(error) =>
            info(s"DBT column $table.$column was added by another server replica")
        }
      }
    }
  }

  private def hasTable(table: String): Boolean = JdbcUtils.withConnection { connection =>
    JdbcUtils.withCloseable(connection.getMetaData.getTables(null, null, table, null))(_.next()) ||
    JdbcUtils.withCloseable(connection.getMetaData.getTables(null, null, table.toUpperCase, null))(
      _.next())
  }

  private def hasColumn(connection: Connection, table: String, column: String): Boolean = {
    JdbcUtils.withCloseable(connection.getMetaData.getColumns(null, null, table, column))(
      _.next()) ||
    JdbcUtils.withCloseable(connection.getMetaData.getColumns(
      null,
      null,
      table.toUpperCase,
      column.toUpperCase))(
      _.next())
  }

  private def duplicateColumn(error: SQLException): Boolean = {
    val message = Option(error.getMessage).getOrElse("").toLowerCase
    error.getSQLState == "42701" || message.contains("duplicate column") ||
    message.contains("already exists")
  }

  private def inTransaction[T](block: Connection => T): T = JdbcUtils.withConnection { connection =>
    val autoCommit = connection.getAutoCommit
    connection.setAutoCommit(false)
    try {
      val result = block(connection)
      connection.commit()
      result
    } catch {
      case error: Throwable =>
        try connection.rollback()
        catch { case rollbackError: Throwable => warn("DBT store rollback failed", rollbackError) }
        throw error
    } finally {
      connection.setAutoCommit(autoCommit)
    }
  }

  private def update(
      connection: Connection,
      sql: String)(bind: PreparedStatement => Unit): Int =
    JdbcUtils.withCloseable(connection.prepareStatement(sql)) { statement =>
      bind(statement)
      statement.executeUpdate()
    }

  private def queryOne[T](
      sql: String,
      bind: java.sql.PreparedStatement => Unit)(mapper: ResultSet => T): Option[T] =
    JdbcUtils.executeQueryWithRowMapper(sql)(bind)(mapper).headOption

  private def nullableLong(rs: ResultSet, column: String): Option[Long] = {
    val value = rs.getLong(column)
    if (rs.wasNull()) None else Some(value)
  }

  private def setNullableLong(
      statement: java.sql.PreparedStatement,
      index: Int,
      value: Option[Long]): Unit = value match {
    case Some(v) => statement.setLong(index, v)
    case None => statement.setNull(index, java.sql.Types.BIGINT)
  }
}

object JDBCDbtStore {

  /** See JDBCNotebookStore.addMissingColumns for why these DDL strings omit IF NOT EXISTS. */
  val MISSING_COLUMN_MIGRATIONS: Seq[(String, String, String)] = Seq(
    (
      "dbt_job_run",
      "dispatch_token",
      "ALTER TABLE dbt_job_run ADD COLUMN dispatch_token VARCHAR(36)"),
    (
      "dbt_job_run",
      "runner_job_uid",
      "ALTER TABLE dbt_job_run ADD COLUMN runner_job_uid VARCHAR(255)"),
    (
      "dbt_job_run",
      "state_updated_at",
      "ALTER TABLE dbt_job_run ADD COLUMN state_updated_at BIGINT DEFAULT 0"),
    (
      "dbt_job_run",
      "log_next_offset",
      "ALTER TABLE dbt_job_run ADD COLUMN log_next_offset BIGINT DEFAULT 0"),
    (
      "dbt_job_run",
      "log_truncated",
      "ALTER TABLE dbt_job_run ADD COLUMN log_truncated BOOLEAN DEFAULT FALSE"),
    (
      "dbt_job_run",
      "profile_notebook_runtime_idle_timeout",
      "ALTER TABLE dbt_job_run ADD COLUMN profile_notebook_runtime_idle_timeout VARCHAR(32)"),
    (
      "dbt_job_run",
      "profile_engine_idle_timeout",
      "ALTER TABLE dbt_job_run ADD COLUMN profile_engine_idle_timeout VARCHAR(32)"),
    (
      "dbt_job_run",
      "profile_python_environment_revision_id",
      "ALTER TABLE dbt_job_run " +
        "ADD COLUMN profile_python_environment_revision_id VARCHAR(64)"),
    (
      "dbt_job_run",
      "profile_revision",
      "ALTER TABLE dbt_job_run ADD COLUMN profile_revision BIGINT DEFAULT 1"),
    ("dbt_job_run", "version", "ALTER TABLE dbt_job_run ADD COLUMN version BIGINT DEFAULT 1"))
}
