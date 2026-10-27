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

{% macro fixture_relation(schema_name, identifier) %}
  {% set catalog = env_var('KYUUBI_DEFAULT_CATALOG', 'lakehouse') %}
  {{ return(
    adapter.quote(catalog) ~ '.' ~
    adapter.quote(schema_name) ~ '.' ~
    adapter.quote(identifier)) }}
{% endmacro %}

{% macro prepare_python_table_fixture() %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set source_schema = target_schema ~ '_source' %}
  {% set catalog = env_var('KYUUBI_DEFAULT_CATALOG', 'lakehouse') %}
  {% set source_relation = fixture_relation(source_schema, 'multipliers') %}
  {% set before_relation = fixture_relation(target_schema, 'python_table_failure_before') %}
  {% set during_relation = fixture_relation(target_schema, 'python_table_failure_during_write') %}

  {% do run_query(
    'CREATE SCHEMA IF NOT EXISTS ' ~ adapter.quote(catalog) ~ '.' ~
    adapter.quote(source_schema)) %}
  {% do run_query(
    'CREATE SCHEMA IF NOT EXISTS ' ~ adapter.quote(catalog) ~ '.' ~
    adapter.quote(target_schema)) %}

  {% do run_query('DROP TABLE IF EXISTS ' ~ source_relation) %}
  {% do run_query(
    'CREATE TABLE ' ~ source_relation ~ ' USING iceberg AS ' ~
    'SELECT * FROM VALUES (1, 2), (2, 3) AS source(order_id, multiplier)') %}

  {% for relation in [before_relation, during_relation] %}
    {% do run_query('DROP TABLE IF EXISTS ' ~ relation) %}
    {% do run_query(
      'CREATE TABLE ' ~ relation ~ ' USING iceberg AS ' ~
      'SELECT cast(99 as bigint) AS id, cast(99 as bigint) AS value') %}
  {% endfor %}
{% endmacro %}

{% macro assert_python_table_results() %}
  {% set python_relation = ref('python_table_complete') %}
  {% set downstream_relation = ref('python_table_downstream') %}
  {% set python_result = run_query(
    'SELECT count(*) AS row_count, sum(adjusted_amount) AS total ' ~
    'FROM ' ~ python_relation) %}
  {% set downstream_result = run_query(
    'SELECT count(*) AS row_count, sum(reported_amount) AS total ' ~
    'FROM ' ~ downstream_relation) %}

  {% if python_result.rows[0][0] | int != 2 or python_result.rows[0][1] | int != 80 %}
    {{ exceptions.raise_compiler_error(
      'Unexpected Python table result: ' ~ python_result.rows[0]) }}
  {% endif %}
  {% if downstream_result.rows[0][0] | int != 2 or downstream_result.rows[0][1] | int != 82 %}
    {{ exceptions.raise_compiler_error(
      'Unexpected downstream SQL result: ' ~ downstream_result.rows[0]) }}
  {% endif %}
{% endmacro %}

{% macro assert_python_from_sql_ephemeral_results() %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set catalog = env_var('KYUUBI_DEFAULT_CATALOG', 'lakehouse') %}
  {% set python_relation = ref('python_from_sql_ephemeral') %}
  {% set result = run_query(
    'SELECT count(*) AS row_count, sum(final_amount) AS total, ' ~
    "sum(case when amount_band = 'large' then 1 else 0 end) AS large_count " ~
    'FROM ' ~ python_relation) %}

  {% if result.rows[0][0] | int != 2
      or result.rows[0][1] | int != 70
      or result.rows[0][2] | int != 1 %}
    {{ exceptions.raise_compiler_error(
      'Unexpected SQL ephemeral to Python result: ' ~ result.rows[0]) }}
  {% endif %}

  {% set ephemeral_tables = run_query(
    'SHOW TABLES IN ' ~ adapter.quote(catalog) ~ '.' ~ adapter.quote(target_schema) ~
    " LIKE 'ephemeral_order_features'") %}
  {% if ephemeral_tables.rows | length != 0 %}
    {{ exceptions.raise_compiler_error(
      'SQL ephemeral model unexpectedly created a catalog relation: ' ~
      ephemeral_tables.rows) }}
  {% endif %}
{% endmacro %}

{% macro assert_failure_baseline(identifier) %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set relation = fixture_relation(target_schema, identifier) %}
  {% set result = run_query(
    'SELECT count(*) AS row_count, max(id) AS max_id, max(value) AS max_value ' ~
    'FROM ' ~ relation) %}
  {% if result.rows[0][0] | int != 1
      or result.rows[0][1] | int != 99
      or result.rows[0][2] | int != 99 %}
    {{ exceptions.raise_compiler_error(
      'The existing Iceberg table changed after Python failure: ' ~ result.rows[0]) }}
  {% endif %}
{% endmacro %}
