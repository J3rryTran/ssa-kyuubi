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

import org.apache.kyuubi.{KyuubiException, KyuubiFunSuite}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_ADDRESSES
import org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStoreConf._
import org.apache.kyuubi.server.notebook.NotebookConf._

class ManagedComputeDeploymentValidatorSuite extends KyuubiFunSuite {

  private def managedConf: KyuubiConf = new KyuubiConf(false)
    .set(COMPUTE_MANAGED_HA_REQUIRED, true)
    .set(COMPUTE_LIFECYCLE_ENABLED, true)
    .set(DBT_MANAGED_CLI_ENABLED, true)
    .set(HA_ADDRESSES, "zookeeper:2181")

  test("managed HA jobs reject a replica-local SQLite metadata store") {
    val error = intercept[KyuubiException] {
      ManagedComputeDeploymentValidator.validate(
        managedConf.set(METADATA_STORE_JDBC_DATABASE_TYPE, "SQLITE"))
    }
    assert(error.getMessage.contains("shared MySQL or PostgreSQL"))
  }

  test("managed HA jobs require ZooKeeper and durable lifecycle reconciliation") {
    val conf = managedConf
      .set(METADATA_STORE_JDBC_DATABASE_TYPE, "POSTGRESQL")
      .set(HA_ADDRESSES, "")
      .set(COMPUTE_LIFECYCLE_ENABLED, false)
    val error = intercept[KyuubiException] {
      ManagedComputeDeploymentValidator.validate(conf)
    }
    assert(error.getMessage.contains("kyuubi.ha.addresses"))
    assert(error.getMessage.contains("kyuubi.compute.lifecycle.enabled=true"))
  }

  test("managed HA jobs accept shared metadata and coordination") {
    ManagedComputeDeploymentValidator.validate(
      managedConf.set(METADATA_STORE_JDBC_DATABASE_TYPE, "POSTGRESQL"))
  }
}
