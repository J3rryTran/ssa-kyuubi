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
import re
import shutil
import subprocess
from pathlib import Path
from typing import Dict

import pytest


FIXTURE = Path(__file__).parent / "python_table"


def integration_environment(tmp_path: Path) -> Dict[str, str]:
    environment = os.environ.copy()
    suffix = re.sub(r"[^a-z0-9_]", "_", tmp_path.name.lower())[-32:]
    environment["KYUUBI_DBT_TEST_SCHEMA"] = "dbt_kyuubi_{}".format(suffix)
    return environment


def run_dbt(
    project: Path,
    environment: Dict[str, str],
    *arguments: str,
) -> subprocess.CompletedProcess[str]:
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
    timeout = int(environment.get("KYUUBI_DBT_TEST_TIMEOUT_SECONDS", "600"))
    return subprocess.run(
        command,
        capture_output=True,
        check=False,
        env=environment,
        text=True,
        timeout=timeout,
    )


def assert_success(result: subprocess.CompletedProcess[str]) -> None:
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.fixture
def python_table_project(tmp_path: Path) -> tuple[Path, Dict[str, str]]:
    if not os.environ.get("KYUUBI_ENGINE_PROFILE_ID"):
        pytest.skip("KYUUBI_ENGINE_PROFILE_ID is required for the Kyuubi integration test")
    project = tmp_path / "python_table"
    shutil.copytree(FIXTURE, project)
    environment = integration_environment(tmp_path)
    assert_success(run_dbt(project, environment, "run-operation", "prepare_python_table_fixture"))
    return project, environment


@pytest.mark.integration
def test_python_table_lifecycle_and_dependencies(
    python_table_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = python_table_project
    selection = "+python_table_downstream"

    assert_success(run_dbt(project, environment, "run", "--select", selection))
    assert_success(run_dbt(project, environment, "run-operation", "assert_python_table_results"))

    assert_success(run_dbt(project, environment, "run", "--select", selection))
    assert_success(run_dbt(project, environment, "run-operation", "assert_python_table_results"))

    assert_success(
        run_dbt(project, environment, "run", "--full-refresh", "--select", selection)
    )
    assert_success(run_dbt(project, environment, "run-operation", "assert_python_table_results"))


@pytest.mark.integration
def test_python_model_reads_sql_ephemeral(
    python_table_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = python_table_project

    assert_success(
        run_dbt(
            project,
            environment,
            "run",
            "--select",
            "+python_from_sql_ephemeral",
        )
    )
    assert_success(
        run_dbt(
            project,
            environment,
            "run-operation",
            "assert_python_from_sql_ephemeral_results",
        )
    )


@pytest.mark.integration
@pytest.mark.parametrize(
    ("model", "expected_error"),
    [
        ("python_table_failure_before", "failure before saveAsTable"),
        ("python_table_failure_during_write", "failure during saveAsTable"),
    ],
)
def test_python_table_failure_preserves_existing_iceberg_table(
    python_table_project: tuple[Path, Dict[str, str]],
    model: str,
    expected_error: str,
) -> None:
    project, environment = python_table_project

    result = run_dbt(project, environment, "run", "--select", model)
    assert result.returncode != 0
    assert expected_error in result.stdout + result.stderr

    assert_success(
        run_dbt(
            project,
            environment,
            "run-operation",
            "assert_failure_baseline",
            "--args",
            '{{"identifier": "{}"}}'.format(model),
        )
    )
