# Plan: cấp "chìa kết nối" Thrift binary và REST cho các đội dev, khi `kyuubi.authentication=OIDC`

Trạng thái: chưa thực hiện. Đối tượng thực thi: AI agent. Phạm vi: `kyuubi-common` (4 file, port từ
`kyuubi-custom`), `extensions/server/kyuubi-oidc-auth` (1 class mới + test), cấu hình Kyuubi, Keycloak,
tài liệu tích hợp cho các đội dev.

**Mục tiêu**: Kyuubi mở hai cổng giao tiếp cho phần mềm của các đội khác, cùng một loại "chìa"
(access token Keycloak):

| Cổng | Dùng khi | Chìa đi ở đâu |
|---|---|---|
| **Thrift binary** `:10009` | ứng dụng dùng JDBC/ODBC/PyHive, công cụ SQL, chạy truy vấn dài, kết quả lớn | ô `password` của SASL PLAIN, `user` = username trong token |
| **REST** `/api/v1` | ứng dụng web/microservice, quản lý session/batch, không muốn phụ thuộc driver | header `Authorization: Bearer <token>` |

Kyuubi không cấp và không lưu chìa; Keycloak cấp, Kyuubi chỉ kiểm.

**Không nằm trong plan**: gỡ REST, gỡ Web UI/BFF, đổi backend notebook sang Thrift, sửa Helm chart
(deploy sẽ chuyển sang Kyuubi operator; cấu hình ở mục 3 là key của Kyuubi, đặt ở đâu cũng được).
Cấu hình xác thực **giữ nguyên `kyuubi.authentication=OIDC`** như đang chạy.

---

## 0. Vấn đề và cách giải

### 0.1 Vì sao Thrift binary chết khi bật OIDC

Thrift binary xác thực bằng **SASL PLAIN** (chỉ `username` + `password`). Luồng server trên `ssa-kyuubi`:

```
PlainSASLHelper.PlainServerCallbackHandler(authMethodStr = "OIDC")
  └─ AuthMethods.withName("OIDC")            ← AuthMethods = {NONE, LDAP, JDBC, CUSTOM}: ném NoSuchElementException
       (nếu qua được) getAuthenticationProviderForServer(method)
          case _ => throw AuthenticationException("Not a valid authentication method")
```

`AuthMethods` của `ssa-kyuubi` không có `OIDC`, nên binary chết ngay ở `withName`. Cùng nguyên nhân,
`BasicAuthenticationHandler.scala:83` của REST cũng gọi `AuthMethods.withName("OIDC")`: trên `ssa-kyuubi`
một request REST gửi `Basic` sẽ nổ 500 thay vì 401 (hiện chưa lộ vì cụm đang chạy image build từ
`kyuubi-custom`, nơi đã có `OIDC` trong `AuthMethods`).

### 0.2 Cách giải

Hai phần, cả hai đều **không đổi** `kyuubi.authentication=OIDC`:

1. **Port 4 file từ `kyuubi-custom` sang `ssa-kyuubi`** (mục 1.1): thêm `OIDC` vào `AuthMethods` và hai
   `case AuthMethods.OIDC` trong `AuthenticationProviderFactory`. Với đường SASL PLAIN, `case` này tạo
   provider từ `kyuubi.authentication.custom.class`, mặc định `DenyPasswordAuthenticationProvider`.
2. **Một `PasswdAuthenticationProvider` mới** (mục 1.2) nhận **JWT trong ô `password`**, kiểm như REST
   Bearer, rồi bắt buộc claim username trùng `username` của SASL. Trỏ `custom.class` vào nó.

Lưu ý nguyên tắc: `AGENTS.md` ghi "Kyuubi core is deliberately untouched" cho phần OIDC. Phần 1 **có**
chạm core (`kyuubi-common`, 2 file nguồn + 2 file test, 10 dòng). Đây là cùng thay đổi `kyuubi-custom`
đang chạy production, nên chấp nhận và sửa lại câu đó trong `AGENTS.md` (mục 8, bước 7).

---

## 1. Code

### 1.1 Port từ `kyuubi-custom` (copy nguyên, không viết lại)

| File (đường dẫn giống nhau ở hai repo) | Thay đổi |
|---|---|
| `kyuubi-common/src/main/scala/org/apache/kyuubi/service/authentication/AuthMethods.scala` | `val NONE, LDAP, JDBC, CUSTOM, OIDC = Value` |
| `kyuubi-common/src/main/scala/org/apache/kyuubi/service/authentication/AuthenticationProviderFactory.scala` | thêm `case AuthMethods.OIDC` trong `getAuthenticationProviderForServer` (dùng `AUTHENTICATION_CUSTOM_CLASS`, mặc định Deny) và trong `getHttpBasicAuthenticationProvider` (dùng `AUTHENTICATION_CUSTOM_BASIC_CLASS`, mặc định Deny) |
| `kyuubi-common/src/test/scala/org/apache/kyuubi/service/authentication/KyuubiAuthenticationFactorySuite.scala` | 2 dòng |
| `kyuubi-common/src/test/scala/org/apache/kyuubi/config/KyuubiConfSuite.scala` | 1 dòng |

Kiểm sau khi port: `diff` 4 file giữa hai repo phải rỗng; `build/mvn -pl kyuubi-common test
-DwildcardSuites=org.apache.kyuubi.service.authentication.KyuubiAuthenticationFactorySuite` xanh.

### 1.2 `JwtPasswordAuthenticationProvider` (file mới trong extension)

`extensions/server/kyuubi-oidc-auth/src/main/scala/org/apache/kyuubi/auth/oidc/JwtPasswordAuthenticationProvider.scala`

```scala
class JwtPasswordAuthenticationProvider(conf: KyuubiConf) extends PasswdAuthenticationProvider {
  def this() = this(null)
  override def authenticate(user: String, password: String): Unit = {
    // 1. password rỗng hoặc không đủ 3 phần '.' -> AuthenticationException(
    //      "Password authentication is disabled; pass an OIDC access token as the password")
    // 2. val p = JwtPasswordAuthenticationProvider.verifier(conf).authenticate(TokenCredential(password))
    //      (tái dùng JwtTokenAuthenticationProvider: chữ ký, iss, aud, exp, nbf, iat, typ, alg)
    // 3. if (p.getName != user) throw AuthenticationException(
    //      s"Token subject '${p.getName}' does not match SASL user '$user'")
    // 4. Không log password/token ở bất kỳ mức nào. conf == null -> IllegalStateException, không NPE.
  }
}
object JwtPasswordAuthenticationProvider {
  // Singleton theo tiến trình, lazy, đồng bộ. BẮT BUỘC vì PlainServerCallbackHandler tạo provider
  // MỚI cho MỖI handshake SASL (PlainSASLHelper.scala:66-67), còn JwtTokenAuthenticationProvider
  // gọi HTTP discovery + tải JWKS trong constructor. Không cache = 2 HTTP tới Keycloak mỗi kết nối.
  def verifier(conf: KyuubiConf): JwtTokenAuthenticationProvider
}
```

- **Bước 3 không được bỏ.** Thiếu nó, user A gửi token thật của A kèm `username=B` là thành B.
- Đọc cấu hình bằng `conf.getOption("kyuubi.authentication.jwt.*")` như hai provider hiện có; không
  thêm entry vào `KyuubiConf`.

Test mới `JwtPasswordAuthenticationProviderSuite` (dựng JWKS giả như `JwtTokenAuthenticationProviderSuite`):

| # | Trường hợp | Kỳ vọng |
|---|---|---|
| 1 | token hợp lệ, `user` = claim | pass |
| 2 | token hợp lệ, `user` ≠ claim | reject, thông điệp "does not match" |
| 3 | password là mật khẩu thường | reject, thông điệp "pass an OIDC access token" |
| 4 | hết hạn / sai iss / sai aud / sai chữ ký / alg=none | reject (tái dùng case của suite cũ) |
| 5 | `authenticate` 1.000 lần | discovery + JWKS chỉ tải **một** lần (đếm request trên server giả) |

---

## 2. Frontend Thrift binary và đường truyền

- Kyuubi phải có `THRIFT_BINARY` trong `kyuubi.frontend.protocols` (port mặc định
  `kyuubi.frontend.thrift.binary.bind.port=10009`). Chart hiện tại tự ghép key này từ các frontend bật;
  operator sau này sẽ có cách riêng. **Plan không làm việc chart/operator**, chỉ ghi điều kiện.
- Mọi ứng dụng của các đội chạy trong cùng cụm Kubernetes với Kyuubi và Keycloak: **không bật SSL**
  cho Thrift binary. Token đi dạng rõ trong SASL PLAIN nhưng không rời mạng cụm. NetworkPolicy giới hạn
  port 10009/10099 theo namespace của các đội là tùy chọn, không chặn plan.

---

## 3. Cấu hình Kyuubi (chỉ thêm một dòng)

```properties
kyuubi.authentication=OIDC                                   # GIỮ NGUYÊN
kyuubi.authentication.custom.class=org.apache.kyuubi.auth.oidc.JwtPasswordAuthenticationProvider   # MỚI

# Không đổi: kyuubi.authentication.jwt.* (issuer, audience, username.claim, jwks.url ...),
# provider mới đọc cùng bộ key. Không cần custom.basic.class / custom.bearer.class:
# AuthenticationFilter đã mặc định Deny cho Basic và JwtTokenAuthenticationProvider cho Bearer khi OIDC.
```

Hệ quả theo đường vào:

| Đường | Trước | Sau |
|---|---|---|
| Thrift binary, SASL PLAIN | chết ở `AuthMethods.withName` | `custom.class` → JWT trong password |
| REST / Thrift HTTP, Bearer | `JwtTokenAuthenticationProvider` | không đổi |
| REST / Thrift HTTP, Basic | 500 (`withName` nổ) trên `ssa-kyuubi` | 401 Deny, đúng như `kyuubi-custom` |
| Web UI BFF | bật vì `authTypes` có OIDC | không đổi |

---

## 4. Keycloak: cách cấp chìa cho một đội

Mỗi ứng dụng của mỗi đội một client Keycloak riêng (realm `lakehouse`), để thu hồi và truy vết theo
từng ứng dụng.

### 4.1 Ứng dụng chạy nền (service, job, API backend): client credentials

| Việc | Giá trị |
|---|---|
| Client | `<team>-<app>`, **confidential**, bật *Service accounts*, tắt standard flow |
| Audience mapper | thêm `kyuubi` vào `aud` (Kyuubi kiểm `aud`) |
| Username trong token | **Ranger giữ nguyên, không thêm user hay policy.** Thêm mapper *hardcoded claim* `preferred_username=<user đã có policy trong Ranger>` cho riêng client này. Không dùng tên mặc định `service-account-<client>` vì Ranger không biết tên đó |
| Access token lifespan | 15–60 phút, đặt ở client (không đổi mặc định realm) |
| `iss` | phải bằng `kyuubi.authentication.jwt.issuer` của Kyuubi (hostname public), kể cả khi ứng dụng gọi Keycloak qua service nội bộ; kiểm bằng decode token |
| Giao cho đội | `client_id`, `client_secret` (qua Kubernetes Secret của namespace đội), địa chỉ token endpoint, địa chỉ Thrift/REST |

### 4.2 Ứng dụng có người dùng đăng nhập (truy vấn bằng quyền của chính người đó)

| Việc | Giá trị |
|---|---|
| Client | `<team>-<app>-ui`, **public**, Authorization Code + **PKCE S256 bắt buộc**, redirect URI là callback **của ứng dụng đó** |
| Audience mapper | thêm `kyuubi` vào `aud` |
| Username | dùng nguyên `preferred_username` của người đăng nhập (đã là user của Ranger) |

Ứng dụng tự đăng nhập Keycloak, giữ token **trong bộ nhớ**, rồi dùng token đó cho Thrift hoặc REST.
Ứng dụng web cùng origin với REST của Kyuubi có thể dùng BFF `/api/v1/authentication/*` thay vì tự làm
PKCE, nhưng khi đó chỉ gọi được REST (BFF không trao token cho client, nên không dùng được Thrift).

---

## 5. Tài liệu tích hợp cho các đội dev

Viết `docs/client/oidc-connect.md` (tiếng Việt, ngắn, chạy được nguyên văn). Nội dung bắt buộc:

### 5.1 Lấy chìa (client credentials)

```bash
TOKEN=$(curl -sf -X POST "$KEYCLOAK/realms/lakehouse/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id="$CLIENT_ID" -d client_secret="$CLIENT_SECRET" \
  | jq -r .access_token)
USER=$(echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq -r .preferred_username)
```

### 5.2 Thrift binary

| Ngôn ngữ | Cách nối |
|---|---|
| Java (JDBC, driver `kyuubi-hive-jdbc`) | `DriverManager.getConnection("jdbc:kyuubi://<host>:10009/default", user, token)` |
| Python (PyHive) | `hive.connect(host=..., port=10009, username=user, password=token, auth="CUSTOM")` |
| CLI | `beeline -u "jdbc:kyuubi://<host>:10009/default" -n "$USER" -p "$TOKEN"` |

Quy tắc phải ghi rõ cho đội dev:

1. `user` **phải bằng** `preferred_username` trong token, nếu không bị từ chối ("does not match").
2. Token chỉ được kiểm **lúc mở kết nối**. Kết nối đã mở vẫn chạy khi token hết hạn; nhưng **mỗi lần
   mở kết nối mới** (pool tạo thêm, retry, reconnect) phải dùng token **còn hạn**. Ứng dụng dùng
   connection pool phải lấy token mới trước khi pool tạo kết nối, không cấu hình token cố định.
3. Đặt `kyuubi.session.name=<team>-<app>` trong URL/session conf để truy vết trên Kyuubi.

### 5.3 REST

```bash
curl -H "Authorization: Bearer $TOKEN" http://<rest>:10099/api/v1/ping
S=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"configs":{"kyuubi.session.name":"<team>-<app>"}}' http://<rest>:10099/api/v1/sessions)
```

Quy tắc phải ghi rõ:

1. Token gửi ở **mỗi** request; hết hạn thì lấy token mới, session Kyuubi vẫn còn.
2. Response `POST /api/v1/sessions` có `kyuubiInstance`: mọi lời gọi sau của session đó phải tới
   **đúng pod** đó (session sống trong một pod). Hiện Service REST pin về pod-0 nên chưa thấy, nhưng
   khi Kyuubi chạy nhiều pod thật sự thì bỏ qua là lỗi 404 ngẫu nhiên.
3. Python dùng `confOverlay {"kyuubi.operation.language":"PYTHON"}` ở statement.

### 5.4 Bảng chọn cổng

| Cần | Chọn |
|---|---|
| Có sẵn driver JDBC/ODBC, công cụ BI/SQL | Thrift binary |
| Kết quả lớn, truy vấn dài | Thrift binary |
| Microservice HTTP, không muốn driver | REST |
| Gửi batch job (Spark submit) | REST (`/api/v1/batches`) |
| Chạy PySpark từng đoạn (notebook) | REST |

---

## 6. Spike bắt buộc (ghi kết quả vào `docs/plans/thrift-binary-oidc-spike.md`)

| # | Kiểm | Lệnh | Đạt khi |
|---|---|---|---|
| S1 | Handshake | `beeline -u "jdbc:kyuubi://<host>:10009/" -n <user> -p "<jwt>"` | `SELECT 1` ra kết quả |
| S2 | Ràng buộc user | như S1, `-n` tên khác | từ chối, thông điệp "does not match" |
| S3 | Mật khẩu thường | `-p abc` | từ chối, thông điệp "pass an OIDC access token" |
| S4 | Token hết hạn | chờ quá lifespan rồi S1 | từ chối |
| S5 | Không tải JWKS lặp | 50 kết nối liên tiếp, đếm request `/certs` trên log Keycloak | ≤ 2 |
| S6 | Ứng dụng mẫu | Chạy nguyên văn ví dụ Java, Python, REST ở mục 5 với một client 4.1 đọc một bảng Iceberg | cả ba chạy; Ranger audit ghi đúng user đã map, policy cũ áp dụng, không sửa gì bên Ranger |
| S8 | Connection pool | HikariCP (Java) lấy token mới qua `DataSource` tùy biến, chạy > lifespan token với pool tạo kết nối mới | không có lỗi xác thực |
| S7 | Kết nối sống qua hạn token | mở beeline, chờ quá lifespan, chạy tiếp | vẫn chạy (ghi vào tài liệu) |

---

## 7. Hồi quy: những gì KHÔNG được đổi

| # | Đường | Kiểm | Kỳ vọng |
|---|---|---|---|
| R1 | REST + Bearer | `curl -H "Authorization: Bearer <jwt>" <rest>/api/v1/ping` | `pong` |
| R2 | REST + Basic | `curl -u user:pass <rest>/api/v1/ping` | **401** (trước đây trên `ssa-kyuubi` là 500) |
| R3 | REST không header | `curl <rest>/api/v1/ping` | 401 |
| R4 | BFF | đăng nhập qua `/api/v1/authentication/login`, gọi REST bằng cookie | 200 |
| R5 | Thrift HTTP + Bearer | JDBC `transportMode=http` với bearer của driver fork | chạy |
| R6 | Backend notebook | smoke test 4 cell qua REST | không đổi |

Rủi ro R4 thấp vì `kyuubi.authentication` không đổi và `AuthenticationFilter` không sửa.
Web UI bị gỡ theo `sync-custom-remove-ui-s3.md`; BFF backend vẫn giữ.

---

## 8. Thứ tự thực hiện

| Bước | Việc | Xong khi |
|---|---|---|
| 1 | 1.1 port 4 file | `diff` rỗng với `kyuubi-custom`; suite `kyuubi-common` xanh |
| 2 | 1.2 provider + suite | `build/mvn -pl extensions/server/kyuubi-oidc-auth test` xanh, đủ 5 case |
| 3 | Build image Kyuubi (`build/dist` + `docker-image-tool.sh` theo README) | image mới |
| 4 | Cấu hình mục 3 + `THRIFT_BINARY` trong protocols, trên cụm thử nghiệm | log Kyuubi có `THRIFT_BINARY`, port 10009 mở |
| 5 | Keycloak mục 4: một client 4.1 và một client 4.2 mẫu | decode token: `aud` có `kyuubi`, `iss` khớp, `preferred_username` là user Ranger đã có |
| 6 | Spike S1–S8, hồi quy R1–R6 | tất cả đạt, có file kết quả |
| 7 | Tài liệu: `docs/security/` thêm "Thrift binary với OIDC"; sửa câu "core untouched" trong `AGENTS.md`; `product-implement.md` thêm frontend binary | không còn chỗ nào nói binary không chạy với OIDC |
| 8 | `docs/client/oidc-connect.md` (mục 5) + quy trình cấp client cho đội mới (mục 4) | một đội dev ngoài dự án làm theo tài liệu tự nối được, không cần hỏi thêm |

Rollback: bỏ dòng `custom.class` (binary quay về Deny) hoặc bỏ `THRIFT_BINARY` khỏi protocols. Không
có migration dữ liệu.

---

## 9. Rủi ro còn lại, nói với người vận hành

1. **Token nằm trong `password`** của Thrift: có thể lộ qua log của driver, cấu hình ứng dụng, lịch sử
   shell. Phải coi như mật khẩu; tài liệu tích hợp phải dặn không log URL/password.
2. **Không thu hồi giữa chừng**: khóa client ở Keycloak không cắt kết nối Thrift đang mở (S7). Muốn cắt
   phải đóng session trên Kyuubi (`/api/v1/admin`) hoặc restart pod.
3. **Ứng dụng nền (4.1) chạy bằng một user Ranger dùng chung**, không phải người dùng cuối. Muốn quyền
   theo từng người thì đội đó dùng luồng 4.2. Truy vết ứng dụng bằng `kyuubi.session.name`.
4. **Mỗi đội tự lo việc làm mới token**; lỗi thường gặp nhất sẽ là pool kết nối giữ token cũ (S8).
