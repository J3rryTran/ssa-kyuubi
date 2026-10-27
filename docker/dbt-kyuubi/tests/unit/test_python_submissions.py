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
from types import SimpleNamespace
from typing import Any, Dict, Iterable, List, Optional

import pytest
from dbt.adapters.spark.connections import SparkConnectionManager, SparkConnectionMethod
from dbt_common.exceptions import DbtRuntimeError

from dbt.adapters.kyuubi import python_submissions
from dbt.adapters.kyuubi.capabilities import PYTHON_INCREMENTAL_CAPABILITIES
from dbt.adapters.kyuubi.connections import KyuubiConnectionManager
from dbt.adapters.kyuubi.python_submissions import KyuubiThriftPythonJobHelper


class FakeThriftClient:
    def __init__(
        self,
        states: Dict[str, Iterable[int]],
        errors: Optional[Dict[str, str]] = None,
    ) -> None:
        self.states = {handle: list(values) for handle, values in states.items()}
        self.errors = errors or {}
        self.execute_requests: List[Any] = []
        self.status_requests: List[Any] = []
        self.cancel_requests: List[Any] = []
        self.close_requests: List[Any] = []
        self.log_requests: List[Any] = []

    def ExecuteStatement(self, request: Any) -> Any:
        self.execute_requests.append(request)
        handle = "operation-{}".format(len(self.execute_requests))
        if handle not in self.states:
            self.states[handle] = [python_submissions.ttypes.TOperationState.FINISHED_STATE]
        return SimpleNamespace(status=None, operationHandle=handle)

    def GetOperationStatus(self, request: Any) -> Any:
        self.status_requests.append(request)
        handle = request.operationHandle
        states = self.states[handle]
        state = states.pop(0) if len(states) > 1 else states[0]
        return SimpleNamespace(
            status=None,
            operationState=state,
            errorMessage=self.errors.get(handle),
        )

    def GetLog(self, request: Any) -> Any:
        self.log_requests.append(request)
        return SimpleNamespace(status=None, log="server-side operation log")

    def CancelOperation(self, request: Any) -> Any:
        self.cancel_requests.append(request)
        return SimpleNamespace(status=None)

    def CloseOperation(self, request: Any) -> Any:
        self.close_requests.append(request)
        return SimpleNamespace(status=None)


class FakeConnection:
    def __init__(self, client: FakeThriftClient) -> None:
        self.client = client
        self.sessionHandle = "session-1"
        self.closed = False

    def close(self) -> None:
        self.closed = True


def make_helper(
    config: Optional[Dict[str, Any]] = None,
    timeout: int = 30,
    batch: Optional[Dict[str, Any]] = None,
) -> KyuubiThriftPythonJobHelper:
    parsed_model = {
        "name": "python_model",
        "alias": "python_model",
        "database": "lakehouse",
        "schema": "dbt_test",
        "config": config or {"materialized": "table", "timeout": timeout},
    }
    if batch is not None:
        parsed_model["batch"] = batch
    credentials = SimpleNamespace(
        method=SparkConnectionMethod.THRIFT,
        query_timeout=timeout,
        poll_interval=1,
    )
    return KyuubiThriftPythonJobHelper(parsed_model, credentials)


def operation_handles(requests: List[Any]) -> List[str]:
    return [request.operationHandle for request in requests]


def python_error(message: str = "dbt Python failure test") -> str:
    payload = {
        "response": {
            "content": {
                "ename": "RuntimeError",
                "evalue": message,
                "traceback": [
                    "Traceback (most recent call last):\n",
                    '  File "<stdin>", line 1, in <module>\n',
                    "RuntimeError: {}\n".format(message),
                ],
            }
        }
    }
    return "Kyuubi Python operation failed: {}".format(json.dumps(payload))


def test_submit_uses_python_overlay_polls_and_closes(monkeypatch: Any) -> None:
    running = python_submissions.ttypes.TOperationState.RUNNING_STATE
    finished = python_submissions.ttypes.TOperationState.FINISHED_STATE
    client = FakeThriftClient({"operation-1": [running, finished]})
    connection = FakeConnection(client)
    helper = make_helper()
    monkeypatch.setattr(helper, "_connect", lambda: connection)
    monkeypatch.setattr(python_submissions.time, "sleep", lambda _: None)

    helper.submit("print('hello from dbt')")

    assert len(client.execute_requests) == 1
    request = client.execute_requests[0]
    assert request.sessionHandle == connection.sessionHandle
    assert request.statement == "print('hello from dbt')"
    assert request.runAsync is True
    assert request.confOverlay == {"kyuubi.operation.language": "PYTHON"}
    assert operation_handles(client.close_requests) == ["operation-1"]
    assert connection.closed is True


def test_python_helper_forwards_the_same_managed_binding_as_sql(monkeypatch: Any) -> None:
    parameters = {
        "kyuubi.engine.profile.id": "profile-a",
        "kyuubi.engine.profile.run.id": "run-a",
        "kyuubi.compute.attempt.id": "attempt-a",
        "kyuubi.compute.allocation.id": "allocation-a",
        "kyuubi.compute.binding.id": "binding-a",
        "kyuubi.compute.binding.token": "secret-a",
    }
    credentials = SimpleNamespace(
        method=SparkConnectionMethod.THRIFT,
        query_timeout=30,
        poll_interval=1,
        user="dbt-runner",
        auth="NONE",
        kerberos_service_name=None,
        password=None,
        server_side_parameters=parameters,
        use_ssl=False,
        host="kyuubi",
        port=10009,
    )
    helper = KyuubiThriftPythonJobHelper(
        {
            "name": "python_model",
            "alias": "python_model",
            "database": "lakehouse",
            "schema": "dbt_test",
            "config": {"materialized": "table"},
        },
        credentials,
    )
    captured = {}
    monkeypatch.setattr(
        python_submissions.hive,
        "connect",
        lambda **arguments: captured.update(arguments) or "connection",
    )

    assert helper._connect() == "connection"
    assert captured["configuration"] is parameters


def test_python_error_is_compacted_and_operation_is_closed(monkeypatch: Any) -> None:
    failed = python_submissions.ttypes.TOperationState.ERROR_STATE
    raw_error = python_error()
    client = FakeThriftClient(
        {"operation-1": [failed]},
        errors={"operation-1": raw_error},
    )
    connection = FakeConnection(client)
    helper = make_helper()
    monkeypatch.setattr(helper, "_connect", lambda: connection)

    with pytest.raises(DbtRuntimeError) as error:
        helper.submit("compiled model source that must not be printed")

    message = str(error.value)
    assert "RuntimeError: dbt Python failure test" in message
    assert "Traceback (most recent call last)" in message
    assert "compiled model source" not in message
    assert operation_handles(client.close_requests) == ["operation-1"]
    assert operation_handles(client.log_requests) == ["operation-1"]
    assert connection.closed is True


def test_timeout_cancels_then_closes_operation(monkeypatch: Any) -> None:
    running = python_submissions.ttypes.TOperationState.RUNNING_STATE
    client = FakeThriftClient({"operation-1": [running]})
    connection = FakeConnection(client)
    helper = make_helper(timeout=1)
    monotonic = iter([0.0, 2.0])
    monkeypatch.setattr(helper, "_connect", lambda: connection)
    monkeypatch.setattr(python_submissions.time, "monotonic", lambda: next(monotonic))

    with pytest.raises(DbtRuntimeError, match="timed out after 1 seconds"):
        helper.submit("while True: pass")

    assert operation_handles(client.cancel_requests) == ["operation-1"]
    assert operation_handles(client.close_requests) == ["operation-1"]
    assert connection.closed is True


def test_incremental_failure_drops_temp_table(monkeypatch: Any) -> None:
    failed = python_submissions.ttypes.TOperationState.ERROR_STATE
    finished = python_submissions.ttypes.TOperationState.FINISHED_STATE
    client = FakeThriftClient(
        {
            "operation-1": [failed],
            "operation-2": [finished],
        },
        errors={"operation-1": python_error("temporary table write failed")},
    )
    connection = FakeConnection(client)
    helper = make_helper({
        "materialized": "incremental",
        "file_format": "iceberg",
        "incremental_strategy": "merge",
        "unique_key": "id",
        "on_schema_change": "ignore",
    })
    monkeypatch.setattr(helper, "_connect", lambda: connection)

    with pytest.raises(DbtRuntimeError, match="temporary table write failed"):
        helper.submit("raise RuntimeError('temporary table write failed')")

    assert len(client.execute_requests) == 2
    cleanup = client.execute_requests[1]
    assert cleanup.statement == (
        "DROP TABLE IF EXISTS `lakehouse`.`dbt_test`.`python_model__dbt_tmp`"
    )
    assert operation_handles(client.close_requests) == ["operation-1", "operation-2"]
    assert connection.closed is True


def test_incremental_keyboard_interrupt_cancels_and_drops_temp_table(
    monkeypatch: Any,
) -> None:
    running = python_submissions.ttypes.TOperationState.RUNNING_STATE
    finished = python_submissions.ttypes.TOperationState.FINISHED_STATE
    client = FakeThriftClient({
        "operation-1": [running],
        "operation-2": [finished],
    })
    connection = FakeConnection(client)
    helper = make_helper({
        "materialized": "incremental",
        "file_format": "iceberg",
        "incremental_strategy": "merge",
        "unique_key": "id",
        "on_schema_change": "ignore",
    })
    monkeypatch.setattr(helper, "_connect", lambda: connection)
    monkeypatch.setattr(
        python_submissions.time,
        "sleep",
        lambda _: (_ for _ in ()).throw(KeyboardInterrupt()),
    )

    with pytest.raises(KeyboardInterrupt):
        helper.submit("long_running_incremental_model()")

    assert operation_handles(client.cancel_requests) == ["operation-1"]
    assert operation_handles(client.close_requests) == ["operation-1", "operation-2"]
    assert client.execute_requests[1].statement == (
        "DROP TABLE IF EXISTS `lakehouse`.`dbt_test`.`python_model__dbt_tmp`"
    )
    assert connection.closed is True


@pytest.mark.parametrize(
    ("config", "expected_error"),
    [
        (
            {
                "materialized": "incremental",
                "file_format": "delta",
                "incremental_strategy": "merge",
                "unique_key": "id",
            },
            "capability is not registered",
        ),
        (
            {
                "materialized": "incremental",
                "file_format": "iceberg",
                "incremental_strategy": "unknown_strategy",
                "unique_key": "id",
            },
            "capability is not registered",
        ),
        (
            {
                "materialized": "incremental",
                "file_format": "iceberg",
                "incremental_strategy": "merge",
            },
            "requires a non-empty unique_key",
        ),
        (
            {
                "materialized": "incremental",
                "file_format": "iceberg",
                "incremental_strategy": "merge",
                "unique_key": "id",
                "on_schema_change": "sync_all_columns",
            },
            "supports on_schema_change only",
        ),
        (
            {
                "materialized": "incremental",
                "file_format": "iceberg",
                "incremental_strategy": "insert_overwrite",
            },
            "requires config: partition_by",
        ),
        (
            {
                "materialized": "incremental",
                "file_format": "iceberg",
                "incremental_strategy": "microbatch",
                "partition_by": ["event_date"],
            },
            "requires config: batch_size, begin, event_time",
        ),
        (
            {
                "materialized": "incremental",
                "file_format": "iceberg",
                "incremental_strategy": "insert_overwrite",
                "partition_by": [""],
            },
            "requires partition_by as a non-empty column name",
        ),
        (
            {
                "materialized": "incremental",
                "file_format": "iceberg",
                "incremental_strategy": "microbatch",
                "partition_by": ["event_date"],
                "event_time": "event_time",
                "begin": "2026-01-01",
                "batch_size": "minute",
            },
            "requires batch_size as hour, day, month, or year",
        ),
    ],
)
def test_unregistered_incremental_capability_is_rejected_before_submit(
    config: Dict[str, Any],
    expected_error: str,
) -> None:
    with pytest.raises(DbtRuntimeError, match=expected_error):
        make_helper(config)


def test_incremental_capability_registry_contains_verified_spark_strategies() -> None:
    assert set(PYTHON_INCREMENTAL_CAPABILITIES) == {
        ("iceberg", "append"),
        ("iceberg", "insert_overwrite"),
        ("iceberg", "merge"),
        ("iceberg", "microbatch"),
    }


@pytest.mark.parametrize(
    "config",
    [
        {
            "materialized": "incremental",
            "file_format": "iceberg",
            "incremental_strategy": "append",
        },
        {
            "materialized": "incremental",
            "file_format": "iceberg",
            "incremental_strategy": "insert_overwrite",
            "partition_by": ["event_date"],
        },
        {
            "materialized": "incremental",
            "file_format": "iceberg",
            "incremental_strategy": "microbatch",
            "partition_by": ["event_date"],
            "event_time": "event_time",
            "begin": "2026-01-01",
            "batch_size": "day",
        },
    ],
)
def test_registered_incremental_capability_is_accepted(config: Dict[str, Any]) -> None:
    make_helper(config)


def test_microbatch_cleanup_uses_batch_specific_temp_relation() -> None:
    helper = make_helper(
        {
            "materialized": "incremental",
            "file_format": "iceberg",
            "incremental_strategy": "microbatch",
            "partition_by": ["event_date"],
            "event_time": "event_time",
            "begin": "2026-01-01",
            "batch_size": "day",
        },
        batch={"id": "20260101"},
    )

    assert helper._incremental_temp_relation() == (
        "`lakehouse`.`dbt_test`.`python_model__dbt_tmp_20260101`"
    )


def test_merge_sql_temp_relation_is_detected_for_cleanup() -> None:
    sql = """
      merge into `lakehouse`.`analytics`.`orders` as DBT_INTERNAL_DEST
      using `lakehouse`.`analytics`.`orders__dbt_tmp` as DBT_INTERNAL_SOURCE
      on DBT_INTERNAL_SOURCE.id = DBT_INTERNAL_DEST.id
    """

    assert KyuubiConnectionManager.incremental_temp_relations(sql) == [
        "`lakehouse`.`analytics`.`orders__dbt_tmp`"
    ]


def test_microbatch_sql_temp_relation_is_detected_for_cleanup() -> None:
    sql = """
      insert overwrite `lakehouse`.`analytics`.`events`
      select * from `lakehouse`.`analytics`.`events__dbt_tmp_20260101`
    """

    assert KyuubiConnectionManager.incremental_temp_relations(sql) == [
        "`lakehouse`.`analytics`.`events__dbt_tmp_20260101`"
    ]


def test_interrupted_merge_attempts_temp_relation_cleanup(monkeypatch: Any) -> None:
    manager = object.__new__(KyuubiConnectionManager)
    cleaned_sql: List[str] = []

    def interrupt(*args: Any, **kwargs: Any) -> Any:
        raise KeyboardInterrupt()

    monkeypatch.setattr(SparkConnectionManager, "execute", interrupt)
    monkeypatch.setattr(
        manager,
        "_cleanup_incremental_temp_relations",
        lambda sql: cleaned_sql.append(sql),
    )
    merge_sql = "merge into target using schema.orders__dbt_tmp on false"

    with pytest.raises(KeyboardInterrupt):
        manager.execute(merge_sql)

    assert cleaned_sql == [merge_sql]


def test_thrift_submission_failure_still_closes_connection(monkeypatch: Any) -> None:
    client = FakeThriftClient({})
    connection = FakeConnection(client)
    helper = make_helper()
    monkeypatch.setattr(helper, "_connect", lambda: connection)

    def reject(_: Any) -> Any:
        status = SimpleNamespace(
            statusCode=python_submissions.ttypes.TStatusCode.ERROR_STATUS,
            errorMessage="session rejected the statement",
        )
        return SimpleNamespace(status=status, operationHandle=None)

    monkeypatch.setattr(client, "ExecuteStatement", reject)

    with pytest.raises(DbtRuntimeError, match="session rejected the statement"):
        helper.submit("print('never started')")

    assert client.close_requests == []
    assert connection.closed is True


@pytest.mark.parametrize(
    ("raw_message", "expected"),
    [
        (python_error("short traceback"), "RuntimeError: short traceback"),
        ("plain Kyuubi error", "plain Kyuubi error"),
    ],
)
def test_compact_error(raw_message: str, expected: str) -> None:
    assert expected in KyuubiThriftPythonJobHelper._compact_error(raw_message)
