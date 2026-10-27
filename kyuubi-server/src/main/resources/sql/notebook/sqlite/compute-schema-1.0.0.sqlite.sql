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
    id TEXT PRIMARY KEY NOT NULL, owner TEXT NOT NULL, effective_user TEXT NOT NULL,
    profile_id TEXT NOT NULL, mode TEXT NOT NULL, workload_type TEXT NOT NULL,
    trigger_type TEXT NOT NULL, run_id TEXT, attempt_id TEXT UNIQUE,
    engine_type TEXT NOT NULL, share_level TEXT NOT NULL, subdomain TEXT NOT NULL,
    discovery_namespace TEXT NOT NULL, engine_key TEXT NOT NULL,
    open_engine_key TEXT UNIQUE, launch_snapshot TEXT NOT NULL, state TEXT NOT NULL,
    controller_id TEXT, controller_generation INTEGER NOT NULL DEFAULT 0,
    claim_expires_at INTEGER, heartbeat_at INTEGER, workload_heartbeat_at INTEGER,
    workload_deadline_at INTEGER, engine_reference_id TEXT,
    engine_application_id TEXT,
    engine_resource_uid TEXT, cleanup_attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
    activated_at INTEGER, release_requested_at INTEGER, released_at INTEGER,
    version INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS compute_allocation_owner_state_index
    ON compute_allocation(owner, state);
CREATE INDEX IF NOT EXISTS compute_allocation_profile_state_index
    ON compute_allocation(profile_id, state);
CREATE INDEX IF NOT EXISTS compute_allocation_claim_index
    ON compute_allocation(state, claim_expires_at, updated_at);

CREATE TABLE IF NOT EXISTS compute_binding(
    id TEXT PRIMARY KEY NOT NULL, allocation_id TEXT NOT NULL, runner_user TEXT NOT NULL,
    effective_user TEXT NOT NULL, profile_id TEXT NOT NULL, run_id TEXT NOT NULL,
    attempt_id TEXT NOT NULL, token_hash TEXT NOT NULL, issued_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL, revoked_at INTEGER, version INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS compute_binding_allocation_index
    ON compute_binding(allocation_id);
CREATE INDEX IF NOT EXISTS compute_binding_expiry_index
    ON compute_binding(expires_at, revoked_at);

CREATE TABLE IF NOT EXISTS compute_attach_reservation(
    id TEXT PRIMARY KEY NOT NULL, binding_id TEXT NOT NULL, allocation_id TEXT NOT NULL,
    runner_user TEXT NOT NULL, effective_user TEXT NOT NULL, profile_id TEXT NOT NULL,
    run_id TEXT NOT NULL, attempt_id TEXT NOT NULL, state TEXT NOT NULL,
    expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL, confirmed_at INTEGER,
    released_at INTEGER, version INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS compute_attach_binding_index
    ON compute_attach_reservation(binding_id);
CREATE INDEX IF NOT EXISTS compute_attach_allocation_state_index
    ON compute_attach_reservation(allocation_id, state);
CREATE INDEX IF NOT EXISTS compute_attach_expiry_index
    ON compute_attach_reservation(state, expires_at);
