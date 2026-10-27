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

import logging
import re
from dataclasses import dataclass
from typing import List, Optional
from uuid import uuid4

from dbt.adapters.spark.connections import SparkConnectionManager, SparkCredentials
from dbt_common.exceptions import DbtRuntimeError


logger = logging.getLogger(__name__)

_QUOTED_IDENTIFIER = r"`(?:``|[^`])+`"
_PLAIN_IDENTIFIER = r"[A-Za-z_][A-Za-z0-9_$-]*"
_RELATION = (
    rf"(?:{_QUOTED_IDENTIFIER}|{_PLAIN_IDENTIFIER})"
    rf"(?:\.(?:{_QUOTED_IDENTIFIER}|{_PLAIN_IDENTIFIER}))*"
)
_RELATION_AFTER_SQL_KEYWORD = re.compile(
    rf"\b(?:from|table|using)\s+({_RELATION})",
    re.IGNORECASE,
)
_DBT_TEMP_IDENTIFIER = re.compile(r".+__dbt_tmp(?:_[A-Za-z0-9_]+)?$")
_RUN_ID_KEY = "kyuubi.engine.profile.run.id"
_MANAGED_COMPUTE_KEYS = {
    "kyuubi.engine.profile.id",
    _RUN_ID_KEY,
    "kyuubi.compute.attempt.id",
    "kyuubi.compute.allocation.id",
    "kyuubi.compute.binding.id",
    "kyuubi.compute.binding.token",
}
_MANAGED_COMPUTE_SELECTOR_KEYS = _MANAGED_COMPUTE_KEYS - {
    "kyuubi.engine.profile.id",
    _RUN_ID_KEY,
}


@dataclass
class KyuubiCredentials(SparkCredentials):
    """Spark credentials with a distinct adapter type and one generated DBT run lease."""

    def __post_init__(self) -> None:
        super().__post_init__()
        parameters = self.server_side_parameters
        selected_managed_keys = _MANAGED_COMPUTE_SELECTOR_KEYS.intersection(parameters)
        if selected_managed_keys:
            missing = _MANAGED_COMPUTE_KEYS.difference(parameters)
            empty = {
                key
                for key in _MANAGED_COMPUTE_KEYS
                if not str(parameters.get(key, "")).strip()
            }
            if missing or empty:
                invalid = sorted(missing.union(empty))
                raise DbtRuntimeError(
                    "Managed Kyuubi compute configuration is incomplete: {}".format(
                        ", ".join(invalid)
                    )
                )
            # All fields came from a server-generated profile plus a Kubernetes Secret. Keep the
            # backend run/attempt identity unchanged so every SQL thread and Python helper attach
            # to the same allocation. Kyuubi still authenticates and scopes the bearer token.
            return

        # A local CLI invocation cannot select managed compute. Ignore any caller-owned run ID
        # and issue one invocation-local ID shared by its SQL and Python connections.
        parameters[_RUN_ID_KEY] = str(uuid4())

    @property
    def type(self) -> str:
        return "kyuubi"


class KyuubiConnectionManager(SparkConnectionManager):
    TYPE = "kyuubi"

    @staticmethod
    def incremental_temp_relations(sql: str) -> List[str]:
        """Return safe relation tokens ending in dbt's incremental temporary suffix."""

        relations = []
        for match in _RELATION_AFTER_SQL_KEYWORD.finditer(sql):
            relation = match.group(1)
            identifier = relation.rsplit(".", 1)[-1]
            if identifier.startswith("`"):
                identifier = identifier[1:-1].replace("``", "`")
            if _DBT_TEMP_IDENTIFIER.fullmatch(identifier) and relation not in relations:
                relations.append(relation)
        return relations

    def _cleanup_incremental_temp_relations(self, sql: str) -> None:
        for relation in self.incremental_temp_relations(sql):
            try:
                super().execute("DROP TABLE IF EXISTS {}".format(relation))
            except BaseException:
                logger.warning(
                    "Could not clean up Python incremental temporary table %s",
                    relation,
                    exc_info=True,
                )

    def execute(
        self,
        sql: str,
        auto_begin: bool = False,
        fetch: bool = False,
        limit: Optional[int] = None,
    ):
        """Clean an incremental temporary source if SQL fails or is interrupted."""

        try:
            return super().execute(
                sql=sql,
                auto_begin=auto_begin,
                fetch=fetch,
                limit=limit,
            )
        except BaseException:
            self._cleanup_incremental_temp_relations(sql)
            raise
