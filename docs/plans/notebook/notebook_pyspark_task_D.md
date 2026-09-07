# TASK D (standalone): route notebook runtime requests to the owning pod

## 0. Context & hard constraints

- Repo: `https://github.com/J3rryTran/kyuubi-custom.git`, branch `notebook`. Base Kyuubi 1.10.3.
- Already done in this repo: PySparkRuntimeAdapter (TASK A), CPython removal (TASK B),
  FileSystemNotebookStore (TASK C — exists but NOT enabled by default; SQLite remains default).
- The system runs `replicaCount: 2`. NO external DB (Postgres/MySQL). Do NOT touch:
  BFF OIDC, arrow-converter /rowset, the Helm chart (deploy side handles it).
- A previous session surveyed this task and stopped before coding. The survey results in
  section 1 are VERIFIED — reuse them, do NOT re-survey.
- Before writing any code: make sure the working tree starts from a committed, green state.
  Commit your work at the end (the previous session ended with uncommitted work — do not
  repeat that).

## 1. Verified survey results (trust these)

1. **Reuse point for the proxy**: `EngineUIProxyServlet` (referenced from
   `ApiRootResource.scala:265`) — a Jetty ProxyServlet already wired to the conf set
   `kyuubi.frontend.rest.proxy.jetty.client.*` (timeout, idleTimeout, maxThreads,
   maxConnections, requestBufferSize, responseBufferSize) and it STREAMS responses —
   exactly what `/logs` and `/outputs` need. Follow its wiring pattern.
2. ZooKeeper HA is live: `kyuubi.ha.zookeeper.quorum`, `kyuubi.ha.namespace`,
   `kyuubi.ha.addresses` — the server already knows its own instance address and the ZK client.
3. The concept `kyuubiInstance` already appears in notebook classes.
4. **CRITICAL storage fact**: the runtime bookkeeping tables (`notebook_session`,
   `notebook_execution`, ...) live in the metadata store which is SQLITE PER POD by default.
   A row written on pod A is INVISIBLE on pod B. Therefore the owner registry for routing
   MUST NOT rely on those tables.

## 2. Locked design decisions (do not change)

1. **Owner registry lives in ZooKeeper, not in the store.**
   - On notebook-session open, the owning pod creates an EPHEMERAL znode:
         <kyuubi.ha.namespace>/notebook-sessions/<sessionId>  ->  "<host>:<rest-port>"
   - Ephemeral = the znode disappears automatically when the owning pod dies -> liveness
     detection for free; no heartbeats, no cleanup jobs.
   - On session close/stop, delete the znode explicitly.
   - Also mirror `kyuubiInstance` into the session record in the store (informational only;
     routing reads ZK, never the store).
2. **Proxy, do not replicate.** A live session (Kyuubi session handle + Spark engine
   connection + python worker) exists only on the owning pod. Other pods forward the
   request; nothing is copied or persisted to HDFS. If the owner is gone, the session is
   gone — the only correct answer is a clean 409 telling the user to restart the session.
3. **Reuse the existing Jetty proxy plumbing** (EngineUIProxyServlet pattern + its conf set).
   Do not hand-roll an HTTP client loop.

## 3. Scope — endpoints that must resolve ownership

Apply owner resolution to runtime endpoints only:

    /api/v1/notebook-sessions/{id}            (GET/DELETE and sub-routes :restart :stop)
    /api/v1/notebook-sessions/{id}/executions
    /api/v1/notebooks/{id}/executions         (POST run-cell — resolves via the notebook's
                                               active session)
    /api/v1/executions/{id} and sub-routes    (/logs /outputs /results /schema :cancel)

Resolution order per request:
1. Session/execution known to the LOCAL session manager -> handle locally (fast path,
   no ZK round-trip).
2. Not local -> look up the znode. Found and it is another instance -> PROXY to it.
3. Znode absent (owner dead or session never existed) -> **409** with body
   `{"error":"runtime lost","action":"restart-session"}`. UI already shows a Restart
   action on this (verify; adjust UI copy only if needed).

Notebook/folder CRUD (`/api/v1/notebooks`, `/api/v1/notebook-folders`, revisions, search)
is NOT routed — it reads the shared store and stays local on every pod.

## 4. Proxy requirements

1. Preserve method, path, query, body, response status, response headers; STREAM bodies
   (chunked / long responses on /logs /outputs must not be fully buffered).
2. Timeouts/limits come from `kyuubi.frontend.rest.proxy.jetty.client.*` (already read by
   the reuse point). No new timeout knobs.
3. **Loop guard**: add header `X-Kyuubi-Proxied: true` on the forwarded request. A pod that
   receives a request with this header and still is not the owner replies **502**
   (never forwards again).
4. **Identity across the hop** — the BFF cookie session store is per-pod, so the target pod
   cannot validate the caller's cookie. Handle it like this:
   - The entry pod fully authenticates the request as today (cookie or Bearer).
   - On the forwarded request, replace credentials with internal headers:
         X-Kyuubi-Proxied: true
         X-Kyuubi-Real-User: <authenticated username>
         X-Kyuubi-Internal-Token: <value of conf kyuubi.notebook.proxy.internal.secret>
   - The receiving pod accepts `X-Kyuubi-Real-User` ONLY when the internal token matches
     its own conf value AND the request arrives on the REST port; otherwise the headers
     are ignored and normal auth applies. Strip/reject these headers on any request that
     did NOT come from the proxy path (a client must never be able to spoof them).
   - Conf `kyuubi.notebook.proxy.internal.secret` (string, optional): when unset, proxying
     still works but forwards the original Authorization header only (cookie-auth requests
     cannot cross pods -> document this limitation in the config description). The deploy
     side will set the secret via the chart.
5. Proxy target address = value stored in the znode (host:port of the owner's REST frontend).

## 5. Acceptance (run with 2 replicas; disable/ignore sessionAffinity while testing)

1. Open a session via pod A, run `a = 1`; send the next cell through pod B -> `print(a)`
   returns 1 (request was proxied to A; check logs for the proxied marker).
2. `/executions/{id}/logs` and `/outputs` fetched via the non-owner pod stream correctly.
3. Kill pod A -> any runtime call for that session via pod B returns 409 restart-session;
   UI shows Restart; after restart the notebook works on the surviving pod.
4. Loop guard: craft a request with `X-Kyuubi-Proxied: true` to a non-owner -> 502.
5. Spoof guard: a client request carrying `X-Kyuubi-Real-User` without the internal token
   is treated as unauthenticated for that identity (normal auth applies).
6. Single-replica dev mode: everything handled locally, zero proxying, no behavior change.
7. Unit tests: znode create/delete lifecycle on open/close; resolution order (local wins
   over ZK); 409 mapping when znode missing; loop-guard 502; identity header
   accept/reject logic.

Post-build verification greps:

    unzip -p kyuubi-server_*.jar 'org/apache/kyuubi/server/notebook/**' | strings \
      | grep -iE 'notebook-sessions/|X-Kyuubi-Proxied|X-Kyuubi-Real-User'

## 6. Delivery

1. Commit in logical chunks (registry, proxy, auth-hop, tests) — do not leave work
   uncommitted.
2. Push a new kyuubi-custom image tag to the registry (10.60.129.132:8890) so the deploy
   side can audit remotely before syncing. Engine jar / spark image are NOT touched by
   this task.
3. Update the docs note that previously said "routing is missing" — remove or mark done.
4. Report: image tag + the conf name for the internal secret so the chart can wire it
   (expected: kyuubi.notebook.proxy.internal.secret).

--- END OF FILE (Delivery is the last section) ---
