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

CREATE TABLE IF NOT EXISTS dbt_workspace(
    id TEXT PRIMARY KEY NOT NULL, owner TEXT NOT NULL, name TEXT NOT NULL,
    project_ref TEXT NOT NULL, engine_profile_id TEXT NOT NULL,
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, version INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS dbt_workspace_owner_index ON dbt_workspace(owner, updated_at);

CREATE TABLE IF NOT EXISTS dbt_job(
    id TEXT PRIMARY KEY NOT NULL, workspace_id TEXT NOT NULL, owner TEXT NOT NULL,
    name TEXT NOT NULL, action TEXT NOT NULL, selector TEXT, engine_profile_id_override TEXT,
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, version INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS dbt_job_workspace_index ON dbt_job(workspace_id, updated_at);

CREATE TABLE IF NOT EXISTS dbt_job_run(
    id TEXT PRIMARY KEY NOT NULL, workspace_id TEXT NOT NULL, job_id TEXT,
    submitted_by TEXT NOT NULL, effective_kyuubi_user TEXT NOT NULL, action TEXT NOT NULL,
    selector TEXT, project_ref TEXT NOT NULL, profile_id TEXT NOT NULL, profile_owner TEXT NOT NULL,
    profile_subdomain TEXT NOT NULL, profile_config TEXT NOT NULL, profile_captured_at INTEGER NOT NULL,
    profile_notebook_runtime_idle_timeout TEXT, profile_engine_idle_timeout TEXT,
    profile_python_environment_revision_id TEXT,
    profile_revision INTEGER NOT NULL DEFAULT 1,
    state TEXT NOT NULL, runner_run_id TEXT, dispatch_token TEXT, runner_job_uid TEXT,
    created_at INTEGER NOT NULL, started_at INTEGER, finished_at INTEGER, error_summary TEXT,
    state_updated_at INTEGER NOT NULL, log_next_offset INTEGER NOT NULL DEFAULT 0,
    log_truncated INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS dbt_job_run_workspace_index ON dbt_job_run(workspace_id, created_at);
CREATE INDEX IF NOT EXISTS dbt_job_run_profile_state_index ON dbt_job_run(profile_id, state);
CREATE INDEX IF NOT EXISTS dbt_job_run_state_index ON dbt_job_run(state, state_updated_at);
CREATE TABLE IF NOT EXISTS dbt_job_run_log(
    run_id TEXT NOT NULL, sequence INTEGER NOT NULL, start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL,
    PRIMARY KEY(run_id, sequence)
);
CREATE INDEX IF NOT EXISTS dbt_job_run_log_offset_index ON dbt_job_run_log(run_id, start_offset);
