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

import java.util.UUID

import scala.io.Source

import org.apache.kyuubi.Utils
import org.apache.kyuubi.server.notebook.NotebookTestBase

class ComputeStoreSuite extends NotebookTestBase {

  private def allocation(
      id: String = UUID.randomUUID().toString,
      engineKey: String = UUID.randomUUID().toString.replace("-", ""),
      mode: ComputeMode.Value = ComputeMode.INTERACTIVE,
      runId: Option[String] = None,
      attemptId: Option[String] = None): ComputeAllocation = {
    val now = System.currentTimeMillis()
    ComputeAllocation(
      id = id,
      owner = "alice",
      effectiveUser = "alice",
      profileId = "profile-a",
      mode = mode,
      workloadType = ComputeWorkloadType.NOTEBOOK,
      triggerType = ComputeTriggerType.MANUAL,
      runId = runId,
      attemptId = attemptId,
      engineType = "SPARK_SQL",
      shareLevel = "USER",
      subdomain = s"allocation-${id.take(12)}",
      discoveryNamespace = "kyuubi-test_USER_SPARK_SQL",
      engineKey = engineKey,
      launchSnapshot = ComputeLaunchSnapshot(
        profileId = "profile-a",
        profileOwner = "alice",
        profileVersion = 3L,
        sparkConfig = Map("spark.driver.memory" -> "2g"),
        capturedAt = now,
        notebookRuntimeIdleTimeout = Some("PT30M"),
        engineIdleTimeout = Some("PT1H"),
        pythonEnvironmentRevisionId = Some("environment-1"),
        runtimeImageReference = Some("spark@sha256:abc")),
      state = ComputeAllocationState.RESERVED,
      controllerId = None,
      controllerGeneration = 0L,
      claimExpiresAt = None,
      heartbeatAt = None,
      workloadHeartbeatAt = if (mode == ComputeMode.JOB) Some(now) else None,
      workloadDeadlineAt = if (mode == ComputeMode.JOB) Some(now + 60000L) else None,
      engineReferenceId = None,
      engineApplicationId = None,
      engineResourceUid = None,
      cleanupAttempts = 0,
      lastError = None,
      createdAt = now,
      updatedAt = now,
      releasedAt = None,
      version = 1L)
  }

  test("persists one immutable launch snapshot with its allocation identity") {
    val created = allocation()
    assert(manager.computeStore.createAllocation(created))

    val stored = manager.computeStore.getAllocation(created.id).get
    assert(stored === created)
    assert(manager.computeStore.getOpenAllocationByEngineKey(created.engineKey).contains(created))
    assert(manager.computeStore.listOpenAllocationsForProfile("profile-a").contains(created))
    manager.computeStore.healthCheck()

    val reopened = new JDBCComputeStore(conf)
    try {
      assert(reopened.getAllocation(created.id).contains(created))
    } finally {
      reopened.close()
    }
  }

  test("open engine and job attempt uniqueness work across store instances") {
    val peer = new JDBCComputeStore(conf)
    try {
      val engineKey = UUID.randomUUID().toString.replace("-", "")
      val first = allocation(engineKey = engineKey)
      val duplicateEngine = allocation(engineKey = engineKey)
      assert(manager.computeStore.createAllocation(first))
      assert(!peer.createAllocation(duplicateEngine))

      val releasing = first.copy(
        state = ComputeAllocationState.RELEASING,
        updatedAt = first.updatedAt + 1,
        version = 2L)
      assert(peer.updateAllocation(
        releasing,
        expectedVersion = 1L,
        expectedStates = Set(ComputeAllocationState.RESERVED)))
      val released = releasing.copy(
        state = ComputeAllocationState.RELEASED,
        updatedAt = first.updatedAt + 2,
        releasedAt = Some(first.updatedAt + 2),
        version = 3L)
      assert(peer.updateAllocation(
        released,
        expectedVersion = 2L,
        expectedStates = Set(ComputeAllocationState.RELEASING)))
      assert(manager.computeStore.createAllocation(duplicateEngine))

      val attemptId = UUID.randomUUID().toString
      val firstJob = allocation(
        mode = ComputeMode.JOB,
        runId = Some(UUID.randomUUID().toString),
        attemptId = Some(attemptId))
      val duplicateAttempt = allocation(
        mode = ComputeMode.JOB,
        runId = Some(UUID.randomUUID().toString),
        attemptId = Some(attemptId))
      assert(peer.createAllocation(firstJob))
      assert(!manager.computeStore.createAllocation(duplicateAttempt))
      assert(manager.computeStore.getAllocationByAttempt(attemptId).contains(firstJob))
    } finally {
      peer.close()
    }
  }

  test("job workload heartbeat is scoped and monotonic") {
    val runId = UUID.randomUUID().toString
    val attemptId = UUID.randomUUID().toString
    val created = allocation(
      mode = ComputeMode.JOB,
      runId = Some(runId),
      attemptId = Some(attemptId))
    assert(manager.computeStore.createAllocation(created))
    val heartbeat = created.workloadHeartbeatAt.get + 1L

    assert(!manager.computeStore.heartbeatJobAllocation(
      created.id,
      "another-run",
      attemptId,
      heartbeat))
    assert(manager.computeStore.heartbeatJobAllocation(
      created.id,
      runId,
      attemptId,
      heartbeat))
    assert(!manager.computeStore.heartbeatJobAllocation(
      created.id,
      runId,
      attemptId,
      heartbeat))
    assert(manager.computeStore.getAllocation(created.id).exists(
      _.workloadHeartbeatAt.contains(heartbeat)))
  }

  test("version CAS and controller generation fence competing replicas") {
    val peer = new JDBCComputeStore(conf)
    try {
      val created = allocation()
      assert(manager.computeStore.createAllocation(created))
      val starting = created.copy(
        state = ComputeAllocationState.STARTING,
        updatedAt = created.updatedAt + 1,
        version = 2L)
      assert(peer.updateAllocation(
        starting,
        expectedVersion = 1L,
        expectedStates = Set(ComputeAllocationState.RESERVED)))
      assert(!manager.computeStore.updateAllocation(
        starting,
        expectedVersion = 1L,
        expectedStates = Set(ComputeAllocationState.RESERVED)))

      val claimTime = starting.updatedAt + 10
      assert(manager.computeStore.claimAllocation(
        created.id,
        expectedVersion = 2L,
        controllerId = "replica-a",
        now = claimTime,
        claimExpiresAt = claimTime + 1000))
      val claimed = peer.getAllocation(created.id).get
      assert(claimed.controllerId.contains("replica-a"))
      assert(claimed.controllerGeneration === 1L)
      assert(claimed.version === 3L)
      assert(!peer.claimAllocation(
        created.id,
        expectedVersion = 3L,
        controllerId = "replica-b",
        now = claimTime + 1,
        claimExpiresAt = claimTime + 1001))

      assert(peer.renewAllocationClaim(
        created.id,
        expectedVersion = 3L,
        controllerId = "replica-a",
        controllerGeneration = 1L,
        heartbeatAt = claimTime + 2,
        claimExpiresAt = claimTime + 2000))
      val renewed = manager.computeStore.getAllocation(created.id).get
      assert(renewed.version === 4L)
      assert(!manager.computeStore.renewAllocationClaim(
        created.id,
        expectedVersion = 4L,
        controllerId = "replica-a",
        controllerGeneration = 2L,
        heartbeatAt = claimTime + 3,
        claimExpiresAt = claimTime + 2001))

      val active = renewed.copy(
        state = ComputeAllocationState.ACTIVE,
        updatedAt = claimTime + 3,
        version = 5L)
      assert(!manager.computeStore.updateClaimedAllocation(
        active,
        expectedVersion = 4L,
        expectedStates = Set(ComputeAllocationState.STARTING),
        expectedClaim = ComputeControllerClaim("replica-b", 1L)))
      assert(peer.updateClaimedAllocation(
        active,
        expectedVersion = 4L,
        expectedStates = Set(ComputeAllocationState.STARTING),
        expectedClaim = ComputeControllerClaim("replica-a", 1L)))

      assert(peer.listClaimableAllocations(
        Set(ComputeAllocationState.ACTIVE),
        claimTime + 2001,
        10).map(_.id).contains(created.id))
      assert(!peer.renewAllocationClaim(
        created.id,
        expectedVersion = 5L,
        controllerId = "replica-a",
        controllerGeneration = 1L,
        heartbeatAt = claimTime + 2001,
        claimExpiresAt = claimTime + 3001))

      assert(manager.computeStore.claimAllocation(
        created.id,
        expectedVersion = 5L,
        controllerId = "replica-b",
        now = claimTime + 2001,
        claimExpiresAt = claimTime + 3001))
      val takenOver = peer.getAllocation(created.id).get
      assert(takenOver.controllerId.contains("replica-b"))
      assert(takenOver.controllerGeneration === 2L)
      assert(takenOver.version === 6L)

      val releasing = takenOver.copy(
        state = ComputeAllocationState.RELEASING,
        updatedAt = claimTime + 2002,
        version = 7L)
      assert(!manager.computeStore.updateClaimedAllocation(
        releasing,
        expectedVersion = 6L,
        expectedStates = Set(ComputeAllocationState.ACTIVE),
        expectedClaim = ComputeControllerClaim("replica-a", 1L)))
      assert(peer.updateClaimedAllocation(
        releasing,
        expectedVersion = 6L,
        expectedStates = Set(ComputeAllocationState.ACTIVE),
        expectedClaim = ComputeControllerClaim("replica-b", 2L)))
    } finally {
      peer.close()
    }
  }

  test("rejects job allocations without attempts and snapshots containing secrets") {
    intercept[IllegalArgumentException] {
      manager.computeStore.createAllocation(allocation(mode = ComputeMode.JOB))
    }

    val base = allocation()
    val unsafe = base.copy(launchSnapshot = base.launchSnapshot.copy(sparkConfig =
      Map("spark.hadoop.fs.s3a.secret.key" -> "not-safe")))
    intercept[IllegalArgumentException] {
      manager.computeStore.createAllocation(unsafe)
    }
  }

  test("schema resources are additive and rerunnable for every supported dialect") {
    Seq("sqlite", "postgresql", "mysql").foreach { dialect =>
      val resource = s"sql/notebook/$dialect/compute-schema-1.0.0.$dialect.sql"
      val stream = Utils.getContextOrKyuubiClassLoader.getResourceAsStream(resource)
      assert(stream != null, s"missing compute schema for $dialect")
      val source = Source.fromInputStream(stream, "UTF-8")
      val ddl =
        try source.mkString
        finally source.close()
      assert(ddl.contains("CREATE TABLE IF NOT EXISTS compute_allocation"))
      assert(ddl.contains("CREATE TABLE IF NOT EXISTS compute_binding"))
      assert(ddl.contains("CREATE TABLE IF NOT EXISTS compute_attach_reservation"))
      assert(!ddl.toUpperCase(java.util.Locale.ROOT).contains("DROP TABLE"))
    }

    manager.computeStore.initSchema()
  }
}
