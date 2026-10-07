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

"""Submit dbt-spark compiled Python models through a Kyuubi Thrift session."""

import json
import logging
import time
from typing import Any, Dict, Optional

from dbt.adapters.base import PythonJobHelper
from dbt.adapters.spark.connections import (
    SparkConnectionMethod,
    SparkCredentials,
    build_ssl_transport,
)
from dbt_common.exceptions import DbtRuntimeError

try:
    from TCLIService import ttypes
    from pyhive import hive
except ImportError:
    ttypes = None
    hive = None


_OPERATION_LANGUAGE_KEY = "kyuubi.operation.language"
_OPERATION_LANGUAGE_PYTHON = "PYTHON"
_DEFAULT_TIMEOUT_SECONDS = 60 * 60 * 24
_DEFAULT_POLL_INTERVAL_SECONDS = 1
_MAX_CLI_ERROR_CHARACTERS = 12000

logger = logging.getLogger(__name__)


class KyuubiThriftPythonJobHelper(PythonJobHelper):
    """Runs one compiled dbt Python model as a Kyuubi Python operation.

    dbt-spark prepares the model script, including its ``saveAsTable`` materialization. This helper
    deliberately does not mutate that script or create a separate Spark application. It opens a
    Kyuubi Thrift session with the profile ID supplied in ``server_side_parameters`` and submits
    exactly that script with an operation-scoped Python-language overlay.
    """

    def __init__(self, parsed_model: Dict[str, Any], credentials: SparkCredentials) -> None:
        if ttypes is None or hive is None:
            raise DbtRuntimeError(
                "Kyuubi Python submission requires dbt-spark[PyHive] to be installed")
        if credentials.method != SparkConnectionMethod.THRIFT:
            raise DbtRuntimeError(
                "Kyuubi Python model submission supports only method: thrift")

        self.parsed_model = parsed_model
        self.credentials = credentials
        self._validate_incremental_configuration()
        configured_timeout = parsed_model["config"].get("timeout")
        self.timeout_seconds = int(
            configured_timeout
            if configured_timeout is not None
            else credentials.query_timeout or _DEFAULT_TIMEOUT_SECONDS)
        if self.timeout_seconds <= 0:
            raise DbtRuntimeError("Python model timeout must be a positive number of seconds")
        self.poll_interval_seconds = max(
            int(credentials.poll_interval or _DEFAULT_POLL_INTERVAL_SECONDS),
            _DEFAULT_POLL_INTERVAL_SECONDS)

    def _validate_incremental_configuration(self) -> None:
        config = self.parsed_model.get("config") or {}
        if config.get("materialized") != "incremental":
            return

        file_format = str(config.get("file_format") or "").lower()
        strategy = str(config.get("incremental_strategy") or "").lower()
        unique_key = config.get("unique_key")
        on_schema_change = str(config.get("on_schema_change") or "ignore").lower()

        if file_format != "iceberg" or strategy != "merge" or not unique_key:
            raise DbtRuntimeError(
                "Kyuubi Python incremental supports only file_format=iceberg, "
                "incremental_strategy=merge, and a non-empty unique_key")
        if on_schema_change not in {"ignore", "append_new_columns"}:
            raise DbtRuntimeError(
                "Kyuubi Python incremental supports on_schema_change only as "
                "ignore or append_new_columns")

    def submit(self, compiled_code: str) -> None:
        connection = None
        operation_handle = None
        failed = False
        try:
            connection = self._connect()
            request = ttypes.TExecuteStatementReq(
                sessionHandle=connection.sessionHandle,
                statement=compiled_code,
                runAsync=True,
                confOverlay={_OPERATION_LANGUAGE_KEY: _OPERATION_LANGUAGE_PYTHON},
            )
            response = connection.client.ExecuteStatement(request)
            self._check_response(response, "submit Python model")
            operation_handle = response.operationHandle
            self._wait_for_completion(connection, operation_handle)
        except KeyboardInterrupt:
            failed = True
            if connection is not None and operation_handle is not None:
                self._cancel_operation(connection, operation_handle)
            raise
        except BaseException:
            failed = True
            raise
        finally:
            if connection is not None and operation_handle is not None:
                self._close_operation(connection, operation_handle)
            if failed and connection is not None:
                self._drop_incremental_temp_relation(connection)
            if connection is not None:
                connection.close()

    def _incremental_temp_relation(self) -> Optional[str]:
        config = self.parsed_model.get("config") or {}
        if config.get("materialized") != "incremental":
            return None

        database = self.parsed_model.get("database")
        schema = self.parsed_model.get("schema")
        alias = self.parsed_model.get("alias") or self.parsed_model.get("name")
        if not isinstance(schema, str) or not schema or not isinstance(alias, str) or not alias:
            return None
        identifiers = [
            identifier
            for identifier in (database, schema, alias + "__dbt_tmp")
            if isinstance(identifier, str) and identifier
        ]
        return ".".join(
            "`{}`".format(identifier.replace("`", "``")) for identifier in identifiers)

    def _drop_incremental_temp_relation(self, connection: Any) -> None:
        relation = self._incremental_temp_relation()
        if relation is None:
            return
        operation_handle = None
        try:
            response = connection.client.ExecuteStatement(
                ttypes.TExecuteStatementReq(
                    sessionHandle=connection.sessionHandle,
                    statement="DROP TABLE IF EXISTS {}".format(relation),
                    runAsync=True,
                ))
            self._check_response(response, "clean up Python incremental temporary table")
            operation_handle = response.operationHandle
            self._wait_for_completion(connection, operation_handle)
        except Exception:
            logger.warning("Could not clean up Python incremental temporary table %s", relation)
        finally:
            if operation_handle is not None:
                self._close_operation(connection, operation_handle)

    def _connect(self) -> Any:
        credentials = self.credentials
        common_arguments = {
            "username": credentials.user,
            "auth": credentials.auth,
            "kerberos_service_name": credentials.kerberos_service_name,
            "password": credentials.password,
            "configuration": credentials.server_side_parameters,
        }
        if credentials.use_ssl:
            transport = build_ssl_transport(
                host=credentials.host,
                port=credentials.port,
                username=credentials.user,
                auth=credentials.auth,
                kerberos_service_name=credentials.kerberos_service_name,
                password=credentials.password,
            )
            return hive.connect(
                thrift_transport=transport,
                configuration=credentials.server_side_parameters,
            )
        return hive.connect(host=credentials.host, port=credentials.port, **common_arguments)

    def _wait_for_completion(self, connection: Any, operation_handle: Any) -> None:
        started_at = time.monotonic()
        while True:
            response = connection.client.GetOperationStatus(
                ttypes.TGetOperationStatusReq(
                    operationHandle=operation_handle,
                    getProgressUpdate=True,
                )
            )
            self._check_response(response, "poll Python model")
            state = response.operationState
            if state == ttypes.TOperationState.FINISHED_STATE:
                return
            if state in self._failed_states():
                raise DbtRuntimeError(self._failure_message(connection, operation_handle, response))
            if time.monotonic() - started_at > self.timeout_seconds:
                self._cancel_operation(connection, operation_handle)
                raise DbtRuntimeError(
                    "Python model timed out after {} seconds".format(self.timeout_seconds))
            time.sleep(self.poll_interval_seconds)

    @staticmethod
    def _failed_states() -> set:
        return {
            ttypes.TOperationState.CANCELED_STATE,
            ttypes.TOperationState.CLOSED_STATE,
            ttypes.TOperationState.ERROR_STATE,
            getattr(ttypes.TOperationState, "UNKNOWN_STATE", None),
            getattr(ttypes.TOperationState, "TIMEDOUT_STATE", None),
        }

    def _failure_message(self, connection: Any, operation_handle: Any, response: Any) -> str:
        raw_message = getattr(response, "errorMessage", None) or "Kyuubi Python operation failed"
        logs = self._operation_logs(connection, operation_handle)
        if logs:
            logger.debug("Kyuubi Python operation log:\n%s", logs)
        logger.debug("Kyuubi Python operation failure:\n%s", raw_message)
        return "Python model failed with traceback:\n{}".format(
            self._compact_error(raw_message))

    @staticmethod
    def _compact_error(raw_message: str) -> str:
        """Keep dbt CLI actionable without printing compiled source or a Java stack trace."""
        try:
            payload = json.loads(raw_message[raw_message.index("{") :])
            content = payload.get("response", {}).get("content", {})
            ename = content.get("ename")
            evalue = content.get("evalue")
            traceback = "".join(content.get("traceback") or [])
            if ename or evalue or traceback:
                message = "{}: {}".format(ename or "Python error", evalue or "")
                return (message + "\n" + traceback).strip()[:_MAX_CLI_ERROR_CHARACTERS]
        except (ValueError, TypeError, json.JSONDecodeError):
            pass
        return raw_message[:_MAX_CLI_ERROR_CHARACTERS]

    def _operation_logs(self, connection: Any, operation_handle: Any) -> str:
        try:
            response = connection.client.GetLog(
                ttypes.TGetLogReq(operationHandle=operation_handle))
            self._check_response(response, "read Python model log")
            return getattr(response, "log", "") or ""
        except Exception:
            return ""

    def _cancel_operation(self, connection: Any, operation_handle: Any) -> None:
        try:
            response = connection.client.CancelOperation(
                ttypes.TCancelOperationReq(operationHandle=operation_handle))
            self._check_response(response, "cancel Python model")
        except Exception:
            # The timeout error is more useful to dbt than a best-effort cancellation failure.
            pass

    def _close_operation(self, connection: Any, operation_handle: Any) -> None:
        try:
            response = connection.client.CloseOperation(
                ttypes.TCloseOperationReq(operationHandle=operation_handle))
            self._check_response(response, "close Python model operation")
        except Exception:
            # Do not hide the execution error when Kyuubi has already removed the operation.
            pass

    @staticmethod
    def _check_response(response: Any, action: str) -> None:
        status = getattr(response, "status", None)
        if status is None:
            return
        if status.statusCode != ttypes.TStatusCode.SUCCESS_STATUS:
            message = getattr(status, "errorMessage", None) or "unknown Thrift error"
            raise DbtRuntimeError(
                "Could not {}: {}".format(action, message[:_MAX_CLI_ERROR_CHARACTERS]))
