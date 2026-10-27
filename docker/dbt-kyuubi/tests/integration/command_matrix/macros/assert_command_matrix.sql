-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

{% macro command_matrix_relation(identifier) %}
  {% set catalog = env_var('KYUUBI_DEFAULT_CATALOG', 'lakehouse') %}
  {% set schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {{ return(
    adapter.quote(catalog) ~ '.' ~
    adapter.quote(schema) ~ '.' ~
    adapter.quote(identifier)) }}
{% endmacro %}

{% macro assert_command_seed() %}
  {% set result = run_query(
    'SELECT count(*), sum(id) FROM ' ~ command_matrix_relation('command_seed')) %}
  {% set row = result.rows[0] %}
  {% if row[0] | int != 2 or row[1] | int != 3 %}
    {{ exceptions.raise_compiler_error('Unexpected seed state: ' ~ row) }}
  {% endif %}
{% endmacro %}

{% macro assert_command_model() %}
  {% set result = run_query(
    'SELECT count(*), sum(id), sum(score) FROM ' ~
    command_matrix_relation('command_model')) %}
  {% set row = result.rows[0] %}
  {% if row[0] | int != 2 or row[1] | int != 3 or row[2] | int != 30 %}
    {{ exceptions.raise_compiler_error('Unexpected model state: ' ~ row) }}
  {% endif %}
{% endmacro %}

{% macro reset_command_snapshot() %}
  {% do run_query(
    'DROP TABLE IF EXISTS ' ~ command_matrix_relation('command_snapshot')) %}
  {% do run_query('DROP VIEW IF EXISTS command_snapshot__dbt_tmp') %}
{% endmacro %}

{% macro assert_command_snapshot() %}
  {% set result = run_query(
    'SELECT count(*), ' ~
    'sum(case when dbt_valid_to is null then 1 else 0 end), ' ~
    'max(case when dbt_valid_to is null then value end), ' ~
    "sum(case when value = 'alpha' and dbt_valid_to is not null then 1 else 0 end) " ~
    'FROM ' ~ command_matrix_relation('command_snapshot')) %}
  {% set row = result.rows[0] %}
  {% if row[0] | int != 2
      or row[1] | int != 1
      or row[2] != 'beta'
      or row[3] | int != 1 %}
    {{ exceptions.raise_compiler_error('Unexpected snapshot state: ' ~ row) }}
  {% endif %}
{% endmacro %}

{% macro assert_retry_model() %}
  {% set result = run_query(
    'SELECT count(*), max(id) FROM ' ~ command_matrix_relation('retry_failure')) %}
  {% set row = result.rows[0] %}
  {% if row[0] | int != 1 or row[1] | int != 1 %}
    {{ exceptions.raise_compiler_error('Unexpected retry state: ' ~ row) }}
  {% endif %}
{% endmacro %}
