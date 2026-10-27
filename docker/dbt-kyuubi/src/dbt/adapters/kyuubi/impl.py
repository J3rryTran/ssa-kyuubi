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

from dataclasses import dataclass
from typing import Any, Dict, Iterable, Type

from dbt.adapters.base import BaseRelation, PythonJobHelper
from dbt.adapters.spark.impl import SparkAdapter, SparkConfig

from dbt.adapters.kyuubi.connections import KyuubiConnectionManager
from dbt.adapters.kyuubi.python_submissions import KyuubiThriftPythonJobHelper


@dataclass
class KyuubiConfig(SparkConfig):
    """Iceberg is the production default; projects and models can still override it."""

    file_format: str = "iceberg"


class KyuubiAdapter(SparkAdapter):
    """dbt-spark SQL behavior plus Kyuubi's Thrift Python submission method."""

    ConnectionManager = KyuubiConnectionManager
    AdapterSpecificConfigs = KyuubiConfig

    def _get_columns_for_catalog(
        self,
        relation: BaseRelation,
    ) -> Iterable[Dict[str, Any]]:
        """Read Iceberg V2 columns when Spark cannot use SHOW TABLE EXTENDED."""

        columns = list(super()._get_columns_for_catalog(relation))
        if not columns:
            columns = []
            for column in self.get_columns_in_relation(relation):
                as_dict = column.to_column_dict()
                as_dict["column_name"] = as_dict.pop("column", None)
                as_dict["column_type"] = as_dict.pop("dtype")
                as_dict["table_database"] = None
                columns.append(as_dict)
        yield from columns

    @property
    def default_python_submission_method(self) -> str:
        return "kyuubi_thrift"

    @property
    def python_submission_helpers(self) -> Dict[str, Type[PythonJobHelper]]:
        return {
            **super().python_submission_helpers,
            "kyuubi_thrift": KyuubiThriftPythonJobHelper,
        }
