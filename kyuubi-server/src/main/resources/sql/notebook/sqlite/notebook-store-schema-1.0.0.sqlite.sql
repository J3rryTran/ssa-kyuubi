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
    id TEXT PRIMARY KEY NOT NULL,
    parent_id TEXT,
    name TEXT NOT NULL,
    path TEXT NOT NULL,
    path_hash TEXT NOT NULL UNIQUE,
    owner TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    updated_by TEXT NOT NULL,
    version INTEGER NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS notebook_folder_owner_index ON notebook_folder(owner);
CREATE INDEX IF NOT EXISTS notebook_folder_parent_index ON notebook_folder(parent_id);

CREATE TABLE IF NOT EXISTS notebook(
    id TEXT PRIMARY KEY NOT NULL,
    folder_id TEXT,
    path TEXT NOT NULL,
    path_hash TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    description TEXT,
    owner TEXT NOT NULL,
    language TEXT NOT NULL DEFAULT 'SQL',
    default_catalog TEXT,
    default_schema TEXT,
    runtime_profile TEXT,
    format_version INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    updated_by TEXT NOT NULL,
    version INTEGER NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS notebook_owner_index ON notebook(owner);
CREATE INDEX IF NOT EXISTS notebook_folder_id_index ON notebook(folder_id);

CREATE TABLE IF NOT EXISTS notebook_cell(
    id TEXT PRIMARY KEY NOT NULL,
    notebook_id TEXT NOT NULL,
    cell_position INTEGER NOT NULL,
    cell_type TEXT NOT NULL,
    language TEXT NOT NULL,
    source TEXT NOT NULL,
    metadata TEXT,
    configuration TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    version INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS notebook_cell_notebook_index ON notebook_cell(notebook_id);

CREATE TABLE IF NOT EXISTS notebook_revision(
    id TEXT PRIMARY KEY NOT NULL,
    notebook_id TEXT NOT NULL,
    revision_number INTEGER NOT NULL,
    document_snapshot TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL,
    reason TEXT,
    protected_revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE (notebook_id, revision_number)
);

CREATE TABLE IF NOT EXISTS notebook_permission(
    notebook_id TEXT NOT NULL,
    principal_type TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    principal_role TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL,
    PRIMARY KEY (notebook_id, principal_type, principal_id)
);

CREATE TABLE IF NOT EXISTS notebook_schedule(
    id TEXT PRIMARY KEY NOT NULL,
    notebook_id TEXT NOT NULL UNIQUE,
    cron_expression TEXT NOT NULL,
    timezone TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    runtime_profile TEXT,
    failure_policy TEXT NOT NULL,
    overlap_policy TEXT NOT NULL,
    last_run_at INTEGER,
    next_run_at INTEGER,
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    updated_by TEXT NOT NULL,
    version INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS notebook_engine_profile(
    subdomain TEXT PRIMARY KEY NOT NULL,
    owner TEXT NOT NULL,
    spark_config TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS notebook_engine_profile_owner_index ON notebook_engine_profile(owner);

-- The original table above remains as a read-only legacy import source. New rows use an
-- immutable ID, owner-scoped display name and a revision-specific Kyuubi subdomain.
CREATE TABLE IF NOT EXISTS notebook_engine_profile_v2(
    profile_id TEXT PRIMARY KEY NOT NULL,
    owner TEXT NOT NULL,
    name TEXT NOT NULL,
    subdomain TEXT NOT NULL UNIQUE,
    spark_config TEXT NOT NULL,
    notebook_runtime_idle_timeout TEXT,
    engine_idle_timeout TEXT,
    python_environment_revision_id TEXT,
    revision INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(owner, name)
);

CREATE TABLE IF NOT EXISTS notebook_engine_profile_revision(
    profile_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    subdomain TEXT NOT NULL UNIQUE,
    spark_config TEXT NOT NULL,
    notebook_runtime_idle_timeout TEXT,
    engine_idle_timeout TEXT,
    python_environment_revision_id TEXT,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(profile_id, revision)
);

CREATE INDEX IF NOT EXISTS notebook_engine_profile_v2_owner_index
ON notebook_engine_profile_v2(owner);

CREATE TABLE IF NOT EXISTS notebook_python_environment_revision(
    id TEXT PRIMARY KEY NOT NULL,
    profile_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    pvc_name TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    state TEXT NOT NULL,
    requirements_lock TEXT,
    metadata TEXT,
    content_checksum TEXT,
    base_image TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    ready_at INTEGER,
    retired_at INTEGER,
    UNIQUE(profile_id, revision)
);

CREATE TABLE IF NOT EXISTS notebook_python_environment_change_request(
    id TEXT PRIMARY KEY NOT NULL,
    profile_id TEXT NOT NULL,
    requested_by TEXT NOT NULL,
    operation TEXT NOT NULL,
    requested_packages TEXT NOT NULL,
    expected_profile_revision INTEGER NOT NULL,
    state TEXT NOT NULL,
    hot_install_state TEXT NOT NULL,
    resulting_environment_revision_id TEXT,
    error_summary TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
