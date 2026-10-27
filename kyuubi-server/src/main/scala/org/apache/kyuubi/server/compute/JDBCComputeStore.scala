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

package org.apache.kyuubi.server.compute

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}
import java.util.stream.Collectors

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import org.apache.kyuubi.{KyuubiException, Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.metadata.jdbc.DatabaseType
import org.apache.kyuubi.server.metadata.jdbc.DatabaseType._
import org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStoreConf._
import org.apache.kyuubi.util.JdbcUtils
import org.apache.kyuubi.util.reflect.ReflectUtils

/** Compute allocation state shares Kyuubi's configured JDBC metadata database. */
class JDBCComputeStore(conf: KyuubiConf) extends ComputeStore with Logging {

  private val dbType = DatabaseType.withName(conf.get(METADATA_STORE_JDBC_DATABASE_TYPE))
  private val driverClass = {
    val configured = conf.get(METADATA_STORE_JDBC_DRIVER)
    dbType match {
      case SQLITE => configured.getOrElse("org.sqlite.JDBC")
      case MYSQL => configured.getOrElse {
          if (ReflectUtils.isClassLoadable("com.mysql.cj.jdbc.Driver")) {
            "com.mysql.cj.jdbc.Driver"
          } else {
            "com.mysql.jdbc.Driver"
          }
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
    value.setPoolName("jdbc-compute-store-pool")
    value
  }
  implicit private val dataSource: HikariDataSource = new HikariDataSource(hikariConfig)
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)

  override def initSchema(): Unit = {
    val dialect = dbType.toString.toLowerCase(java.util.Locale.ROOT)
    val resource = s"sql/notebook/$dialect/compute-schema-1.0.0.$dialect.sql"
    val stream = Utils.getContextOrKyuubiClassLoader.getResourceAsStream(resource)
    if (stream == null) {
      throw new KyuubiException(s"Compute allocation schema resource not found: $resource")
    }
    val ddl =
      try {
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .lines().collect(Collectors.joining("\n"))
      } finally {
        stream.close()
      }
    val statements = ddl.split("\n").filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty)
    JdbcUtils.withConnection { connection =>
      statements.foreach { statement =>
        JdbcUtils.withCloseable(connection.prepareStatement(statement))(_.execute())
      }
      addColumnIfMissing(
        connection,
        "engine_reference_id",
        "VARCHAR(255)")
      addColumnIfMissing(connection, "workload_heartbeat_at", "BIGINT")
      addColumnIfMissing(connection, "workload_deadline_at", "BIGINT")
      addColumnIfMissing(connection, "activated_at", "BIGINT")
      addColumnIfMissing(connection, "release_requested_at", "BIGINT")
    }
  }

  private def addColumnIfMissing(
      connection: Connection,
      column: String,
      definition: String): Unit = {
    if (!hasColumn(connection, "compute_allocation", column)) {
      try {
        JdbcUtils.withCloseable(connection.prepareStatement(
          s"ALTER TABLE compute_allocation ADD COLUMN $column $definition"))(_.execute())
        info(s"Added missing column compute_allocation.$column")
      } catch {
        case _: SQLException if hasColumn(connection, "compute_allocation", column) =>
          info(s"Compute allocation column $column was added by another server replica")
        case error: SQLException => throw error
      }
    }
  }

  private def hasColumn(connection: Connection, table: String, column: String): Boolean =
    try {
      JdbcUtils.withCloseable(
        connection.prepareStatement(s"SELECT $column FROM $table WHERE 1 = 0"))(_.execute())
      true
    } catch {
      case _: SQLException => false
    }

  override def healthCheck(): Unit = {
    JdbcUtils.executeQuery("SELECT 1 FROM compute_allocation WHERE 1 = 0")()(_ => ())
    JdbcUtils.executeQuery("SELECT 1 FROM compute_binding WHERE 1 = 0")()(_ => ())
    JdbcUtils.executeQuery("SELECT 1 FROM compute_attach_reservation WHERE 1 = 0")()(_ => ())
  }

  override def close(): Unit = dataSource.close()

  override def createAllocation(allocation: ComputeAllocation): Boolean = {
    ComputeAllocation.validateForCreate(allocation)
    try {
      JdbcUtils.executeUpdate(
        """INSERT INTO compute_allocation(
          | id, owner, effective_user, profile_id, mode, workload_type, trigger_type,
          | run_id, attempt_id, engine_type, share_level, subdomain, discovery_namespace,
          | engine_key, open_engine_key, launch_snapshot, state, controller_id,
          | controller_generation, claim_expires_at, heartbeat_at, workload_heartbeat_at,
          | workload_deadline_at, engine_reference_id, engine_application_id,
          | engine_resource_uid, cleanup_attempts, last_error, created_at, updated_at,
          | activated_at, release_requested_at, released_at, version)
          | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
          | ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin) { statement =>
        bindCreate(statement, allocation)
      } == 1
    } catch {
      case error: SQLException if duplicateKey(error) => false
    }
  }

  override def getAllocation(id: String): Option[ComputeAllocation] =
    queryOne("SELECT * FROM compute_allocation WHERE id = ?", _.setString(1, id))

  override def getAllocationByAttempt(attemptId: String): Option[ComputeAllocation] =
    queryOne(
      "SELECT * FROM compute_allocation WHERE attempt_id = ?",
      _.setString(1, attemptId))

  override def getOpenAllocationByEngineKey(engineKey: String): Option[ComputeAllocation] =
    queryOne(
      "SELECT * FROM compute_allocation WHERE open_engine_key = ?",
      _.setString(1, engineKey))

  override def listOpenAllocationsForProfile(profileId: String): Seq[ComputeAllocation] =
    JdbcUtils.executeQueryWithRowMapper(
      """SELECT * FROM compute_allocation
        | WHERE profile_id = ? AND state <> 'RELEASED'
        | ORDER BY created_at""".stripMargin)(_.setString(1, profileId))(allocationMapper)

  override def listOpenAllocations(limit: Int): Seq[ComputeAllocation] = {
    require(limit > 0, "compute allocation limit must be positive")
    JdbcUtils.executeQueryWithRowMapper(
      """SELECT * FROM compute_allocation
        | WHERE state <> 'RELEASED' ORDER BY created_at LIMIT ?""".stripMargin)(_.setInt(
      1,
      limit))(allocationMapper)
  }

  override def countOpenJobAllocations(owner: Option[String]): Long = {
    val ownerPredicate = owner.map(_ => " AND owner = ?").getOrElse("")
    JdbcUtils.executeQueryWithRowMapper(
      s"""SELECT COUNT(*) FROM compute_allocation
         | WHERE mode = 'JOB' AND state <> 'RELEASED'$ownerPredicate""".stripMargin) { statement =>
      owner.foreach(value => statement.setString(1, value))
    }(_.getLong(1)).head
  }

  override def createBinding(binding: ComputeBinding): Boolean = {
    require(binding.id.trim.nonEmpty, "compute binding ID must not be empty")
    require(binding.allocationId.trim.nonEmpty, "compute binding allocation ID must not be empty")
    require(binding.tokenHash.trim.nonEmpty, "compute binding token hash must not be empty")
    require(binding.expiresAt > binding.issuedAt, "compute binding expiry must follow issue time")
    require(binding.revokedAt.isEmpty, "a new compute binding must not be revoked")
    require(binding.version == 1L, "a new compute binding must be created at version 1")
    try {
      JdbcUtils.executeUpdate(
        """INSERT INTO compute_binding(
          | id, allocation_id, runner_user, effective_user, profile_id, run_id, attempt_id,
          | token_hash, issued_at, expires_at, revoked_at, version)
          | SELECT ?, ca.id, ?, ca.effective_user, ca.profile_id, ca.run_id, ca.attempt_id,
          | ?, ?, ?, NULL, 1 FROM compute_allocation ca WHERE ca.id = ? AND ca.mode = 'JOB'
          | AND ca.state IN ('RESERVED', 'STARTING', 'ACTIVE') AND ca.effective_user = ?
          | AND ca.profile_id = ? AND ca.run_id = ? AND ca.attempt_id = ?""".stripMargin) {
        statement =>
          statement.setString(1, binding.id)
          statement.setString(2, binding.runnerUser)
          statement.setString(3, binding.tokenHash)
          statement.setLong(4, binding.issuedAt)
          statement.setLong(5, binding.expiresAt)
          statement.setString(6, binding.allocationId)
          statement.setString(7, binding.effectiveUser)
          statement.setString(8, binding.profileId)
          statement.setString(9, binding.runId)
          statement.setString(10, binding.attemptId)
      } == 1
    } catch {
      case error: SQLException if duplicateKey(error) => false
    }
  }

  override def getBinding(id: String): Option[ComputeBinding] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM compute_binding WHERE id = ?")(_.setString(1, id))(bindingMapper).headOption

  override def revokeBinding(
      id: String,
      expectedVersion: Long,
      revokedAt: Long): Boolean = {
    val revoked = JdbcUtils.executeUpdate(
      """UPDATE compute_binding SET revoked_at = ?, version = version + 1
        | WHERE id = ? AND version = ? AND revoked_at IS NULL""".stripMargin) { statement =>
      statement.setLong(1, revokedAt)
      statement.setString(2, id)
      statement.setLong(3, expectedVersion)
    } == 1
    if (revoked) {
      JdbcUtils.executeUpdate(
        """UPDATE compute_attach_reservation SET state = 'RELEASED', released_at = ?,
          | version = version + 1 WHERE binding_id = ? AND state = 'RESERVED'""".stripMargin) {
        statement =>
          statement.setLong(1, revokedAt)
          statement.setString(2, id)
      }
    }
    revoked
  }

  override def revokeBindingsForAllocation(allocationId: String, revokedAt: Long): Int = {
    val count = JdbcUtils.executeUpdate(
      """UPDATE compute_binding SET revoked_at = ?, version = version + 1
        | WHERE allocation_id = ? AND revoked_at IS NULL""".stripMargin) { statement =>
      statement.setLong(1, revokedAt)
      statement.setString(2, allocationId)
    }
    JdbcUtils.executeUpdate(
      """UPDATE compute_attach_reservation SET state = 'RELEASED', released_at = ?,
        | version = version + 1 WHERE allocation_id = ? AND state <> 'RELEASED'""".stripMargin) {
      statement =>
        statement.setLong(1, revokedAt)
        statement.setString(2, allocationId)
    }
    count
  }

  override def createAttachReservation(
      reservation: ComputeAttachReservation,
      expectedTokenHash: String,
      now: Long): Boolean = {
    require(
      reservation.state == ComputeAttachState.RESERVED,
      "a new compute attach must be created in RESERVED state")
    require(reservation.expiresAt > now, "compute attach expiry must be in the future")
    require(reservation.version == 1L, "a new compute attach must be created at version 1")
    try {
      JdbcUtils.executeUpdate(
        """INSERT INTO compute_attach_reservation(
          | id, binding_id, allocation_id, runner_user, effective_user, profile_id, run_id,
          | attempt_id, state, expires_at, created_at, confirmed_at, released_at, version)
          | SELECT ?, cb.id, ca.id, cb.runner_user, ca.effective_user,
          | ca.profile_id, ca.run_id, ca.attempt_id, 'RESERVED', ?, ?, NULL, NULL, 1
          | FROM compute_binding cb JOIN compute_allocation ca ON ca.id = cb.allocation_id
          | WHERE cb.id = ? AND cb.token_hash = ? AND cb.runner_user = ?
          | AND cb.effective_user = ? AND cb.profile_id = ? AND cb.run_id = ?
          | AND cb.attempt_id = ? AND cb.allocation_id = ?
          | AND cb.revoked_at IS NULL AND cb.expires_at > ? AND ca.mode = 'JOB'
          | AND ca.state IN ('RESERVED', 'STARTING', 'ACTIVE')
          | AND ca.effective_user = ? AND ca.profile_id = ?
          | AND ca.run_id = ? AND ca.attempt_id = ?""".stripMargin) { statement =>
        statement.setString(1, reservation.id)
        statement.setLong(2, reservation.expiresAt)
        statement.setLong(3, reservation.createdAt)
        statement.setString(4, reservation.bindingId)
        statement.setString(5, expectedTokenHash)
        statement.setString(6, reservation.runnerUser)
        statement.setString(7, reservation.effectiveUser)
        statement.setString(8, reservation.profileId)
        statement.setString(9, reservation.runId)
        statement.setString(10, reservation.attemptId)
        statement.setString(11, reservation.allocationId)
        statement.setLong(12, now)
        statement.setString(13, reservation.effectiveUser)
        statement.setString(14, reservation.profileId)
        statement.setString(15, reservation.runId)
        statement.setString(16, reservation.attemptId)
      } == 1
    } catch {
      case error: SQLException if duplicateKey(error) => false
    }
  }

  override def getAttachReservation(id: String): Option[ComputeAttachReservation] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM compute_attach_reservation WHERE id = ?")(
      _.setString(1, id))(attachMapper).headOption

  override def confirmAttachReservation(id: String, confirmedAt: Long): Boolean =
    JdbcUtils.executeUpdate(
      """UPDATE compute_attach_reservation SET state = 'ATTACHED', confirmed_at = ?,
        | version = version + 1 WHERE id = ? AND state = 'RESERVED' AND expires_at > ?
        | AND EXISTS (SELECT 1 FROM compute_binding cb
        | JOIN compute_allocation ca ON ca.id = cb.allocation_id
        | WHERE cb.id = compute_attach_reservation.binding_id
        | AND ca.id = compute_attach_reservation.allocation_id
        | AND cb.revoked_at IS NULL AND cb.expires_at > ?
        | AND ca.state IN ('RESERVED', 'STARTING', 'ACTIVE'))""".stripMargin) { statement =>
      statement.setLong(1, confirmedAt)
      statement.setString(2, id)
      statement.setLong(3, confirmedAt)
      statement.setLong(4, confirmedAt)
    } == 1

  override def releaseAttachReservation(id: String, releasedAt: Long): Boolean =
    JdbcUtils.executeUpdate(
      """UPDATE compute_attach_reservation SET state = 'RELEASED', released_at = ?,
        | version = version + 1 WHERE id = ? AND state IN ('RESERVED', 'ATTACHED')""".stripMargin) {
      statement =>
        statement.setLong(1, releasedAt)
        statement.setString(2, id)
    } == 1

  override def updateAllocation(
      allocation: ComputeAllocation,
      expectedVersion: Long,
      expectedStates: Set[ComputeAllocationState.Value]): Boolean =
    updateAllocationInternal(allocation, expectedVersion, expectedStates, None)

  override def updateClaimedAllocation(
      allocation: ComputeAllocation,
      expectedVersion: Long,
      expectedStates: Set[ComputeAllocationState.Value],
      expectedClaim: ComputeControllerClaim): Boolean = {
    require(expectedClaim.controllerId.trim.nonEmpty, "controller ID must not be empty")
    require(expectedClaim.generation > 0, "controller generation must be positive")
    updateAllocationInternal(
      allocation,
      expectedVersion,
      expectedStates,
      Some(expectedClaim))
  }

  override def requestAllocationRelease(
      id: String,
      expectedVersion: Long,
      expectedStates: Set[ComputeAllocationState.Value],
      requestedAt: Long,
      reason: Option[String]): Boolean = {
    require(expectedStates.nonEmpty, "release source states must not be empty")
    require(
      expectedStates.forall(
        ComputeAllocationState.canTransition(_, ComputeAllocationState.RELEASING)),
      "all release source states must transition to RELEASING")
    val placeholders = List.fill(expectedStates.size)("?").mkString(", ")
    val released = JdbcUtils.executeUpdate(
      s"""UPDATE compute_allocation SET state = 'RELEASING', controller_id = NULL,
         | controller_generation = controller_generation + 1, claim_expires_at = NULL,
         | heartbeat_at = NULL, last_error = ?, release_requested_at =
         | COALESCE(release_requested_at, ?), updated_at = ?, version = version + 1
         | WHERE id = ? AND version = ? AND state IN ($placeholders)""".stripMargin) { statement =>
      statement.setString(1, reason.orNull)
      statement.setLong(2, requestedAt)
      statement.setLong(3, requestedAt)
      statement.setString(4, id)
      statement.setLong(5, expectedVersion)
      expectedStates.toSeq.sortBy(_.toString).zipWithIndex.foreach { case (state, index) =>
        statement.setString(6 + index, state.toString)
      }
    } == 1
    if (released) {
      revokeBindingsForAllocation(id, requestedAt)
    }
    released
  }

  override def heartbeatJobAllocation(
      id: String,
      runId: String,
      attemptId: String,
      heartbeatAt: Long): Boolean = {
    val sql =
      """UPDATE compute_allocation SET workload_heartbeat_at = ?, updated_at = ?,
        | version = version + 1 WHERE id = ? AND run_id = ? AND attempt_id = ?
        | AND mode = 'JOB' AND state IN ('RESERVED', 'STARTING', 'ACTIVE')
        | AND workload_deadline_at > ?
        | AND (workload_heartbeat_at IS NULL OR workload_heartbeat_at < ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { statement =>
      statement.setLong(1, heartbeatAt)
      statement.setLong(2, heartbeatAt)
      statement.setString(3, id)
      statement.setString(4, runId)
      statement.setString(5, attemptId)
      statement.setLong(6, heartbeatAt)
      statement.setLong(7, heartbeatAt)
    } == 1
  }

  private def updateAllocationInternal(
      allocation: ComputeAllocation,
      expectedVersion: Long,
      expectedStates: Set[ComputeAllocationState.Value],
      expectedClaim: Option[ComputeControllerClaim]): Boolean = {
    require(expectedStates.nonEmpty, "expected allocation states must not be empty")
    require(
      allocation.version == expectedVersion + 1,
      "updated compute allocation version must advance exactly once")
    require(
      expectedStates.forall(ComputeAllocationState.canTransition(_, allocation.state)),
      s"invalid compute allocation transition to ${allocation.state} from " +
        expectedStates.toSeq.sortBy(_.toString).mkString(", "))
    if (allocation.state == ComputeAllocationState.RELEASED) {
      require(allocation.releasedAt.nonEmpty, "a released allocation requires releasedAt")
    }
    val placeholders = List.fill(expectedStates.size)("?").mkString(", ")
    val claimPredicate = expectedClaim
      .map(_ =>
        " AND controller_id = ? AND controller_generation = ?" +
          " AND claim_expires_at > ?")
      .getOrElse(" AND controller_id IS NULL")
    val sql =
      s"""UPDATE compute_allocation SET state = ?, engine_reference_id = ?,
         | engine_application_id = ?, engine_resource_uid = ?, cleanup_attempts = ?,
         | last_error = ?, updated_at = ?, activated_at = COALESCE(activated_at, ?),
         | release_requested_at = COALESCE(release_requested_at, ?), released_at = ?,
         | open_engine_key = CASE WHEN ? = 'RELEASED' THEN NULL ELSE open_engine_key END,
         | controller_id = CASE WHEN ? = 'RELEASED' THEN NULL ELSE controller_id END,
         | claim_expires_at = CASE WHEN ? = 'RELEASED' THEN NULL ELSE claim_expires_at END,
         | heartbeat_at = CASE WHEN ? = 'RELEASED' THEN NULL ELSE heartbeat_at END, version = ?
         | WHERE id = ? AND version = ? AND state IN ($placeholders)$claimPredicate""".stripMargin
    val updated = JdbcUtils.executeUpdate(sql) { statement =>
      statement.setString(1, allocation.state.toString)
      statement.setString(2, allocation.engineReferenceId.orNull)
      statement.setString(3, allocation.engineApplicationId.orNull)
      statement.setString(4, allocation.engineResourceUid.orNull)
      statement.setInt(5, allocation.cleanupAttempts)
      statement.setString(6, allocation.lastError.orNull)
      statement.setLong(7, allocation.updatedAt)
      setNullableLong(
        statement,
        8,
        allocation.activatedAt.orElse(
          if (allocation.state == ComputeAllocationState.ACTIVE) {
            Some(allocation.updatedAt)
          } else {
            None
          }))
      setNullableLong(
        statement,
        9,
        allocation.releaseRequestedAt.orElse(
          if (allocation.state == ComputeAllocationState.RELEASING) {
            Some(allocation.updatedAt)
          } else {
            None
          }))
      setNullableLong(statement, 10, allocation.releasedAt)
      statement.setString(11, allocation.state.toString)
      statement.setString(12, allocation.state.toString)
      statement.setString(13, allocation.state.toString)
      statement.setString(14, allocation.state.toString)
      statement.setLong(15, allocation.version)
      statement.setString(16, allocation.id)
      statement.setLong(17, expectedVersion)
      expectedStates.toSeq.sortBy(_.toString).zipWithIndex.foreach { case (state, index) =>
        statement.setString(18 + index, state.toString)
      }
      expectedClaim.foreach { claim =>
        val claimIndex = 18 + expectedStates.size
        statement.setString(claimIndex, claim.controllerId)
        statement.setLong(claimIndex + 1, claim.generation)
        statement.setLong(claimIndex + 2, allocation.updatedAt)
      }
    } == 1
    if (updated && Set(
        ComputeAllocationState.RELEASING,
        ComputeAllocationState.RELEASED).contains(allocation.state)) {
      revokeBindingsForAllocation(allocation.id, allocation.updatedAt)
    }
    updated
  }

  override def claimAllocation(
      id: String,
      expectedVersion: Long,
      controllerId: String,
      now: Long,
      claimExpiresAt: Long): Boolean = {
    require(controllerId.trim.nonEmpty, "controller ID must not be empty")
    require(claimExpiresAt > now, "controller claim expiry must be in the future")
    JdbcUtils.executeUpdate(
      """UPDATE compute_allocation SET controller_id = ?,
        | controller_generation = controller_generation + 1, claim_expires_at = ?,
        | heartbeat_at = ?, version = version + 1
        | WHERE id = ? AND version = ? AND state <> 'RELEASED'
        | AND (controller_id IS NULL OR controller_id = ? OR claim_expires_at IS NULL
        | OR claim_expires_at <= ?)""".stripMargin) { statement =>
      statement.setString(1, controllerId)
      statement.setLong(2, claimExpiresAt)
      statement.setLong(3, now)
      statement.setString(4, id)
      statement.setLong(5, expectedVersion)
      statement.setString(6, controllerId)
      statement.setLong(7, now)
    } == 1
  }

  override def renewAllocationClaim(
      id: String,
      expectedVersion: Long,
      controllerId: String,
      controllerGeneration: Long,
      heartbeatAt: Long,
      claimExpiresAt: Long): Boolean = {
    require(controllerId.trim.nonEmpty, "controller ID must not be empty")
    require(controllerGeneration > 0, "controller generation must be positive")
    require(claimExpiresAt > heartbeatAt, "controller claim expiry must follow its heartbeat")
    JdbcUtils.executeUpdate(
      """UPDATE compute_allocation SET claim_expires_at = ?, heartbeat_at = ?,
        | version = version + 1
        | WHERE id = ? AND version = ? AND controller_id = ?
        | AND controller_generation = ? AND state <> 'RELEASED'
        | AND claim_expires_at > ?""".stripMargin) { statement =>
      statement.setLong(1, claimExpiresAt)
      statement.setLong(2, heartbeatAt)
      statement.setString(3, id)
      statement.setLong(4, expectedVersion)
      statement.setString(5, controllerId)
      statement.setLong(6, controllerGeneration)
      statement.setLong(7, heartbeatAt)
    } == 1
  }

  override def listClaimableAllocations(
      states: Set[ComputeAllocationState.Value],
      claimExpiredBefore: Long,
      limit: Int): Seq[ComputeAllocation] = {
    require(states.nonEmpty, "claimable allocation states must not be empty")
    require(states.subsetOf(ComputeAllocationState.open), "released allocations cannot be claimed")
    require(limit > 0, "claimable allocation limit must be positive")
    val placeholders = List.fill(states.size)("?").mkString(", ")
    JdbcUtils.executeQueryWithRowMapper(
      s"""SELECT * FROM compute_allocation WHERE state IN ($placeholders)
         | AND (controller_id IS NULL OR claim_expires_at IS NULL OR claim_expires_at <= ?)
         | ORDER BY updated_at LIMIT ?""".stripMargin) { statement =>
      states.toSeq.sortBy(_.toString).zipWithIndex.foreach { case (state, index) =>
        statement.setString(index + 1, state.toString)
      }
      statement.setLong(states.size + 1, claimExpiredBefore)
      statement.setInt(states.size + 2, limit)
    }(allocationMapper)
  }

  private def bindCreate(statement: PreparedStatement, allocation: ComputeAllocation): Unit = {
    statement.setString(1, allocation.id)
    statement.setString(2, allocation.owner)
    statement.setString(3, allocation.effectiveUser)
    statement.setString(4, allocation.profileId)
    statement.setString(5, allocation.mode.toString)
    statement.setString(6, allocation.workloadType.toString)
    statement.setString(7, allocation.triggerType.toString)
    statement.setString(8, allocation.runId.orNull)
    statement.setString(9, allocation.attemptId.orNull)
    statement.setString(10, allocation.engineType)
    statement.setString(11, allocation.shareLevel)
    statement.setString(12, allocation.subdomain)
    statement.setString(13, allocation.discoveryNamespace)
    statement.setString(14, allocation.engineKey)
    statement.setString(15, allocation.engineKey)
    statement.setString(16, mapper.writeValueAsString(allocation.launchSnapshot))
    statement.setString(17, allocation.state.toString)
    statement.setString(18, allocation.controllerId.orNull)
    statement.setLong(19, allocation.controllerGeneration)
    setNullableLong(statement, 20, allocation.claimExpiresAt)
    setNullableLong(statement, 21, allocation.heartbeatAt)
    setNullableLong(statement, 22, allocation.workloadHeartbeatAt)
    setNullableLong(statement, 23, allocation.workloadDeadlineAt)
    statement.setString(24, allocation.engineReferenceId.orNull)
    statement.setString(25, allocation.engineApplicationId.orNull)
    statement.setString(26, allocation.engineResourceUid.orNull)
    statement.setInt(27, allocation.cleanupAttempts)
    statement.setString(28, allocation.lastError.orNull)
    statement.setLong(29, allocation.createdAt)
    statement.setLong(30, allocation.updatedAt)
    setNullableLong(statement, 31, allocation.activatedAt)
    setNullableLong(statement, 32, allocation.releaseRequestedAt)
    setNullableLong(statement, 33, allocation.releasedAt)
    statement.setLong(34, allocation.version)
  }

  private def allocationMapper(result: ResultSet): ComputeAllocation = ComputeAllocation(
    id = result.getString("id"),
    owner = result.getString("owner"),
    effectiveUser = result.getString("effective_user"),
    profileId = result.getString("profile_id"),
    mode = ComputeMode.withName(result.getString("mode")),
    workloadType = ComputeWorkloadType.withName(result.getString("workload_type")),
    triggerType = ComputeTriggerType.withName(result.getString("trigger_type")),
    runId = Option(result.getString("run_id")),
    attemptId = Option(result.getString("attempt_id")),
    engineType = result.getString("engine_type"),
    shareLevel = result.getString("share_level"),
    subdomain = result.getString("subdomain"),
    discoveryNamespace = result.getString("discovery_namespace"),
    engineKey = result.getString("engine_key"),
    launchSnapshot = mapper.readValue(
      result.getString("launch_snapshot"),
      classOf[ComputeLaunchSnapshot]),
    state = ComputeAllocationState.withName(result.getString("state")),
    controllerId = Option(result.getString("controller_id")),
    controllerGeneration = result.getLong("controller_generation"),
    claimExpiresAt = nullableLong(result, "claim_expires_at"),
    heartbeatAt = nullableLong(result, "heartbeat_at"),
    workloadHeartbeatAt = nullableLong(result, "workload_heartbeat_at"),
    workloadDeadlineAt = nullableLong(result, "workload_deadline_at"),
    engineReferenceId = Option(result.getString("engine_reference_id")),
    engineApplicationId = Option(result.getString("engine_application_id")),
    engineResourceUid = Option(result.getString("engine_resource_uid")),
    cleanupAttempts = result.getInt("cleanup_attempts"),
    lastError = Option(result.getString("last_error")),
    createdAt = result.getLong("created_at"),
    updatedAt = result.getLong("updated_at"),
    releasedAt = nullableLong(result, "released_at"),
    version = result.getLong("version"),
    activatedAt = nullableLong(result, "activated_at"),
    releaseRequestedAt = nullableLong(result, "release_requested_at"))

  private def bindingMapper(result: ResultSet): ComputeBinding = ComputeBinding(
    id = result.getString("id"),
    allocationId = result.getString("allocation_id"),
    runnerUser = result.getString("runner_user"),
    effectiveUser = result.getString("effective_user"),
    profileId = result.getString("profile_id"),
    runId = result.getString("run_id"),
    attemptId = result.getString("attempt_id"),
    tokenHash = result.getString("token_hash"),
    issuedAt = result.getLong("issued_at"),
    expiresAt = result.getLong("expires_at"),
    revokedAt = nullableLong(result, "revoked_at"),
    version = result.getLong("version"))

  private def attachMapper(result: ResultSet): ComputeAttachReservation =
    ComputeAttachReservation(
      id = result.getString("id"),
      bindingId = result.getString("binding_id"),
      allocationId = result.getString("allocation_id"),
      runnerUser = result.getString("runner_user"),
      effectiveUser = result.getString("effective_user"),
      profileId = result.getString("profile_id"),
      runId = result.getString("run_id"),
      attemptId = result.getString("attempt_id"),
      state = ComputeAttachState.withName(result.getString("state")),
      expiresAt = result.getLong("expires_at"),
      createdAt = result.getLong("created_at"),
      confirmedAt = nullableLong(result, "confirmed_at"),
      releasedAt = nullableLong(result, "released_at"),
      version = result.getLong("version"))

  private def queryOne(
      sql: String,
      bind: PreparedStatement => Unit): Option[ComputeAllocation] =
    JdbcUtils.executeQueryWithRowMapper(sql)(bind)(allocationMapper).headOption

  private def nullableLong(result: ResultSet, column: String): Option[Long] = {
    val value = result.getLong(column)
    if (result.wasNull()) None else Some(value)
  }

  private def setNullableLong(
      statement: PreparedStatement,
      index: Int,
      value: Option[Long]): Unit = value match {
    case Some(present) => statement.setLong(index, present)
    case None => statement.setNull(index, java.sql.Types.BIGINT)
  }

  private def duplicateKey(error: SQLException): Boolean = {
    val message = Option(error.getMessage).getOrElse("").toLowerCase(java.util.Locale.ROOT)
    error.getSQLState == "23505" || error.getErrorCode == 1062 ||
    message.contains("unique constraint") || message.contains("constraint_unique") ||
    message.contains("duplicate entry")
  }
}
