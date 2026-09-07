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

  val NOTEBOOK_IMPORT_MAX_SIZE: ConfigEntry[Long] =
    buildConf("kyuubi.notebook.import.max.size")
      .doc("Maximum size in bytes of a notebook document accepted by the import endpoint.")
      .version("1.10.3")
      .serverOnly
      .longConf
      .createWithDefault(16 * 1024 * 1024)
}
