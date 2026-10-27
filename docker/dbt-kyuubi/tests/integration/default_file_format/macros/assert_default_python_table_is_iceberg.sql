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

{% macro assert_default_python_table_is_iceberg() %}
  {% set relation = ref('default_python_table') %}
  {% set description = run_query('DESCRIBE TABLE EXTENDED ' ~ relation) %}
  {% set actual = namespace(provider=none) %}
  {% for row in description.rows %}
    {% if row[0] | string | trim | lower == 'provider' %}
      {% set actual.provider = row[1] | string | trim | lower %}
    {% endif %}
  {% endfor %}
  {% if actual.provider != 'iceberg' %}
    {{ exceptions.raise_compiler_error(
      'Expected default_python_table provider iceberg, got ' ~ actual.provider) }}
  {% endif %}
{% endmacro %}
