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
from uuid import uuid4

from dbt.adapters.spark.connections import SparkConnectionManager, SparkCredentials



@dataclass
class KyuubiCredentials(SparkCredentials):
    """Spark credentials with a distinct adapter type and one generated DBT run lease."""

    def __post_init__(self) -> None:
        super().__post_init__()
        # Do not accept a browser or profiles.yml supplied run ID. One dbt process owns one
        # credentials object, so SQL and Python connections share this generated value.
        self.server_side_parameters["kyuubi.engine.profile.run.id"] = str(uuid4())

    @property
    def type(self) -> str:
        return "kyuubi"


class KyuubiConnectionManager(SparkConnectionManager):
    TYPE = "kyuubi"
