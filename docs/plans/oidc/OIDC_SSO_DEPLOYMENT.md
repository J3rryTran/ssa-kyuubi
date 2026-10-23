# OIDC / Keycloak SSO for Apache Kyuubi — Deployment & Usage Guide

This guide covers the OIDC single-sign-on capability implemented on top of Apache Kyuubi 1.10.3. It
is the operator/user companion to the design in `OIDC_SSO_FEASIBILITY_AND_DESIGN.md`.

The feature has two independent, additive parts:

1. **Server plugin** `kyuubi-oidc-auth` — validates OAuth2 **access-token** JWTs (Keycloak or any
   standard OIDC provider) against the provider's JWKS, over the existing HTTP Bearer path.
2. **JDBC driver OIDC mode** — acquires the access token interactively (Authorization Code + PKCE with
   an auto-launched browser) or non-interactively (Device Flow / pre-issued token), then sends it as
   `Authorization: Bearer`.

Nothing in the existing authentication paths changes; both parts are opt-in. The existing `auth=jwt`
(pre-supplied token) mode is preserved unchanged.

---

## 1. Keycloak (or other OIDC provider) setup

Create a **public** client for the JDBC driver and, if you want a separate API audience, keep the
Kyuubi audience configurable.

- Client type: **OpenID Connect**, **public** (no client secret) for desktop SSO.
- **Standard flow** (Authorization Code) enabled — for desktop browser SSO.
- **OAuth 2.0 Device Authorization Grant** enabled — for headless clients.
- **PKCE**: Advanced → *Proof Key for Code Exchange Code Challenge Method* = **S256**.
- **Valid redirect URIs**: `http://127.0.0.1/*` (loopback; the driver uses an ephemeral port per
  RFC 8252). If you pin `oidcRedirectPort=NNNN`, use `http://127.0.0.1:NNNN/callback`.
- **Audience**: ensure the issued access token's `aud` contains a value you will configure on the
  server (commonly the client id). In Keycloak this is typically added via a *client scope* /
  *audience mapper*. **Never** rely on the realm name as the audience.

Discovery document (used by both server and driver):
`https://<keycloak-host>/realms/<realm>/.well-known/openid-configuration`

---

## 2. Server configuration (`kyuubi-defaults.conf`)

```properties
# 1. Enable the HTTP thrift transport (Bearer auth requires it) alongside the REST frontend.
kyuubi.frontend.protocols                 THRIFT_HTTP,REST

# 2. Enable the built-in OIDC authentication mode.
#    Kyuubi automatically wires the bundled JWT bearer and deny-password providers.
kyuubi.authentication                     OIDC

# 3. JWT validation settings (read by the bundled OIDC provider).
kyuubi.authentication.jwt.issuer          https://keycloak.example.com/realms/prod
kyuubi.authentication.jwt.audience        kyuubi-jdbc          # accepted aud (comma-separated); usually the client id
#kyuubi.authentication.jwt.jwks.url       https://keycloak.example.com/realms/prod/protocol/openid-connect/certs  # optional; auto-discovered from issuer
kyuubi.authentication.jwt.username.claim  preferred_username    # claim used as the session user
kyuubi.authentication.jwt.allowed.algorithms RS256              # allow-list; alg=none & unlisted algs rejected
#kyuubi.authentication.jwt.expected.typ   at+jwt                # optional: require JOSE typ, rejecting ID tokens (Keycloak access tokens: "Bearer")
kyuubi.authentication.jwt.clock.skew.seconds 30

# 4. Fat tokens: Keycloak access tokens with many roles/groups can exceed the 6 KB header default.
kyuubi.frontend.thrift.http.request.header.size 32768

# 5. ALWAYS use TLS in production — a bearer token over plaintext HTTP is a credential leak.
#    (configure the frontend SSL keystore per your Kyuubi security setup)
```

All `kyuubi.authentication.jwt.*` keys:

| Key | Required | Default | Meaning |
|-----|----------|---------|---------|
| `kyuubi.authentication.jwt.issuer` | yes | — | OIDC issuer; must equal the token `iss` |
| `kyuubi.authentication.jwt.audience` | yes | — | Accepted `aud` value(s), comma-separated (typically the client id) |
| `kyuubi.authentication.jwt.jwks.url` | no | discovered | JWKS URL; auto-derived from the issuer's discovery doc if omitted |
| `kyuubi.authentication.jwt.username.claim` | no | `preferred_username` | Claim mapped to the session user (falls back to `sub` only if you set it) |
| `kyuubi.authentication.jwt.allowed.algorithms` | no | `RS256` | Signature algorithm allow-list (`alg=none`/unlisted rejected) |
| `kyuubi.authentication.jwt.expected.typ` | no | — | If set, the JOSE `typ` header must match (rejects OIDC ID tokens) |
| `kyuubi.authentication.jwt.clock.skew.seconds` | no | `30` | Clock-skew tolerance for `exp`/`nbf`/`iat` |
| `kyuubi.authentication.jwt.connect.timeout.ms` | no | `5000` | Discovery/JWKS connect timeout |
| `kyuubi.authentication.jwt.read.timeout.ms` | no | `5000` | Discovery/JWKS read timeout |

The plugin jar (`kyuubi-oidc-auth_2.12-<version>.jar`) and Nimbus JOSE+JWT ship in `$KYUUBI_HOME/jars/`
as part of the distribution — no manual install needed.

---

## 3. Client (JDBC URL)

### 3a. Desktop SSO — Authorization Code + PKCE (browser auto-launch)

```
jdbc:kyuubi://kyuubi-host:10009/default;transportMode=http;httpPath=cliservice;ssl=true;auth=oidc;oidcIssuer=https://keycloak.example.com/realms/prod;oidcClientId=kyuubi-jdbc
```

On connect, the driver opens the system browser to Keycloak; after login (with MFA/SSO as configured)
it captures the redirect on a loopback port, exchanges the code for tokens, and connects. Subsequent
connections in the same JVM (e.g. a DBeaver session) reuse the cached token — no repeat prompt.

### 3b. Headless — Device Authorization Flow

```
jdbc:kyuubi://kyuubi-host:10009/default;transportMode=http;httpPath=cliservice;ssl=true;auth=oidc;oidcIssuer=https://keycloak.example.com/realms/prod;oidcClientId=kyuubi-jdbc;oidcFlow=device
```

The driver prints a URL and a short user code; the user completes sign-in on any device. With
`oidcFlow=auto` the driver uses Authorization Code when a browser is available and falls back to
Device Flow otherwise.

### 3c. Headless — pre-issued token (existing mode, unchanged)

```
jdbc:kyuubi://kyuubi-host:10009/default;transportMode=http;httpPath=cliservice;ssl=true;auth=jwt
```

with the token supplied via the `JWT` environment variable or `;jwt=<token>`. This continues to work
exactly as before; OIDC is an additional way to acquire such a token.

### 3d. Alternative syntaxes (backward-compatible)

These are equivalent triggers for the OIDC flow, chosen to minimize churn for existing users:

- `auth=oidc` (explicit)
- `auth=jwt;oidc=true` (extends the existing JWT mode)
- any `oidcIssuer=`/`oidcDiscoveryUri=` present (presence-triggered)

### Driver parameters

| Param | Default | Meaning |
|-------|---------|---------|
| `oidcIssuer` | — | OIDC issuer; endpoints auto-discovered (preferred) |
| `oidcDiscoveryUri` | — | Explicit discovery document URL (alternative to issuer) |
| `oidcClientId` | — | **Required.** Public client id |
| `oidcClientSecret` | — | Optional; only for confidential clients |
| `oidcScope` | `openid profile email` | Requested scopes |
| `oidcFlow` | `authcode` | `authcode` \| `device` \| `auto` |
| `oidcRedirectPort` | `0` | Loopback port; `0` = ephemeral |
| `oidcTokenCache` | `true` | Reuse tokens across connections in the JVM |
| `oidcBrowser` | `auto` | `auto` \| `none` (suppress browser launch) |
| `oidcLogout` | `false` | On `close()`, call the provider's `end_session_endpoint` |

> **Transport requirement:** OIDC needs `transportMode=http`. In binary mode the driver fails fast with
> a clear message. Always use `ssl=true` in production.

---

## 4. How it behaves at runtime

- The access token is validated by the server at **session establishment**. After the first success,
  Kyuubi issues its signed cookie (default 24h) and continues the session via the cookie — the token
  is not re-validated on every RPC.
- The driver **refreshes** the access token silently (using the refresh token) when it must present a
  bearer again (e.g. cookie expiry) on a long-running connection; only if the refresh token is also
  dead does it re-run the interactive flow (desktop only).
- On `Connection.close()`, cached tokens for the connection are dropped; with `oidcLogout=true` the
  driver additionally calls the provider's RP-initiated logout endpoint.

---

## 5. Security notes

- PKCE **S256** and a random `state`/`nonce` are always used; the loopback redirect binds `127.0.0.1`
  only, on an ephemeral single-use port.
- Server validation is strict: signature (allow-listed algs; `alg=none` rejected), `iss`, `aud`,
  `exp`, `nbf`, `iat`; access tokens only (set `expected.typ` to reject ID tokens); JWKS fetched over
  HTTPS with caching and `kid` rotation. Token contents are never logged.
- Use TLS for the Kyuubi HTTP frontend. Keep access-token lifetimes short and rely on refresh + cookie.
- If tokens are large, raise `kyuubi.frontend.thrift.http.request.header.size` or trim Keycloak token
  claims via client scopes / protocol mappers.

---

## 6. Build / module layout

- Server plugin: `extensions/server/kyuubi-oidc-auth`
  (`org.apache.kyuubi.auth.oidc.JwtTokenAuthenticationProvider`,
  `org.apache.kyuubi.auth.oidc.DenyPasswordAuthenticationProvider`). Depends on `nimbus-jose-jwt`;
  bundled into `$KYUUBI_HOME/jars/` via `kyuubi-server`.
- Driver: `kyuubi-hive-jdbc`, package `org.apache.kyuubi.jdbc.hive.auth.oidc` (JDK-only, no new
  runtime dependency), integrated in `KyuubiConnection.getHttpClient()` and reusing
  `HttpJwtAuthRequestInterceptor`.

---

## 7. Web UI SSO

The Kyuubi Web UI ships a username/password dialog that posts HTTP Basic. Under
`kyuubi.authentication=OIDC` that path is served by `DenyPasswordAuthenticationProvider`, so it can
never succeed. The UI therefore performs its own **Authorization Code + PKCE** login and calls the
REST API with `Authorization: Bearer <access_token>` instead.

### 7.1 Server configuration

Add these next to the JWT settings in `kyuubi-defaults.conf`:

```properties
# OIDC client the Web UI authenticates as. Browser-based, hence a PUBLIC client with no secret.
kyuubi.authentication.oidc.ui.client.id     kyuubi-ui
# Optional; defaults to "openid profile email".
#kyuubi.authentication.oidc.ui.scope        openid profile email
```

The issuer is reused from `kyuubi.authentication.jwt.issuer` — it is not configured twice.

The UI reads these from `GET /api/v1/authentication/config`, which is intentionally **not**
authenticated (the browser has no credential before signing in). It returns only public discovery
inputs — `authType`, `oidcEnabled`, `issuer`, `clientId`, `scope` — and never a secret. When
`kyuubi.authentication` is not `OIDC`, the endpoint reports `oidcEnabled: false` and the UI keeps
the original username/password dialog.

### 7.2 Keycloak client for the Web UI

Register a **separate** client from the JDBC one, because the redirect URIs differ:

| Setting | Value |
|---------|-------|
| Client ID | `kyuubi-ui` |
| Client authentication | **Off** (public client) |
| Standard flow | **On** (Direct access grants off) |
| Valid redirect URIs | `http://<kyuubi-host>:<rest-port>/ui/callback` |
| Valid post logout redirect URIs | `http://<kyuubi-host>:<rest-port>/ui` |
| **Web origins** | `http://<kyuubi-host>:<rest-port>` |

`Web origins` is mandatory: the browser calls Keycloak's token endpoint directly, so without the
matching CORS header the exchange fails even though the login itself succeeded.

Add an **audience mapper** on this client emitting the same `aud` the server expects
(`kyuubi.authentication.jwt.audience`), otherwise every REST call is rejected as a wrong audience.

### 7.3 Notes and constraints

- `crypto.subtle` is unavailable outside secure contexts, so on a plain-HTTP UI the S256 challenge is
  computed by a bundled SHA-256 (`src/utils/pkce.ts`). Behaviour is identical either way, but serving
  the UI over HTTPS remains the recommendation — bearer tokens in plaintext are a credential leak.
- A self-signed Keycloak certificate must be trusted by the **browser** for the UI flow (this is
  separate from the server-side truststore used for JWKS). Otherwise discovery fails silently as a
  network error.
- Signing in redirects to the provider immediately — there is no intermediate "continue with SSO"
  prompt. The same happens when a session dies mid-use, so an expired token sends the user straight
  back to Keycloak; the page being viewed is restored afterwards.
- Tokens are renewed silently via `refresh_token` shortly before expiry, and once more on a 401/403
  before that redirect is triggered.
- A dialog appears only when the redirect itself could not be started (provider unreachable, issuer
  or client id unset), reporting the reason rather than leaving a blank page. Concurrent failed
  requests collapse into a single redirect.
- "Sign out" clears local state and, when the provider advertises `end_session_endpoint`, ends the
  Keycloak session too.

### 7.4 Confidential clients: the backend-for-frontend (BFF) flow

The flow above needs a **public** client, because the browser performs the code exchange itself and
has nowhere safe to keep a `client_secret`. When policy requires the UI client to stay
**confidential**, that exchange has to move to the server: the browser is redirected by Kyuubi,
Kyuubi swaps the code for tokens using the secret, and the browser gets back nothing but an opaque
session id in an `HttpOnly` cookie. No token ever reaches JavaScript, and the session survives a
page reload.

Setting `kyuubi.authentication.oidc.ui.client.secret` is what switches the flow on. Without it,
everything below is dormant and §7.1–7.3 apply unchanged; the UI picks its behaviour from the
`flow` field (`"bff"` or `"public"`) of `GET /api/v1/authentication/config`.

```properties
# Presence of this secret enables the BFF flow.
kyuubi.authentication.oidc.ui.client.secret        <client-secret>

# Where the BROWSER is sent to authorize. Must be the externally reachable issuer URL.
kyuubi.authentication.oidc.authorization.endpoint  https://keycloak.example.com/realms/lakehouse/protocol/openid-connect/auth
# Where the SERVER exchanges and refreshes tokens. May be an in-cluster URL.
kyuubi.authentication.oidc.token.endpoint          http://keycloak.internal:8080/realms/lakehouse/protocol/openid-connect/token
kyuubi.authentication.oidc.end.session.endpoint    https://keycloak.example.com/realms/lakehouse/protocol/openid-connect/logout

# Optional.
#kyuubi.authentication.oidc.redirect.uri           http://<kyuubi-host>:<rest-port>/api/v1/authentication/callback
#kyuubi.authentication.oidc.session.timeout        PT4H
#kyuubi.authentication.oidc.cookie.name            KYUUBI_SESSION
#kyuubi.authentication.oidc.cookie.secure          false
```

Any endpoint left unset is discovered from `kyuubi.authentication.jwt.issuer`. Pin all three when
the issuer is published with a certificate the server does not trust — discovery would be the only
thing reaching for it, and it fails where the token call over the internal URL would not.
`kyuubi.authentication.jwt.issuer` itself must stay the **external** URL either way: it is matched
against the `iss` claim.

Keycloak client changes relative to §7.2: client authentication **on**, and the valid redirect URI
becomes `http://<kyuubi-host>:<rest-port>/api/v1/authentication/callback` (the JDBC loopback entry
stays). `Web origins` is no longer required for the token call — the browser never makes one — but
is harmless to keep.

Three endpoints implement the flow, all unauthenticated because they are the way in:

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/authentication/login?redirect=<path>` | 302 to the provider, keeping `state`, `nonce` and the PKCE verifier server side |
| `GET /api/v1/authentication/callback?code=&state=` | Exchanges the code, verifies the id_token and its `nonce`, sets the session cookie, 302 back into the UI |
| `POST /api/v1/authentication/logout` | Drops the session, expires the cookie, returns `endSessionUrl` |

What the implementation guarantees:

- **Cookie**: `HttpOnly; Path=/; SameSite=Lax; Max-Age=<session.timeout>`, plus `Secure` when
  `cookie.secure=true` (set it once the UI is served over HTTPS). The value is 32 random bytes from
  `SecureRandom` — an id, not a JWT, and it carries no token inside.
- **PKCE is kept** alongside the client secret. Keycloak accepts both, and the verifier never
  leaves the server.
- **CSRF**: cookie-authenticated `POST/PUT/PATCH/DELETE` must carry a same-origin `Origin` (or
  `Referer`); otherwise 403. Requests authenticated with an `Authorization` header are exempt, so
  JDBC — which sends no `Origin` — is unaffected.
- **Precedence**: an `Authorization` header always wins. The cookie is only consulted when there
  is none, which is why nothing about the JDBC path changes.
- **Refresh**: renewed ~60s before expiry, under a per-session lock so parallel requests cannot
  race. Rotated refresh tokens replace the old one; an `invalid_grant` destroys the session and
  returns 401, at which point the UI silently starts a new login against the still-live SSO
  session.
- **Logging**: no `client_secret`, `code`, or token of any kind is logged, at any level. Session
  ids appear truncated to 8 characters.

Sessions are held in memory, so with `replicaCount > 1` a request that lands on another pod finds
no session and gets a 401 — the UI then re-runs the login, which returns without a password prompt
because the provider's own session is untouched. `sessionAffinity: ClientIP` on the Service avoids
even that. The store sits behind `OidcSessionStore`, so a shared implementation can replace it
without touching the flow.
