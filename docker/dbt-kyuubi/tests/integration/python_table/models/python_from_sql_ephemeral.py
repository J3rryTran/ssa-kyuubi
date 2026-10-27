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


def model(dbt, session):
    dbt.config(materialized="table", file_format="iceberg")
    ephemeral_orders = dbt.ref("ephemeral_order_features")
    return ephemeral_orders.selectExpr(
        "order_id",
        "customer_id",
        "amount_band",
        "doubled_amount + 5 as final_amount",
    )
