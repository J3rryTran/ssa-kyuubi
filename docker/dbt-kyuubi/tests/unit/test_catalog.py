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

from types import SimpleNamespace
from typing import Any, Dict

from dbt.adapters.kyuubi.impl import KyuubiAdapter


class CatalogColumn:
    def to_column_dict(self) -> Dict[str, Any]:
        return {
            "table_database": None,
            "table_schema": "analytics",
            "table_name": "orders",
            "table_type": "table",
            "table_comment": None,
            "table_owner": None,
            "column": "id",
            "column_index": 0,
            "dtype": "bigint",
            "column_comment": None,
            "stats:bytes:label": None,
            "stats:bytes:value": None,
            "stats:bytes:description": None,
            "stats:bytes:include": False,
            "stats:rows:label": None,
            "stats:rows:value": None,
            "stats:rows:description": None,
            "stats:rows:include": False,
        }


def test_catalog_falls_back_to_describe_for_iceberg_v2(monkeypatch: Any) -> None:
    adapter = object.__new__(KyuubiAdapter)
    relation = SimpleNamespace(information="")
    monkeypatch.setattr(adapter, "get_columns_in_relation", lambda _: [CatalogColumn()])

    columns = list(adapter._get_columns_for_catalog(relation))

    assert len(columns) == 1
    assert columns[0]["column_name"] == "id"
    assert columns[0]["column_type"] == "bigint"
    assert "column" not in columns[0]
    assert "dtype" not in columns[0]
