# Kế hoạch: Chạy song song LDAP (password) + OIDC (bearer) dưới `kyuubi.authentication=CUSTOM`

Tài liệu này là plan cho AI agent sửa code. Nó ghi lại hiện trạng đã khảo sát (kèm file:line), các khoảng trống phải lấp để chế độ `CUSTOM` phục vụ được **đồng thời** LDAP và OIDC, danh sách file cần sửa, các task nhỏ theo thứ tự, và cách kiểm thử. Đọc `fix_oidc.md` trước khi đụng vào BFF — đường đó đang chạy production.

---

## 1. Bối cảnh & Mục tiêu

### Hiện trạng
- Fork hiện chạy `kyuubi.authentication=OIDC`: password bị chặn hẳn bởi `DenyPasswordAuthenticationProvider`, chỉ nhận Bearer JWT; Web UI đăng nhập qua BFF (cookie `KYUUBI_SESSION`).
- Yêu cầu mới: **cùng một server** phải nhận cả
  - `Authorization: Basic user:pass` → xác thực qua **LDAP** (beeline/JDBC password, REST, Thrift binary 10009), và
  - `Authorization: Bearer <jwt>` → xác thực qua **OIDC/JWKS** (JDBC driver OIDC, REST token, Web UI BFF).

### Vì sao không dùng `kyuubi.authentication=LDAP,OIDC`
`AuthenticationFilter.initAuthHandlers` ([AuthenticationFilter.scala:63-69](../kyuubi-server/src/main/scala/org/apache/kyuubi/server/http/authentication/AuthenticationFilter.scala)) lọc bỏ KERBEROS/NOSASL rồi lấy **`headOption`** → chỉ type đầu tiên có hiệu lực. Thrift cũng vậy qua `AuthUtils.effectivePlainAuthType` ([AuthUtils.scala:68](../kyuubi-common/src/main/scala/org/apache/kyuubi/service/authentication/AuthUtils.scala)). Multi-type chỉ có ý nghĩa cho KERBEROS + một plain type.

### Cách đúng: một type `CUSTOM`, hai provider class
Với `CUSTOM` (hoặc `OIDC`), filter đăng ký **cả hai** handler theo scheme ([AuthenticationFilter.scala:76-93](../kyuubi-server/src/main/scala/org/apache/kyuubi/server/http/authentication/AuthenticationFilter.scala)):
- `BasicAuthenticationHandler` dùng `kyuubi.authentication.custom.basic.class`
- `BearerAuthenticationHandler` dùng `kyuubi.authentication.custom.bearer.class`

Thrift binary dùng `kyuubi.authentication.custom.class` qua `AuthenticationProviderFactory.getAuthenticationProviderForServer` ([AuthenticationProviderFactory.scala:56-60](../kyuubi-common/src/main/scala/org/apache/kyuubi/service/authentication/AuthenticationProviderFactory.scala)).

Mục tiêu: **giữ nguyên nguyên tắc của fork** — Kyuubi core (`kyuubi-common`) không sửa; mọi thứ wire bằng config + code trong `kyuubi-server` (đã là fork-modified) và module `extensions/server/kyuubi-oidc-auth`.

---

## 2. Khoảng trống phải lấp (đã verify trong code)

| # | Vấn đề | Vị trí | Hệ quả nếu không sửa |
|---|--------|--------|----------------------|
| G1 | `LdapAuthenticationProviderImpl` **không gán trực tiếp** qua `custom.*.class` được. Ctor của nó là `(conf: KyuubiConf, searchFactory: DirSearchFactory = new LdapSearchFactory)` — Scala default-arg **không** sinh ctor `(KyuubiConf)`; còn `ClassUtils.createInstance` chỉ thử ctor `(KyuubiConf)` rồi no-arg ([ClassUtils.scala:34-46](../kyuubi-common/src/main/scala/org/apache/kyuubi/util/ClassUtils.scala)). | `LdapAuthenticationProviderImpl.scala:32`, `ClassUtils.scala:37-41` | Server ném `KyuubiException: ... must extend of PasswdAuthenticationProvider` ngay request Basic đầu tiên. |
| G2 | `GET /api/v1/authentication/config` chỉ báo `oidcEnabled=true` khi auth type **== OIDC** ([ApiRootResource.scala:67-68](../kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/ApiRootResource.scala)). | `ApiRootResource.authenticationConfig` | Dưới `CUSTOM`, Web UI chỉ hiện form password; BFF/SSO không bao giờ được gọi dù đã cấu hình. |
| G3 | UI login là **either/or**: `oidcEnabled` → chỉ nút SSO/"Try again"; ngược lại chỉ form password ([login/index.vue:31-60,117-124](../kyuubi-server/web-ui/src/components/login/index.vue)). `auth-required` event cũng rẽ nhánh một trong hai. | `login/index.vue`, `pinia/auth/auth.ts` | Không có cách để user chọn SSO **hoặc** LDAP trên cùng màn hình. |
| G4 | Thrift binary dưới `CUSTOM` **bắt buộc** có `kyuubi.authentication.custom.class` (require ở `AuthenticationProviderFactory.scala:57-59`). | config | Thiếu → `IllegalArgumentException` khi client Thrift kết nối. |
| G5 | Chart `charts/kyuubi` chỉ có `kyuubiConf.kyuubiDefaults` free-form ([values.yaml:171-181](../charts/kyuubi/values.yaml)), render qua `tpl` vào ConfigMap ([kyuubi-configmap.yaml:45-47](../charts/kyuubi/templates/kyuubi-configmap.yaml)). Không có mẫu OIDC/LDAP, và `oidc.ui.client.secret` + LDAP bind password sẽ nằm plaintext trong ConfigMap. | chart | Deploy thiếu key, secret lộ trong ConfigMap. |
| G6 | `docs/security/oidc.md` chỉ mô tả `kyuubi.authentication=OIDC`. | docs | Người vận hành không biết chế độ song song. |

Ghi chú: `OidcBffConf.enabled` ([OidcBffConf.scala:59](../kyuubi-server/src/main/scala/org/apache/kyuubi/server/http/authentication/oidc/OidcBffConf.scala)) **không** phụ thuộc auth type — chỉ cần `oidc.ui.client.id` + `oidc.ui.client.secret`. Và `AuthenticationFilter` đổi cookie → Bearer trước khi tới handler (dòng 118-158, 208). Nên BFF backend đã sẵn sàng cho `CUSTOM`; chỉ G2/G3 chặn ở phía UI.

---

## 3. Thiết kế

### 3.1 Provider cho Basic/Thrift → LDAP (lấp G1)
Thêm vào `extensions/server/kyuubi-oidc-auth` (module đã có sẵn trên classpath server, không cần module mới):

```scala
// org.apache.kyuubi.auth.oidc.LdapPasswordAuthenticationProvider
class LdapPasswordAuthenticationProvider(conf: KyuubiConf)
  extends PasswdAuthenticationProvider {
  private val delegate = new LdapAuthenticationProviderImpl(conf)
  override def authenticate(user: String, password: String): Unit =
    delegate.authenticate(user, password)
}
```
- Mục đích duy nhất: cung cấp ctor `(KyuubiConf)` mà `ClassUtils.createInstance` tìm được. Không copy logic LDAP.
- Dùng cho **cả** `custom.class` (Thrift) và `custom.basic.class` (HTTP Basic).
- Đặt tên/package: cân nhắc package `org.apache.kyuubi.auth.ldap` trong cùng module để không gây hiểu nhầm là OIDC; nếu đổi package thì cập nhật cả docs/chart.

### 3.2 Server báo cho UI đúng khả năng (lấp G2)
Sửa `ApiRootResource.authenticationConfig`:
- `oidcEnabled` := bearer provider có cấu hình, **không** phụ thuộc auth type:
  ```scala
  val authTypeSet = authTypes.map(AuthTypes.withName).toSet
  val bearerConfigured = authTypeSet.contains(OIDC) ||
    (authTypeSet.contains(CUSTOM) && conf.get(AUTHENTICATION_CUSTOM_BEARER_CLASS).nonEmpty)
  val oidcEnabled = bearerConfigured && conf.getOption(JWT_ISSUER).exists(_.nonEmpty)
  ```
- Thêm field mới **`passwordEnabled: Boolean`**: `true` khi Basic handler thực sự chấp nhận password, tức auth type ∈ {LDAP, JDBC, CUSTOM với `custom.basic.class` ≠ `DenyPasswordAuthenticationProvider`}; `false` với OIDC thuần hoặc NONE/NOSASL (NONE: UI không cần login, giữ hành vi hiện tại).
- Giữ nguyên các field cũ (`authType`, `issuer`, `clientId`, `scope`, `flow`, `authenticated`, `username`) để UI cũ/clients khác không vỡ. Vẫn **không** lộ secret.

### 3.3 UI hỗ trợ cả hai (lấp G3)
`components/login/index.vue`:
- Dialog hiển thị theo ma trận `(oidcEnabled, passwordEnabled)`:
  - `(true, true)` → form username/password **+** nút "Sign in with SSO" (primary) ở footer, nút "Log in" cho form.
  - `(true, false)` → như hiện tại (SSO hint + Try again).
  - `(false, true)` → như hiện tại (form password).
- Handler `auth-required`: nếu `oidcEnabled && !passwordEnabled` → auto redirect SSO như hiện tại; nếu `passwordEnabled` → mở dialog cho user chọn (không tự redirect, vì redirect tự động sẽ cướp mất lựa chọn LDAP).
- `pinia/auth/auth.ts`: thêm `passwordEnabled` vào type `AuthConfig`; default `false`. Logic BFF/refresh giữ nguyên.
- `api/auth/index.ts` + `utils/request.ts`: kiểm tra Basic login path (đang dùng cho LDAP/NONE) vẫn hoạt động khi `oidcEnabled=true` — hiện có chỗ nào `if (oidcEnabled) → bearer only` thì nới ra theo nguồn credential thực tế của session (có token → Bearer; có basic creds → Basic; BFF → cookie).

### 3.4 Config đầy đủ cho chế độ song song (lấp G4, G6)
```properties
kyuubi.authentication=CUSTOM

# --- Password (Thrift binary + HTTP Basic) -> LDAP
kyuubi.authentication.custom.class=org.apache.kyuubi.auth.oidc.LdapPasswordAuthenticationProvider
kyuubi.authentication.custom.basic.class=org.apache.kyuubi.auth.oidc.LdapPasswordAuthenticationProvider
kyuubi.authentication.ldap.url=ldap://ldap.example.com:389
kyuubi.authentication.ldap.baseDN=dc=example,dc=com
kyuubi.authentication.ldap.binddn=cn=kyuubi,ou=svc,dc=example,dc=com
kyuubi.authentication.ldap.bindpw=<secret>
# (tuỳ chọn) ldap.userDNPattern / userFilter / groupFilter ... như upstream

# --- Bearer (HTTP) -> OIDC JWKS   (copy nguyên khối đang dùng với kyuubi.authentication=OIDC)
kyuubi.authentication.custom.bearer.class=org.apache.kyuubi.auth.oidc.JwtTokenAuthenticationProvider
kyuubi.authentication.jwt.issuer=https://keycloak.example.com/realms/prod
kyuubi.authentication.jwt.audience=kyuubi-jdbc
kyuubi.authentication.jwt.username.claim=preferred_username
kyuubi.authentication.jwt.allowed.algorithms=RS256
kyuubi.authentication.jwt.clock.skew.seconds=30

# --- Web UI BFF (giữ nguyên khối hiện tại)
kyuubi.authentication.oidc.ui.client.id=kyuubi-ui
kyuubi.authentication.oidc.ui.client.secret=<secret>
kyuubi.authentication.oidc.ui.scope=openid profile email
kyuubi.authentication.oidc.authorization.endpoint=...
kyuubi.authentication.oidc.token.endpoint=...
kyuubi.authentication.oidc.end.session.endpoint=...
#kyuubi.authentication.oidc.redirect.uri=...
#kyuubi.authentication.oidc.session.timeout=PT4H
#kyuubi.authentication.oidc.cookie.secure=true
```
Ràng buộc vận hành (ghi vào docs):
- **Không** đặt `DenyPasswordAuthenticationProvider` làm basic/custom class trong chế độ này.
- Bearer chỉ đi qua HTTP (`transportMode=http` / REST / UI). Thrift binary 10009 chỉ có LDAP.
- Username từ claim JWT phải **trùng** uid LDAP (khuyến nghị Keycloak federate từ cùng LDAP), nếu không Ranger/engine share-level USER sẽ thấy hai user khác nhau.

### 3.5 Chart (lấp G5)
Trong `charts/kyuubi`:
- `values.yaml`: thêm block **comment mẫu** đầy đủ cho chế độ `CUSTOM` (nội dung §3.4) ngay dưới ví dụ `kyuubiDefaults` hiện có; thêm `authSecret` (tên Secret có sẵn hoặc `existingSecret`) chứa `LDAP_BIND_PASSWORD`, `OIDC_UI_CLIENT_SECRET`.
- `templates/kyuubi-statefulset.yaml` (hoặc deployment đang dùng): nếu `authSecret` được set → inject `envFrom`/`env` từ Secret.
- Vì `kyuubiDefaults` đã qua `tpl`, mẫu dùng `{{ .Values.auth.ldapBindDn }}` cho giá trị không nhạy cảm. Với secret: Kyuubi **không** expand env var trong `kyuubi-defaults.conf`, nên dùng một trong hai cách (chọn cách 1 nếu không muốn sửa image):
  1. `kyuubiConf.kyuubiEnv` thêm `export KYUUBI_JAVA_OPTS="$KYUUBI_JAVA_OPTS -Dkyuubi.authentication.ldap.bindpw=$LDAP_BIND_PASSWORD -Dkyuubi.authentication.oidc.ui.client.secret=$OIDC_UI_CLIENT_SECRET"` — cần verify `KyuubiConf` load system properties `kyuubi.*` (upstream có; kiểm tra lại trong `KyuubiConf.loadFromMap`/`Utils.getSystemProperties`).
  2. Mount Secret thành file `kyuubi-defaults.conf` phụ và nối vào bằng init container.
- Thêm `charts/kyuubi/values-auth-custom-example.yaml` để `helm template -f` kiểm tra render.
- `NOTES.txt`: cảnh báo nếu `kyuubi.authentication=CUSTOM` mà thiếu `custom.class`/`custom.basic.class`/`custom.bearer.class` (regexMatch tương tự check `bind.host` hiện có).

---

## 4. File cần sửa / thêm

**Thêm**
- `extensions/server/kyuubi-oidc-auth/src/main/scala/org/apache/kyuubi/auth/oidc/LdapPasswordAuthenticationProvider.scala`
- `extensions/server/kyuubi-oidc-auth/src/test/scala/org/apache/kyuubi/auth/oidc/LdapPasswordAuthenticationProviderSuite.scala`
- `charts/kyuubi/values-auth-custom-example.yaml`

**Sửa — server**
- `kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/ApiRootResource.scala` (`authenticationConfig`)
- `kyuubi-server/src/test/scala/org/apache/kyuubi/server/http/authentication/AuthenticationFilterSuite.scala` (case CUSTOM + 2 handler)
- `kyuubi-server/src/test/scala/org/apache/kyuubi/server/http/authentication/OidcCookieAuthenticationSuite.scala` (chạy lại với `CUSTOM`)
- Test mới/mở rộng cho `authentication/config` (tìm suite REST hiện có của `ApiRootResource`, thêm 3 case theo ma trận §3.3)

**Sửa — web UI** (`kyuubi-server/web-ui/src`)
- `components/login/index.vue`
- `pinia/auth/auth.ts`
- `api/auth/index.ts`, `utils/request.ts` (chỉ nếu có nhánh bearer-only — xem §3.3)
- `test/` tương ứng (vitest) cho store + login component

**Sửa — chart & docs**
- `charts/kyuubi/values.yaml`, `charts/kyuubi/templates/kyuubi-statefulset.yaml` (hoặc file workload thực tế), `charts/kyuubi/templates/NOTES.txt`
- `docs/security/oidc.md` → thêm mục "Chạy song song LDAP + OIDC (`CUSTOM`)"
- `AGENTS.md` → một dòng trong mục OIDC: `CUSTOM` + `LdapPasswordAuthenticationProvider` là cách chạy song song

**Không sửa** (nguyên tắc fork): `kyuubi-common/**` (`LdapAuthenticationProviderImpl`, `ClassUtils`, `AuthenticationProviderFactory`, `AuthenticationFilter.initAuthHandlers`).

---

## 5. Task breakdown (theo thứ tự, mỗi task tự verify được)

### T1 — Wrapper LDAP provider
1. Viết `LdapPasswordAuthenticationProvider` (§3.1), license header, scalafmt.
2. Unit test: dùng `DirSearchFactory` giả (xem cách `LdapAuthenticationProviderImpl` được test ở upstream `kyuubi-common/src/test/.../LdapAuthenticationProviderImplSuite`) hoặc tối thiểu test `ClassUtils.createInstance(className, classOf[PasswdAuthenticationProvider], conf)` **không ném** và trả về instance đúng type.
3. Build: `./build/mvn -pl extensions/server/kyuubi-oidc-auth -am test -DskipTests=false -Dtest=none -DwildcardSuites=org.apache.kyuubi.auth.oidc.LdapPasswordAuthenticationProviderSuite`.

### T2 — Filter integration test
1. Trong `AuthenticationFilterSuite`, thêm case: `AUTHENTICATION_METHOD=CUSTOM`, `custom.basic.class=<fake passwd provider>`, `custom.bearer.class=<fake token provider>` → assert `authSchemeHandlers` có cả `BASIC` và `BEARER`; request Basic đi tới passwd provider, request Bearer đi tới token provider.
2. Chạy `OidcCookieAuthenticationSuite` với `CUSTOM` thay vì `OIDC` (parametrize hoặc thêm case) để chứng minh cookie→Bearer vẫn đúng.

### T3 — `authentication/config`
1. Sửa theo §3.2, thêm `passwordEnabled`.
2. Test REST: (a) `OIDC` thuần → `oidcEnabled=true, passwordEnabled=false`; (b) `CUSTOM` + cả hai class → `true, true`; (c) `LDAP` → `false, true`; (d) `CUSTOM` + basic=Deny → `true,false`.
3. Bảo đảm response không chứa `client.secret` / `ldap.bindpw` (assert negative).

### T4 — Web UI
1. `auth.ts`: thêm `passwordEnabled`.
2. `login/index.vue`: ma trận §3.3; `auth-required` không auto-redirect khi `passwordEnabled`.
3. Rà `request.ts`/`api/auth` cho nhánh bearer-only.
4. `pnpm lint && pnpm test && pnpm build` trong `kyuubi-server/web-ui`.

### T5 — Chart
1. `values.yaml` + example values + Secret injection + NOTES check (§3.5).
2. Verify: `helm template charts/kyuubi -f charts/kyuubi/values-auth-custom-example.yaml | grep -A40 kyuubi-defaults.conf` ra đủ key; `helm lint charts/kyuubi`.
3. Verify cách truyền secret (system property hay file) thực sự được `KyuubiConf` đọc — ghi kết quả vào docs.

### T6 — Docs & cleanup
1. `docs/security/oidc.md`, `AGENTS.md`.
2. `./dev/reformat`; full build các module đụng tới: `./build/mvn -pl kyuubi-server,extensions/server/kyuubi-oidc-auth -am -DskipTests package`.

---

## 6. Kiểm thử end-to-end (staging, trước khi lên production)

| Kịch bản | Cách chạy | Kỳ vọng |
|----------|-----------|---------|
| Thrift binary + LDAP | `beeline -u "jdbc:kyuubi://host:10009/" -n ldapuser -p ldappass` | kết nối OK; sai pass → `AuthenticationException` |
| HTTP Basic + LDAP | `curl -u ldapuser:pass http://host:10099/api/v1/sessions` | 200 |
| HTTP Bearer + JWT | `curl -H "Authorization: Bearer $TOKEN" .../api/v1/sessions` | 200; token sai iss/aud/exp → 401 |
| JDBC OIDC qua HTTP | `jdbc:kyuubi://host:10009/;transportMode=http;httpPath=cliservice;auth=oidc;...` (xem `OidcParams`) | login browser/device OK |
| JDBC OIDC qua binary | cùng URL, bỏ `transportMode=http` | **phải thất bại rõ ràng** (chỉ LDAP) — ghi vào docs |
| Web UI | mở UI → dialog có **cả** form password **và** nút SSO; test cả hai đường; logout BFF vẫn xoá cookie | |
| Cùng user hai đường | login LDAP và login OIDC cùng `preferred_username` | `/api/v1/sessions` hiện cùng `user`, Ranger áp cùng policy |
| Audit | log `AuthenticationFilter`/`OidcBffService` không in token/password | |

---

## 7. Rủi ro & quyết định cần chốt

1. **Auto-redirect SSO**: hiện UI tự redirect khi nhận `auth-required`. Dưới chế độ song song plan đề xuất **không** auto-redirect (hiện dialog có 2 lựa chọn). Nếu muốn mặc định SSO và chỉ "Dùng tài khoản LDAP" như link phụ → chốt trước T4.
2. **Package của wrapper**: `org.apache.kyuubi.auth.oidc` (cùng module, ít thay đổi) hay `org.apache.kyuubi.auth.ldap`. Plan mặc định giữ module `kyuubi-oidc-auth`, package tuỳ chọn.
3. **Secret trong chart**: cách 1 (`-D` qua `kyuubiEnv`) phụ thuộc `KyuubiConf` đọc system properties — phải verify ở T5.3; nếu không, dùng cách 2.
4. **Hai user khác tên** nếu LDAP uid ≠ claim JWT: không có cách map trong code hiện tại; nếu cần, mở rộng `kyuubi.authentication.jwt.username.claim` hoặc dùng Keycloak mapper. Ngoài phạm vi plan này.
5. Kyuubi core (`kyuubi-common`) **không** sửa — nếu agent thấy buộc phải sửa, dừng và báo lại.
