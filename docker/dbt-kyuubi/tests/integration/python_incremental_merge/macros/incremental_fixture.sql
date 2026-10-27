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

{% macro incremental_fixture_relation(schema_name, identifier) %}
  {% set catalog = env_var('KYUUBI_DEFAULT_CATALOG', 'lakehouse') %}
  {{ return(
    adapter.quote(catalog) ~ '.' ~
    adapter.quote(schema_name) ~ '.' ~
    adapter.quote(identifier)) }}
{% endmacro %}

{% macro set_merge_input(scenario='initial') %}
  {% set source_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') ~ '_source' %}
  {% set relation = incremental_fixture_relation(source_schema, 'merge_input') %}
  {% do run_query('DROP TABLE IF EXISTS ' ~ relation) %}

  {% if scenario == 'initial' %}
    {% set values = "(1, 10), (2, 20)" %}
  {% elif scenario == 'updated' %}
    {% set values = "(1, 11), (2, 20), (3, 30)" %}
  {% elif scenario == 'null_key' %}
    {% set values = "(cast(null as bigint), 40), (2, 20)" %}
  {% elif scenario == 'duplicate_key' %}
    {% set values = "(1, 11), (1, 12), (2, 20)" %}
  {% else %}
    {{ exceptions.raise_compiler_error('Unknown merge input scenario: ' ~ scenario) }}
  {% endif %}

  {% do run_query(
    'CREATE TABLE ' ~ relation ~ ' USING iceberg AS ' ~
    'SELECT * FROM VALUES ' ~ values ~ ' AS source(id, value)') %}
{% endmacro %}

{% macro set_schema_input(include_extra_column=false) %}
  {% set source_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') ~ '_source' %}
  {% set relation = incremental_fixture_relation(source_schema, 'schema_input') %}
  {% do run_query('DROP TABLE IF EXISTS ' ~ relation) %}
  {% if include_extra_column %}
    {% do run_query(
      'CREATE TABLE ' ~ relation ~ ' USING iceberg AS ' ~
      "SELECT * FROM VALUES (1, 11, 'updated'), (2, 20, 'stable'), " ~
      "(3, 30, 'new') AS source(id, value, category)") %}
  {% else %}
    {% do run_query(
      'CREATE TABLE ' ~ relation ~ ' USING iceberg AS ' ~
      'SELECT * FROM VALUES (1, 10), (2, 20) AS source(id, value)') %}
  {% endif %}
{% endmacro %}

{% macro set_failure_input(should_fail=false) %}
  {% set source_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') ~ '_source' %}
  {% set relation = incremental_fixture_relation(source_schema, 'failure_input') %}
  {% do run_query('DROP TABLE IF EXISTS ' ~ relation) %}
  {% do run_query(
    'CREATE TABLE ' ~ relation ~ ' USING iceberg AS ' ~
    'SELECT * FROM VALUES (1, 10, ' ~
    ('true' if should_fail else 'false') ~
    ') AS source(id, value, should_fail)') %}
{% endmacro %}

{% macro set_append_input(stage='initial') %}
  {% set source_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') ~ '_source' %}
  {% set relation = incremental_fixture_relation(source_schema, 'append_input') %}
  {% do run_query('DROP TABLE IF EXISTS ' ~ relation) %}
  {% if stage == 'initial' %}
    {% set values = '(1, 10), (2, 20)' %}
  {% elif stage == 'new_only' %}
    {% set values = '(3, 30)' %}
  {% else %}
    {{ exceptions.raise_compiler_error('Unknown append input stage: ' ~ stage) }}
  {% endif %}
  {% do run_query(
    'CREATE TABLE ' ~ relation ~ ' USING iceberg AS ' ~
    'SELECT * FROM VALUES ' ~ values ~ ' AS source(id, value)') %}
{% endmacro %}

{% macro set_overwrite_input(stage='initial') %}
  {% set source_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') ~ '_source' %}
  {% set relation = incremental_fixture_relation(source_schema, 'overwrite_input') %}
  {% do run_query('DROP TABLE IF EXISTS ' ~ relation) %}
  {% if stage == 'initial' %}
    {% set values =
      "(1, 10, date '2026-01-01', false), " ~
      "(2, 20, date '2026-01-01', false), " ~
      "(3, 30, date '2026-01-02', false)" %}
  {% elif stage == 'replace_day_one' %}
    {% set values = "(1, 11, date '2026-01-01', false)" %}
  {% elif stage == 'fail_day_two' %}
    {% set values = "(3, 999, date '2026-01-02', true)" %}
  {% elif stage == 'recover_day_two' %}
    {% set values = "(3, 31, date '2026-01-02', false)" %}
  {% else %}
    {{ exceptions.raise_compiler_error('Unknown overwrite input stage: ' ~ stage) }}
  {% endif %}
  {% do run_query(
    'CREATE TABLE ' ~ relation ~ ' USING iceberg AS ' ~
    'SELECT * FROM VALUES ' ~ values ~
    ' AS source(id, value, event_date, should_fail)') %}
{% endmacro %}

{% macro set_microbatch_input(fail_second_batch=false) %}
  {% set source_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') ~ '_source' %}
  {% set relation = incremental_fixture_relation(source_schema, 'microbatch_input') %}
  {% do run_query('DROP TABLE IF EXISTS ' ~ relation) %}
  {% set second_failure = 'true' if fail_second_batch else 'false' %}
  {% do run_query(
    'CREATE TABLE ' ~ relation ~ ' USING iceberg AS SELECT * FROM VALUES ' ~
    "(1, 10, timestamp '2026-01-01 01:00:00', date '2026-01-01', false), " ~
    "(2, 20, timestamp '2026-01-02 01:00:00', date '2026-01-02', " ~
    second_failure ~ ') AS source(id, value, event_time, event_date, should_fail)') %}
{% endmacro %}

{% macro prepare_incremental_fixture() %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set source_schema = target_schema ~ '_source' %}
  {% set catalog = env_var('KYUUBI_DEFAULT_CATALOG', 'lakehouse') %}
  {% do run_query(
    'CREATE SCHEMA IF NOT EXISTS ' ~ adapter.quote(catalog) ~ '.' ~
    adapter.quote(target_schema)) %}
  {% do run_query(
    'CREATE SCHEMA IF NOT EXISTS ' ~ adapter.quote(catalog) ~ '.' ~
    adapter.quote(source_schema)) %}

  {% for identifier in [
      'incremental_merge',
      'incremental_append_columns',
      'incremental_temp_failure',
      'incremental_merge_failure',
      'incremental_append',
      'incremental_insert_overwrite',
      'incremental_microbatch'] %}
    {% set target = incremental_fixture_relation(target_schema, identifier) %}
    {% set temporary = incremental_fixture_relation(target_schema, identifier ~ '__dbt_tmp') %}
    {% do run_query('DROP TABLE IF EXISTS ' ~ temporary) %}
    {% do run_query('DROP TABLE IF EXISTS ' ~ target) %}
  {% endfor %}

  {% do set_merge_input('initial') %}
  {% do set_schema_input(false) %}
  {% do set_failure_input(false) %}
  {% do set_append_input('initial') %}
  {% do set_overwrite_input('initial') %}
  {% do set_microbatch_input(false) %}
{% endmacro %}

{% macro assert_incremental_state(identifier, expected_count, expected_sum, expected_id1) %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set relation = incremental_fixture_relation(target_schema, identifier) %}
  {% set result = run_query(
    'SELECT count(*) AS row_count, count(distinct id) AS distinct_ids, ' ~
    'sum(value) AS value_sum, max(case when id = 1 then value end) AS id1_value ' ~
    'FROM ' ~ relation) %}
  {% set row = result.rows[0] %}
  {% if row[0] | int != expected_count | int
      or row[1] | int != expected_count | int
      or row[2] | int != expected_sum | int
      or row[3] | int != expected_id1 | int %}
    {{ exceptions.raise_compiler_error(
      'Unexpected incremental state for ' ~ identifier ~ ': ' ~ row) }}
  {% endif %}
{% endmacro %}

{% macro assert_append_new_columns_state() %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set relation = incremental_fixture_relation(target_schema, 'incremental_append_columns') %}
  {% set result = run_query(
    'SELECT count(*) AS row_count, sum(value) AS value_sum, ' ~
    "max(case when id = 3 then category end) AS new_category FROM " ~ relation) %}
  {% set row = result.rows[0] %}
  {% if row[0] | int != 3 or row[1] | int != 61 or row[2] != 'new' %}
    {{ exceptions.raise_compiler_error(
      'Unexpected append_new_columns state: ' ~ row) }}
  {% endif %}
{% endmacro %}

{% macro assert_no_incremental_temp(identifier) %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set catalog = env_var('KYUUBI_DEFAULT_CATALOG', 'lakehouse') %}
  {% set result = run_query(
    'SHOW TABLES IN ' ~ adapter.quote(catalog) ~ '.' ~ adapter.quote(target_schema) ~
    " LIKE '" ~ identifier ~ "__dbt_tmp'") %}
  {% if result.rows | length != 0 %}
    {{ exceptions.raise_compiler_error(
      'Incremental temporary table was not cleaned: ' ~ identifier ~ '__dbt_tmp') }}
  {% endif %}
{% endmacro %}

{% macro assert_append_state(expected_count, expected_distinct, expected_sum) %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set relation = incremental_fixture_relation(target_schema, 'incremental_append') %}
  {% set result = run_query(
    'SELECT count(*), count(distinct id), sum(value) FROM ' ~ relation) %}
  {% set row = result.rows[0] %}
  {% if row[0] | int != expected_count | int
      or row[1] | int != expected_distinct | int
      or row[2] | int != expected_sum | int %}
    {{ exceptions.raise_compiler_error('Unexpected append state: ' ~ row) }}
  {% endif %}
{% endmacro %}

{% macro assert_partitioned_state(
    identifier, expected_count, expected_sum, expected_day_one_count) %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set relation = incremental_fixture_relation(target_schema, identifier) %}
  {% set result = run_query(
    'SELECT count(*), sum(value), ' ~
    "sum(case when event_date = date '2026-01-01' then 1 else 0 end) " ~
    'FROM ' ~ relation) %}
  {% set row = result.rows[0] %}
  {% if row[0] | int != expected_count | int
      or row[1] | int != expected_sum | int
      or row[2] | int != expected_day_one_count | int %}
    {{ exceptions.raise_compiler_error(
      'Unexpected partitioned state for ' ~ identifier ~ ': ' ~ row) }}
  {% endif %}
{% endmacro %}

{% macro assert_no_incremental_temp_pattern(identifier) %}
  {% set target_schema = env_var('KYUUBI_DBT_TEST_SCHEMA') %}
  {% set catalog = env_var('KYUUBI_DEFAULT_CATALOG', 'lakehouse') %}
  {% set result = run_query(
    'SHOW TABLES IN ' ~ adapter.quote(catalog) ~ '.' ~ adapter.quote(target_schema) ~
    " LIKE '" ~ identifier ~ "__dbt_tmp*'") %}
  {% if result.rows | length != 0 %}
    {{ exceptions.raise_compiler_error(
      'Incremental batch temporary tables were not cleaned: ' ~ identifier) }}
  {% endif %}
{% endmacro %}
