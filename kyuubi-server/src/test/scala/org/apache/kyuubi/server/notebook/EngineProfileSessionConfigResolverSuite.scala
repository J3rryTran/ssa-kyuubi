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

import java.util

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.compute.{ComputeAllocation, ComputeAllocationState, ComputeTriggerType, ComputeWorkloadType, IssuedComputeBinding}
import org.apache.kyuubi.server.engineprofile.{EngineProfilePrincipal, EngineProfileSessionConfigResolver}
import org.apache.kyuubi.server.notebook.api.{NotebookErrorCode, UpsertEngineProfileRequest}

class EngineProfileSessionConfigResolverSuite extends NotebookTestBase {

  private lazy val resolver = new EngineProfileSessionConfigResolver(
    manager.engineProfiles,
    manager.pythonEnvironments,
    manager.engineProfileRunSnapshots,
    manager.computeBindings,
    manager.computeAllocations)

  private def createProfile(): String = {
    val request = new UpsertEngineProfileRequest
    request.setName("dbt-heavy-" + java.util.UUID.randomUUID().toString.take(8))
    request.setSparkConfig(new util.HashMap(Map(
      "spark.driver.memory" -> "4g",
      "spark.executor.memory" -> "8g",
      "spark.executor.instances" -> "2").asJava))
    request.setEngineIdleTimeout("PT30M")
    manager.engineProfiles.create(
      EngineProfilePrincipal(alice.user, alice.admin),
      request).profileId
  }

  private def reserveManagedJob(profileId: String): (ComputeAllocation, IssuedComputeBinding) = {
    val allocation = manager.computeAllocations.reserveJob(
      EngineProfilePrincipal(alice.user, alice.admin),
      profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      java.util.UUID.randomUUID().toString,
      java.util.UUID.randomUUID().toString)
    allocation -> manager.computeBindings.issue(allocation.id, "dbt-runner")
  }

  private def managedConfiguration(
      allocation: ComputeAllocation,
      binding: IssuedComputeBinding): Map[String, String] = Map(
    EngineProfileSessionConfigResolver.ProfileIdKey -> allocation.profileId,
    EngineProfileSessionConfigResolver.RunIdKey -> allocation.runId.get,
    EngineProfileSessionConfigResolver.BindingIdKey -> binding.bindingId,
    EngineProfileSessionConfigResolver.BindingTokenKey -> binding.token,
    EngineProfileSessionConfigResolver.AllocationIdKey -> allocation.id,
    EngineProfileSessionConfigResolver.AttemptIdKey -> allocation.attemptId.get,
    "hive.server2.proxy.user" -> alice.user)

  private def release(allocation: ComputeAllocation): Unit = {
    val current = manager.computeStore.getAllocation(allocation.id).get
    val now = math.max(System.currentTimeMillis(), current.updatedAt + 1)
    val releasing = current.copy(
      state = ComputeAllocationState.RELEASING,
      updatedAt = now,
      version = current.version + 1)
    assert(manager.computeStore.updateAllocation(releasing, current.version, Set(current.state)))
    val released = releasing.copy(
      state = ComputeAllocationState.RELEASED,
      updatedAt = now + 1,
      releasedAt = Some(now + 1),
      version = releasing.version + 1)
    assert(manager.computeStore.updateAllocation(
      released,
      releasing.version,
      Set(ComputeAllocationState.RELEASING)))
  }

  test("resolves an owned profile into server-managed session configuration") {
    val profileId = createProfile()
    val resolved = resolver.resolve(
      alice.user,
      Map(
        EngineProfileSessionConfigResolver.ProfileIdKey -> profileId,
        "spark.sql.defaultCatalog" -> "lakehouse"))
    val snapshot = manager.engineProfiles.snapshotForUse(
      profileId,
      EngineProfilePrincipal(alice.user, alice.admin))

    assert(!resolved.contains(EngineProfileSessionConfigResolver.ProfileIdKey))
    assert(resolved("kyuubi.engine.type") === "SPARK_SQL")
    assert(resolved("kyuubi.engine.share.level") === "USER")
    assert(resolved("kyuubi.engine.share.level.subdomain") === snapshot.subdomain)
    assert(resolved("spark.driver.memory") === "4g")
    assert(resolved("spark.executor.memory") === "8g")
    assert(resolved("spark.executor.instances") === "2")
    assert(resolved("kyuubi.session.engine.idle.timeout") === "1800000")
    assert(resolved("kyuubi.operation.idle.timeout") === "1800000")
    assert(resolved("spark.sql.defaultCatalog") === "lakehouse")
  }

  test("rejects raw launch configuration with an Engine Profile") {
    val profileId = createProfile()

    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      resolver.resolve(
        alice.user,
        Map(
          EngineProfileSessionConfigResolver.ProfileIdKey -> profileId,
          "spark.jars" -> "file:///untrusted.jar"))
    }
  }

  test("rejects an empty Engine Profile ID") {
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      resolver.resolve(alice.user, Map(EngineProfileSessionConfigResolver.ProfileIdKey -> "  "))
    }
  }

  test("rejects a profile owned by another effective user") {
    val profileId = createProfile()

    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      resolver.resolve(bob.user, Map(EngineProfileSessionConfigResolver.ProfileIdKey -> profileId))
    }
  }

  test("rejects the removed legacy profile revision parameter") {
    val profileId = createProfile()

    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      resolver.resolve(
        alice.user,
        Map(
          EngineProfileSessionConfigResolver.ProfileIdKey -> profileId,
          EngineProfileSessionConfigResolver.LegacyProfileRevisionKey -> "1"))
    }
  }

  test("DBT and interactive Thrift sessions reuse the same profile allocation") {
    val profileId = createProfile()
    val runId = java.util.UUID.randomUUID().toString
    val first = resolver.resolve(
      alice.user,
      Map(
        EngineProfileSessionConfigResolver.ProfileIdKey -> profileId,
        EngineProfileSessionConfigResolver.RunIdKey -> runId))
    val secondDbtSession = resolver.resolve(
      alice.user,
      Map(
        EngineProfileSessionConfigResolver.ProfileIdKey -> profileId,
        EngineProfileSessionConfigResolver.RunIdKey -> runId))
    val interactiveSql = resolver.resolve(
      alice.user,
      Map(EngineProfileSessionConfigResolver.ProfileIdKey -> profileId))

    assert(secondDbtSession("kyuubi.engine.share.level.subdomain") ===
      first("kyuubi.engine.share.level.subdomain"))
    assert(interactiveSql("kyuubi.engine.share.level.subdomain") ===
      first("kyuubi.engine.share.level.subdomain"))
    val allocations = manager.computeStore.listOpenAllocationsForProfile(profileId)
    assert(allocations.size === 1)
    release(allocations.head)
  }

  test("managed attach derives routing from a scoped server-side allocation") {
    val (allocation, binding) = reserveManagedJob(createProfile())
    try {
      val resolved = resolver.resolve(
        "dbt-runner",
        alice.user,
        managedConfiguration(allocation, binding) +
          ("spark.sql.defaultCatalog" -> "lakehouse"))

      assert(resolved("kyuubi.engine.share.level.subdomain") === allocation.subdomain)
      assert(resolved("spark.driver.memory") === "4g")
      assert(resolved("spark.sql.defaultCatalog") === "lakehouse")
      assert(!resolved.contains(EngineProfileSessionConfigResolver.BindingIdKey))
      assert(!resolved.contains(EngineProfileSessionConfigResolver.BindingTokenKey))
      assert(!resolved.contains(EngineProfileSessionConfigResolver.AllocationIdKey))
      val reservationId =
        resolved(EngineProfileSessionConfigResolver.AttachReservationIdKey)
      assert(manager.computeBindings.confirmAttach(reservationId))
    } finally {
      release(allocation)
    }
  }

  test("managed attach never falls back for incomplete invalid or forged input") {
    val (allocation, binding) = reserveManagedJob(createProfile())
    try {
      interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
        resolver.resolve(
          "dbt-runner",
          alice.user,
          Map(EngineProfileSessionConfigResolver.BindingIdKey -> binding.bindingId))
      }
      interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
        resolver.resolve(
          "dbt-runner",
          alice.user,
          managedConfiguration(allocation, binding) +
            (EngineProfileSessionConfigResolver.BindingTokenKey -> "wrong-token"))
      }
      interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
        resolver.resolve(
          "dbt-runner",
          alice.user,
          managedConfiguration(allocation, binding) + ("spark.driver.memory" -> "64g"))
      }
      interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
        resolver.resolve(
          "dbt-runner",
          bob.user,
          managedConfiguration(allocation, binding))
      }
    } finally {
      release(allocation)
    }
  }
}
