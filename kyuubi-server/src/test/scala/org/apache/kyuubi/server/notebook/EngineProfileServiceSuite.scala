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

import org.apache.kyuubi.server.engineprofile._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.service.{NotebookJson, NotebookPrincipal}

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

  test("default resources expose only explicitly configured launch values") {
    val defaults = Map(
      "spark.driver.memory" -> "1g",
      "spark.executor.memory" -> "2g",
      "spark.executor.instances" -> "1",
      "spark.hadoop.fs.s3a.secret.key" -> "not-for-the-browser")
    val serverSettings = Map(
      "spark.driver.memory" -> "4g",
      "spark.executor.cores" -> "2",
      "spark.hadoop.fs.s3a.access.key" -> "also-secret")

    assert(EngineProfileService.defaultResourceConfig(defaults, serverSettings) === Map(
      "spark.driver.memory" -> "4g",
      "spark.executor.memory" -> "2g",
      "spark.executor.cores" -> "2",
      "spark.executor.instances" -> "1"))
  }

  test("engine profile store persists one current configuration without a public generation") {
    val created = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("revisionless-store-profile", "spark.driver.memory" -> "2g"))
    val winner = manager.engineProfiles.update(
      created.profileId,
      profilePrincipal(alice),
      request("revisionless-store-profile", "spark.driver.memory" -> "4g"))

    assert(winner.sparkConfig === Map("spark.driver.memory" -> "4g"))
    val profileJson = NotebookJson.mapper.valueToTree[com.fasterxml.jackson.databind.JsonNode](
      EngineProfileView(winner))
    assert(!profileJson.has("revision"))
    assert(!profileJson.has("version"))
    val statusJson = NotebookJson.mapper.valueToTree[com.fasterxml.jackson.databind.JsonNode](
      EngineProfileEngineStatusView(winner.profileId, "STOPPED", 0))
    assert(!statusJson.has("revision"))
    assert(!statusJson.has("version"))
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

  test("profile update keeps a stable engine subdomain") {
    val created = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("heavy", "spark.driver.memory" -> "2g"))
    val updated = manager.engineProfiles.update(
      created.profileId,
      profilePrincipal(alice),
      request("heavy", "spark.driver.memory" -> "4g"))

    assert(updated.profileId === created.profileId)
    assert(updated.name === created.name)
    assert(updated.version === 2L)
    assert(updated.subdomain === created.subdomain)
    assert(updated.sparkConfig("spark.driver.memory") === "4g")
  }

  test("profile update is rejected while a workload guard reports active compute") {
    val created = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("must-stop-before-update", "spark.driver.memory" -> "2g"))
    manager.engineProfiles.onBeforeUpdate { (_, profile) =>
      if (profile.profileId == created.profileId) {
        throw NotebookException.invalid(
          s"stop engine profile ${profile.profileId} before changing its configuration")
      }
    }

    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.engineProfiles.update(
        created.profileId,
        profilePrincipal(alice),
        request("must-stop-before-update", "spark.driver.memory" -> "4g"))
    }
    assert(manager.engineProfiles.getOwned(created.profileId, profilePrincipal(alice)) === created)
  }

  test("profile updates retain omitted Spark settings and idle timeouts") {
    val initial = request(
      "inherited-revision",
      "spark.driver.memory" -> "2g",
      "spark.executor.memory" -> "4g")
    initial.setNotebookRuntimeIdleTimeout("PT10M")
    initial.setEngineIdleTimeout("PT30M")
    val created = manager.engineProfiles.create(profilePrincipal(alice), initial)

    val updated = manager.engineProfiles.update(
      created.profileId,
      profilePrincipal(alice),
      new UpsertEngineProfileRequest)
    assert(updated.version === 2L)
    assert(updated.sparkConfig === created.sparkConfig)
    assert(updated.notebookRuntimeIdleTimeout === created.notebookRuntimeIdleTimeout)
    assert(updated.engineIdleTimeout === created.engineIdleTimeout)

    val replacement = request("inherited-revision", "spark.driver.memory" -> "8g")
    val replaced = manager.engineProfiles.update(
      created.profileId,
      profilePrincipal(alice),
      replacement)
    assert(replaced.sparkConfig === Map("spark.driver.memory" -> "8g"))
    assert(replaced.notebookRuntimeIdleTimeout.contains("PT10M"))
    assert(replaced.engineIdleTimeout.contains("PT30M"))
  }

  test("Python environment publication retains profile resources and idle timeouts") {
    val initial = request("python-revision", "spark.executor.memory" -> "4g")
    initial.setNotebookRuntimeIdleTimeout("PT15M")
    initial.setEngineIdleTimeout("PT45M")
    val created = manager.engineProfiles.create(profilePrincipal(alice), initial)

    val published = manager.engineProfiles.publishPythonEnvironment(created.profileId, "env-1")
    assert(published.version === 2L)
    assert(published.subdomain === created.subdomain)
    assert(published.sparkConfig === created.sparkConfig)
    assert(published.notebookRuntimeIdleTimeout === created.notebookRuntimeIdleTimeout)
    assert(published.engineIdleTimeout === created.engineIdleTimeout)
    assert(published.pythonEnvironmentRevisionId.contains("env-1"))
  }

  test("only one replica can claim collecting Python environment requests") {
    val createdProfile: EngineProfile = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("atomic-python-environment", "spark.driver.memory" -> "1g"))
    val now = System.currentTimeMillis()
    val changeRequest = PythonEnvironmentChangeRequest(
      id = "atomic-python-request",
      profileId = createdProfile.profileId,
      requestedBy = alice.user,
      operation = PythonEnvironmentOperation.INSTALL,
      requestedPackages = Seq("seaborn"),
      expectedProfileVersion = createdProfile.version,
      state = PythonEnvironmentState.COLLECTING,
      hotInstallState = "SUCCEEDED",
      resultingEnvironmentRevisionId = None,
      errorSummary = None,
      createdAt = now,
      updatedAt = now)
    manager.store.createPythonEnvironmentChangeRequest(changeRequest)

    assert(manager.store.claimCollectingPythonEnvironmentChangeRequests(
      createdProfile.profileId,
      "environment-winner",
      now + 1))
    assert(!manager.store.claimCollectingPythonEnvironmentChangeRequests(
      createdProfile.profileId,
      "environment-loser",
      now + 2))

    val claimed = manager.store.getPythonEnvironmentChangeRequest(changeRequest.id).get
    assert(claimed.state === PythonEnvironmentState.PENDING)
    assert(claimed.resultingEnvironmentRevisionId.contains("environment-winner"))
  }

  test("built-in default profile uses server Spark defaults without a persisted row") {
    val snapshot = manager.engineProfiles.snapshotForUse("default", profilePrincipal(alice))

    assert(snapshot.profileId === "default")
    assert(snapshot.owner === alice.user)
    assert(snapshot.subdomain === "default")
    assert(snapshot.sparkConfig.isEmpty)
    assert(snapshot.notebookRuntimeIdleTimeout.isEmpty)
    assert(snapshot.engineIdleTimeout.isEmpty)
    assert(org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot
      .clientSessionCleanupConfig(
        snapshot,
        1800000L)("kyuubi.session.idle.timeout") === "1800000")
    assert(org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot
      .clientSessionCleanupConfig(
        snapshot,
        1800000L)("kyuubi.operation.idle.timeout") === "1800000")
  }

  test("profile snapshots preserve validated idle timeout overrides") {
    val created = manager.engineProfiles.create(
      profilePrincipal(alice),
      requestWithTimeouts("timed", "PT5M", "PT30M"))
    val snapshot = manager.engineProfiles.snapshotForUse(created.profileId, profilePrincipal(alice))

    assert(snapshot.notebookRuntimeIdleTimeout.contains("PT5M"))
    assert(snapshot.engineIdleTimeout.contains("PT30M"))
    assert(org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot
      .engineSessionConfig(snapshot)("kyuubi.session.engine.idle.timeout") === "1800000")
    assert(org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot
      .engineSessionConfig(snapshot)("kyuubi.operation.idle.timeout") === "1800000")
    assert(org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot
      .notebookSessionConfig(snapshot)("kyuubi.session.idle.timeout") === "300000")
    assert(org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot
      .clientSessionCleanupConfig(
        snapshot,
        60000L)("kyuubi.session.idle.timeout") === "1800000")

    val updated = manager.engineProfiles.update(
      created.profileId,
      profilePrincipal(alice),
      requestWithTimeouts("timed", "inherit", "PT1H"))
    assert(updated.notebookRuntimeIdleTimeout.isEmpty)
    assert(updated.engineIdleTimeout.contains("PT1H"))
    assert(updated.version === 2L)
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

  test("profile deletion succeeds when it has no Python environment") {
    val profile = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("persistent-delete", "spark.driver.memory" -> "2g"))

    manager.engineProfiles.delete(profile.profileId, profilePrincipal(alice))

    interceptNotebook(NotebookErrorCode.NOTEBOOK_NOT_FOUND) {
      manager.engineProfiles.getOwned(profile.profileId, profilePrincipal(alice))
    }
  }

  test("profile cannot be deleted during a pending Python environment change") {
    val profile = manager.engineProfiles.create(
      profilePrincipal(alice),
      request("pending-delete", "spark.driver.memory" -> "2g"))
    val now = System.currentTimeMillis()
    manager.store.createPythonEnvironmentChangeRequest(PythonEnvironmentChangeRequest(
      id = "pending-delete-test",
      profileId = profile.profileId,
      requestedBy = alice.user,
      operation = "INSTALL",
      requestedPackages = Seq("seaborn"),
      expectedProfileVersion = profile.version,
      state = PythonEnvironmentState.COLLECTING,
      hotInstallState = "SUCCEEDED",
      resultingEnvironmentRevisionId = None,
      errorSummary = None,
      createdAt = now,
      updatedAt = now))

    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      manager.engineProfiles.delete(profile.profileId, profilePrincipal(alice))
    }
    assert(manager.engineProfiles.getOwned(profile.profileId, profilePrincipal(alice)) === profile)
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
