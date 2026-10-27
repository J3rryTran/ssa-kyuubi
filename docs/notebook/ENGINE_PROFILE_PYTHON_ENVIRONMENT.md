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

An Engine Profile has a stable, server-generated profile ID and one current configuration. The
profile display name is unique only within its owner. Each profile may also have a persistent
Python environment stored separately from Spark Driver and Executor Pods.

```text
Engine Profile
  ├─ current configuration
  ├─ interactive and JOB compute allocations
  └─ profile-scoped PVC
       ├─ Python environment env-3
       └─ Python environment env-4 (READY/current)
```

The UI shows only browser-safe lifecycle data: profile, environment generation, state, resolved
package names, resource configuration and timeouts. It deliberately does not expose PVC names,
filesystem paths, raw engine subdomains or package index credentials.

## Installing Python packages

Use a PySpark Notebook cell for package changes:

```python
%pip install pandas seaborn
```

The command has two related effects:

1. The package is installed into the live Driver's session package directory.
   Python workers attached to that same live Driver can use it immediately.
2. The backend records a persistent package intent for the selected Engine Profile. After all
   compute allocations using the current environment have stopped, the builder records its
   resolved requirements and creates a `READY` environment.

`%pip list` includes session-scoped packages, so it can show packages that are
available immediately even while the persistent build is still running.

Restarting a Notebook runtime does not necessarily restart the Spark Driver.
If Kyuubi reuses the living Driver, the hot-installed packages remain available
without waiting for a new engine. Once that Driver has stopped, the next engine
mounts the latest `READY` persistent environment for the profile.

## Allocation and environment lifecycle

Saving resource or timeout settings is rejected while any interactive or JOB allocation for the
profile is open. Kyuubi does not change Driver/Executor resources in place and does not stop an
engine as a side effect of Save.

Every allocation captures its exact environment ID, PVC name, relative directory and Spark mount
overlay. All Notebook cells and dbt models in that allocation therefore continue to use the same
interpreter even if a newer environment becomes ready later. Publication waits until every
allocation is released, including allocations waiting for cleanup.

JOB cleanup removes only the Spark Driver/Executor resources owned by that allocation. It never
deletes the shared Python PVC. Profile deletion is different: after the user has stopped all
compute and all environment builds are terminal, it removes the profile's builder resources,
PVC and environment metadata before deleting the profile document.

## PVC access mode and multi-node placement

The claim access mode is controlled by:

```properties
kyuubi.notebook.python.environment.pvc.storage-class=<storage-class>
kyuubi.notebook.python.environment.pvc.access-mode=ReadWriteOnce
```

`ReadWriteOnce` is suitable for the local single-node K3s demo. It constrains every Driver and
Executor sharing that profile environment to the node on which the volume can be attached. A
production cluster that allows those pods to span nodes must configure an RWX-capable
StorageClass and `ReadWriteMany`.

Kyuubi applies StorageClass and access mode only when it creates a new PVC. It never patches or
deletes an existing claim during upgrade. If the configured policy differs from the existing PVC,
the environment build fails with an explicit migration error; an operator must copy/migrate the
claim deliberately.

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

Do not delete the profile PVC as a recovery shortcut: the last `READY` environment remains the
safe fallback for later engines. A failed or incomplete build is never promoted.
