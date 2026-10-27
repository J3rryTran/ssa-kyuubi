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

import pytest
from dbt_common.exceptions import DbtRuntimeError

from dbt.adapters.kyuubi.connections import KyuubiCredentials


def credentials(parameters):
    values = {
        "schema": "dbt",
        "host": "kyuubi",
        "port": 10009,
        "user": "dbt-runner",
        "method": "thrift",
        "auth": "NONE",
        "server_side_parameters": parameters,
    }
    # dbt-core supplies the remaining dataclass fields through defaults. Constructing from the
    # dictionary keeps this test aligned with profiles.yml parsing rather than a Thrift mock.
    return KyuubiCredentials.from_dict(values)


def test_local_invocation_replaces_untrusted_run_id():
    supplied = "caller-owned"
    result = credentials(
        {
            "kyuubi.engine.profile.id": "profile-a",
            "kyuubi.engine.profile.run.id": supplied,
        }
    )

    generated = result.server_side_parameters["kyuubi.engine.profile.run.id"]
    assert generated != supplied
    assert generated


def test_managed_invocation_preserves_complete_binding_for_all_connections():
    parameters = {
        "kyuubi.engine.profile.id": "profile-a",
        "kyuubi.engine.profile.run.id": "run-a",
        "kyuubi.compute.attempt.id": "attempt-a",
        "kyuubi.compute.allocation.id": "allocation-a",
        "kyuubi.compute.binding.id": "binding-a",
        "kyuubi.compute.binding.token": "secret-a",
    }

    result = credentials(dict(parameters))

    assert result.server_side_parameters == {
        **parameters,
        "spark.sql.ansi.enabled": "false",
    }


def test_partial_managed_binding_is_rejected_before_open_session():
    with pytest.raises(DbtRuntimeError, match="Managed Kyuubi compute configuration is incomplete"):
        credentials(
            {
                "kyuubi.engine.profile.id": "profile-a",
                "kyuubi.compute.allocation.id": "allocation-a",
            }
        )
