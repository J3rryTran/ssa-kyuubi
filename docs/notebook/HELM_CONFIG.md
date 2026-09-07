<!--
- Licensed to the Apache Software Foundation (ASF) under one or more
- contributor license agreements.  See the NOTICE file distributed with
- this work for additional information regarding copyright ownership.
- The ASF licenses this file to You under the Apache License, Version 2.0
- (the "License"); you may not use this file except in compliance with
- the License.  You may obtain a copy of the License at
-
-   http://www.apache.org/licenses/LICENSE-2.0
-
- Unless required by applicable law or agreed to in writing, software
- distributed under the License is distributed on an "AS IS" BASIS,
- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
- See the License for the specific language governing permissions and
- limitations under the License.
-->

# Notebook configuration for the Helm chart

Every key below is read by name from the running code. Values are the deployment's decision; no
address, mirror or limit is baked into the source. A key that is absent is skipped, never
defaulted to something invented.

Two sides are configured separately:

- **Kyuubi server confs** go in `kyuubi-defaults.conf` (chart values for the Kyuubi container).
- **Spark confs** (`spark.kyuubi.notebook.*`) are rendered for the **engine**, and reach the
  Python worker from there. They belong with the other `spark.*` confs the chart already sets.

## 1. Kyuubi server

```properties
kyuubi.notebook.enabled=true

# Must stay true. This is what adds the `language` column to an existing `notebook` table on
# upgrade; with it off, an upgraded database is missing the column and every notebook read fails.
kyuubi.notebook.store.schema.init=true
```

Optional, all with working defaults — set only to change them:

|                      Key                      |  Default   |                  Meaning                  |
|-----------------------------------------------|------------|-------------------------------------------|
| `kyuubi.notebook.cell.source.max.size`        | `1048576`  | Bytes accepted in one cell                |
| `kyuubi.notebook.max.cells`                   | `500`      | Cells per notebook                        |
| `kyuubi.notebook.max.page.size`               | `200`      | Upper bound for `limit` on list endpoints |
| `kyuubi.notebook.revision.auto.enabled`       | `true`     | Snapshot on every content change          |
| `kyuubi.notebook.revision.max.per.notebook`   | `100`      | Revisions kept before trimming            |
| `kyuubi.notebook.runtime.idle.timeout`        | `PT1H`     | Idle runtime reclaim                      |
| `kyuubi.notebook.runtime.idle.check.interval` | `PT5M`     | How often idle runtimes are looked for    |
| `kyuubi.notebook.execution.log.max.lines`     | `1000`     | Log lines per request                     |
| `kyuubi.notebook.execution.event.max.wait.ms` | `10000`    | Long-poll ceiling                         |
| `kyuubi.notebook.import.max.size`             | `16777216` | Bytes accepted by import                  |

## 2. Where notebooks are stored

Notebooks, folders, cells and revisions live in the store named here. **The default is unchanged
from previous releases**, so doing nothing keeps current behaviour.

```properties
# Default - notebooks in the JDBC metadata database.
kyuubi.notebook.store.class=org.apache.kyuubi.server.notebook.store.JDBCNotebookStore
```

With the default and a SQLite metadata store, the database is a file on one pod's disk. That is
fine for a single replica and **wrong for two**: a notebook created on pod A does not exist for
pod B. For more than one replica, switch the store to the shared filesystem:

```properties
kyuubi.notebook.store.class=org.apache.kyuubi.server.notebook.store.FileSystemNotebookStore
kyuubi.notebook.store.fs.root=hdfs:///user/kyuubi/notebooks
```

|                     Key                      |   Default   |                                                      Meaning                                                       |
|----------------------------------------------|-------------|--------------------------------------------------------------------------------------------------------------------|
| `kyuubi.notebook.store.fs.root`              | `notebooks` | Root directory; a path with no scheme resolves against `fs.defaultFS`                                              |
| `kyuubi.notebook.store.fs.list.cache.ttl`    | `5000` (ms) | How stale another pod's change may look; a pod always sees its own writes at once                                  |
| `kyuubi.notebook.store.fs.migrate.from.jdbc` | `true`      | Copy notebooks out of the JDBC store once, when the filesystem store is still empty. Never deletes from the source |

The server needs read/write on that path as the user it runs as. It uses the Hadoop configuration
and Kerberos login the pod already has - no extra keytab, no separate principal.

### Also required with a SQLite metadata store

```properties
kyuubi.metadata.store.jdbc.database.type=SQLITE
kyuubi.metadata.store.jdbc.url=jdbc:sqlite:<KYUUBI_HOME>/kyuubi_state_store.db
```

Even with notebooks on HDFS, this database still holds the runtime bookkeeping (sessions,
executions, events). **Mount a volume for `<KYUUBI_HOME>`** or a pod restart discards it.

## 3. Spark confs for the engine (Python cells)

All optional. Absent means "no limit" or "pip defaults" - the worker never invents a value.

```properties
# Package index. Leave both unset in an environment that reaches the index directly.
spark.kyuubi.notebook.pip.indexUrl=
spark.kyuubi.notebook.pip.trustedHost=
spark.kyuubi.notebook.pip.timeout=300

# Per-worker ceilings. All notebooks of one user share one driver, so without these one cell can
# take the whole engine down and that user's SQL with it.
spark.kyuubi.notebook.python.memory.limit=4g
spark.kyuubi.notebook.python.cpu.time.limit=3600

# Makes toPandas() transfer in batches instead of row by row.
spark.sql.execution.arrow.pyspark.enabled=true
```

`indexUrl` over plain `http://` without `trustedHost` is refused by pip itself; the cell shows
pip's own message rather than a guess made on the operator's behalf.

## 4. Do NOT set these

```properties
# The notebook sets both itself, per session or per operation. A global value breaks other
# clients: the first makes every SQL cell run as Python, the second changes what non-notebook
# JDBC clients receive.
kyuubi.operation.language
kyuubi.engine.spark.output.mode
```

## 5. Removed - delete if still present

The server no longer contains a Python interpreter; Python cells run in the Spark engine. These
have no effect and should be dropped from values:

```properties
kyuubi.notebook.python.enabled
kyuubi.notebook.python.executable
kyuubi.notebook.python.work.dir
kyuubi.notebook.python.venv.system.site.packages
kyuubi.notebook.python.execution.timeout.seconds
kyuubi.notebook.python.limit.cpu.seconds
kyuubi.notebook.python.limit.memory.mb
kyuubi.notebook.python.limit.processes
kyuubi.notebook.python.package.index.url
kyuubi.notebook.python.package.allowlist
kyuubi.notebook.python.package.denylist
kyuubi.notebook.python.package.constraints.file
kyuubi.notebook.python.package.max.count
kyuubi.notebook.python.package.install.timeout.seconds
kyuubi.notebook.python.environment.max.size.mb
kyuubi.notebook.python.environment.max.per.user
kyuubi.notebook.python.environment.keep.revisions
```

Along with them, the chart pieces that served them: the `prepare-python-packages` init container,
the emptyDir mounted over `/usr/local`, the `pipConfig` block and the server-pod `pip.conf`.

## 6. Images

Two images, and the order matters.

1. **Spark image** carries `kyuubi-spark-sql-engine_2.12-1.10.3.jar`, which contains the Python
   worker. `%pip`, the resource limits and the engine half of output handling live there. It also
   needs `python3-pip`, the notebook libraries, and:

   ```
   PYSPARK_PYTHON=/usr/bin/python3
   PYSPARK_DRIVER_PYTHON=/usr/bin/python3
   MPLBACKEND=Agg
   ```
2. **kyuubi-custom image** carries the server and Web UI.

Rebuilding only kyuubi-custom leaves `%pip` reporting an unknown magic. See
[spark-image-python.md](../spark-image-python.md).

## 7. Routing between replicas

A notebook session is held by the pod that opened it - it is a Kyuubi session handle, a Spark
engine connection and a python worker inside one process, none of which can be shared. Requests
for it are routed to that pod.

Ownership is recorded in ZooKeeper, under the HA namespace already in use:

```
<kyuubi.ha.namespace>/notebook-sessions/<sessionId>  ->  "<host>:<rest-port>"
```

The node is ephemeral, so a dead pod's claim disappears with it. Nothing extra to configure -
the existing `kyuubi.ha.*` settings are enough, and with no quorum configured routing simply
switches off and every request is served locally, which is what a single replica wants.

### The one key to set

```properties
# Same value on every replica.
kyuubi.notebook.proxy.internal.secret=<a long random string>
```

A forwarded request carries the identity the entry pod already authenticated, because the pod
holding the session cannot re-check a Web UI cookie it never issued. The receiving pod honours
that identity **only** against this secret, so a client that copies the header names gains
nothing.

**Leaving it unset is a working but reduced mode**: only the original `Authorization` header is
carried over, so JDBC and Bearer clients still work across pods while Web UI cookie sessions do
not. Set it.

Timeouts and connection limits for the hop come from the existing
`kyuubi.frontend.rest.proxy.jetty.client.*` set; there are no new knobs.

### What a user sees when a pod dies

Any runtime call for a session whose owner is gone answers `409` with
`{"error":"runtime lost","action":"restart-session"}`. The notebook itself is untouched - it is
in the store - so restarting the session is enough to carry on.
