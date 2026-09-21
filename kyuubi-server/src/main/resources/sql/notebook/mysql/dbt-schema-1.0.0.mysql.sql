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
    id varchar(36) PRIMARY KEY NOT NULL, owner varchar(255) NOT NULL, name varchar(255) NOT NULL,
    project_ref mediumtext NOT NULL, engine_profile_id varchar(128) NOT NULL,
    created_at bigint NOT NULL, updated_at bigint NOT NULL, version bigint NOT NULL,
    KEY dbt_workspace_owner_index(owner, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS dbt_job(
    id varchar(36) PRIMARY KEY NOT NULL, workspace_id varchar(36) NOT NULL, owner varchar(255) NOT NULL,
    name varchar(255) NOT NULL, action varchar(32) NOT NULL, selector mediumtext,
    engine_profile_id_override varchar(128), created_at bigint NOT NULL, updated_at bigint NOT NULL,
    version bigint NOT NULL, KEY dbt_job_workspace_index(workspace_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS dbt_job_run(
    id varchar(36) PRIMARY KEY NOT NULL, workspace_id varchar(36) NOT NULL, job_id varchar(36),
    submitted_by varchar(255) NOT NULL, effective_kyuubi_user varchar(255) NOT NULL,
    action varchar(32) NOT NULL, selector mediumtext, project_ref mediumtext NOT NULL,
    profile_id varchar(128) NOT NULL, profile_owner varchar(255) NOT NULL,
    profile_subdomain varchar(128) NOT NULL, profile_config mediumtext NOT NULL,
    profile_captured_at bigint NOT NULL, profile_notebook_runtime_idle_timeout varchar(32),
    profile_engine_idle_timeout varchar(32), profile_python_environment_revision_id varchar(64),
    profile_revision bigint NOT NULL DEFAULT 1,
    state varchar(32) NOT NULL, runner_run_id varchar(255),
    dispatch_token varchar(36), runner_job_uid varchar(255), created_at bigint NOT NULL,
    started_at bigint, finished_at bigint, error_summary mediumtext, state_updated_at bigint NOT NULL,
    log_next_offset bigint NOT NULL DEFAULT 0, log_truncated boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1,
    KEY dbt_job_run_workspace_index(workspace_id, created_at),
    KEY dbt_job_run_profile_state_index(profile_id, state),
    KEY dbt_job_run_state_index(state, state_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS dbt_job_run_log(
    run_id varchar(36) NOT NULL, sequence bigint NOT NULL, start_offset bigint NOT NULL,
    end_offset bigint NOT NULL, content mediumtext NOT NULL, created_at bigint NOT NULL,
    PRIMARY KEY(run_id, sequence), KEY dbt_job_run_log_offset_index(run_id, start_offset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
