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

import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict

import pytest


FIXTURE = Path(__file__).parent / "command_matrix"
ANSI_ESCAPE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def integration_environment(tmp_path: Path) -> Dict[str, str]:
    environment = os.environ.copy()
    suffix = re.sub(r"[^a-z0-9_]", "_", tmp_path.name.lower())[-32:]
    environment["KYUUBI_DBT_TEST_SCHEMA"] = "dbt_kyuubi_commands_{}".format(suffix)
    environment["KYUUBI_DBT_SNAPSHOT_VALUE"] = "alpha"
    environment["KYUUBI_DBT_FORCE_RETRY_FAILURE"] = "false"
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


def load_json(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def result_for(project: Path, unique_id_suffix: str) -> Dict[str, Any]:
    results = load_json(project / "target" / "run_results.json")["results"]
    matches = [
        result for result in results if result["unique_id"].endswith(unique_id_suffix)
    ]
    assert len(matches) == 1, results
    return matches[0]


@pytest.fixture
def command_project(tmp_path: Path) -> tuple[Path, Dict[str, str]]:
    if not os.environ.get("KYUUBI_ENGINE_PROFILE_ID"):
        pytest.skip("KYUUBI_ENGINE_PROFILE_ID is required for the Kyuubi integration test")
    project = tmp_path / "command_matrix"
    shutil.copytree(FIXTURE, project)
    return project, integration_environment(tmp_path)


@pytest.mark.integration
def test_dbt_debug(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    result = run_dbt(project, environment, "debug")

    assert_success(result)
    output = ANSI_ESCAPE.sub("", result.stdout + result.stderr)
    assert "Connection test:" in output
    assert "All checks passed" in output or "OK connection ok" in output


@pytest.mark.integration
def test_dbt_parse(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    assert_success(run_dbt(project, environment, "parse"))

    manifest = load_json(project / "target" / "manifest.json")
    assert "model.dbt_kyuubi_command_matrix.command_model" in manifest["nodes"]
    assert "seed.dbt_kyuubi_command_matrix.command_seed" in manifest["nodes"]
    assert "snapshot.dbt_kyuubi_command_matrix.command_snapshot" in manifest["nodes"]


@pytest.mark.integration
def test_dbt_compile(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    assert_success(run_dbt(project, environment, "compile", "--select", "command_model"))

    compiled = (
        project
        / "target"
        / "compiled"
        / "dbt_kyuubi_command_matrix"
        / "models"
        / "command_model.sql"
    ).read_text(encoding="utf-8")
    assert "command_seed" in compiled
    assert "score" in compiled


@pytest.mark.integration
def test_dbt_seed(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    assert_success(run_dbt(project, environment, "seed", "--select", "command_seed"))
    assert result_for(project, ".command_seed")["status"] == "success"
    assert_success(run_dbt(project, environment, "run-operation", "assert_command_seed"))


@pytest.mark.integration
def test_dbt_run(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    assert_success(run_dbt(project, environment, "seed", "--select", "command_seed"))
    assert_success(run_dbt(project, environment, "run", "--select", "command_model"))
    assert result_for(project, ".command_model")["status"] == "success"
    assert_success(run_dbt(project, environment, "run-operation", "assert_command_model"))


@pytest.mark.integration
def test_dbt_test(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    assert_success(run_dbt(project, environment, "seed", "--select", "command_seed"))
    assert_success(run_dbt(project, environment, "run", "--select", "command_model"))
    assert_success(run_dbt(project, environment, "test", "--select", "command_model"))

    results = load_json(project / "target" / "run_results.json")["results"]
    assert len(results) == 2
    assert all(result["status"] == "pass" for result in results)


@pytest.mark.integration
def test_dbt_build(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    assert_success(run_dbt(project, environment, "build", "--select", "+command_model"))

    results = load_json(project / "target" / "run_results.json")["results"]
    result_ids = {result["unique_id"] for result in results}
    assert "seed.dbt_kyuubi_command_matrix.command_seed" in result_ids
    assert "model.dbt_kyuubi_command_matrix.command_model" in result_ids
    test_count = sum(
        unique_id.startswith("test.dbt_kyuubi_command_matrix.")
        for unique_id in result_ids
    )
    assert test_count == 2
    assert all(result["status"] in {"success", "pass"} for result in results)


@pytest.mark.integration
def test_dbt_snapshot(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    assert_success(run_dbt(project, environment, "run-operation", "reset_command_snapshot"))
    assert_success(run_dbt(project, environment, "run", "--select", "snapshot_input"))
    assert_success(run_dbt(project, environment, "snapshot", "--select", "command_snapshot"))

    updated_environment = environment.copy()
    updated_environment["KYUUBI_DBT_SNAPSHOT_VALUE"] = "beta"
    assert_success(run_dbt(project, updated_environment, "run", "--select", "snapshot_input"))
    assert_success(
        run_dbt(
            project,
            updated_environment,
            "snapshot",
            "--select",
            "command_snapshot",
        )
    )
    assert result_for(project, ".command_snapshot")["status"] == "success"
    assert_success(
        run_dbt(project, updated_environment, "run-operation", "assert_command_snapshot")
    )


@pytest.mark.integration
def test_dbt_run_operation(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    assert_success(run_dbt(project, environment, "seed", "--select", "command_seed"))
    assert_success(run_dbt(project, environment, "run", "--select", "command_model"))
    assert_success(run_dbt(project, environment, "run-operation", "assert_command_model"))


@pytest.mark.integration
def test_dbt_docs_generate(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project

    assert_success(run_dbt(project, environment, "seed", "--select", "command_seed"))
    assert_success(run_dbt(project, environment, "run", "--select", "command_model"))
    assert_success(run_dbt(project, environment, "docs", "generate"))

    target = project / "target"
    assert (target / "index.html").stat().st_size > 0
    catalog = load_json(target / "catalog.json")
    assert "model.dbt_kyuubi_command_matrix.command_model" in catalog["nodes"]
    assert "seed.dbt_kyuubi_command_matrix.command_seed" in catalog["nodes"]


@pytest.mark.integration
def test_dbt_retry(command_project: tuple[Path, Dict[str, str]]) -> None:
    project, environment = command_project
    failing_environment = environment.copy()
    failing_environment["KYUUBI_DBT_FORCE_RETRY_FAILURE"] = "true"

    failure = run_dbt(
        project,
        failing_environment,
        "run",
        "--select",
        "retry_failure",
    )
    assert failure.returncode != 0
    assert "command matrix retry failure" in failure.stdout + failure.stderr
    assert result_for(project, ".retry_failure")["status"] == "error"

    assert_success(run_dbt(project, environment, "retry"))
    assert result_for(project, ".retry_failure")["status"] == "success"
    assert_success(run_dbt(project, environment, "run-operation", "assert_retry_model"))
