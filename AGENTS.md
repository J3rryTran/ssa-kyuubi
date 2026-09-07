# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## What this repo is

A fork of [apache/kyuubi](https://github.com/apache/kyuubi) at version **1.10.3**, carrying local customizations on top of upstream. Everything upstream still applies; the fork-specific work is:

- `kyuubi-server/.../server/notebook/**` + `web-ui/src/views/notebook/**` — a SQL + PySpark notebook subsystem with its own REST API, persistence and Vue UI. This is the largest customization and the focus of the `notebook` branch.
- `extensions/server/kyuubi-oidc-auth` — new module: server-side OIDC/JWT bearer-token authentication.
- `kyuubi-server/.../http/authentication/oidc/**` — BFF OIDC for the Web UI (server-side code exchange, `KYUUBI_SESSION` cookie).
- `kyuubi-hive-jdbc` `.../hive/auth/oidc/**` — client-side OIDC login (auth-code + PKCE, device flow, token cache).
- `extensions/spark/kyuubi-spark-authz` — Ranger authz reworked to support a **StarRocks** Ranger service in addition to the Hive/Spark one.

Design notes (mostly Vietnamese) that drove this work: `plans/ideal.md` for the StarRocks authz motivation, `notebook_plan.md` + `notebook_tasks.md` for the notebook, `fix_oidc.md` for the BFF. Read the relevant one before changing that area — they record constraints the code alone doesn't explain.

## Build / test / lint

Always use the `build/mvn` wrapper, not a system `mvn`.

```bash
./build/mvn clean
``

Formatting is enforced by Spotless (scalafmt + java + license headers). Apply before committing:

```bash
./dev/reformat
```

Binary distribution (see README for the full docker-image-tool flow):

```bash
./build/dist --name <suffix> --tgz --web-ui --spark-provided --flink-provided --hive-provided
```

Built jars land at `<module>/target/<artifact>-1.10.3.jar`.

### Web UI

The UI is a Vue 3 + Vite + Element Plus app under `kyuubi-server/web-ui`. Maven only builds it when the `web-ui` profile is on (it flips `webui.skip`), driving **pnpm** through `frontend-maven-plugin`; day-to-day work is faster run directly:

```bash
cd kyuubi-server/web-ui && pnpm dev
```

That serves on port 9090 and type-checks with `vue-tsc` first. `pnpm lint`, `pnpm test` (vitest), and `pnpm build` are the other entry points. Note `pnpm-lock.yaml` is the lockfile Maven honors even though a stale `package-lock.json` sits beside it.

## Profiles that matter

The root `pom.xml` is heavily profile-driven — a module list can change depending on which profile is on. `spark-3.3/3.4/3.5/4.0` select which `extensions/spark/kyuubi-extension-spark-3-x` module is even built (3.5 is default). `scala-2.12`/`scala-2.13`, `flink-1.17..1.20`, `java-8/11/17/21`, `web-ui`, and `*-provided` (exclude Spark/Flink/Hive from the assembly) are the others in regular use. Passing `-Dspark.version=` / `-Dranger.version=` overrides versions without switching profiles — the authz module is commonly built this way.

## Architecture orientation

Layered by role, not by feature:

- `kyuubi-server` — the Thrift/REST frontend, session and engine lifecycle management. Authentication providers and the notebook subsystem plug in here.
- `kyuubi-common` / `kyuubi-ha` / `kyuubi-events` / `kyuubi-metrics` — config (`KyuubiConf`), service traits, ZK-based discovery, event bus, metrics.
- `externals/kyuubi-*-engine` — the actual query engines (Spark, Flink, Hive, Trino, JDBC, chat) launched as separate processes; they talk back to the server over Thrift.
- `extensions/spark/*` — Spark-side plugins injected into the engine JVM (authz, lineage, connectors, SQL extensions). These run inside Spark, not inside Kyuubi.
- `kyuubi-hive-jdbc` (+ `-shaded`) — the client driver.

### Notebook subsystem (fork-specific)

Lives entirely in `kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook`, split into four layers that should not be short-circuited:

- **`NotebookManager`** — composition root and `AbstractService` lifecycle owner, held by `KyuubiRestFrontendService`. It constructs every store, service and adapter in `initialize`, runs the post-restart reconciliation and the idle-runtime reaper in `start`. REST resources reach services *only* through it.
- **`service/`** — owns persistence, authorization and state transitions (documents, content, revisions, sessions, executions, schedules, permissions).
- **`runtime/`** — `NotebookRuntimeAdapter` is the language-runtime boundary: an adapter owns only the conversation with its backend. `KyuubiSqlRuntimeAdapter` (which also implements `TabularNotebookRuntimeAdapter` for schema/results) and `PySparkRuntimeAdapter` are registered into `RuntimeAdapterRegistry`, which resolves a spec id or language to an adapter. **Adding a language means adding an adapter and registering it in `NotebookManager` — not touching the REST layer, which never knows which runtime it is talking to.**
- **`store/`** — `NotebookStore` / `JDBCNotebookStore`. It deliberately reuses `kyuubi.metadata.store.jdbc.*` so a deployment has one database; DDL lives in `src/main/resources/sql/notebook/{mysql,postgresql,sqlite}/` with a file per concern (store / runtime) and must be kept in sync across all three dialects.

REST resources are `NotebookFoldersResource`, `NotebooksResource`, `NotebookCollectionResources`, `NotebookRuntimeResources` under `api/v1`, registered as sub-resource locators in `ApiRootResource`. They mix in `NotebookApiSupport`, which derives the caller from the authenticated security context only — `principal` can never come from a body or query parameter, which is what keeps `owner` unforgeable. `NotebookException` is rendered by `NotebookExceptionMapper` into the documented error envelope; throw a `NotebookException` with a `NotebookErrorCode` rather than a bare JAX-RS response.

Config entries are registered normally in `NotebookConf` (`kyuubi.notebook.*`) — unlike the OIDC code below.

**Python environments.** `PythonEnvironmentBuilder` creates per-user venvs with `--system-site-packages`, so packages baked into the image (`docker/requirements.txt`, installed into the system interpreter) are visible without a download and each user revision only stores the difference. A user *cannot* uninstall an image-provided package — the server refuses rather than pretending. Requests carry requirement names only, never pip options; the index URL, allow/denylist and constraints file are server-side config. `docker/python-packages/` builds a separate packages image that a deployment can overlay onto `/usr/local`.

**Arrow results.** With `kyuubi.operation.result.format=arrow` a `TRowSet` carries serialized Arrow IPC batches in a single BINARY column, not rows. `ArrowRowSetConverter` decodes it by driving the JDBC driver's existing decoder rather than reimplementing one; without it notebook cells come back empty or full of binary noise.

Tests: `NotebookTestBase` wires the real services over a throwaway SQLite database, so suites exercise the actual SQL rather than a stub store. Run them with `-DwildcardSuites=org.apache.kyuubi.server.notebook.<Suite>`.

### OIDC authentication (fork-specific)

Two independent server-side pieces, both wired purely by config so that **Kyuubi core is deliberately untouched**. Both read their settings raw via `conf.getOption("kyuubi.authentication.{jwt,oidc}.*")` instead of registering `KyuubiConf` entries — preserve that property when extending them.

- **Bearer tokens** (`extensions/server/kyuubi-oidc-auth`): `JwtTokenAuthenticationProvider` implements `TokenAuthenticationProvider`, selected via `kyuubi.authentication.custom.bearer.class`. It validates JWTs against the provider's JWKS: allow-listed algorithms only (`alg=none` rejected), `iss`/`aud`/`exp`/`nbf`/`iat`, and optionally the JOSE `typ` header to reject ID tokens. `DenyPasswordAuthenticationProvider` hard-disables password auth alongside it.
- **BFF for the Web UI** (`server/http/authentication/oidc`): the Keycloak client is *confidential*, so the code-for-token exchange must happen server-side — the browser never sees a client secret. `ApiRootResource` exposes `authentication/config|login|callback|logout`; `OidcBffService` + `TokenEndpointClient` do the exchange and `OidcSessionStore` holds the session behind the HttpOnly `KYUUBI_SESSION` cookie, which `AuthenticationFilter` then honors. `OidcBffConf.flow` reports `bff` or `public` so the UI knows which mode it is in. This path is in production — treat changes to it as high-risk and read `fix_oidc.md` first.

Client side, `kyuubi-hive-jdbc`'s `auth/oidc` package drives the browser/device login and caches tokens; `OidcParams` defines the JDBC URL options (including `oidcInsecureTls`).

### Ranger authz with StarRocks (fork-specific)

Upstream maps Spark objects onto Ranger's *hive* service, which has no catalog level. This fork introduces `RangerServiceProfile` as the abstraction point: `SparkRangerServiceProfile` (upstream behavior, default) vs `StarRocksRangerServiceProfile`, selected by `spark.kyuubi.authz.ranger.service.type` (`spark` | `starrocks`), with `spark.kyuubi.authz.ranger.starrocks.default.catalog` and `.catalog.mapping` for catalog resolution. The profile decides the Ranger resource shape (`AccessResource`) and the access-type naming (`AccessRequest`), so `PrivilegeObject` now carries a catalog. When changing authorization behavior, change the profile — not the call sites in `RuleAuthorization`, the row-filter/data-masking rules, or `SparkRangerAdminPlugin`.

The `TableCommands.scala` / `IcebergCommands.scala` / `HudiCommands.scala` / `DeltaCommands.scala` files under `authz/gen/` are **generators**: the JSON specs in `src/main/resources/*_command_spec.json` are produced from them, so edit the Scala and regenerate rather than hand-editing the JSON.
