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

CREATE TABLE IF NOT EXISTS compute_allocation(
    id varchar(36) PRIMARY KEY NOT NULL, owner varchar(255) NOT NULL,
    effective_user varchar(255) NOT NULL, profile_id varchar(128) NOT NULL,
    mode varchar(32) NOT NULL, workload_type varchar(32) NOT NULL,
    trigger_type varchar(32) NOT NULL, run_id varchar(36), attempt_id varchar(36) UNIQUE,
    engine_type varchar(32) NOT NULL, share_level varchar(32) NOT NULL,
    subdomain varchar(128) NOT NULL, discovery_namespace varchar(255) NOT NULL,
    engine_key varchar(64) NOT NULL, open_engine_key varchar(64) UNIQUE,
    launch_snapshot text NOT NULL, state varchar(32) NOT NULL, controller_id varchar(255),
    controller_generation bigint NOT NULL DEFAULT 0, claim_expires_at bigint,
    heartbeat_at bigint, workload_heartbeat_at bigint, workload_deadline_at bigint,
    engine_reference_id varchar(255),
    engine_application_id varchar(255), engine_resource_uid varchar(255),
    cleanup_attempts integer NOT NULL DEFAULT 0, last_error text,
    created_at bigint NOT NULL, updated_at bigint NOT NULL, activated_at bigint,
    release_requested_at bigint, released_at bigint, version bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS compute_allocation_owner_state_index
    ON compute_allocation(owner, state);
CREATE INDEX IF NOT EXISTS compute_allocation_profile_state_index
    ON compute_allocation(profile_id, state);
CREATE INDEX IF NOT EXISTS compute_allocation_claim_index
    ON compute_allocation(state, claim_expires_at, updated_at);

CREATE TABLE IF NOT EXISTS compute_binding(
    id varchar(36) PRIMARY KEY NOT NULL, allocation_id varchar(36) NOT NULL,
    runner_user varchar(255) NOT NULL, effective_user varchar(255) NOT NULL,
    profile_id varchar(128) NOT NULL, run_id varchar(36) NOT NULL,
    attempt_id varchar(36) NOT NULL, token_hash varchar(64) NOT NULL,
    issued_at bigint NOT NULL, expires_at bigint NOT NULL, revoked_at bigint,
    version bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS compute_binding_allocation_index
    ON compute_binding(allocation_id);
CREATE INDEX IF NOT EXISTS compute_binding_expiry_index
    ON compute_binding(expires_at, revoked_at);

CREATE TABLE IF NOT EXISTS compute_attach_reservation(
    id varchar(36) PRIMARY KEY NOT NULL, binding_id varchar(36) NOT NULL,
    allocation_id varchar(36) NOT NULL, runner_user varchar(255) NOT NULL,
    effective_user varchar(255) NOT NULL, profile_id varchar(128) NOT NULL,
    run_id varchar(36) NOT NULL, attempt_id varchar(36) NOT NULL,
    state varchar(32) NOT NULL, expires_at bigint NOT NULL, created_at bigint NOT NULL,
    confirmed_at bigint, released_at bigint, version bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS compute_attach_binding_index
    ON compute_attach_reservation(binding_id);
CREATE INDEX IF NOT EXISTS compute_attach_allocation_state_index
    ON compute_attach_reservation(allocation_id, state);
CREATE INDEX IF NOT EXISTS compute_attach_expiry_index
    ON compute_attach_reservation(state, expires_at);
