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

import os
import shutil
import subprocess
from pathlib import Path

import pytest


FIXTURE = Path(__file__).parent / "default_file_format"


def run_dbt(project: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    command = [
        "dbt",
        *arguments,
        "--project-dir",
        str(project),
        "--profiles-dir",
        str(project),
        "--target",
        "integration",
    ]
    timeout = int(os.environ.get("KYUUBI_DBT_TEST_TIMEOUT_SECONDS", "600"))
    return subprocess.run(
        command,
        capture_output=True,
        check=False,
        text=True,
        timeout=timeout,
    )


@pytest.mark.integration
def test_default_python_table_uses_iceberg(tmp_path: Path) -> None:
    if not os.environ.get("KYUUBI_ENGINE_PROFILE_ID"):
        pytest.skip("KYUUBI_ENGINE_PROFILE_ID is required for the Kyuubi integration test")

    project = tmp_path / "default_file_format"
    shutil.copytree(FIXTURE, project)

    run = run_dbt(project, "run", "--select", "default_python_table")
    assert run.returncode == 0, run.stdout + run.stderr

    generated = (
        project
        / "target"
        / "run"
        / "dbt_kyuubi_default_file_format"
        / "models"
        / "default_python_table.py"
    ).read_text(encoding="utf-8")
    assert '.format("iceberg")' in generated
    assert '.format("delta")' not in generated

    provider = run_dbt(project, "run-operation", "assert_default_python_table_is_iceberg")
    assert provider.returncode == 0, provider.stdout + provider.stderr
