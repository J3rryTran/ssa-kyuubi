#!/usr/bin/env sh
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set +e
dbt "$@" &
dbt_pid=$!

terminate_dbt() {
  kill -TERM "$dbt_pid" 2>/dev/null
  wait "$dbt_pid"
  exit 143
}

trap terminate_dbt TERM INT
wait "$dbt_pid"
dbt_status=$?
trap - TERM INT

# Completed pods cannot be exec'd reliably. Emit server-recognizable artifact records before the
# container exits so Kyuubi can persist them together with the logs before Kubernetes Job TTL.
python - <<'PY'
import base64
from pathlib import Path

prefix = "KYUUBI_DBT_ARTIFACT_V1"
for name in ("manifest.json", "run_results.json"):
    path = Path("target") / name
    if path.is_file():
        encoded = base64.b64encode(path.read_bytes()).decode("ascii")
        chunks = [encoded[index : index + 12000] for index in range(0, len(encoded), 12000)]
        for index, chunk in enumerate(chunks):
            print(
                "{} {} {} {} {}".format(prefix, name, index, len(chunks), chunk),
                flush=True,
            )
PY

exit "$dbt_status"
