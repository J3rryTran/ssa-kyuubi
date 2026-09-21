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

import org.apache.kyuubi.server.engineprofile.EngineProfilePrincipal
import org.apache.kyuubi.server.notebook.api.{NotebookErrorCode, UpsertEngineProfileRequest}
import org.apache.kyuubi.server.notebook.service.NotebookPrincipal

class EngineProfileServiceSuite extends NotebookTestBase {

  private def profilePrincipal(principal: NotebookPrincipal): EngineProfilePrincipal =
    EngineProfilePrincipal(principal.user, principal.admin)

  private def request(name: String, config: (String, String)*): UpsertEngineProfileRequest = {
    val result = new UpsertEngineProfileRequest
    result.setName(name)
    result.setSparkConfig(new util.HashMap(config.toMap.asJava))
    result
  }

  private def requestWithTimeouts(
      name: String,
      notebookRuntimeIdleTimeout: String,
      engineIdleTimeout: String): UpsertEngineProfileRequest = {
    val result = request(name)
    result.setNotebookRuntimeIdleTimeout(notebookRuntimeIdleTimeout)
    result.setEngineIdleTimeout(engineIdleTimeout)
    result
  }

  test("different owners can create profiles with the same display name") {
    val aliceProfile = manager.engineProfiles.create(
      profilePrincipal(alice),
      request(
        "small",
        "spark.driver.memory" -> "1g"))
    val bobProfile = manager.engineProfiles.create(
      profilePrincipal(bob),
      request(
        "small",
        "spark.driver.memory" -> "1g"))

    assert(aliceProfile.name === "small")
    assert(bobProfile.name === "small")
    assert(aliceProfile.profileId !== bobProfile.profileId)
    assert(aliceProfile.subdomain !== bobProfile.subdomain)
  }

  test("owner can snapshot an engine profile by immutable ID") {
    val profile = manager.engineProfiles.create(
      profilePrincipal(alice),
      request(
        "alice-small",
        "spark.driver.memory" -> "1g",
        "spark.driver.cores" -> "1",
        "spark.executor.memory" -> "2g",
        "spark.executor.cores" -> "1",
        "spark.executor.instances" -> "1"))

    assert(profile.owner === alice.user)
    val snapshot = manager.engineProfiles.snapshotForUse(profile.profileId, profilePrincipal(alice))
    assert(snapshot.profileId === profile.profileId)
    assert(snapshot.subdomain === profile.subdomain)
    assert(snapshot.sparkConfig === profile.sparkConfig)
  }

  test("profile update creates a new revision and a new engine subdomain") {
    val created = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("heavy", "spark.driver.memory" -> "2g"))
    val updated = manager.engineProfiles.update(
      created.profileId,
      profilePrincipal(alice),
      request("heavy", "spark.driver.memory" -> "4g"))

    assert(updated.profileId === created.profileId)
    assert(updated.name === created.name)
    assert(updated.revision === 2L)
    assert(updated.subdomain !== created.subdomain)
    assert(updated.sparkConfig("spark.driver.memory") === "4g")
    assert(manager.engineProfiles.listRevisions(created.profileId, profilePrincipal(alice))
      .map(_.revision) === Seq(2L, 1L))
  }

  test("only a former revision is eligible for explicit termination") {
    val created = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("draining", "spark.driver.memory" -> "1g"))
    val updated = manager.engineProfiles.update(
      created.profileId,
      profilePrincipal(alice),
      request("draining", "spark.driver.memory" -> "2g"))

    val draining = manager.engineProfiles.requireDrainingRevision(
      created.profileId,
      created.revision,
      profilePrincipal(alice))
    assert(draining.subdomain === created.subdomain)
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.engineProfiles.requireDrainingRevision(
        created.profileId,
        updated.revision,
        profilePrincipal(alice))
    }
  }

  test("built-in default profile uses server Spark defaults without a persisted row") {
    val snapshot = manager.engineProfiles.snapshotForUse("default", profilePrincipal(alice))

    assert(snapshot.profileId === "default")
    assert(snapshot.owner === alice.user)
    assert(snapshot.subdomain === "default")
    assert(snapshot.sparkConfig.isEmpty)
    assert(snapshot.notebookRuntimeIdleTimeout.isEmpty)
    assert(snapshot.engineIdleTimeout.isEmpty)
  }

  test("profile snapshots preserve validated idle timeout overrides") {
    val created = manager.engineProfiles.create(
      profilePrincipal(alice),
      requestWithTimeouts("timed", "PT5M", "PT30M"))
    val snapshot = manager.engineProfiles.snapshotForUse(created.profileId, profilePrincipal(alice))

    assert(snapshot.notebookRuntimeIdleTimeout.contains("PT5M"))
    assert(snapshot.engineIdleTimeout.contains("PT30M"))
    assert(snapshot.revision === 1L)
    assert(org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot
      .engineSessionConfig(snapshot)("kyuubi.session.engine.idle.timeout") === "1800000")

    val updated = manager.engineProfiles.update(
      created.profileId,
      profilePrincipal(alice),
      requestWithTimeouts("timed", "inherit", "PT1H"))
    assert(updated.notebookRuntimeIdleTimeout.isEmpty)
    assert(updated.engineIdleTimeout.contains("PT1H"))
    assert(updated.revision === 2L)
  }

  test("profile rejects invalid idle timeout overrides") {
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.engineProfiles.create(
        profilePrincipal(alice),
        requestWithTimeouts("invalid-timeout", "5m", "PT30S"))
    }
  }

  test("another user cannot read or use an engine profile") {
    val profile = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("alice-private", "spark.driver.memory" -> "1g"))

    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      manager.engineProfiles.getOwned(profile.profileId, profilePrincipal(bob))
    }
    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      manager.engineProfiles.snapshotForUse(profile.profileId, profilePrincipal(bob))
    }
  }

  test("administrator can manage a known profile owned by another user") {
    val profile = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("alice-admin-managed", "spark.driver.memory" -> "1g"))

    val updated = manager.engineProfiles.update(
      profile.profileId,
      profilePrincipal(root),
      request("alice-admin-managed", "spark.driver.memory" -> "2g"))
    assert(updated.owner === alice.user)
    assert(updated.sparkConfig("spark.driver.memory") === "2g")
    manager.engineProfiles.delete(profile.profileId, profilePrincipal(root))
    interceptNotebook(NotebookErrorCode.NOTEBOOK_NOT_FOUND) {
      manager.engineProfiles.getOwned(profile.profileId, profilePrincipal(alice))
    }
  }

  test("profile rejects dangerous keys and inconsistent dynamic allocation bounds") {
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.engineProfiles.create(
        profilePrincipal(alice),
        request(
          "invalid-key",
          "spark.kubernetes.authenticate.driver.serviceAccountName" -> "privileged"))
    }
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.engineProfiles.create(
        profilePrincipal(alice),
        request(
          "invalid-dynamic",
          "spark.dynamicAllocation.enabled" -> "true",
          "spark.dynamicAllocation.minExecutors" -> "3",
          "spark.dynamicAllocation.initialExecutors" -> "2",
          "spark.dynamicAllocation.maxExecutors" -> "4"))
    }
  }
}
