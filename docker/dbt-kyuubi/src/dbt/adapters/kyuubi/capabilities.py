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

"""Capability contract for Kyuubi Python incremental materializations."""

from dataclasses import dataclass
from typing import Any, Dict, FrozenSet, Mapping, Tuple

from dbt_common.exceptions import DbtRuntimeError


@dataclass(frozen=True)
class PythonIncrementalCapability:
    """One format/strategy combination enabled for compatibility testing."""

    requires_unique_key: bool
    required_configs: FrozenSet[str]
    on_schema_change: FrozenSet[str]


PYTHON_INCREMENTAL_CAPABILITIES: Mapping[
    Tuple[str, str], PythonIncrementalCapability
] = {
    ("iceberg", "merge"): PythonIncrementalCapability(
        requires_unique_key=True,
        required_configs=frozenset(),
        on_schema_change=frozenset({"ignore", "append_new_columns"}),
    ),
    ("iceberg", "append"): PythonIncrementalCapability(
        requires_unique_key=False,
        required_configs=frozenset(),
        on_schema_change=frozenset({"ignore", "append_new_columns"}),
    ),
    ("iceberg", "insert_overwrite"): PythonIncrementalCapability(
        requires_unique_key=False,
        required_configs=frozenset({"partition_by"}),
        on_schema_change=frozenset({"ignore", "append_new_columns"}),
    ),
    ("iceberg", "microbatch"): PythonIncrementalCapability(
        requires_unique_key=False,
        required_configs=frozenset({"partition_by", "event_time", "begin", "batch_size"}),
        on_schema_change=frozenset({"ignore", "append_new_columns"}),
    ),
}


def _has_valid_unique_key(unique_key: Any) -> bool:
    if isinstance(unique_key, str):
        return bool(unique_key.strip())
    if isinstance(unique_key, (list, tuple)):
        return bool(unique_key) and all(
            isinstance(key, str) and bool(key.strip()) for key in unique_key
        )
    return False


def _has_valid_partition_by(partition_by: Any) -> bool:
    if isinstance(partition_by, str):
        return bool(partition_by.strip())
    if isinstance(partition_by, (list, tuple)):
        return bool(partition_by) and all(
            isinstance(column, str) and bool(column.strip()) for column in partition_by
        )
    return False


def validate_python_incremental_config(config: Dict[str, Any]) -> None:
    """Reject unverified Python incremental combinations before connecting to Kyuubi."""

    if config.get("materialized") != "incremental":
        return

    file_format = str(config.get("file_format") or "").lower()
    strategy = str(config.get("incremental_strategy") or "").lower()
    capability = PYTHON_INCREMENTAL_CAPABILITIES.get((file_format, strategy))
    if capability is None:
        supported = ", ".join(
            "file_format={}, incremental_strategy={}".format(*key)
            for key in sorted(PYTHON_INCREMENTAL_CAPABILITIES)
        )
        raise DbtRuntimeError(
            "Kyuubi Python incremental capability is not registered for "
            "file_format={}, incremental_strategy={}. Supported: {}".format(
                file_format or "<missing>",
                strategy or "<missing>",
                supported,
            )
        )

    unique_key = config.get("unique_key")
    if capability.requires_unique_key and not _has_valid_unique_key(unique_key):
        raise DbtRuntimeError(
            "Kyuubi Python incremental {} {} requires a non-empty unique_key".format(
                file_format,
                strategy,
            )
        )

    missing_configs = sorted(
        key for key in capability.required_configs if not config.get(key)
    )
    if missing_configs:
        raise DbtRuntimeError(
            "Kyuubi Python incremental {} {} requires config: {}".format(
                file_format,
                strategy,
                ", ".join(missing_configs),
            )
        )

    if "partition_by" in capability.required_configs and not _has_valid_partition_by(
        config.get("partition_by")
    ):
        raise DbtRuntimeError(
            "Kyuubi Python incremental {} {} requires partition_by as a non-empty "
            "column name or list of column names".format(file_format, strategy)
        )

    if strategy == "microbatch" and config.get("batch_size") not in {
        "hour",
        "day",
        "month",
        "year",
    }:
        raise DbtRuntimeError(
            "Kyuubi Python incremental iceberg microbatch requires batch_size as "
            "hour, day, month, or year"
        )

    on_schema_change = str(config.get("on_schema_change") or "ignore").lower()
    if on_schema_change not in capability.on_schema_change:
        raise DbtRuntimeError(
            "Kyuubi Python incremental {} {} supports on_schema_change only as {}".format(
                file_format,
                strategy,
                " or ".join(sorted(capability.on_schema_change)),
            )
        )
