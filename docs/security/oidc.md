# OIDC SSO Configuration

> Web UI và BFF cookie flow đã bị gỡ khỏi fork này; Kyuubi chỉ nhận `Authorization: Bearer` (REST, Thrift HTTP) và access token trong ô password của SASL PLAIN (Thrift binary).

## 1. Keycloak Client Setup

### JDBC Client (public)
| Setting | Value |
|---------|-------|
| Client ID | `kyuubi-jdbc` |
| Client protocol | OpenID Connect |
| Client authentication | OFF |
| Standard flow | ON |
| OAuth 2.0 Device Authorization Grant | ON |
| PKCE Code Challenge Method | S256 |
| Valid redirect URIs | `http://127.0.0.1/*` |
| Audience | thêm client scope emit audience = `kyuubi-jdbc` |

Discovery: `https://<keycloak-host>/realms/<realm>/.well-known/openid-configuration`

---

## 2. Server Configuration

```properties
# Enable HTTP thrift + REST
kyuubi.frontend.protocols                 THRIFT_HTTP,REST

# Enable OIDC authentication
kyuubi.authentication                     OIDC

# JWT validation
kyuubi.authentication.jwt.issuer          https://keycloak.example.com/realms/prod
kyuubi.authentication.jwt.audience       kyuubi-jdbc
kyuubi.authentication.jwt.username.claim   preferred_username
kyuubi.authentication.jwt.allowed.algorithms RS256
kyuubi.authentication.jwt.clock.skew.seconds 30

# Fat token header size (Keycloak tokens with many roles)
kyuubi.frontend.thrift.http.request.header.size 32768
```

### All JWT Config Keys

| Key | Required | Default | Description |
|-----|----------|---------|-------------|
| `jwt.issuer` | Yes | - | OIDC issuer URL |
| `jwt.audience` | Yes | - | Accepted audience (usually client ID) |
| `jwt.jwks.url` | No | auto-discovered | JWKS URL |
| `jwt.username.claim` | No | `preferred_username` | Claim for session user |
| `jwt.allowed.algorithms` | No | `RS256` | Allowed signature algorithms |
| `jwt.expected.typ` | No | `at+jwt` | Required JOSE typ header (set to empty to disable) |
| `jwt.allowed.azp` | No | - | Allowed authorized party (`azp`) client ID(s), comma-separated. Dùng để giới hạn token chỉ từ client được cấp phép khi chưa tách riêng audience. |
| `jwt.clock.skew.seconds` | No | `30` | Clock skew tolerance |
| `jwt.connect.timeout.ms` | No | `5000` | JWKS connect timeout |
| `jwt.read.timeout.ms` | No | `5000` | JWKS read timeout |
| `jwt.password.enabled` | No | `true` | Cho phép xác thực token qua password (Thrift binary và HTTP Basic). Đặt false sẽ chỉ còn Bearer |
| `jwt.password.user.aliases` | No | `token` | Danh sách alias người dùng phân cách bằng dấu phẩy. Khi username là alias, user thật lấy từ token |

---

## 3. Thrift binary

### Cấu hình (không cần `custom.class`)
```properties
kyuubi.frontend.protocols                         THRIFT_BINARY,THRIFT_HTTP,REST
kyuubi.authentication                             OIDC
# mặc định true; đặt false sẽ chặn CẢ binary lẫn HTTP Basic, chỉ còn Bearer
kyuubi.authentication.jwt.password.enabled        true
kyuubi.authentication.jwt.password.user.aliases   token
```

### URL JDBC
```
jdbc:kyuubi://<host>:10009/default;auth=oidc;oidcIssuer=<issuer>;oidcClientId=<client>
jdbc:kyuubi://<host>:10009/default;auth=jwt;jwt=<access-token>
```
PyHive / beeline: `username=token` (hoặc đúng `preferred_username`), `password=<access-token>`.

### Quy tắc
- `user` phải bằng claim username trong token, hoặc là một alias (`token`). Alias **không bao giờ** được trùng tên user thật.
- Token chỉ kiểm lúc mở kết nối; pool kết nối phải lấy token còn hạn khi tạo kết nối mới.
- Token đi dạng rõ trong SASL PLAIN nếu không bật `kyuubi.frontend.thrift.binary.ssl.enabled` — coi password như mật khẩu, không log URL/password.
- Đổi bất kỳ `kyuubi.authentication.jwt.*` nào phải restart Kyuubi.

---

## 4. Thrift HTTP JDBC URL

### Desktop SSO (Auth Code + PKCE)
```
jdbc:kyuubi://host:10009/default;transportMode=http;httpPath=cliservice;ssl=true;auth=oidc;oidcIssuer=https://keycloak.example.com/realms/prod;oidcClientId=kyuubi-jdbc
```

### Headless (Device Flow)
```
jdbc:kyuubi://host:10009/default;transportMode=http;httpPath=cliservice;ssl=true;auth=oidc;oidcIssuer=https://keycloak.example.com/realms/prod;oidcClientId=kyuubi-jdbc;oidcFlow=device
```

### Headless (Pre-issued Token)
```
jdbc:kyuubi://host:10009/default;transportMode=http;httpPath=cliservice;ssl=true;auth=jwt
```
Set token via `JWT` env var or `;jwt=<token>`

---

## 5. Driver Parameters

| Param | Default | Description |
|-------|---------|-------------|
| `oidcIssuer` | - | OIDC issuer URL |
| `oidcClientId` | - | Public client ID |
| `oidcClientSecret` | - | For confidential clients |
| `oidcScope` | `openid profile email` | OAuth scopes |
| `oidcFlow` | `authcode` | `authcode` \| `device` \| `auto` |
| `oidcRedirectPort` | `0` | Loopback port (0=ephemeral) |
| `oidcTokenCache` | `true` | Reuse tokens in JVM |
| `oidcBrowser` | `auto` | `auto` \| `none` |
| `oidcLogout` | `false` | Call end_session_endpoint on close |
