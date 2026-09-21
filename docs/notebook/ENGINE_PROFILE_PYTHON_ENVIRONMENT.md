<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Engine Profile Python Environment Lifecycle

An Engine Profile has a stable, server-generated profile ID and immutable
engine revisions. The profile display name is unique only within its owner.
Each profile may also have a persistent Python environment stored separately
from Spark Driver and Executor Pods.

```text
Engine Profile
  ├─ revision r1 (DRAINING) ─ old Spark engine, if it is still alive
  ├─ revision r2 (ACTIVE)   ─ configuration used by newly opened sessions
  └─ Python environment env-4 (READY)
       └─ PVC data retained after a Spark engine stops
```

The UI entry point is **Management → Engine Profiles → Lifecycle**. It shows
only browser-safe lifecycle data: profile and environment revision, state,
resolved package names, resource configuration and timeouts. It deliberately
does not expose PVC names, filesystem paths, raw engine subdomains or package
index credentials.

## Installing Python packages

Use a PySpark Notebook cell for package changes:

```python
%pip install pandas seaborn
```

The command has two related effects:

1. The package is installed into the live Driver's session package directory.
   Python workers attached to that same live Driver can use it immediately.
2. The backend submits an immutable persistent-environment build for the
   selected Engine Profile. The build records its resolved requirements and
   creates a `READY` environment only after validation succeeds.

`%pip list` includes session-scoped packages, so it can show packages that are
available immediately even while the persistent build is still running.

Restarting a Notebook runtime does not necessarily restart the Spark Driver.
If Kyuubi reuses the living Driver, the hot-installed packages remain available
without waiting for a new engine. Once that Driver has stopped, the next engine
mounts the latest `READY` persistent environment for the profile.

## Revision and engine lifecycle

Saving engine resources or timeout settings creates a new immutable Engine
Profile revision. Existing engines keep their former revision and are marked
`DRAINING`; new sessions use the new revision. Kyuubi does not change Spark
Driver/Executor resources in place and does not kill the old engine as a side
effect of save.

The Lifecycle dialog offers **Terminate** only for a draining revision. The
action requires an explicit confirmation and interrupts Notebook or SQL
sessions attached to that old engine. It must not be used merely to publish a
Python package: normal idle shutdown is safer and preserves shared-client
workloads.

The current persistent environment is promoted for future engines only after
the previous profile engine has disappeared from Kyuubi discovery. This avoids
mounting a different Python environment into a live shared engine.

## Demo operations and recovery

Observe persistent-environment build Jobs in the Kyuubi namespace:

```bash
kubectl get jobs,pods -n kyuubi \
  -l app.kubernetes.io/managed-by=kyuubi-python-environment
```

For a failed build, inspect its output before making another package request:

```bash
kubectl logs -n kyuubi job/<python-environment-build-job>
```

Correct the package name, compatibility issue, allowlist or package-index
configuration and submit a new `%pip install` request. Do not delete the
profile PVC as a recovery shortcut: the last `READY` environment remains the
safe fallback for later engines. A failed or incomplete build is never
promoted.

For a local single-node K3s demo, the configured `local-path` PVC is adequate.
Production installations where builders, Drivers and Executors can run on
different nodes require an RWX-capable StorageClass, or an equivalent immutable
environment artifact fetched by an init container. A local-path/RWO volume is
not a safe multi-node production solution.
