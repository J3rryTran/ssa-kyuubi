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

package org.apache.kyuubi.server.engineprofile

import java.nio.file.Files

import scala.collection.JavaConverters._

import org.apache.kyuubi.{KyuubiException, KyuubiFunSuite}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.ha.HighAvailabilityConf.{HA_ADDRESSES, HA_NAMESPACE}
import org.apache.kyuubi.server.notebook.NotebookConf.{ENGINE_PROFILE_STORE_FS_PROBE_ENABLED, ENGINE_PROFILE_STORE_FS_ROOT}
import org.apache.kyuubi.server.notebook.api.EngineProfile
import org.apache.kyuubi.zookeeper.{EmbeddedZookeeper, ZookeeperConf}

class S3EngineProfileStoreSuite extends KyuubiFunSuite {

  private val zkServer = new EmbeddedZookeeper()

  override def beforeAll(): Unit = {
    super.beforeAll()
    val conf = new KyuubiConf(false).set(ZookeeperConf.ZK_CLIENT_PORT, 0)
    zkServer.initialize(conf)
    zkServer.start()
  }

  override def afterAll(): Unit = {
    zkServer.stop()
    super.afterAll()
  }

  private def store(root: java.nio.file.Path, namespace: String): S3EngineProfileStore = {
    val conf = new KyuubiConf(false)
      .set(HA_ADDRESSES, zkServer.getConnectString)
      .set(HA_NAMESPACE, namespace)
      .set(ENGINE_PROFILE_STORE_FS_ROOT, root.toUri.toString)
      .set(ENGINE_PROFILE_STORE_FS_PROBE_ENABLED, false)
    S3EngineProfileStore.create(conf)
  }

  private def profile(
      id: String,
      owner: String = "alice",
      name: String = "heavy",
      generation: Long = 1L): EngineProfile =
    EngineProfile(
      profileId = id,
      name = name,
      subdomain = s"ep-$id",
      owner = owner,
      sparkConfig = Map("spark.driver.memory" -> "2g"),
      notebookRuntimeIdleTimeout = Some("PT10M"),
      engineIdleTimeout = Some("PT30M"),
      createdAt = 1L,
      updatedAt = 1L,
      version = generation)

  test("stores every profile of one owner in one shared document") {
    val root = Files.createTempDirectory("kyuubi-s3-engine-profile")
    val first = store(root, "s3-store-one")
    val second = store(root, "s3-store-one")
    try {
      first.initialize()
      second.initialize()
      val created = first.create(profile("one"))
      val secondCreated = first.create(profile("two", name = "light"))

      assert(second.list("alice").map(_.profileId).toSet === Set("one", "two"))
      val files = Files.list(root)
      try {
        assert(files.iterator().asScala.count(_.getFileName.toString.endsWith(".json")) === 1)
      } finally files.close()

      val updated = second.update(
        secondCreated.copy(sparkConfig = Map("spark.driver.memory" -> "8g")),
        secondCreated.version)
      assert(updated.exists(_.sparkConfig("spark.driver.memory") === "8g"))
      assert(second.getOwned(created.profileId, "alice").exists(_.profileId == created.profileId))
    } finally {
      first.close()
      second.close()
    }
  }

  test("rejects duplicate owner name while allowing another owner") {
    val root = Files.createTempDirectory("kyuubi-s3-engine-profile-owner")
    val engineProfiles = store(root, "s3-store-owner")
    try {
      engineProfiles.initialize()
      engineProfiles.create(profile("one"))
      intercept[KyuubiException] {
        engineProfiles.create(profile("two"))
      }
      engineProfiles.create(profile("two", owner = "bob"))
      assert(engineProfiles.list("bob").map(_.profileId) === Seq("two"))
    } finally engineProfiles.close()
  }
}
