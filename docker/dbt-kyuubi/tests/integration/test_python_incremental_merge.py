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


FIXTURE = Path(__file__).parent / "python_incremental_merge"


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


def run_operation(
    project: Path,
    environment: Dict[str, str],
    macro: str,
    arguments: str = "",
) -> subprocess.CompletedProcess[str]:
    command = ["run-operation", macro]
    if arguments:
        command.extend(["--args", arguments])
    return run_dbt(project, environment, *command)


def assert_state(
    project: Path,
    environment: Dict[str, str],
    identifier: str,
    count: int,
    total: int,
    id1: int,
) -> None:
    arguments = (
        '{{"identifier": "{}", "expected_count": {}, '
        '"expected_sum": {}, "expected_id1": {}}}'
    ).format(identifier, count, total, id1)
    assert_success(run_operation(project, environment, "assert_incremental_state", arguments))
    assert_success(
        run_operation(
            project,
            environment,
            "assert_no_incremental_temp",
            '{{"identifier": "{}"}}'.format(identifier),
        )
    )


def assert_partitioned_state(
    project: Path,
    environment: Dict[str, str],
    identifier: str,
    count: int,
    total: int,
    day_one_count: int,
) -> None:
    arguments = (
        '{{"identifier": "{}", "expected_count": {}, '
        '"expected_sum": {}, "expected_day_one_count": {}}}'
    ).format(identifier, count, total, day_one_count)
    assert_success(run_operation(project, environment, "assert_partitioned_state", arguments))
    assert_success(
        run_operation(
            project,
            environment,
            "assert_no_incremental_temp_pattern",
            '{{"identifier": "{}"}}'.format(identifier),
        )
    )


@pytest.fixture
def incremental_project(tmp_path: Path) -> tuple[Path, Dict[str, str]]:
    if not os.environ.get("KYUUBI_ENGINE_PROFILE_ID"):
        pytest.skip("KYUUBI_ENGINE_PROFILE_ID is required for the Kyuubi integration test")
    project = tmp_path / "python_incremental_merge"
    shutil.copytree(FIXTURE, project)
    environment = integration_environment(tmp_path)
    assert_success(run_operation(project, environment, "prepare_incremental_fixture"))
    return project, environment


@pytest.mark.integration
def test_incremental_merge_lifecycle(
    incremental_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = incremental_project

    assert_success(run_dbt(project, environment, "run", "--select", "incremental_merge"))
    assert_state(project, environment, "incremental_merge", 2, 30, 10)

    assert_success(
        run_operation(
            project,
            environment,
            "set_merge_input",
            '{"scenario": "updated"}',
        )
    )
    assert_success(run_dbt(project, environment, "run", "--select", "incremental_merge"))
    assert_state(project, environment, "incremental_merge", 3, 61, 11)

    assert_success(run_dbt(project, environment, "run", "--select", "incremental_merge"))
    assert_state(project, environment, "incremental_merge", 3, 61, 11)

    assert_success(
        run_dbt(
            project,
            environment,
            "run",
            "--full-refresh",
            "--select",
            "incremental_merge",
        )
    )
    assert_state(project, environment, "incremental_merge", 3, 61, 11)


@pytest.mark.integration
def test_incremental_merge_appends_new_columns(
    incremental_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = incremental_project
    model = "incremental_append_columns"

    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_success(
        run_operation(
            project,
            environment,
            "set_schema_input",
            '{"include_extra_column": true}',
        )
    )
    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_success(run_operation(project, environment, "assert_append_new_columns_state"))
    assert_success(
        run_operation(
            project,
            environment,
            "assert_no_incremental_temp",
            '{{"identifier": "{}"}}'.format(model),
        )
    )


@pytest.mark.integration
@pytest.mark.parametrize(
    ("scenario", "expected_error"),
    [
        ("null_key", "unique_key columns contain null values"),
        ("duplicate_key", "source contains duplicate unique_key values"),
    ],
)
def test_incremental_merge_rejects_ambiguous_keys_without_changing_target(
    incremental_project: tuple[Path, Dict[str, str]],
    scenario: str,
    expected_error: str,
) -> None:
    project, environment = incremental_project
    model = "incremental_merge"

    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_success(
        run_operation(
            project,
            environment,
            "set_merge_input",
            '{{"scenario": "{}"}}'.format(scenario),
        )
    )

    result = run_dbt(project, environment, "run", "--select", model)
    assert result.returncode != 0
    assert expected_error in result.stdout + result.stderr
    assert_state(project, environment, model, 2, 30, 10)


@pytest.mark.integration
def test_incremental_temp_write_failure_is_cleaned(
    incremental_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = incremental_project
    model = "incremental_temp_failure"

    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_state(project, environment, model, 1, 10, 10)
    assert_success(
        run_operation(
            project,
            environment,
            "set_failure_input",
            '{"should_fail": true}',
        )
    )

    result = run_dbt(project, environment, "run", "--select", model)
    assert result.returncode != 0
    assert "incremental temporary table write failure" in result.stdout + result.stderr
    assert_state(project, environment, model, 1, 10, 10)


@pytest.mark.integration
def test_incremental_merge_failure_is_cleaned(
    incremental_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = incremental_project
    model = "incremental_merge_failure"

    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_state(project, environment, model, 2, 30, 10)
    assert_success(
        run_operation(
            project,
            environment,
            "set_merge_input",
            '{"scenario": "updated"}',
        )
    )

    result = run_dbt(project, environment, "run", "--select", model)
    assert result.returncode != 0
    assert_state(project, environment, model, 2, 30, 10)


@pytest.mark.integration
def test_incremental_append_create_rerun_and_full_refresh(
    incremental_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = incremental_project
    model = "incremental_append"

    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_success(
        run_operation(
            project,
            environment,
            "assert_append_state",
            '{"expected_count": 2, "expected_distinct": 2, "expected_sum": 30}',
        )
    )
    assert_success(
        run_operation(
            project,
            environment,
            "set_append_input",
            '{"stage": "new_only"}',
        )
    )

    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_success(
        run_operation(
            project,
            environment,
            "assert_append_state",
            '{"expected_count": 3, "expected_distinct": 3, "expected_sum": 60}',
        )
    )

    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_success(
        run_operation(
            project,
            environment,
            "assert_append_state",
            '{"expected_count": 4, "expected_distinct": 3, "expected_sum": 90}',
        )
    )

    assert_success(
        run_dbt(
            project,
            environment,
            "run",
            "--full-refresh",
            "--select",
            model,
        )
    )
    assert_success(
        run_operation(
            project,
            environment,
            "assert_append_state",
            '{"expected_count": 1, "expected_distinct": 1, "expected_sum": 30}',
        )
    )


@pytest.mark.integration
def test_incremental_insert_overwrite_partition_retry_and_failure(
    incremental_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = incremental_project
    model = "incremental_insert_overwrite"

    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_partitioned_state(project, environment, model, 3, 60, 2)

    assert_success(
        run_operation(
            project,
            environment,
            "set_overwrite_input",
            '{"stage": "replace_day_one"}',
        )
    )
    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_partitioned_state(project, environment, model, 2, 41, 1)

    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_partitioned_state(project, environment, model, 2, 41, 1)

    assert_success(
        run_operation(
            project,
            environment,
            "set_overwrite_input",
            '{"stage": "fail_day_two"}',
        )
    )
    failure = run_dbt(project, environment, "run", "--select", model)
    assert failure.returncode != 0
    assert "insert_overwrite temporary table write failure" in failure.stdout + failure.stderr
    assert_partitioned_state(project, environment, model, 2, 41, 1)

    assert_success(
        run_operation(
            project,
            environment,
            "set_overwrite_input",
            '{"stage": "recover_day_two"}',
        )
    )
    assert_success(run_dbt(project, environment, "run", "--select", model))
    assert_partitioned_state(project, environment, model, 2, 42, 1)


def microbatch_run_arguments() -> tuple[str, ...]:
    return (
        "run",
        "--select",
        "incremental_microbatch",
        "--event-time-start",
        "2026-01-01",
        "--event-time-end",
        "2026-01-03",
    )


@pytest.mark.integration
def test_incremental_microbatch_is_idempotent_and_cleans_batch_temp_tables(
    incremental_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = incremental_project
    model = "incremental_microbatch"

    assert_success(run_dbt(project, environment, *microbatch_run_arguments()))
    assert_partitioned_state(project, environment, model, 2, 30, 1)

    assert_success(run_dbt(project, environment, *microbatch_run_arguments()))
    assert_partitioned_state(project, environment, model, 2, 30, 1)


@pytest.mark.integration
def test_incremental_microbatch_retry_runs_failed_batch(
    incremental_project: tuple[Path, Dict[str, str]],
) -> None:
    project, environment = incremental_project
    model = "incremental_microbatch"
    assert_success(
        run_operation(
            project,
            environment,
            "set_microbatch_input",
            '{"fail_second_batch": true}',
        )
    )

    failure = run_dbt(project, environment, *microbatch_run_arguments())
    assert failure.returncode != 0
    assert "microbatch temporary table write failure" in failure.stdout + failure.stderr
    run_results_path = project / "target" / "run_results.json"
    manifest_path = project / "target" / "manifest.json"
    failed_run_results = run_results_path.read_bytes()
    failed_manifest = manifest_path.read_bytes()

    assert_partitioned_state(project, environment, model, 1, 10, 1)
    assert_success(
        run_operation(
            project,
            environment,
            "set_microbatch_input",
            '{"fail_second_batch": false}',
        )
    )
    run_results_path.write_bytes(failed_run_results)
    manifest_path.write_bytes(failed_manifest)

    assert_success(run_dbt(project, environment, "retry"))
    assert_partitioned_state(project, environment, model, 2, 30, 1)
