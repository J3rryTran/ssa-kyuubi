# Plan: OIDC trên Thrift binary (chạy song song với Thrift HTTP và REST)

Trạng thái: **chưa thực hiện**. Đối tượng thực thi: AI agent. Người duyệt: j3rrytran.

Thay thế `docs/plans/thrift-binary-oidc.md` (plan cũ, đã lỗi thời: phần "port `AuthMethods.OIDC`" và
`JwtPasswordAuthenticationProvider` trong đó **đã có sẵn** trong code).

Phụ thuộc: làm **sau** `docs/plans/oidc/fix-oidc-security.md` (A1–A6 đã xong ở commit `d589eef`).

Ngoài phạm vi: luồng ETL/dbt "route A" ở `docs/plans/test.md` (người duyệt hoãn lại, **không** làm).

---

## 0. Bối cảnh — đọc trước khi sửa gì

### 0.1 Ba cổng, một loại chìa

Kyuubi bật cả 3 frontend, cùng kiểm **access token Keycloak** bằng `JwtTokenAuthenticationProvider`:

| Cổng | Token đi ở đâu | Code phía server |
|---|---|---|
| REST `/api/v1` | `Authorization: Bearer <jwt>` | `BearerAuthenticationHandler` → `JwtTokenAuthenticationProvider` |
| Thrift HTTP `/cliservice` | `Authorization: Bearer <jwt>` (hoặc Basic với password = jwt) | như trên / `BasicAuthenticationHandler` → `JwtPasswordAuthenticationProvider` |
| **Thrift binary `:10009`** | ô **password** của SASL PLAIN | `PlainSASLServer` → `PlainSASLHelper.PlainServerCallbackHandler` → `JwtPasswordAuthenticationProvider` |

### 0.2 Đường binary hiện tại (đã đọc code, đúng tới commit `d589eef`)

```
client ──SASL PLAIN: [authzid] \0 user \0 password(=jwt)──▶ PlainSASLServer.evaluateResponse
   PlainSASLServer:   user = authcid client gửi; tạo AuthorizeCallback(user, authzid)
   └─ PlainServerCallbackHandler.handle
        provider = AuthenticationProviderFactory.getAuthenticationProvider(OIDC, conf)
                   → mặc định JwtPasswordAuthenticationProvider (khi jwt.password.enabled != false)
        effectiveUser = provider.authenticateAndResolve(user, password)   ← = claim username trong token
        ac.setAuthorized(true); ac.setAuthorizedID(effectiveUser)
   PlainSASLServer.getAuthorizationID = user          ← !!! trả authcid client gửi, KHÔNG phải effectiveUser
TSetIpAddressProcessor: userName = saslServer.getAuthorizationID
TFrontendService.getRealUserAndSessionUser: realUser = getShortName(authFactory.getRemoteUser)
```

Đã có và đúng:
- `AuthMethods` có `OIDC`; `getAuthenticationProviderForServer` có `case AuthMethods.OIDC`.
- Không cần `kyuubi.authentication.custom.class`: khi `kyuubi.authentication=OIDC`, binary mặc định
  dùng `JwtPasswordAuthenticationProvider`.
- Verifier JWT là singleton (`JwtPasswordAuthenticationProvider.verifier`): không gọi Keycloak mỗi lần
  bắt tay SASL.
- Token phải khớp user: client gửi `user` khác claim → từ chối. `user` nằm trong
  `kyuubi.authentication.jwt.password.user.aliases` (mặc định `token`) → chấp nhận, user thật lấy
  từ token.

### 0.3 Lỗ hổng/thiếu sót cần sửa

| # | Mức | Vấn đề |
|---|---|---|
| T1 | 🔴 | **Session user sai khi dùng alias.** `PlainSASLServer.getAuthorizationID` trả `user` client gửi, bỏ qua `effectiveUser` handler đã đặt. Client kết nối `user=token`, password = token của `alice` → xác thực **qua**, nhưng session Kyuubi/Spark chạy dưới user **`token`**, không phải `alice`. Hậu quả: Ranger phân quyền và audit theo `token`; mọi người dùng alias đều thành **cùng một user**. Đường HTTP Basic không bị (đã dùng `effectiveUser`). Unit test của provider (`1b`) không bắt được vì không đi qua `PlainSASLServer`. |
| T2 | 🟠 | **Driver JDBC của fork không dùng được OIDC trên binary.** `KyuubiConnection.createBinaryTransport` ném `"OIDC authentication requires HTTP transport"` khi `auth=oidc`. Với `auth=jwt` (token tĩnh) thì tệ hơn: rơi xuống nhánh PLAIN và **âm thầm gửi password `anonymous`** thay vì token. DBeaver/SSA muốn dùng binary phải tự đặt token vào ô password bằng tay. |
| T3 | ⚪ | Tài liệu `docs/security/oidc.md` không có mục Thrift binary; URL JDBC mẫu chỉ có `transportMode=http`. |

### 0.4 Ràng buộc bắt buộc với agent

1. **Không chạy lệnh git làm thay đổi trạng thái** (`add`, `commit`, `stash`, `checkout -- <file>`, `reset`, …). Chỉ `git status`, `git diff`, `git log`.
2. **Không chạy build/test/format** (`mvn`, `./build/mvn`, `./dev/reformat`, spotless). Người duyệt tự build.
3. **Chỉ ký tự ASCII trong file `.scala`** — scalastyle (`nonascii.message`) sẽ fail build. Comment trong code viết **tiếng Anh**. Tiếng Việt chỉ dùng trong file `.md`.
4. Style: indent 2, dòng ≤ 100 ký tự (kể cả scaladoc), import gom nhóm `java` → `scala` → bên thứ ba → `org.apache.kyuubi`; Java theo google-java-format như file bên cạnh. File mới có license header ASF.
5. Không thêm `ConfigEntry` vào `KyuubiConf`; key OIDC đọc raw qua `conf.getOption` (xem `AGENTS.md`).
6. Làm theo thứ tự **T1 → T2 → T3**. Không sửa ngoài phạm vi; phát hiện thêm ghi vào mục 4.

---

## 1. Task

### T1 — Session user trên binary phải là user đã xác thực từ token 🔴

#### Sửa

**File:** `kyuubi-common/src/main/scala/org/apache/kyuubi/service/authentication/PlainSASLServer.scala`
(trong `evaluateResponse`)

```scala
      val acCallback: AuthorizeCallback = new AuthorizeCallback(user, authzId)
      // Default the authorized identity to the authenticated name (never the client-supplied
      // authzid). The callback handler may override it with the identity it resolved, e.g. the
      // username claim of an OIDC access token sent as the password.
      acCallback.setAuthorizedID(user)
      val cbList: Array[Callback] = Array(nameCallback, pcCallback, acCallback)
      handler.handle(cbList)
      if (!acCallback.isAuthorized) {
        throw new SaslException("Authentication failed")
      }
      user = Option(acCallback.getAuthorizedID).filter(_.nonEmpty).getOrElse(user)
```

Vì sao phải `setAuthorizedID(user)` **trước** `handle`: `AuthorizeCallback.getAuthorizedID()` của JDK
trả `authorizationID` (= `authzid` **do client gửi**) khi chưa ai gọi `setAuthorizedID`. Nếu bỏ dòng
này, một handler không đặt authorizedID (LDAP/JDBC/CUSTOM của bên thứ ba) sẽ cho client tự chọn user
qua `authzid` → leo thang quyền. Đặt sẵn = `user` giữ nguyên hành vi cũ cho mọi handler khác.

**File:** `kyuubi-common/src/main/scala/org/apache/kyuubi/service/authentication/PlainSASLHelper.scala`

- Giữ logic `handle` như hiện tại (đã `setAuthorizedID(effectiveUser)` khi khác rỗng).
- Đổi `private class PlainServerCallbackHandler private (...)` thành
  `private[authentication] class PlainServerCallbackHandler private[authentication] (...)` để test gọi
  được. Không đổi gì khác.

Không đụng tới phía engine (`isServer=false`): provider engine dùng `authenticateAndResolve` mặc
định, trả nguyên `user` → hành vi không đổi.

Ghi chú: đây là thay đổi trong `kyuubi-common` (core). Fork đã sửa core ở đúng chỗ này
(`authenticateAndResolve`), nên chấp nhận; ghi vào báo cáo.

#### Test

**File:** `kyuubi-common/src/test/scala/org/apache/kyuubi/service/authentication/PlainSASLServerSuite.scala`
— thêm 3 test (dùng `SaslPlainServerFactory` như test sẵn có, message SASL dựng bằng
`s"$authzid\u0000$user\u0000$pw".getBytes(UTF_8)` — chú ý `\u0000` là escape ASCII, hợp lệ):

| Test | Handler | Message | Kỳ vọng `getAuthorizationID` |
|---|---|---|---|
| handler resolves identity | `setAuthorized(true)` + `setAuthorizedID("alice")` | `"" / token / pw` | `"alice"` |
| client authzid is ignored | chỉ `setAuthorized(true)` | `"admin" / bob / pw` | `"bob"` (không phải `admin`) |
| empty resolved id falls back | `setAuthorized(true)` + `setAuthorizedID("")` | `"" / bob / pw` | `"bob"` |

Test sẵn có `assert(server.getAuthorizationID === "a")` phải vẫn đúng — đọc lại để xác nhận.

**File:** `kyuubi-common/src/test/scala/org/apache/kyuubi/service/authentication/PlainSASLHelperSuite.scala`
— thêm 1 test đi trọn đường handler thật:

- Provider test mới (đặt trong cùng file test hoặc file test riêng, constructor `(conf: KyuubiConf)`)
  implement `PasswdAuthenticationProvider`, `authenticateAndResolve(user, pw)` trả `"alice"` khi
  `pw == "good"`, ném `AuthenticationException` khi khác.
- `conf`: `kyuubi.authentication=CUSTOM`, `kyuubi.authentication.custom.class=<provider test>`.
- Dựng `new PlainSASLHelper.PlainServerCallbackHandler("CUSTOM", conf, true)`, tạo server bằng
  `new SaslPlainServerFactory().createSaslServer("PLAIN", "CUSTOM", "KYUUBI", emptyMap, handler)`.
- `evaluateResponse("\u0000token\u0000good")` → `getAuthorizationID === "alice"`.
- `evaluateResponse("\u0000token\u0000bad")` trên server mới → `SaslException`.

#### Nghiệm thu T1

- [ ] Diff `PlainSASLServer.scala` chỉ gồm 2 dòng code + comment như trên.
- [ ] Diff `PlainSASLHelper.scala` chỉ đổi visibility.
- [ ] 4 test mới đã viết; test cũ không sửa.
- [ ] Đọc lại `TSetIpAddressProcessor` + `TFrontendService.getRealUserAndSessionUser`: xác nhận session user giờ lấy từ authorizedID — ghi 1 dòng vào báo cáo.

---

### T2 — Driver JDBC: `auth=oidc` và `auth=jwt` chạy được trên binary 🟠

#### Sửa

**File:** `kyuubi-hive-jdbc/src/main/java/org/apache/kyuubi/jdbc/hive/KyuubiConnection.java`,
hàm `createBinaryTransport` (khoảng dòng 736–760).

Thay khối:

```java
      // OIDC bearer tokens are only carried over the HTTP transport (Authorization header).
      if (isOidcAuthMode()) {
        throw new KyuubiSQLException(...);
      }
```

bằng:

```java
      // OIDC / JWT over binary: the access token travels as the SASL PLAIN password and the
      // server resolves the session user from the token's username claim.
      if (isOidcAuthMode() || isJwtAuthMode()) {
        String token = acquireBinaryToken();
        return PlainSaslHelper.getPlainTransport(getBinaryTokenUser(), token, socketTransport);
      }
```

Thêm 2 hàm private:

```java
  /** Access token sent as the SASL PLAIN password; checked by the server only at open. */
  private String acquireBinaryToken() throws KyuubiSQLException {
    if (isOidcAuthMode()) {
      try {
        return getOrCreateOidcAuthenticator().acquireInitialToken();
      } catch (RuntimeException e) {
        throw new KyuubiSQLException("OIDC authentication failed: " + e.getMessage(), "08S01", e);
      }
    }
    String jwt = getJWT();
    if (jwt == null || jwt.isEmpty()) {
      throw new KyuubiSQLException("For jwt auth mode, a signed jwt must be provided", "08S01");
    }
    return jwt;
  }

  /**
   * SASL user for token auth: the explicit user if given, otherwise "token", a placeholder the
   * server accepts (kyuubi.authentication.jwt.password.user.aliases) and replaces with the
   * username claim of the verified token.
   */
  private String getBinaryTokenUser() {
    String user = sessConfMap.get(AUTH_USER);
    return user == null || user.isEmpty() ? "token" : user;
  }
```

Lưu ý cho agent:
- Kiểm `acquireInitialToken()` trả `String` token (đọc `OidcAuthenticator.java:59`). Nếu trả kiểu khác,
  dùng `currentAccessToken()` sau khi gọi `acquireInitialToken()`; ghi vào báo cáo.
- Đọc lại thứ tự gọi trong `createBinaryTransport`: nhánh mới phải nằm **sau** `if (!isSaslAuthMode())`
  và **trước** `if (isPlainSaslAuthMode())`. Kiểm `isPlainSaslAuthMode()` không loại trừ `auth=oidc`/
  `auth=jwt` theo cách làm nhánh mới không chạy tới.
- Không log token. Nếu cần log, chỉ log 7 ký tự cuối như `getJWTStringFromSession` đang làm.
- Không đổi đường HTTP (`createHttpTransport`).
- Không gọi `logout()` của authenticator ở nhánh binary; `close()` hiện có xử lý chung — đọc để xác nhận nó không giả định transport HTTP.

Token chỉ được kiểm **lúc mở kết nối**. Kết nối đã mở vẫn chạy sau khi token hết hạn. Mỗi kết nối
mới gọi `acquireInitialToken()`, và hàm này dùng token cache/refresh của `TokenStore`, nên không bắt
đăng nhập lại mỗi lần.

#### Test

**File:** `kyuubi-hive-jdbc/src/test/java/org/apache/kyuubi/jdbc/hive/auth/oidc/OidcClientTest.java`
hoặc file test mới cùng thư mục — nếu `KyuubiConnection` khó dựng không có server, tách
`getBinaryTokenUser` thành hàm `static` package-private nhận `Map<String, String>` và test:

| Input `sessConfMap` | Kỳ vọng |
|---|---|
| không có `user` | `"token"` |
| `user=""` | `"token"` |
| `user=alice` | `"alice"` |

Phần gửi token qua SASL do người duyệt kiểm thủ công (mục 2).

#### Nghiệm thu T2

- [ ] Không còn chuỗi `"OIDC authentication requires HTTP transport"` trong `kyuubi-hive-jdbc/src/main`.
- [ ] `grep -n "acquireBinaryToken\|getBinaryTokenUser" KyuubiConnection.java` ra định nghĩa + chỗ gọi.
- [ ] Test cho `getBinaryTokenUser` đã viết.
- [ ] Ghi vào báo cáo: `isPlainSaslAuthMode()` trả gì với `auth=oidc`, `auth=jwt` (đọc code).

---

### T3 — Tài liệu ⚪

**File:** `docs/security/oidc.md` — thêm mục **"Thrift binary"** sau mục cấu hình server:

1. Cấu hình (không cần `custom.class`):
   ```properties
   kyuubi.frontend.protocols                         THRIFT_BINARY,THRIFT_HTTP,REST
   kyuubi.authentication                             OIDC
   # mặc định true; đặt false sẽ chặn CẢ binary lẫn HTTP Basic, chỉ còn Bearer
   kyuubi.authentication.jwt.password.enabled        true
   kyuubi.authentication.jwt.password.user.aliases   token
   ```
2. URL JDBC:
   ```
   jdbc:kyuubi://<host>:10009/default;auth=oidc;oidcIssuer=<issuer>;oidcClientId=<client>
   jdbc:kyuubi://<host>:10009/default;auth=jwt;jwt=<access-token>
   ```
   PyHive / beeline: `username=token` (hoặc đúng `preferred_username`), `password=<access-token>`.
3. Quy tắc:
   - `user` phải bằng claim username trong token, hoặc là một alias (`token`). Alias **không bao giờ**
     được trùng tên user thật.
   - Token chỉ kiểm lúc mở kết nối; pool kết nối phải lấy token còn hạn khi tạo kết nối mới.
   - Token đi dạng rõ trong SASL PLAIN nếu không bật `kyuubi.frontend.thrift.binary.ssl.enabled`
     — coi password như mật khẩu, không log URL/password.
   - Đổi bất kỳ `kyuubi.authentication.jwt.*` nào phải restart Kyuubi.
4. Cập nhật bảng "Driver Parameters" nếu có dòng nói OIDC chỉ dùng HTTP.

**File:** `docs/plans/thrift-binary-oidc.md` — chỉ thêm 1 dòng ngay dưới tiêu đề:
`> Lỗi thời — xem docs/plans/oidc/thrift-binary-oidc.md.` Không sửa gì khác.

#### Nghiệm thu T3

- [ ] `grep -n "10009\|binary" docs/security/oidc.md` có mục mới.
- [ ] Không còn câu nào trong `docs/security/oidc.md` nói OIDC "chỉ" chạy trên HTTP.

---

## 2. Kiểm thủ công — người duyệt làm sau khi build (agent KHÔNG làm)

Trên cụm thử, với token thật của `alice` (`$TOKEN`):

| # | Lệnh | Kỳ vọng |
|---|---|---|
| M1 | `beeline -u "jdbc:kyuubi://<host>:10009/default" -n alice -p "$TOKEN" -e "select current_user()"` | `alice` |
| M2 | như M1 nhưng `-n token` | **`alice`** (trước T1 sẽ ra `token`) |
| M3 | `-n bob -p "$TOKEN"` | từ chối, "does not match" |
| M4 | `-n alice -p abc` | từ chối, "pass an OIDC access token" |
| M5 | `-p` là id_token | từ chối (typ ≠ `at+jwt`) |
| M6 | DBeaver, URL `auth=oidc` không có `transportMode=http` | mở trình duyệt đăng nhập, `current_user()` = user đăng nhập |
| M7 | Ranger audit của M2 | ghi user `alice`, không phải `token` |
| M8 | Hồi quy: REST Bearer, Thrift HTTP Bearer, HTTP Basic (password = token) | không đổi |

---

## 3. Việc của người vận hành (ngoài code)

- Bật `THRIFT_BINARY` trong frontend protocols (operator/Helm `values-operator.yaml`).
- **Rà Ranger:** trước khi T1 lên production, kiểm có policy nào cấp cho user tên `token` (hoặc alias
  khác) không; nếu có, gỡ. Kiểm audit cũ xem đã có truy vấn chạy dưới user `token` chưa.
- **Mã hoá đường truyền (khuyến nghị, không bắt buộc):** người duyệt đã bỏ TLS cho binary (B5 cũ).
  Nhắc lại rủi ro: nếu DBeaver của người dùng nối binary **từ ngoài cụm** (IP/DNS), access token đi
  dạng rõ qua mạng. Bật `kyuubi.frontend.thrift.binary.ssl.enabled=true` + keystore, hoặc chỉ mở
  10009 trong cụm và cho người dùng ngoài cụm đi Thrift HTTPS.
- NetworkPolicy giới hạn nguồn được vào 10009 (tuỳ chọn).

---

## 4. Phát hiện thêm trong lúc thực hiện

_(agent ghi vào đây, kèm file:dòng, không tự sửa)_

---

## 5. Báo cáo cuối của agent

1. `git status --short` và `git diff --stat`.
2. Từng task: đã làm gì, file:dòng, test đã viết (tên test).
3. Các mục "ghi vào báo cáo" trong từng phần nghiệm thu.
4. Những chỗ agent không chắc về compile/hành vi, để người duyệt xem trước khi build.
5. Xác nhận: không có ký tự non-ASCII trong file `.scala` đã sửa
   (`grep -nP '[^\x00-\x7F]' <file>` trả rỗng).
