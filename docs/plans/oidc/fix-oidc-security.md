# Plan: Siết bảo mật OIDC trên ssa-kyuubi

Trạng thái: **chưa thực hiện**. Đối tượng thực thi: AI agent. Người duyệt: j3rrytran.

## 0. Bối cảnh — đọc trước khi sửa gì

### 0.1 Kiến trúc thực tế đang chạy

```
SSA (portal) ──redirect──▶ Keycloak ──access_token──▶ App
                                                        │  Authorization: Bearer <access_token>
                                                        ▼
                                              Kyuubi (REST /api/v1, Thrift HTTP)
                                                        │
                                                        ▼
                                              Spark engine + Ranger plugin (cưỡng chế phân quyền)
```

- Người dùng đăng nhập **ở SSA**, không phải ở Kyuubi.
- Kyuubi là **resource server thuần**: nhận JWT, kiểm chữ ký/claim, lấy `preferred_username` làm session user. Không quản phiên, không phát cookie.
- **Web UI của Kyuubi đã bị gỡ** (`kyuubi-server/web-ui/` không còn; `installWebUI`, engine UI proxy, notebook, SQL editor đều đã xoá khỏi `KyuubiRestFrontendService.startInternal`).
- Phân quyền dữ liệu nằm **duy nhất** ở Ranger plugin trong Spark engine. Mọi thứ App tự hỏi Ranger chỉ để vẽ UI.

### 0.2 Các mảnh OIDC trong repo

| Mảnh | Vị trí | Trạng thái sau plan |
|---|---|---|
| Kiểm JWT (lõi) | `extensions/server/kyuubi-oidc-auth/.../JwtTokenAuthenticationProvider.scala` | **giữ, sửa** |
| Token qua SASL PLAIN (Thrift binary) | `extensions/server/kyuubi-oidc-auth/.../JwtPasswordAuthenticationProvider.scala` | giữ nguyên |
| Handler HTTP Bearer | `kyuubi-server/.../http/authentication/BearerAuthenticationHandler.scala` | **giữ, sửa** |
| Factory tạo provider | `kyuubi-common/.../service/authentication/AuthenticationProviderFactory.scala` | **giữ, sửa** |
| BFF cho Web UI (cookie `KYUUBI_SESSION`) | `kyuubi-server/.../http/authentication/oidc/**` + 4 endpoint trong `ApiRootResource` | **xoá toàn bộ** |
| Client JDBC | `kyuubi-hive-jdbc/.../auth/oidc/**` | giữ, vệ sinh nhỏ |

### 0.3 Ràng buộc bắt buộc với agent

1. **Không chạy bất kỳ lệnh git nào làm thay đổi trạng thái** (`add`, `commit`, `stash`, `checkout -- <file>`, `reset`, …). Chỉ được `git status`, `git diff`, `git log`. Commit là việc của người duyệt.
2. **Không chạy build/test/format** (`mvn`, `./build/mvn`, `./dev/reformat`, spotless). Người duyệt sẽ tự build và chạy test lần cuối sau khi agent hoàn thành code và testcase. Agent chỉ sửa code, viết test, và tự rà bằng cách đọc code + `grep`.
3. Viết code theo đúng style scalafmt/scalastyle của repo (nhìn file bên cạnh để bắt chước: indent 2, dòng ≤ 100 ký tự, import gom nhóm `java` → `scala` → bên thứ ba → `org.apache.kyuubi`, license header ASF đầu file mới).
4. Không đăng ký `ConfigEntry` mới trong `KyuubiConf` cho các key `kyuubi.authentication.jwt.*` — giữ nguyên quy ước đọc raw qua `conf.getOption` để Kyuubi core không bị đụng (xem `AGENTS.md`).
5. Mỗi task bên dưới là một đơn vị độc lập, có tiêu chí nghiệm thu riêng. Làm theo đúng thứ tự.
6. Không "tiện tay" sửa thứ ngoài phạm vi. Nếu phát hiện vấn đề khác, ghi vào mục 5 (Phát hiện thêm) ở cuối file này.

---

## 1. Danh sách task

Thứ tự: **A1 → A2 → A3 → A4 → A5 → A6**. A1 và A2 độc lập, có thể làm song song nếu agent chạy nhiều worktree.

---

### A1 — Cache `TokenAuthenticationProvider`: ngừng gọi Keycloak mỗi request 🔴

#### Vấn đề

`BearerAuthenticationHandler.authenticate` gọi `AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(providerClass, conf)` **mỗi request**. Factory dùng `ClassUtils.createInstance` → reflection tạo đối tượng mới, không cache. Constructor của `JwtTokenAuthenticationProvider`:

- gọi `discoverJwksUri()` = 1 HTTPS GET tới `<issuer>/.well-known/openid-configuration` (khi `kyuubi.authentication.jwt.jwks.url` không set);
- dựng `JWKSourceBuilder` mới → cache JWKS nằm trên instance → chết theo request → lần sau tải lại JWKS.

Hậu quả: 1 REST call = 1–2 HTTPS call ra Keycloak. Keycloak chậm/chết → Kyuubi mất xác thực. Kẻ tấn công **không cần tài khoản** gửi `Authorization: Bearer rac` liên tục là bơm tải thẳng vào IdP.

Đối chiếu: `JwtPasswordAuthenticationProvider` (đường Thrift binary) **có** singleton `_verifier`; `OidcBffService` dùng `lazy val`. Chỉ đường Bearer quên.

#### Sửa

**File:** `kyuubi-common/src/main/scala/org/apache/kyuubi/service/authentication/AuthenticationProviderFactory.scala`

Thêm cache trong `object AuthenticationProviderFactory`, key = `providerClass`:

```scala
import java.util.concurrent.ConcurrentHashMap
import java.util.function.{Function => JFunction}

// Một provider là đối tượng nặng (tải JWKS, giữ cache khoá), nên được dùng lại
// cho mọi request thay vì dựng lại từng lần.
private val bearerProviders =
  new ConcurrentHashMap[String, TokenAuthenticationProvider]()

def getHttpBearerAuthenticationProvider(
    providerClass: String,
    conf: KyuubiConf): TokenAuthenticationProvider = {
  require(
    !StringUtils.isBlank(providerClass),
    "kyuubi.authentication.custom.bearer.class must be set for http bearer authentication.")
  bearerProviders.computeIfAbsent(
    providerClass,
    new JFunction[String, TokenAuthenticationProvider] {
      override def apply(cls: String): TokenAuthenticationProvider =
        ClassUtils.createInstance(cls, classOf[TokenAuthenticationProvider], conf)
    })
}

/** Chỉ dùng trong test để đổi conf giữa các case. */
private[kyuubi] def resetBearerProviderCache(): Unit = bearerProviders.clear()
```

Ghi chú thiết kế, để trong comment code:
- Key chỉ là `providerClass`, không gồm `conf`: trong một JVM server chỉ có một `KyuubiConf`; test nào cần conf khác phải gọi `resetBearerProviderCache()`.
- Nếu constructor ném exception (ví dụ discovery fail lúc khởi động), `computeIfAbsent` **không** cache gì → request sau sẽ thử lại. Đây là hành vi mong muốn.
- `JwtTokenAuthenticationProvider` **được phép** dùng chung giữa các thread: Nimbus `DefaultJWTProcessor` và `JWKSource` là thread-safe.

**Không** sửa `getHttpBasicAuthenticationProvider`: provider password cho OIDC đã có singleton nội bộ, còn LDAP/JDBC là hành vi upstream, ngoài phạm vi.

**File:** `kyuubi-server/src/main/scala/org/apache/kyuubi/server/http/authentication/BearerAuthenticationHandler.scala`

Không cần đổi — nó vẫn gọi factory, factory giờ trả instance cache. Nhưng **phải** kiểm chắc `authenticate` không giữ state nào trên handler giữa các request (hiện không có, chỉ xác nhận).

#### Test

Thêm vào `kyuubi-common/src/test/scala/org/apache/kyuubi/service/authentication/` một suite mới `AuthenticationProviderFactorySuite` (nếu chưa có file cùng tên — kiểm trước, có thì thêm test case vào):

```scala
test("bearer provider is created once per class and reused") {
  AuthenticationProviderFactory.resetBearerProviderCache()
  val conf = KyuubiConf()
  val cls = classOf[CountingTokenProvider].getName
  CountingTokenProvider.constructed.set(0)
  val a = AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(cls, conf)
  val b = AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(cls, conf)
  assert(a eq b)
  assert(CountingTokenProvider.constructed.get() === 1)
}

test("a failing constructor is not cached") {
  AuthenticationProviderFactory.resetBearerProviderCache()
  val cls = classOf[ThrowingTokenProvider].getName
  intercept[KyuubiException] {
    AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(cls, KyuubiConf())
  }
  // lần 2 vẫn phải thử dựng lại, không trả null/instance hỏng
  intercept[KyuubiException] {
    AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(cls, KyuubiConf())
  }
}
```

Với `CountingTokenProvider`/`ThrowingTokenProvider` là hai class test đơn giản implement `TokenAuthenticationProvider` có constructor `(conf: KyuubiConf)`.

Rà các test hiện có trong `kyuubi-server/src/test` và `extensions/server/kyuubi-oidc-auth/src/test` gọi `getHttpBearerAuthenticationProvider` với conf khác nhau giữa các case — nếu có, thêm `resetBearerProviderCache()` vào `beforeEach`.

#### Nghiệm thu A1

- [ ] Suite mới đã viết với 2 test ở trên và 2 class provider giả.
- [ ] Đã `grep` mọi test gọi `getHttpBearerAuthenticationProvider` và thêm `resetBearerProviderCache()` nơi cần.
- [ ] Đọc lại `JwtTokenAuthenticationProvider`: xác nhận không có field mutable nào ngoài những thứ Nimbus quản — ghi một dòng vào báo cáo.

---

### A2 — Xoá toàn bộ BFF (cookie session cho Web UI đã gỡ) 🔴

#### Vấn đề

Web UI không còn, nhưng toàn bộ code BFF vẫn compile và **4 endpoint vẫn đăng ký, vẫn miễn xác thực**:

- `GET /api/v1/authentication/config` — **không** nằm sau `withBff`, luôn trả lời, không cần token, lộ: `authType`, thrift bind host + port, issuer URL, clientId, scope. Information disclosure không có công dụng bù lại.
- `GET /api/v1/authentication/login` — nếu BFF bật: ai cũng tạo được `PendingLogin` sống 10 phút, store không giới hạn → phình heap.
- `GET /api/v1/authentication/callback` — nếu BFF bật: `state` không ràng buộc với trình duyệt → login CSRF / session fixation.
- `POST /api/v1/authentication/logout`.

Xoá sạch sẽ đóng đồng thời các lỗi: session fixation, cookie thiếu `Secure`, DoS phình RAM, phiên không hết hạn tuyệt đối, logout không revoke, rò rỉ `/config`.

#### Xoá

**Thư mục (xoá hoàn toàn):**
```
kyuubi-server/src/main/scala/org/apache/kyuubi/server/http/authentication/oidc/
  BearerSessionRequest.scala
  OidcBffConf.scala
  OidcBffService.scala
  OidcBffUtils.scala
  OidcSessionStore.scala
  TokenEndpointClient.scala
kyuubi-server/src/test/scala/org/apache/kyuubi/server/http/authentication/oidc/
  OidcBffServiceSuite.scala
  OidcBffUtilsSuite.scala
  OidcSessionStoreSuite.scala
kyuubi-server/src/test/scala/org/apache/kyuubi/server/http/authentication/OidcCookieAuthenticationSuite.scala
```

**File:** `kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/ApiRootResource.scala`

- Xoá 4 method: `authenticationConfig`, `oidcLogin`, `oidcCallback`, `oidcLogout`.
- Xoá các helper chỉ chúng dùng: `currentSession`, `withBff`, `problem`, `noStore` (kiểm `grep` trước khi xoá — nếu `problem`/`noStore` còn chỗ khác dùng thì giữ).
- Xoá import: `java.net.URI`, `javax.ws.rs.{POST, QueryParam}` (giữ `GET, Path, Produces`), `org.apache.kyuubi.server.http.authentication.oidc.*`, `OidcBffConf.FLOW_PUBLIC`, `ApiRootResource.{JWT_ISSUER, OIDC_UI_CLIENT_ID, OIDC_UI_SCOPE}`, `org.apache.kyuubi.service.authentication.AuthTypes`, và `KyuubiConf._` nếu không còn dùng.
- Trong `object ApiRootResource`: xoá 3 `private[v1] val JWT_ISSUER / OIDC_UI_CLIENT_ID / OIDC_UI_SCOPE`.
- Giữ nguyên `version`, `ping`, các sub-resource `sessions/operations/batches/admin`, `test()`, `getServletHandler`.

**File:** `kyuubi-server/src/main/scala/org/apache/kyuubi/server/http/authentication/AuthenticationFilter.scala`

- Xoá import `org.apache.kyuubi.server.http.authentication.oidc.{BearerSessionRequest, OidcAuthFailure, OidcBffService, OidcBffUtils}`.
- Xoá `private[authentication] lazy val oidcBff`.
- Xoá cả hai overload `applyCookieSession` và `private def unauthorized`.
- Trong `doFilter`:
  - Xoá khối `if (UNAUTHENTICATED_PATHS.contains(...)) { ...; return }`.
  - Thay
    ```scala
    val httpRequest = applyCookieSession(incomingRequest, httpResponse) match {
      case Some(req) => req
      case None =>
        AuthenticationAuditLogger.audit(incomingRequest, httpResponse)
        return
    }
    ```
    bằng `val httpRequest = incomingRequest`. Phần còn lại giữ nguyên (so với upstream Kyuubi 1.10.3 `doFilter`, kết quả phải giống hệt — đối chiếu nếu cần).
- Trong `object AuthenticationFilter`: xoá `final val UNAUTHENTICATED_PATHS`.
- `initAuthHandlers` **giữ nguyên** — phần chọn `OIDC_PASSWD_PROVIDER_CLASS` / `OIDC_BEARER_PROVIDER_CLASS` là của đường Bearer/Basic, không phải BFF.

**Kiểm sau khi xoá:**
```bash
grep -rn "authentication\.oidc\.\|OidcBff\|BearerSessionRequest\|UNAUTHENTICATED_PATHS\|KYUUBI_SESSION" \
  --include=*.scala --include=*.java --include=*.xml kyuubi-server kyuubi-common extensions | grep -v /target/
```
Kết quả phải **rỗng** (các key `kyuubi.authentication.oidc.ui.*` chỉ còn trong docs).

`com.nimbusds` trong `kyuubi-server/src/main` chỉ được dùng bởi `OidcBffService` — sau khi xoá, không cần đụng `pom.xml` vì dependency đến qua `kyuubi-oidc-auth`.

**Docs:** `docs/security/oidc.md`
- Xoá mục `### Web UI Client (public)`, `### Web UI Client (BFF - confidential)`, toàn bộ `## 3. Web UI SSO Config` (và đánh số lại các mục sau).
- Thêm một dòng ở đầu: *"Web UI và BFF cookie flow đã bị gỡ khỏi fork này; Kyuubi chỉ nhận `Authorization: Bearer` (REST, Thrift HTTP) và access token trong ô password của SASL PLAIN (Thrift binary)."*

`docs/plans/oidc/OIDC_SSO_DEPLOYMENT.md`, `docs/plans/execution-order.md`, `docs/plans/sync-custom-remove-ui-s3.md`: **không sửa** (là lịch sử plan), nhưng ghi vào báo cáo rằng chúng mô tả trạng thái cũ.

#### Nghiệm thu A2

- [ ] Rà bằng mắt 2 file đã sửa: không còn import thừa, không còn symbol trỏ tới package `oidc`.
- [ ] `grep` ở trên trả rỗng.
- [ ] Đọc lại `doFilter` sau khi sửa và đối chiếu với upstream Kyuubi 1.10.3: logic phải giống hệt.
- [ ] Đọc code để khẳng định: `GET /api/v1/authentication/config` → **404** (không còn resource), `GET /api/v1/sessions` không header → **401** (handler vẫn hoạt động). Ghi vào báo cáo.
- [ ] Số dòng xoá ≈ 900 main + ≈ 800 test (`git diff --stat`). Nếu chênh lớn, kiểm lại có sót file.

---

### A3 — Mặc định `expected.typ = at+jwt` 🟠

#### Vấn đề

`kyuubi.authentication.jwt.expected.typ` là tuỳ chọn. Không set → `JwtTokenAuthenticationProvider` không nhìn JOSE header `typ` → một **id_token** (cùng `iss`, cùng `aud`, có `preferred_username`) được chấp nhận như access token. App cầm cả hai token, truyền nhầm là lỗi phổ biến; hiện nó âm thầm chạy được. Keycloak phát access token với `typ: "at+jwt"` và id_token với `typ: "JWT"`.

Sau A2, không còn chỗ nào trong server cần verify id_token nữa (chỉ `OidcBffService` làm việc đó), nên đổi mặc định không có tác dụng phụ.

#### Sửa

**File:** `extensions/server/kyuubi-oidc-auth/src/main/scala/org/apache/kyuubi/auth/oidc/JwtTokenAuthenticationProvider.scala`

```scala
// Trước
private val expectedTyp: Option[String] =
  conf.getOption(EXPECTED_TYP).map(_.trim).filter(_.nonEmpty)

// Sau
/**
 * JOSE `typ` bắt buộc. Mặc định `at+jwt` (RFC 9068) để chỉ nhận access token và loại
 * id_token. Đặt rõ giá trị rỗng (`kyuubi.authentication.jwt.expected.typ=`) để tắt kiểm tra,
 * ví dụ với IdP không ghi typ.
 */
private val expectedTyp: Option[String] = conf.getOption(EXPECTED_TYP) match {
  case None => Some(DEFAULT_EXPECTED_TYP)
  case Some(raw) => Option(raw.trim).filter(_.nonEmpty)
}
```

Trong `object JwtTokenAuthenticationProvider` thêm `val DEFAULT_EXPECTED_TYP = "at+jwt"`.

Trong dòng `info(s"Initialized JWT authentication: ...")` thêm `expectedTyp=${expectedTyp.getOrElse("<disabled>")}`. Nếu `expectedTyp.isEmpty` → thêm `warn("JOSE typ check is disabled; id_tokens will be accepted as access tokens")`.

Cập nhật scaladoc đầu class: dòng `expected.typ (optional)` → `(default at+jwt; set to empty to disable)`.

**Kiểm tác dụng phụ:** `JwtPasswordAuthenticationProvider.verifier(conf)` dùng cùng class → đường Thrift binary cũng bắt đầu yêu cầu `at+jwt`. Đây là mong muốn. Ghi rõ vào báo cáo và docs.

**Docs:** `docs/security/oidc.md` mục `### All JWT Config Keys`: sửa mô tả `expected.typ`.

#### Test

`extensions/server/kyuubi-oidc-auth/src/test/.../JwtTokenAuthenticationProviderSuite.scala` — suite đã có helper `signedToken(typ = ...)`. Rà toàn bộ test hiện tại: những test tạo token **không có** `typ` và kỳ vọng pass sẽ **fail** sau thay đổi. Sửa theo nguyên tắc:

- Test về hành vi mặc định → token phải có `typ = "at+jwt"`.
- Thêm test mới: `"default typ rejects an id_token (typ=JWT) and a token without typ"`.
- Thêm test mới: `"empty expected.typ disables the check"` — `baseConf(Map(EXPECTED_TYP -> ""))`, token không typ → accepted.
- Test `"configured expected typ rejects a mismatching (ID) token..."` hiện có giữ nguyên.

Làm tương tự với `JwtPasswordAuthenticationProviderSuite.scala` (cũng có helper `typ`).

#### Nghiệm thu A3

- [ ] Đã rà **từng** test trong 2 suite: test nào tạo token không có `typ` mà kỳ vọng accepted thì đã thêm `typ = "at+jwt"` (liệt kê tên test đã sửa vào báo cáo).
- [ ] 2 test mới đã viết.
- [ ] `grep -n "typ" docs/security/oidc.md` cho thấy mặc định mới.

---

### A4 — Kiểm `azp` (authorized party) tuỳ chọn 🟠

#### Vấn đề

Nếu Kyuubi phải chấp nhận `aud` của client SSA (vì chưa tách audience riêng — xem mục 3, B2), thì **mọi dịch vụ nào nhận được token của SSA** đều replay được vào Kyuubi (confused deputy). `azp` cho biết token được cấp cho client nào; kiểm nó là lớp bù cho tới khi tách audience.

#### Sửa

**File:** `extensions/server/kyuubi-oidc-auth/src/main/scala/org/apache/kyuubi/auth/oidc/JwtTokenAuthenticationProvider.scala`

- Thêm key: `val ALLOWED_AZP: String = PREFIX + "allowed.azp"` (comma-separated, optional, rỗng = không kiểm).
- Đọc: `private val allowedAzp: Set[String] = conf.getOption(ALLOWED_AZP).map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty)`.
- Trong `authenticate`, **sau** `jwtProcessor.process` và trước kiểm username:
  ```scala
  if (allowedAzp.nonEmpty) {
    val azp = claims.getStringClaim("azp")
    if (azp == null || !allowedAzp.contains(azp)) {
      throw new AuthenticationException(
        s"JWT 'azp' '${Option(azp).getOrElse("<missing>")}' is not an allowed authorized party")
    }
  }
  ```
- Thêm vào log `info("Initialized JWT authentication: ...")`: `allowedAzp=${if (allowedAzp.isEmpty) "<any>" else allowedAzp.mkString(",")}`.
- Scaladoc: thêm dòng mô tả key.

**Không** làm trong `MultiIssuerJWTClaimsVerifier` (giữ verifier chỉ lo iss/aud).

**Docs:** thêm key vào `docs/security/oidc.md` mục `All JWT Config Keys`, kèm 1 câu giải thích khi nào dùng.

#### Test

`JwtTokenAuthenticationProviderSuite`: helper `signedToken` cần nhận thêm tham số `azp: String = null`. Thêm:
- `"allowed.azp accepts a listed azp and rejects others / missing"`.
- `"allowed.azp unset accepts any azp"`.

#### Nghiệm thu A4

- [ ] 2 test mới đã viết. Không set key → không có nhánh code nào mới được chạy (đọc lại `authenticate` để xác nhận).

---

### A5 — Sửa `authenticationSupported` viết ngược ⚪

**File:** `kyuubi-server/src/main/scala/org/apache/kyuubi/server/http/authentication/BearerAuthenticationHandler.scala`

```scala
// Trước: chiều ngược và kết quả bị vứt → luôn true khi class tồn tại
Class.forName(providerClass).isAssignableFrom(classOf[TokenAuthenticationProvider])
true

// Sau
classOf[TokenAuthenticationProvider].isAssignableFrom(Class.forName(providerClass))
```

Giữ `try/catch` trả `false` khi class không tồn tại. Hệ quả: khai nhầm class không implement `TokenAuthenticationProvider` → `addAuthHandler` bỏ qua handler và log `warn` ngay lúc khởi động, thay vì lỗi lúc chạy.

Kiểm `BasicAuthenticationHandler.authenticationSupported` có cùng lỗi không; nếu có, sửa tương tự và ghi vào báo cáo.

#### Nghiệm thu A5

- [ ] Thêm 2 test vào suite handler hiện có (hoặc `AuthenticationFilterSuite`): class hợp lệ → `true`; class tồn tại nhưng không implement interface (ví dụ `java.lang.String`) → `false`.

---

### A6 — Vệ sinh client JDBC ⚪

**File:** `kyuubi-hive-jdbc/src/main/java/org/apache/kyuubi/jdbc/hive/auth/oidc/HttpJsonClient.java`
- Trong constructor, nếu `insecureTls == true`: `LOG.warn("OIDC insecure TLS is enabled: certificate and hostname verification are DISABLED for the token endpoint. Never use this outside development.")`. Log một lần mỗi instance là đủ.

**File:** `kyuubi-hive-jdbc/src/main/java/org/apache/kyuubi/jdbc/hive/auth/oidc/LoopbackCallbackServer.java` (~dòng 105)
- `!expectedState.equals(params.get("state"))` → so sánh constant-time:
  ```java
  String got = params.get("state");
  boolean stateOk = got != null && MessageDigest.isEqual(
      expectedState.getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8));
  ```
  Thêm import `java.security.MessageDigest`, `java.nio.charset.StandardCharsets`.

#### Nghiệm thu A6

- [ ] Diff chỉ chạm 2 file, không đổi logic ngoài 2 chỗ trên.

---

## 2. Rà soát cuối cùng (agent làm trước khi báo xong — không build)

```bash
git status --short                      # chỉ liệt kê, không add/commit
git diff --stat
grep -rn "authentication\.oidc\.\|OidcBff\|BearerSessionRequest\|UNAUTHENTICATED_PATHS\|KYUUBI_SESSION" \
  --include=*.scala --include=*.java --include=*.xml kyuubi-server kyuubi-common extensions | grep -v /target/
```

Rà bằng mắt từng file trong `git diff`:
- Import mới có đầy đủ và không thừa (đặc biệt sau A2: `ApiRootResource`, `AuthenticationFilter`).
- Mỗi method/val bị xoá không còn chỗ nào tham chiếu (`grep` tên nó trong toàn repo, trừ `/target/`).
- Code mới khớp style file xung quanh; file mới có license header ASF.
- Test mới dùng đúng base class (`KyuubiFunSuite`) và helper sẵn có trong suite đó.

Người duyệt sẽ chạy build + toàn bộ test sau khi nhận bàn giao. Agent **không** chạy.

Báo cáo cuối phải có:
1. Danh sách file đã sửa/xoá, nhóm theo task.
2. Danh sách test mới theo từng task, và những test cũ phải sửa ở A3 kèm lý do.
3. Kết quả 3 lệnh rà soát ở trên.
4. Những chỗ agent **không chắc** về compile/behavior (ví dụ: helper test có tồn tại không, import có đúng package không) — liệt kê rõ để người duyệt nhìn trước khi build.
5. Mọi thứ agent **không** làm được và tại sao.
6. Mục 5 bên dưới nếu có phát hiện thêm.

---

## 3. Việc của người vận hành (ngoài code — agent KHÔNG làm, chỉ nhắc lại trong báo cáo)

| # | Việc | Vì sao |
|---|---|---|
| B1 | Gỡ `kyuubi.authentication.oidc.ui.client.id` / `.secret` / `.scope` và mọi `kyuubi.authentication.oidc.*` khỏi CR/ConfigMap deploy | Sau A2 chúng vô nghĩa; gỡ để config không gây hiểu nhầm. Trước A2 được deploy, kiểm `curl -sk https://<kyuubi>:10099/api/v1/authentication/config \| jq .flow` — nếu `"bff"` thì các lỗ hổng BFF đang mở. |
| B2 | Tách audience riêng: tạo client `kyuubi` trong Keycloak; SSA xin token có `aud` chứa `kyuubi` (audience mapper hoặc token exchange); Kyuubi đặt `kyuubi.authentication.jwt.audience=kyuubi` | Chặn token-của-SSA bị replay vào Kyuubi từ dịch vụ khác. Chưa làm được thì dùng A4: `kyuubi.authentication.jwt.allowed.azp=<client-id-SSA>`. |
| B3 | Pin `kyuubi.authentication.jwt.jwks.url=https://<keycloak>/realms/<realm>/protocol/openid-connect/certs` | Bỏ hẳn discovery; cùng A1 cho phép xác thực offline trong thời gian cache JWKS. |
| B4 | Kiểm `kyuubi.server.administrators` **không** chứa service account của App. App gọi bằng token riêng từng người dùng, **không** truyền `kyuubi.session.proxy.user` / `hive.server2.proxy.user` | Admin bỏ qua hoàn toàn `verifyProxyAccess` (`KyuubiRestFrontendService.getProxyUser`) → mạo danh vô hạn, Ranger chấm quyền theo tên giả. User OS chạy Kyuubi cũng tự động là admin. |
| B6 | Mọi client secret (nếu còn) → k8s `Secret`, không để trong CR/ConfigMap | Bộ RBAC đã có `secrets: [get, create, patch, delete]`. |
| B7 | Khẳng định trong tài liệu kiến trúc: Ranger plugin trong Spark engine là lớp cưỡng chế **duy nhất**; App → Ranger chỉ để vẽ UI | Tránh ai đó cho App gọi Kyuubi bằng danh tính đặc quyền dựa trên kết quả tự hỏi Ranger. |
| B8 | Sửa trạng thái `docs/plans/thrift-binary-oidc.md` ("chưa thực hiện" → đã có trong code) | Tài liệu lạc hậu. |

Đã **loại khỏi plan** theo quyết định của người duyệt: bật TLS trên cổng Thrift binary (B5 cũ).

---

## 4. Những gì KHÔNG nằm trong plan

- Không đụng `JwtPasswordAuthenticationProvider` / `DenyPasswordAuthenticationProvider` ngoài tác dụng gián tiếp của A3.
- Không đụng `KyuubiOpenApiResource`, `AdminResource`, `BatchesResource`, `SessionsResource`.
- Không thêm rate-limit HTTP (sau A2 không còn endpoint miễn xác thực nào tạo state).
- Không sửa `requestOrigin`/CSRF (bị xoá theo A2).
- Không sửa `getHttpBasicAuthenticationProvider` (LDAP/JDBC là hành vi upstream).
- Không đổi giao thức JDBC client (OIDC vẫn chỉ trên HTTP transport).

---

## 5. Phát hiện thêm trong lúc thực hiện

- `kyuubi-server/src/main/scala/org/apache/kyuubi/server/http/authentication/BearerAuthenticationHandler.scala:34`: `private val allowAnonymous = classOf[AnonymousAuthenticationProviderImpl].getName == providerClass`. Tuy nhiên `AnonymousAuthenticationProviderImpl` chỉ implement `PasswdAuthenticationProvider` chứ không implement `TokenAuthenticationProvider`. Do đó nếu cấu hình `AnonymousAuthenticationProviderImpl` làm bearer provider thì `authenticationSupported` sẽ trả về `false` (vì không phải `TokenAuthenticationProvider`). Không ảnh hưởng tới luồng Bearer token/JWT thông thường.
