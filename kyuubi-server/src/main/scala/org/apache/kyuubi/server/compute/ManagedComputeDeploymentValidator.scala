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

import java.util.concurrent.TimeUnit

import scala.collection.mutable.ArrayBuffer

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_ADDRESSES
import org.apache.kyuubi.server.metadata.jdbc.DatabaseType.{MYSQL, POSTGRESQL}
import org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStoreConf._
import org.apache.kyuubi.server.notebook.NotebookConf._

/** Startup validation for the explicit multi-replica managed workload mode. */
object ManagedComputeDeploymentValidator {

  def validate(conf: KyuubiConf): Unit = {
    val problems = ArrayBuffer.empty[String]
    if (conf.get(COMPUTE_LIFECYCLE_CLEANUP_RETRY_INITIAL_BACKOFF) >
        conf.get(COMPUTE_LIFECYCLE_CLEANUP_RETRY_MAX_BACKOFF)) {
      problems += "cleanup retry initial-backoff must not exceed max-backoff"
    }
    if (conf.get(COMPUTE_LIFECYCLE_ENABLED) &&
      conf.get(COMPUTE_LIFECYCLE_RECONCILE_INTERVAL) >=
        conf.get(COMPUTE_LIFECYCLE_CLAIM_TTL)) {
      problems += "compute lifecycle reconcile interval must be shorter than its claim TTL"
    }
    if (conf.get(DBT_MANAGED_CLI_ENABLED) &&
      conf.get(DBT_MANAGED_CLI_HEARTBEAT_INTERVAL) >=
        conf.get(COMPUTE_JOB_HEARTBEAT_TIMEOUT)) {
      problems += "managed DBT CLI heartbeat interval must be shorter than heartbeat timeout"
    }
    val bindingUpperBound = conf.get(COMPUTE_JOB_TIMEOUT) + TimeUnit.MINUTES.toMillis(5)
    if (conf.get(COMPUTE_BINDING_TTL) > bindingUpperBound) {
      problems += "compute binding TTL must not exceed job timeout plus five minutes"
    }

    val managedJobsEnabled = conf.get(DBT_RUNNER_ENABLED) ||
      conf.get(DBT_MANAGED_CLI_ENABLED) || conf.get(NOTEBOOK_JOB_RUNNER_ENABLED)
    if (conf.get(COMPUTE_MANAGED_HA_REQUIRED) && managedJobsEnabled) {
      if (!Set(MYSQL.toString, POSTGRESQL.toString).contains(
          conf.get(METADATA_STORE_JDBC_DATABASE_TYPE))) {
        problems += "managed HA jobs require a shared MySQL or PostgreSQL metadata store"
      }
      if (conf.get(HA_ADDRESSES).trim.isEmpty) {
        problems += "managed HA jobs require kyuubi.ha.addresses for ZooKeeper coordination"
      }
      if (!conf.get(COMPUTE_LIFECYCLE_ENABLED)) {
        problems += "managed HA jobs require kyuubi.compute.lifecycle.enabled=true"
      }
      if (conf.get(NOTEBOOK_JOB_RUNNER_ENABLED) &&
        conf.get(NOTEBOOK_JOB_RUN_TIMEOUT) > conf.get(COMPUTE_JOB_TIMEOUT)) {
        problems += "Notebook job run timeout must not exceed the compute job timeout"
      }
      if (conf.get(DBT_RUNNER_ENABLED) &&
        conf.get(DBT_RUNNER_KUBERNETES_JOB_TIMEOUT) > conf.get(COMPUTE_JOB_TIMEOUT)) {
        problems += "DBT runner Job timeout must not exceed the compute job timeout"
      }
    }

    if (problems.nonEmpty) {
      throw new KyuubiException(
        s"Invalid managed compute deployment configuration: ${problems.mkString("; ")}")
    }
  }
}
