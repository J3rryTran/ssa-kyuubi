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

import org.apache.kyuubi.server.engineprofile.{EngineProfilePrincipal, EngineProfileSessionConfigResolver}
import org.apache.kyuubi.server.notebook.api.{NotebookErrorCode, UpsertEngineProfileRequest}

class EngineProfileSessionConfigResolverSuite extends NotebookTestBase {

  private lazy val resolver = new EngineProfileSessionConfigResolver(
    manager.engineProfiles,
    manager.pythonEnvironments,
    manager.engineProfileRunSnapshots)

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

  test("uses the stable profile subdomain across DBT sessions and runs") {
    val profileId = createProfile()
    val runId = java.util.UUID.randomUUID().toString
    val first = resolver.resolve(
      alice.user,
      Map(
        EngineProfileSessionConfigResolver.ProfileIdKey -> profileId,
        EngineProfileSessionConfigResolver.RunIdKey -> runId))

    val update = new UpsertEngineProfileRequest
    update.setSparkConfig(new util.HashMap(Map(
      "spark.driver.memory" -> "6g",
      "spark.executor.memory" -> "12g",
      "spark.executor.instances" -> "3").asJava))
    manager.engineProfiles.update(
      profileId,
      EngineProfilePrincipal(alice.user, alice.admin),
      update)

    val pinned = resolver.resolve(
      alice.user,
      Map(
        EngineProfileSessionConfigResolver.ProfileIdKey -> profileId,
        EngineProfileSessionConfigResolver.RunIdKey -> runId))
    val nextRun = resolver.resolve(
      alice.user,
      Map(
        EngineProfileSessionConfigResolver.ProfileIdKey -> profileId,
        EngineProfileSessionConfigResolver.RunIdKey -> java.util.UUID.randomUUID().toString))

    assert(pinned("kyuubi.engine.share.level.subdomain") ===
      first("kyuubi.engine.share.level.subdomain"))
    assert(nextRun("kyuubi.engine.share.level.subdomain") ===
      first("kyuubi.engine.share.level.subdomain"))
  }
}
