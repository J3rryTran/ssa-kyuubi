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

  val ENGINE_PROFILE_STORE_FS_ROOT: OptionalConfigEntry[String] =
    buildConf("kyuubi.engine.profile.store.fs.root")
      .doc("Shared filesystem root for current owner Engine Profile JSON documents. This can be " +
        "an `s3a://` URI for SeaweedFS or another S3-compatible store. Runtime state, DBT run " +
        "pins and Python environment build state remain in the JDBC metadata store.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createOptional

  val ENGINE_PROFILE_STORE_CLASS: ConfigEntry[String] =
    buildConf("kyuubi.engine.profile.store.class")
      .doc("Authoritative Engine Profile configuration store. The S3 store keeps one owner JSON " +
        "document under `kyuubi.engine.profile.store.fs.root` and uses ZooKeeper to coordinate " +
        "writes across Kyuubi replicas.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .createWithDefault("org.apache.kyuubi.server.engineprofile.S3EngineProfileStore")

  val ENGINE_PROFILE_STORE_ZK_LOCK_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.engine.profile.store.zk.lock.timeout")
      .doc("Maximum time to acquire the per-owner ZooKeeper mutex before an Engine Profile " +
        "write fails. The lock protects one owner JSON document from concurrent Kyuubi replicas.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.SECONDS.toMillis(30))

  val ENGINE_PROFILE_STORE_FS_PROBE_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.engine.profile.store.fs.probe.enabled")
      .doc("Whether startup verifies read, create, list and delete access to the Engine Profile " +
        "filesystem root. Enable this for S3A/SeaweedFS deployments so a missing Hadoop AWS " +
        "connector, credential or bucket permission fails before a profile is accepted.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(true)

  val COMPUTE_ALLOCATION_ZK_LOCK_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.compute.allocation.zk.lock.timeout")
      .doc("Maximum time to acquire the global JOB allocation reservation lock in ZooKeeper. " +
        "The lock serializes quota checks and durable reservation across Kyuubi replicas.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.SECONDS.toMillis(30))

  val COMPUTE_JOB_MAX_CONCURRENT_PER_USER: ConfigEntry[Int] =
    buildConf("kyuubi.compute.job.max.concurrent.per-user")
      .doc("Maximum number of open dedicated JOB allocations for one effective user.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(2)

  val COMPUTE_JOB_MAX_CONCURRENT_GLOBAL: ConfigEntry[Int] =
    buildConf("kyuubi.compute.job.max.concurrent.global")
      .doc("Maximum number of open dedicated JOB allocations across the deployment.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(10)

  val COMPUTE_BINDING_TRUSTED_RUNNER_USERS: ConfigEntry[Set[String]] =
    buildConf("kyuubi.compute.binding.trusted.runner.users")
      .doc("Authenticated service identities allowed to attach managed JOB sessions. " +
        "An empty set disables managed binding issuance and attach.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .toSet()
      .createWithDefault(Set.empty)

  val COMPUTE_BINDING_TTL: ConfigEntry[Long] =
    buildConf("kyuubi.compute.binding.ttl")
      .doc("Lifetime of a scoped managed-runner credential. It must exceed the configured " +
        "managed runner timeout; completion and cancellation revoke it immediately.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.MINUTES.toMillis(65))

  val COMPUTE_ATTACH_RESERVATION_TTL: ConfigEntry[Long] =
    buildConf("kyuubi.compute.attach.reservation.ttl")
      .doc("Maximum time between authorizing a managed attach and confirming OpenSession.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.MINUTES.toMillis(2))

  val COMPUTE_LIFECYCLE_RECONCILE_INTERVAL: ConfigEntry[Long] =
    buildConf("kyuubi.compute.lifecycle.reconcile.interval")
      .doc("Interval between durable compute lifecycle reconciliation passes.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.SECONDS.toMillis(5))

  val COMPUTE_LIFECYCLE_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.compute.lifecycle.enabled")
      .doc("Whether this Kyuubi replica participates in durable compute lifecycle " +
        "reconciliation. It is disabled by default until the allocation schema and all " +
        "replicas have been upgraded.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val COMPUTE_LIFECYCLE_CLAIM_TTL: ConfigEntry[Long] =
    buildConf("kyuubi.compute.lifecycle.claim.ttl")
      .doc("Lease held by one Kyuubi replica while reconciling a compute allocation. " +
        "An expired lease may be taken over by another replica.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.SECONDS.toMillis(30))

  val COMPUTE_LIFECYCLE_BATCH_SIZE: ConfigEntry[Int] =
    buildConf("kyuubi.compute.lifecycle.batch.size")
      .doc("Maximum number of newly claimable compute allocations handled in one pass.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(100)

  val COMPUTE_LIFECYCLE_LATE_LAUNCH_GRACE: ConfigEntry[Long] =
    buildConf("kyuubi.compute.lifecycle.late-launch.grace")
      .doc("Minimum time a releasing allocation without an engine reference remains visible. " +
        "This fences an OpenSession that was already in flight when cancellation won.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.MINUTES.toMillis(2))

  val COMPUTE_LIFECYCLE_CLEANUP_RETRY_INITIAL_BACKOFF: ConfigEntry[Long] =
    buildConf("kyuubi.compute.lifecycle.cleanup.retry.initial-backoff")
      .doc("Initial delay before retrying a failed compute cleanup. Retry is idempotent and " +
        "never re-runs the workload.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.SECONDS.toMillis(1))

  val COMPUTE_LIFECYCLE_CLEANUP_RETRY_MAX_BACKOFF: ConfigEntry[Long] =
    buildConf("kyuubi.compute.lifecycle.cleanup.retry.max-backoff")
      .doc("Maximum delay between failed compute cleanup attempts.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.MINUTES.toMillis(1))

  val COMPUTE_LIFECYCLE_CLEANUP_DEADLINE: ConfigEntry[Long] =
    buildConf("kyuubi.compute.lifecycle.cleanup.deadline")
      .doc("Operational deadline after cleanup is requested. Passing this deadline does not " +
        "delete metadata or stop retries; status and logs mark the cleanup overdue.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.MINUTES.toMillis(5))

  val COMPUTE_METADATA_MIN_RETENTION: ConfigEntry[Long] =
    buildConf("kyuubi.compute.metadata.min.retention")
      .doc("Minimum retention for terminal allocation metadata. Open or cleanup-pending " +
        "allocations must never be removed by retention processing.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(
        _ >= java.util.concurrent.TimeUnit.HOURS.toMillis(24),
        "must be at least 24 hours")
      .createWithDefault(java.util.concurrent.TimeUnit.HOURS.toMillis(24))

  val COMPUTE_MANAGED_HA_REQUIRED: ConfigEntry[Boolean] =
    buildConf("kyuubi.compute.managed.ha.required")
      .doc("Whether enabled managed DBT/Notebook jobs must use shared MySQL/PostgreSQL " +
        "metadata, ZooKeeper coordination and the durable compute lifecycle controller. " +
        "Enable this for multi-replica deployments; leave it false only for local tests.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val COMPUTE_ENGINE_REGISTRATION_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.compute.engine.registration.timeout")
      .doc("Maximum time a STARTING allocation may remain without a discoverable engine. " +
        "Expiration creates cleanup intent; it never marks the allocation released directly.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.SECONDS.toMillis(60))

  val COMPUTE_JOB_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.compute.job.timeout")
      .doc("Server-owned wall-clock deadline for a managed JOB allocation.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.HOURS.toMillis(1))

  val COMPUTE_JOB_HEARTBEAT_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.compute.job.heartbeat.timeout")
      .doc("Maximum silence from a managed JOB attempt before cleanup is requested. " +
        "This timeout is independent of client session and engine idle timeouts.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.MINUTES.toMillis(2))

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
        "keep runtimes which inherit this platform default until they are stopped explicitly; " +
        "an Engine Profile may still provide its own positive runtime timeout.")
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

  val NOTEBOOK_JOB_RUNNER_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.notebook.job.runner.enabled")
      .doc("Whether the authenticated run-now API may execute a pinned Notebook schedule on " +
        "dedicated JOB compute. This does not enable cron scheduling.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val NOTEBOOK_JOB_RUNNER_THREADS: ConfigEntry[Int] =
    buildConf("kyuubi.notebook.job.runner.threads")
      .doc("Maximum number of pinned Notebook occurrences executed concurrently by one server.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(4)

  val NOTEBOOK_JOB_POLL_INTERVAL: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.job.poll.interval")
      .doc("How often the headless Notebook runner polls its current cell and cancellation state.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(250L)

  val NOTEBOOK_JOB_RUN_TIMEOUT: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.job.run.timeout")
      .doc("Maximum elapsed time for one headless scheduled Notebook occurrence. A timeout " +
        "cancels the current cell, fails the run and requests JOB compute cleanup.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.HOURS.toMillis(4))

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

  val PYTHON_ENVIRONMENT_PVC_ACCESS_MODE: ConfigEntry[String] =
    buildConf("kyuubi.notebook.python.environment.pvc.access-mode")
      .doc(
        "Kubernetes access mode requested when creating a profile Python environment PVC. " +
          "ReadWriteOnce constrains all consumers to one node; use an RWX-capable " +
          "StorageClass with ReadWriteMany when Driver and Executor pods may span nodes. " +
          "Changing this setting never mutates or deletes an existing PVC.")
      .version("1.10.3")
      .serverOnly
      .stringConf
      .checkValues(Set("ReadWriteOnce", "ReadWriteMany"))
      .createWithDefault("ReadWriteOnce")

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

  val DBT_MANAGED_CLI_ENABLED: ConfigEntry[Boolean] =
    buildConf("kyuubi.dbt.managed.cli.enabled")
      .doc("Whether an authenticated caller may prepare a local DBT CLI process as a managed " +
        "SCHEDULE-triggered JOB allocation. This test and integration path is disabled by " +
        "default and does not start a Kubernetes runner Job.")
      .version("1.10.3")
      .serverOnly
      .booleanConf
      .createWithDefault(false)

  val DBT_MANAGED_CLI_HEARTBEAT_INTERVAL: ConfigEntry[Long] =
    buildConf("kyuubi.dbt.managed.cli.heartbeat.interval")
      .doc("Heartbeat interval returned to the local managed DBT CLI wrapper. It must remain " +
        "shorter than the managed JOB heartbeat timeout.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(java.util.concurrent.TimeUnit.SECONDS.toMillis(10))

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

  val DBT_RUNNER_THREADS: ConfigEntry[Int] =
    buildConf("kyuubi.dbt.runner.threads")
      .doc("Number of dbt model threads assigned by the server to a managed runner. " +
        "The browser and project cannot override this value.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(2)

  val DBT_RUNNER_THREADS_MAX: ConfigEntry[Int] =
    buildConf("kyuubi.dbt.runner.threads.max")
      .doc("Upper bound for the server-controlled dbt thread count of a managed runner.")
      .version("1.10.3")
      .serverOnly
      .intConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(4)

  val DBT_RUNNER_RECONCILE_INTERVAL: ConfigEntry[Long] =
    buildConf("kyuubi.dbt.runner.reconcile.interval")
      .doc("How often the server reconciles managed DBT runs, including Kubernetes runner " +
        "state and orphaned local CLI attempts.")
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

  val DBT_RUNNER_ARTIFACT_MAX_BYTES: ConfigEntry[Long] =
    buildConf("kyuubi.dbt.runner.artifact.max.bytes")
      .doc("Maximum decoded size of one managed DBT artifact persisted before runner Job TTL.")
      .version("1.10.3")
      .serverOnly
      .longConf
      .checkValue(_ > 0, "must be positive")
      .createWithDefault(16L * 1024 * 1024)

  val DBT_ENGINE_PROFILE_RUN_SNAPSHOT_TTL: ConfigEntry[Long] =
    buildConf("kyuubi.dbt.engine-profile.run.snapshot.ttl")
      .doc("Retention lease for the Engine Profile version leased to one DBT CLI run.")
      .version("1.10.3")
      .serverOnly
      .timeConf
      .createWithDefault(java.util.concurrent.TimeUnit.HOURS.toMillis(24))

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
