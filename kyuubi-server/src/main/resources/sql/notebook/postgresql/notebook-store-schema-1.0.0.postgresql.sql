--
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
--

-- path_hash is the uniqueness key rather than path itself, so that the index width does not
-- depend on the path length. Soft-deleted rows re-hash with their id mixed in, which frees the
-- live path for reuse while keeping history.

CREATE TABLE IF NOT EXISTS notebook_folder(
    id varchar(36) PRIMARY KEY NOT NULL,
    parent_id varchar(36),
    name varchar(255) NOT NULL,
    path varchar(1024) NOT NULL,
    path_hash varchar(64) NOT NULL UNIQUE,
    owner varchar(255) NOT NULL,
    created_at bigint NOT NULL,
    created_by varchar(255) NOT NULL,
    updated_at bigint NOT NULL,
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL,
    deleted int NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS notebook_folder_owner_index ON notebook_folder(owner);
CREATE INDEX IF NOT EXISTS notebook_folder_parent_index ON notebook_folder(parent_id);

CREATE TABLE IF NOT EXISTS notebook(
    id varchar(36) PRIMARY KEY NOT NULL,
    folder_id varchar(36),
    path varchar(1024) NOT NULL,
    path_hash varchar(64) NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    description varchar(2048),
    owner varchar(255) NOT NULL,
    language varchar(16) NOT NULL DEFAULT 'SQL',
    default_catalog varchar(255),
    default_schema varchar(255),
    runtime_profile varchar(255),
    format_version int NOT NULL,
    created_at bigint NOT NULL,
    created_by varchar(255) NOT NULL,
    updated_at bigint NOT NULL,
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL,
    deleted int NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS notebook_owner_index ON notebook(owner);
CREATE INDEX IF NOT EXISTS notebook_folder_id_index ON notebook(folder_id);

CREATE TABLE IF NOT EXISTS notebook_cell(
    id varchar(36) PRIMARY KEY NOT NULL,
    notebook_id varchar(36) NOT NULL,
    cell_position int NOT NULL,
    cell_type varchar(32) NOT NULL,
    language varchar(32) NOT NULL,
    source text NOT NULL,
    metadata text,
    configuration text,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    version bigint NOT NULL
);

CREATE INDEX IF NOT EXISTS notebook_cell_notebook_index ON notebook_cell(notebook_id);

CREATE TABLE IF NOT EXISTS notebook_revision(
    id varchar(36) PRIMARY KEY NOT NULL,
    notebook_id varchar(36) NOT NULL,
    revision_number bigint NOT NULL,
    document_snapshot text NOT NULL,
    created_at bigint NOT NULL,
    created_by varchar(255) NOT NULL,
    reason varchar(512),
    protected_revision int NOT NULL DEFAULT 0,
    UNIQUE (notebook_id, revision_number)
);

CREATE TABLE IF NOT EXISTS notebook_permission(
    notebook_id varchar(36) NOT NULL,
    principal_type varchar(16) NOT NULL,
    principal_id varchar(255) NOT NULL,
    principal_role varchar(16) NOT NULL,
    created_at bigint NOT NULL,
    created_by varchar(255) NOT NULL,
    PRIMARY KEY (notebook_id, principal_type, principal_id)
);

CREATE TABLE IF NOT EXISTS notebook_schedule(
    id varchar(36) PRIMARY KEY NOT NULL,
    notebook_id varchar(36) NOT NULL UNIQUE,
    cron_expression varchar(255) NOT NULL,
    timezone varchar(64) NOT NULL,
    enabled int NOT NULL,
    runtime_profile varchar(255),
    failure_policy varchar(32) NOT NULL,
    overlap_policy varchar(32) NOT NULL,
    last_run_at bigint,
    next_run_at bigint,
    created_at bigint NOT NULL,
    created_by varchar(255) NOT NULL,
    updated_at bigint NOT NULL,
    updated_by varchar(255) NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS notebook_engine_profile(
    subdomain varchar(128) PRIMARY KEY NOT NULL,
    owner varchar(255) NOT NULL,
    spark_config text NOT NULL,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL
);

CREATE INDEX IF NOT EXISTS notebook_engine_profile_owner_index ON notebook_engine_profile(owner);

CREATE TABLE IF NOT EXISTS notebook_engine_profile_v2(
    profile_id varchar(64) PRIMARY KEY NOT NULL,
    owner varchar(255) NOT NULL,
    name varchar(128) NOT NULL,
    subdomain varchar(128) NOT NULL UNIQUE,
    spark_config text NOT NULL,
    notebook_runtime_idle_timeout varchar(32),
    engine_idle_timeout varchar(32),
    python_environment_revision_id varchar(64),
    revision bigint NOT NULL,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    UNIQUE(owner, name)
);

CREATE TABLE IF NOT EXISTS notebook_engine_profile_revision(
    profile_id varchar(64) NOT NULL,
    revision bigint NOT NULL,
    subdomain varchar(128) NOT NULL UNIQUE,
    spark_config text NOT NULL,
    notebook_runtime_idle_timeout varchar(32),
    engine_idle_timeout varchar(32),
    python_environment_revision_id varchar(64),
    created_at bigint NOT NULL,
    PRIMARY KEY(profile_id, revision)
);

CREATE INDEX IF NOT EXISTS notebook_engine_profile_v2_owner_index
ON notebook_engine_profile_v2(owner);

CREATE TABLE IF NOT EXISTS notebook_python_environment_revision(
    id varchar(64) PRIMARY KEY NOT NULL,
    profile_id varchar(64) NOT NULL,
    revision bigint NOT NULL,
    pvc_name varchar(128) NOT NULL,
    relative_path varchar(255) NOT NULL,
    state varchar(16) NOT NULL,
    requirements_lock text,
    metadata text,
    content_checksum varchar(128),
    base_image varchar(512) NOT NULL,
    created_at bigint NOT NULL,
    ready_at bigint,
    retired_at bigint,
    UNIQUE(profile_id, revision)
);

CREATE TABLE IF NOT EXISTS notebook_python_environment_change_request(
    id varchar(64) PRIMARY KEY NOT NULL,
    profile_id varchar(64) NOT NULL,
    requested_by varchar(255) NOT NULL,
    operation varchar(16) NOT NULL,
    requested_packages text NOT NULL,
    expected_profile_revision bigint NOT NULL,
    state varchar(16) NOT NULL,
    hot_install_state varchar(16) NOT NULL,
    resulting_environment_revision_id varchar(64),
    error_summary text,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL
);
