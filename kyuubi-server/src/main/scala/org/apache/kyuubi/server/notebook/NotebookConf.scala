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

import org.apache.kyuubi.config.{ConfigEntry, OptionalConfigEntry}
import org.apache.kyuubi.config.KyuubiConf.buildConf

object NotebookConf {

  val NOTEBOOK_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.enabled")
      .doc("Whether to enable the notebook subsystem and its REST endpoints. The notebook " +
        "stores its state in the same JDBC database as the server metadata store, configured " +
        "by `kyuubi.metadata.store.jdbc.*`.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val NOTEBOOK_PROXY_INTERNAL_SECRET: OptionalConfigEntry[String] =
    buildConf("kyuubi.notebook.proxy.internal.secret")
      .doc("Shared secret that lets one server instance forward an already authenticated " +
        "notebook request to the instance owning the session, carrying the caller's identity " +
        "with it. Every instance must be given the same value. When it is unset, forwarding " +
        "still happens but only the original `Authorization` header is carried over, so a " +
        "request authenticated by the Web UI's session cookie cannot cross instances - the " +
        "cookie is only known to the instance that issued it.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createOptional

  val NOTEBOOK_STORE_CLASS: ConfigEntry[String] =
    buildConf("kyuubi.notebook.store.class")
      .doc("Implementation of `NotebookStore`. The default keeps every existing deployment " +
        "unchanged by storing notebooks in the JDBC metadata database. " +
        "`org.apache.kyuubi.server.notebook.store.FileSystemNotebookStore` keeps notebook " +
        "documents on a shared filesystem instead, which is what more than one server replica " +
        "needs: a SQLite file on one pod's disk cannot be read by another.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("org.apache.kyuubi.server.notebook.store.JDBCNotebookStore")

  val NOTEBOOK_STORE_FS_ROOT: ConfigEntry[String] =
    buildConf("kyuubi.notebook.store.fs.root")
      .doc("Root directory of the filesystem notebook store, for example " +
        "`hdfs:///user/kyuubi/notebooks`. It is created when missing. A path without a scheme " +
        "is resolved against the Hadoop `fs.defaultFS` the server already reads from core-site.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("notebooks")

  val NOTEBOOK_STORE_FS_LIST_CACHE_TTL: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.store.fs.list.cache.ttl")
      .doc("How long a directory listing read from the shared filesystem may be reused. A pod " +
        "drops its own cache as soon as it writes, so this only bounds how stale a change made " +
        "by another pod can look.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(5000L)

  val NOTEBOOK_STORE_FS_MIGRATE: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.store.fs.migrate.from.jdbc")
      .doc("Whether to copy notebooks out of the JDBC store into the filesystem store at " +
        "start-up when the filesystem store is still empty. It runs once and never deletes " +
        "anything from the JDBC store, so it is safe to leave on.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val NOTEBOOK_SCHEMA_INIT: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.store.schema.init")
      .doc("Whether to create the notebook tables at startup if they are missing.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val NOTEBOOK_CELL_SOURCE_MAX_SIZE: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.cell.source.max.size")
      .doc("Maximum size in bytes of a single cell source. Requests exceeding it are rejected.")
      .version("1.10.3")
      .serverOnly
      .longConf
      .createWithDefault(1024 * 1024)

  val NOTEBOOK_MAX_CELLS: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.max.cells")
      .doc("Maximum number of cells a single notebook may contain.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(500)

  val NOTEBOOK_MAX_PAGE_SIZE: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.max.page.size")
      .doc("Upper bound for the `limit` parameter of notebook list and search endpoints.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(200)

  val NOTEBOOK_AUTO_REVISION_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.revision.auto.enabled")
      .doc("Whether to snapshot a notebook automatically when its content changes.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val NOTEBOOK_MAX_REVISIONS: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.revision.max.per.notebook")
      .doc("How many revisions to retain per notebook. The oldest unprotected revisions are " +
        "trimmed beyond this count; revisions created by a restore are protected and kept.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(100)

  val NOTEBOOK_RUNTIME_IDLE_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.runtime.idle.timeout")
      .doc("How long a runtime may sit idle before it is stopped and its resources released. " +
        "A session whose runtimes have all been reclaimed is stopped with them. Set to 0 to " +
        "keep runtimes until they are stopped explicitly.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.HOURS.toMillis(1))

  val NOTEBOOK_RUNTIME_IDLE_CHECK_INTERVAL: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.runtime.idle.check.interval")
      .doc("How often idle runtimes are looked for.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.MINUTES.toMillis(5))

  val NOTEBOOK_EXECUTION_LOG_MAX_LINES: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.execution.log.max.lines")
      .doc("Maximum number of log lines returned by one execution log request.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(1000)

  val NOTEBOOK_EVENT_MAX_WAIT_MS: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.execution.event.max.wait.ms")
      .doc("Upper bound for the `waitMillis` long-polling parameter of the execution event " +
        "endpoint. It caps how long a request may occupy a server thread.")
      .version("1.10.3")
      .serverOnly
      .longConf
      .createWithDefault(10000L)

  val PYTHON_ENVIRONMENT_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.python.environment.enabled")
      .doc("Whether Engine Profile Python environments may be mounted into Spark Driver and " +
        "Executor pods. It is disabled by default; the server is the sole source of PVC and " +
        "Python executable settings.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val PYTHON_ENVIRONMENT_MOUNT_PATH: ConfigEntry[String] =
    buildConf("kyuubi.notebook.python.environment.mount.path")
      .doc("Absolute path where a READY Engine Profile environment PVC is mounted read-only.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("/python-env")

  val PYTHON_ENVIRONMENT_BASE_IMAGE: ConfigEntry[String] =
    buildConf("kyuubi.notebook.python.environment.base-image")
      .doc("Pinned Spark Python image identity used to reject incompatible persistent " +
        "environments. This value is server controlled, not a browser parameter.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("")

  val PYTHON_ENVIRONMENT_KUBERNETES_NAMESPACE: ConfigEntry[String] =
    buildConf("kyuubi.notebook.python.environment.kubernetes.namespace")
      .doc("Namespace where the server provisions profile PVCs and environment builder Jobs.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("default")

  val PYTHON_ENVIRONMENT_KUBERNETES_BUILDER_IMAGE: ConfigEntry[String] =
    buildConf("kyuubi.notebook.python.environment.kubernetes.builder.image")
      .doc("Pinned image containing the trusted Python environment builder entrypoint.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("")

  val PYTHON_ENVIRONMENT_KUBERNETES_SERVICE_ACCOUNT: ConfigEntry[String] =
    buildConf("kyuubi.notebook.python.environment.kubernetes.service-account")
      .doc("ServiceAccount assigned to Python environment builder Jobs.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("default")

  val PYTHON_ENVIRONMENT_PVC_STORAGE_CLASS: ConfigEntry[String] =
    buildConf("kyuubi.notebook.python.environment.pvc.storage-class")
      .doc(
        "StorageClass for profile-scoped environment PVCs. " +
          "Empty delegates selection to Kubernetes.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("")

  val PYTHON_ENVIRONMENT_PVC_SIZE: ConfigEntry[String] =
    buildConf("kyuubi.notebook.python.environment.pvc.size")
      .doc("PersistentVolumeClaim size for one Engine Profile Python environment store.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("10Gi")

  val PYTHON_ENVIRONMENT_RECONCILE_INTERVAL: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.python.environment.reconcile.interval")
      .doc("How often Kyuubi reconnects persisted Python environment build requests to Jobs.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.SECONDS.toMillis(10))

  val DBT_RUNNER_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.dbt.runner.enabled")
      .doc("Whether the server may dispatch DBT runs to Kubernetes Jobs. It is disabled by " +
        "default; enabling it requires a shared JDBC metadata store and a configured " +
        "Kubernetes runner image.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val DBT_RUNNER_KUBERNETES_NAMESPACE: ConfigEntry[String] =
    buildConf("kyuubi.dbt.runner.kubernetes.namespace")
      .doc("Namespace in which the DBT runner creates Jobs and ConfigMaps.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("default")

  val DBT_RUNNER_KUBERNETES_IMAGE: ConfigEntry[String] =
    buildConf("kyuubi.dbt.runner.kubernetes.image")
      .doc("Container image containing the pinned dbt-core and dbt-spark runner.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("")

  val DBT_RUNNER_KUBERNETES_SERVICE_ACCOUNT: ConfigEntry[String] =
    buildConf("kyuubi.dbt.runner.kubernetes.service-account")
      .doc("ServiceAccount assigned to each DBT runner Job.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("default")

  val DBT_RUNNER_KYUUBI_HOST: ConfigEntry[String] =
    buildConf("kyuubi.dbt.runner.kyuubi.host")
      .doc("Internal Kyuubi Thrift host used by DBT runner Jobs.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("")

  val DBT_RUNNER_KYUUBI_PORT: ConfigEntry[Int] =
    buildConf("kyuubi.dbt.runner.kyuubi.port")
      .doc("Internal Kyuubi Thrift port used by DBT runner Jobs.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(10009)

  val DBT_RUNNER_KYUUBI_USER: ConfigEntry[String] =
    buildConf("kyuubi.dbt.runner.kyuubi.user")
      .doc("Authenticated Kyuubi service identity used before proxy-user impersonation.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("dbt-runner")

  val DBT_RUNNER_KYUUBI_AUTH: ConfigEntry[String] =
    buildConf("kyuubi.dbt.runner.kyuubi.auth")
      .doc("dbt-spark authentication mode for the DBT runner's Kyuubi connection.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("NONE")

  val DBT_RUNNER_KYUUBI_SCHEMA: ConfigEntry[String] =
    buildConf("kyuubi.dbt.runner.kyuubi.schema")
      .doc("Default schema written into the runner-generated dbt profile.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("dbt")

  val DBT_RUNNER_RECONCILE_INTERVAL: ConfigEntry[Long] =
    buildConf("kyuubi.dbt.runner.reconcile.interval")
      .doc("How often the server refreshes Kubernetes DBT Job states.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(10000L)

  val DBT_RUNNER_KUBERNETES_JOB_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.dbt.runner.kubernetes.job.timeout")
      .doc("Maximum wall-clock duration of a DBT Kubernetes Job before Kubernetes terminates it.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.HOURS.toMillis(1))

  val DBT_RUNNER_KUBERNETES_JOB_TTL: ConfigEntry[Long] =
    buildConf("kyuubi.dbt.runner.kubernetes.job.ttl")
      .doc("How long Kubernetes retains a finished DBT Job after its logs are persisted.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.HOURS.toMillis(1))

  val DBT_RUNNER_LOG_MAX_BYTES: ConfigEntry[Long] =
    buildConf("kyuubi.dbt.runner.log.max.bytes")
      .doc("Maximum number of DBT runner log bytes persisted for one run.")
      .version("1.10.3")
      .serverOnly
      .longConf
      .createWithDefault(10L * 1024 * 1024)

  val DBT_RUNNER_LOG_PAGE_MAX_BYTES: ConfigEntry[Int] =
    buildConf("kyuubi.dbt.runner.log.page.max.bytes")
      .doc("Maximum number of persisted DBT runner log bytes returned by one API request.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .createWithDefault(256 * 1024)

  val NOTEBOOK_IMPORT_MAX_SIZE: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.import.max.size")
      .doc("Maximum size in bytes of a notebook document accepted by the import endpoint.")
      .version("1.10.3")
      .serverOnly
      .longConf
      .createWithDefault(16 * 1024 * 1024)
}
