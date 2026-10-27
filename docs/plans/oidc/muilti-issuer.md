# Plan: Kyuubi OIDC/JWT nhận nhiều issuer (IP + DNS + in-cluster) và user/password mang JWT trên THRIFT_HTTP + THRIFT_BINARY

# Phần A: nhiều issuer

## 1. Bối cảnh

- Kyuubi chạy từ image custom `10.60.129.132:8890/j3rrytran/kyuubi-custom:v0.0-operator`, chart `kyuubi-helm`, release `kyuubi-operator-experiment` (values `values-operator.yaml`).
- Image này có lớp auth OIDC/JWT riêng (không có trong Apache Kyuubi upstream), cấu hình qua các key:
  - `kyuubi.authentication=OIDC`
  - `kyuubi.authentication.jwt.issuer` (hiện là **một** chuỗi, so khớp chính xác với claim `iss`)
  - `kyuubi.authentication.jwt.audience`
  - `kyuubi.authentication.jwt.jwks.url`
  - `kyuubi.authentication.jwt.username.claim`
  - `kyuubi.authentication.jwt.allowed.algorithms`
- Kyuubi này đóng vai **kyuubi-operation**: quản lý tài nguyên Spark (user tự chỉnh giới hạn tài nguyên) và quản lý thư viện. Image **đã bỏ toàn bộ frontend/UI** để tập trung cho operation. Điều đó chỉ bỏ phần Kyuubi **tự làm OIDC client** (mở trang login, redirect sang Keycloak, đổi authorization code lấy token, giữ session cookie). Các key `kyuubi.authentication.oidc.*` (ui.client.id, ui.client.secret, ui.scope, authorization/token/end.session endpoint, redirect.uri, session.timeout) chỉ phục vụ luồng đó, nay là dấu vết cũ, không có code đọc.
- Kyuubi **vẫn giao tiếp với Keycloak và Ranger**:
  - Keycloak: Kyuubi là **resource server** OIDC. Client tự lấy token từ Keycloak rồi gửi kèm khi mở phiên; Kyuubi tải JWKS từ Keycloak (`jwt.jwks.url`) để kiểm chữ ký, kiểm `iss`/`aud`/`exp`, lấy username từ claim. Đây vẫn là xác thực OIDC, chỉ khác là Kyuubi không còn tự đi xin token.
  - Ranger: không liên quan tới UI. Plugin Ranger trong Spark engine vẫn tải policy từ Ranger Admin và áp theo user của phiên (user lấy từ token). Không đổi gì.
- Image có `DenyPasswordAuthenticationProvider`: chặn PLAIN/password trên THRIFT_BINARY, nên hiện chỉ còn THRIFT_HTTP + `Authorization: Bearer <token>` đi được. Phần B khôi phục đường THRIFT_BINARY.

### 1.1 Các client đang nối vào kyuubi-operation

| Client | Transport | Cách mang token | Lấy token ở đâu | `iss` của token |
|---|---|---|---|---|
| **SSA** (web app, cấu hình kết nối tới các ứng dụng) | 2 cổng THRIFT_BINARY (dựng biến môi trường) + REST API (dev) | binary: password = JWT (Phần B); REST: `Authorization: Bearer` hoặc Basic | in-cluster (pod SSA gọi Keycloak qua service) | in-cluster |
| **DBeaver** (Kyuubi JDBC driver, OIDC) | THRIFT_BINARY | driver tự lấy token qua trình duyệt rồi đặt vào password (cách đã chạy được trước khi đổi image) | máy người dùng, qua IP hoặc DNS | IP hoặc DNS |
| **dbt** (Airflow) | THRIFT_BINARY | password = JWT | in-cluster, client credentials | in-cluster |

OIDC **không gắn với transport nào**. Khác nhau chỉ ở chỗ đặt token: trên HTTP (THRIFT_HTTP, REST) là header Bearer; trên THRIFT_BINARY là trường password của SASL PLAIN. Không có yêu cầu SSA phải chuyển sang THRIFT_HTTP.
- Keycloak realm `lakehouse` phát token với `iss` **đổi theo host client gọi vào**:
  - gọi qua NodePort ngoài: `https://10.29.19.56:32004/realms/lakehouse`
  - gọi qua DNS (sắp có): `https://<KEYCLOAK_DNS>/realms/lakehouse`
  - gọi in-cluster: `http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse`
- `iss` do **URL mà client gọi token endpoint** quyết định, không do Kyuubi. Vì `jwt.issuer` chỉ nhận một giá trị nên hiện chỉ token xin qua đúng URL đó mới đăng nhập được: cấu hình IP thì SSA/dbt (in-cluster) bị từ chối, cấu hình in-cluster thì DBeaver (IP/DNS) bị từ chối.

## 2. Mục tiêu

1. `kyuubi.authentication.jwt.issuer` nhận **danh sách issuer** phân tách bằng dấu phẩy. Token có `iss` thuộc danh sách là hợp lệ.
2. kyuubi-operation chấp nhận token xin qua **bất kỳ đường nào trong ba đường** IP, DNS, in-cluster mà người vận hành khai trong danh sách, không cần đặt Frontend URL trên Keycloak. Danh sách có thể là 1, 2 hoặc 3 giá trị tuỳ client thực tế (xem 4.1).
3. Giữ tương thích ngược: giá trị một issuer như hiện tại vẫn chạy nguyên.
4. Vẫn giữ nguyên kiểm tra chữ ký (JWKS), `aud`, `exp`, thuật toán. **Không** bỏ kiểm tra issuer, **không** so khớp theo path.

## 3. Ngoài phạm vi

- Cấp cert cho tên DNS của Keycloak, cấu hình ingress/DNS. Việc của hạ tầng.
- Thay đổi JWKS URL: giữ in-cluster `http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse/protocol/openid-connect/certs` vì cùng một realm thì key ký giống nhau bất kể issuer.
- Sửa dbt/profiles.yml.
- Notebook service: hệ riêng, có cách xác thực riêng, không thuộc plan này.

## 4. Thiết kế

### 4.1 Định dạng cấu hình

Key nhận **một hoặc nhiều** issuer, phân tách bằng dấu phẩy. **Không bắt buộc đủ ba.** Chỉ cần liệt kê những URL mà client thực tế dùng để gọi token endpoint của Keycloak; thiếu URL nào thì token xin qua URL đó bị từ chối.

```
# Chỉ client in-cluster (SSA, dbt)
kyuubi.authentication.jwt.issuer=http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse

# In-cluster + DBeaver qua IP
kyuubi.authentication.jwt.issuer=https://10.29.19.56:32004/realms/lakehouse,http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse

# Đủ ba đường (tối đa)
kyuubi.authentication.jwt.issuer=https://10.29.19.56:32004/realms/lakehouse,https://<KEYCLOAK_DNS>/realms/lakehouse,http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse
```

Cách chọn cho hệ hiện tại: SSA, dbt chạy trong cluster nên nếu chúng gọi Keycloak qua service in-cluster thì issuer in-cluster là **bắt buộc**. Thêm IP khi còn DBeaver/người dùng ngoài cluster. Thêm DNS khi DNS Keycloak sẵn sàng. Thêm/bớt chỉ cần sửa values và restart pod, không build lại image.

Quy tắc parse:

- Tách theo `,`, `trim()` từng phần tử, bỏ phần tử rỗng.
- Chuẩn hoá: bỏ dấu `/` cuối nếu có (Keycloak phát `iss` không có `/` cuối, nhưng người cấu hình hay thừa).
- So khớp: **exact, case-sensitive** giữa `iss` (đã chuẩn hoá cùng cách) và từng phần tử trong danh sách.
- Danh sách rỗng sau khi parse → **fail fast lúc khởi động** với thông báo rõ ràng, không được ngầm bỏ kiểm tra issuer.
- Không thêm key mới. Giữ đúng key `kyuubi.authentication.jwt.issuer` để values hiện tại không phải đổi tên.

### 4.2 Hành vi khi verify

- `iss` thiếu hoặc không thuộc danh sách → từ chối, HTTP/Thrift trả lỗi auth như hiện tại.
- Log `WARN` một dòng khi mismatch: `JWT issuer '<iss>' not in accepted issuers [<list>]`. `iss` không phải bí mật nên log được. **Không** log token.
- Mọi kiểm tra khác (chữ ký qua JWKS theo `kid`, `aud`, `exp`/`nbf`, `alg` thuộc `allowed.algorithms`, claim username) giữ nguyên.

### 4.3 Không có luồng UI

- Kyuubi không tự redirect, không giữ session cookie, không gọi token endpoint. Nó chỉ verify JWT mà client gửi (Bearer trên HTTP, hoặc password trên PLAIN/Basic theo Phần B). Vì vậy verifier chỉ có **một** chỗ so issuer, dùng cho mọi transport.
- Mọi `ConfigEntry` hoặc code còn sót cho `kyuubi.authentication.oidc.*` (nếu grep ở Bước 1 còn thấy) là dead code: xoá luôn trong PR này để values và code khớp nhau. Nếu xoá entry thì Kyuubi vẫn chạy khi values cũ còn key đó (chỉ log warning key lạ), nên không cần giữ lại vì tương thích.

## 5. Các bước cho repo Kyuubi custom

> Source của image `j3rrytran/kyuubi-custom` **không nằm trong** `D:\vtnet\applications`. Agent cần được chỉ repo đó trước khi bắt đầu.

### Bước 1. Định vị code

```bash
grep -rn "kyuubi.authentication.jwt.issuer" --include=*.scala --include=*.java --include=*.conf --include=*.md .
grep -rn "DenyPasswordAuthenticationProvider" --include=*.scala --include=*.java .
grep -rln "jwks\|JWKS\|JWTClaimsSet\|JWTVerifier\|Jwts\." --include=*.scala --include=*.java .
grep -rn "kyuubi.authentication.oidc" --include=*.scala --include=*.java .   # kỳ vọng rỗng; còn gì là dead code, xoá
```

Xác định:

- File khai báo `ConfigEntry` của `kyuubi.authentication.jwt.issuer` (thường trong `KyuubiConf.scala` hoặc một object conf riêng của lớp OIDC).
- File verify token (class provider cho THRIFT_HTTP bearer và, nếu có, REST filter).
- Thư viện JWT đang dùng. Cách sửa tuỳ thư viện:

| Thư viện | Dấu hiệu | Cách sửa |
|---|---|---|
| nimbus-jose-jwt | `DefaultJWTClaimsVerifier`, `JWTClaimsSet` | `DefaultJWTClaimsVerifier` chỉ nhận một `iss` qua exact-match claims. Thay bằng verifier riêng: subclass `DefaultJWTClaimsVerifier` (bỏ `iss` khỏi exactMatch, giữ `aud`), override `verify()` gọi `super.verify()` rồi tự kiểm `claims.getIssuer` thuộc set. |
| auth0 java-jwt | `JWT.require(alg)` | `withIssuer(String...)` đã nhận varargs, truyền thẳng mảng issuer. |
| jjwt | `Jwts.parser()...requireIssuer()` | `requireIssuer` chỉ nhận một giá trị. Bỏ `requireIssuer`, parse xong tự kiểm `claims.getIssuer` thuộc set. |

### Bước 2. Sửa config entry

- Đổi mô tả của `kyuubi.authentication.jwt.issuer` thành "comma-separated list of accepted issuers".
- Thêm hàm tiện ích (ví dụ `JwtIssuers.parse(raw: String): Set[String]`) thực hiện đúng quy tắc ở mục 4.1 và hàm `normalize(iss: String)` dùng chung cho cả cấu hình lẫn claim.
- Nếu conf hiện tại khai báo kiểu `stringConf`, giữ kiểu string và parse ở nơi dùng. Không đổi sang `toSequence` nếu điều đó làm đổi tên key hay cách render trong `kyuubi-defaults.conf`.

### Bước 3. Sửa verifier

- Thay so khớp một issuer bằng kiểm tra membership trên set đã parse.
- Set issuer được parse **một lần** lúc khởi tạo provider, không parse lại mỗi request.
- Fail fast khi set rỗng (ném exception lúc init provider, Kyuubi server không lên).
- Thêm log WARN khi mismatch theo mục 4.2.
- Thêm log INFO lúc khởi tạo liệt kê danh sách issuer đã nạp (để đối chiếu khi kiểm chứng sau deploy).

### Bước 4. Unit test

Tối thiểu các case:

1. Một issuer (giá trị cũ) → token đúng pass, token sai iss fail. Đảm bảo tương thích ngược.
2. Ba issuer (IP, DNS, in-cluster) → token mang từng iss đều pass.
3. Token iss không thuộc danh sách → fail, có log WARN.
4. Cấu hình có khoảng trắng, phần tử rỗng, dấu `/` cuối: `" a/ , ,b"` → set `{a, b}`.
5. Cấu hình rỗng hoặc chỉ dấu phẩy → init provider ném exception.
6. Token thiếu claim `iss` → fail.
7. Token iss đúng nhưng `aud` sai hoặc chữ ký sai → vẫn fail (chứng minh các kiểm tra khác không bị bỏ).

Dùng key RSA sinh trong test để ký token, mock JWKS (hoặc dùng `ImmutableJWKSet` của nimbus) thay vì gọi Keycloak thật.

## 6. Keycloak

Không bắt buộc đổi gì cho mục tiêu này. Khuyến nghị kiểm tra trên Admin Console, realm `lakehouse`:

- Client `kyuubi`: Valid redirect URIs không được chứa URL `thrift-binary:10009` (không phải HTTP).
- Client phát token cho dbt (service account) phải có mapper audience thêm `kyuubi` vào `aud`.
- **Không** đặt Realm Settings → Frontend URL trỏ vào service in-cluster. Nếu sau này muốn cố định issuer về DNS thì đặt Frontend URL = `https://<KEYCLOAK_DNS>`; lúc đó danh sách issuer vẫn hoạt động vì DNS đã nằm trong danh sách.

## 7. Tiêu chí hoàn thành

- [ ] PR repo Kyuubi custom: config entry + verifier + unit test pass, image `v0.1-operator` đã push.
- [ ] Code Kyuubi không còn entry `kyuubi.authentication.oidc.*`.
- [ ] Token xin qua từng đường có trong danh sách issuer đều đăng nhập được Kyuubi (THRIFT_BINARY và THRIFT_HTTP); token có issuer ngoài danh sách bị từ chối và có log WARN.
- [ ] Ghi lại: commit hash, tag image, lệnh build, kết quả unit test.

---

# Phần B: THRIFT_HTTP nhận user/password mang JWT, cùng cơ chế với THRIFT_BINARY

## B1. Vấn đề

- Trên THRIFT_BINARY, client (SSA, dbt-spark, PyHive, beeline, Kyuubi JDBC driver của DBeaver) gửi credential theo SASL PLAIN: **username + password**. Image operator hiện chặn hoàn toàn đường này bằng `DenyPasswordAuthenticationProvider`.
- **Trước khi đổi image, DBeaver đã xác thực OIDC qua THRIFT_BINARY và chạy được.** Nghĩa là image cũ (còn UI) đã có provider nhận token trong trường password của SASL PLAIN. Phần B là **khôi phục đường đó** cho image operator, không phải cơ chế mới. Agent phải xem lại image/driver cũ để giữ đúng định dạng credential (xem Bước 5).
- SSA đang nối qua 2 cổng THRIFT_BINARY để dựng biến môi trường và REST API để dev. SSA **không cần đổi transport**; chỉ cần password của kết nối binary là JWT.
- Trên THRIFT_HTTP, image chỉ nhận `Authorization: Bearer <JWT>`. Nhiều client không gửi được Bearer:
  - dbt-spark `method: thrift` chỉ nói THRIFT_BINARY + PLAIN (user/password).
  - dbt-spark `method: http` gửi **HTTP Basic** với user cố định `token` và password = token, tới path kiểu Databricks `/sql/protocolv1/o/<organization>/<cluster>`, luôn ép `https://`.
  - Các tool BI/JDBC cũ chỉ có ô user/password.
- Mục tiêu: một **cơ chế duy nhất "password mang JWT"** chạy trên cả hai transport. Client điền user/password như thời LDAP, nhưng password là access token của Keycloak. Kyuubi verify token bằng đúng verifier ở Phần A (danh sách issuer, JWKS, `aud`, `exp`, `alg`).

## B2. Thiết kế

### B2.1 Provider mới `JwtPasswordAuthenticationProvider`

Thay thế `DenyPasswordAuthenticationProvider` ở **cùng điểm cắm** (`PasswdAuthenticationProvider.authenticate(user, password)`), nên tự động phủ các đường:

| Transport | Cách credential đến provider | Thay đổi client |
|---|---|---|
| THRIFT_BINARY | SASL PLAIN → `authenticate(user, password)` | password = JWT |
| THRIFT_HTTP | HTTP `Authorization: Basic base64(user:password)` → `BasicAuthenticationHandler` → `authenticate(user, password)` | password = JWT |
| THRIFT_HTTP | HTTP `Authorization: Bearer <JWT>` | giữ nguyên, không đổi |
| REST | HTTP Basic / Bearer | như trên |

Logic `authenticate(user, password)`:

1. `password` rỗng → từ chối.
2. `password` không có dạng JWT (không đủ 3 phần `a.b.c`, hoặc header không decode được) → từ chối với thông báo hiện tại `Password authentication is disabled; use OIDC/JWT ...`. **Không** fallback về LDAP hay bất kỳ kiểm tra mật khẩu thật nào.
3. Verify JWT bằng **cùng** verifier/hàm của Phần A (issuer thuộc danh sách, chữ ký JWKS, `aud`, `exp`/`nbf`, `alg`). Fail → từ chối, log WARN lý do (không log token).
4. Lấy `tokenUser` = claim `kyuubi.authentication.jwt.username.claim` (hiện là `preferred_username`).
5. Đối chiếu `user` client gửi:
   - `user` rỗng, hoặc thuộc `kyuubi.authentication.jwt.password.user.aliases` (mặc định `token`) → chấp nhận, identity phiên = `tokenUser`.
   - `user == tokenUser` → chấp nhận.
   - Khác → từ chối với log `WARN client user '<user>' does not match token subject '<tokenUser>'`. Không cho client tự chọn tên khác với token để tránh mượn identity.
6. Identity phiên trả về cho Kyuubi **luôn là `tokenUser`** (dùng cho Ranger authz, engine share level, Kerberos proxy user…), không phải chuỗi `user` client gửi.

### B2.2 Cấu hình mới

```
kyuubi.authentication.jwt.password.enabled=true          # bật provider; false → giữ hành vi Deny như hiện tại
kyuubi.authentication.jwt.password.user.aliases=token    # user được coi là placeholder, phân tách dấu phẩy
```

Cả hai transport dùng chung hai key này. Không thêm key riêng cho từng transport.

### B2.3 Đường đi của bearer trên HTTP

- Giữ `BearerAuthenticationHandler` hiện có. Refactor để Bearer và Basic-với-JWT gọi **một** hàm verify + một hàm resolve username. Không để hai bản copy logic.
- Thứ tự xử lý trên HTTP: có header `Bearer` → đi đường bearer; có `Basic` → decode rồi gọi `JwtPasswordAuthenticationProvider`; không có header → trả 401 với `WWW-Authenticate: Basic realm="kyuubi", Bearer`.

### B2.4 Path HTTP cho client kiểu Databricks (tuỳ chọn)

- `kyuubi.frontend.thrift.http.path` hiện là `cliservice`. Kyuubi chỉ phục vụ một path. Để dbt `method: http` dùng được mà không sửa dbt, có hai lựa chọn, agent chọn một và ghi trong PR:
  - **(a)** Đặt `kyuubi.frontend.thrift.http.path=sql/protocolv1/o/0/kyuubi`. Beeline/JDBC khác phải đổi `httpPath` theo. Không sửa code.
  - **(b)** Sửa `ThriftHttpFrontendService` để nhận thêm danh sách path phụ (`kyuubi.frontend.thrift.http.path.aliases`) map về cùng servlet. Giữ `cliservice` cho client cũ. Nhiều code hơn.
- dbt `method: http` ép `https://`, nên nếu đi lựa chọn này phải bật TLS cho THRIFT_HTTP (`kyuubi.frontend.thrift.http.use.SSL=true` + keystore) hoặc đặt ingress TLS trước Kyuubi. Khuyến nghị cho dbt: **dùng `method: thrift` trên THRIFT_BINARY in-cluster** (mục B5), tránh phải dựng TLS.

### B2.5 Bảo mật

- Token nằm trong trường password nên đi qua dây như mật khẩu. In-cluster qua Service ClusterIP: chấp nhận. Mở ra ngoài cluster (NodePort/ingress): **bắt buộc TLS** cho transport tương ứng.
- Token Keycloak mặc định sống 5 phút. Kyuubi chỉ verify lúc `OpenSession`, phiên mở rồi không bị cắt khi token hết hạn. Nhưng client tạo kết nối mới (dbt mỗi thread, reconnect) sẽ fail nếu token đã hết hạn → xem B5 về lifespan.

## B3. Các bước cho repo Kyuubi custom (bổ sung sau Bước 4 của Phần A)

### Bước 5. Tìm điểm cắm

```bash
grep -rn "DenyPasswordAuthenticationProvider" --include=*.scala --include=*.java .
grep -rn "PasswdAuthenticationProvider\|AuthenticationProviderFactory" --include=*.scala .
grep -rn "BasicAuthenticationHandler\|BearerAuthenticationHandler\|AuthenticationFilter" --include=*.scala .
grep -rn "thrift.http.path\|THRIFT_HTTP_PATH" --include=*.scala .
# Bản cũ còn UI (tag trước v0.0-operator, hoặc tag tangerinee/kyuubi-custom:v4.1 của release values-oidc.yaml): provider nào nhận token qua PLAIN?
git log --all --oneline -- '*AuthenticationProvider*' | head
grep -rn "oidcClientSecret\|oidc" --include=*.java --include=*.scala <thư mục Kyuubi JDBC driver> | head
```

Xác định: nơi `AuthenticationProviderFactory` chọn provider theo `kyuubi.authentication`, class handler Basic/Bearer trên HTTP, nơi verify JWT của Bearer (để tái dùng).

**Giữ tương thích với Kyuubi JDBC driver (DBeaver):** đọc code driver phần OIDC (`oidcClientSecret` và các tham số liên quan) để biết driver đặt gì vào `user` và `password` khi mở phiên (thường user = `preferred_username` hoặc rỗng, password = access token thô; có thể có tiền tố). Provider mới phải nhận đúng định dạng đó để DBeaver chạy lại **mà không đổi driver**. Nếu driver gửi dạng khác JWT thô (ví dụ `Bearer <token>`), thêm bước strip tiền tố trước khi verify và ghi rõ trong PR.

### Bước 6. Tách verifier dùng chung

- Đưa verify JWT + resolve username ra một class dùng chung (ví dụ `JwtTokenVerifier`), khởi tạo một lần từ `KyuubiConf`, cache JWKS như hiện tại.
- `BearerAuthenticationHandler` và provider mới cùng gọi class này.

### Bước 7. Viết `JwtPasswordAuthenticationProvider`

- Theo B2.1. Đăng ký trong factory: khi `kyuubi.authentication=OIDC` và `jwt.password.enabled=true` → provider mới; `false` → `DenyPasswordAuthenticationProvider` như cũ.
- Đảm bảo identity phiên dùng `tokenUser`: kiểm tra chỗ Kyuubi lấy `realUser`/`sessionUser` sau SASL PLAIN. Nếu framework lấy từ `user` của SASL thì cần hook để trả về username đã resolve từ token.

### Bước 8. Config entries mới

- `kyuubi.authentication.jwt.password.enabled` (boolean, mặc định `true`).
- `kyuubi.authentication.jwt.password.user.aliases` (string, mặc định `token`, parse như danh sách issuer).
- Tuỳ chọn (b) ở B2.4: `kyuubi.frontend.thrift.http.path.aliases`.

### Bước 9. Unit test bổ sung

1. password là JWT hợp lệ, user rỗng → pass, identity = `preferred_username`.
2. password JWT hợp lệ, user = `token` → pass, identity = `preferred_username`.
3. password JWT hợp lệ, user = đúng `preferred_username` → pass.
4. password JWT hợp lệ, user khác → fail, log WARN mismatch.
5. password không phải JWT (`abc`, rỗng, `a.b`) → fail với thông báo Deny, **không** gọi JWKS.
6. password JWT sai chữ ký / sai `aud` / hết hạn / `iss` ngoài danh sách → fail.
7. `jwt.password.enabled=false` → mọi password đều fail như hiện tại.
8. HTTP: request `Basic base64("token:<JWT>")` → 200 OpenSession; `Bearer <JWT>` vẫn 200; không header → 401 kèm `WWW-Authenticate`.
9. Nếu làm B2.4 (b): request tới path alias và path gốc đều đến cùng servlet.

### Bước 10. Build image

Gộp chung với Phần A, tag `v0.1-operator`.

## B4. Helm values bổ sung

`kyuubi-helm/values-operator.yaml`:

- Bật lại THRIFT_BINARY để dbt `method: thrift` dùng được:
  ```yaml
  server:
    thriftBinary:
      enabled: true
      port: 10009
      service:
        type: ClusterIP
    thriftHttp:
      enabled: true
      port: 10010
      service:
        type: ClusterIP
  ```
- Thêm vào block `kyuubi-defaults.conf`:
  ```
  kyuubi.authentication.jwt.password.enabled=true
  kyuubi.authentication.jwt.password.user.aliases=token
  ```
- Nếu chọn B2.4 (a): `kyuubi.frontend.thrift.http.path=sql/protocolv1/o/0/kyuubi` và cấu hình TLS cho THRIFT_HTTP.
- Kiểm tra render:
  ```bash
  /d/Driver/windows-amd64/helm template kyuubi-operator-experiment D:/vtnet/applications/kyuubi-helm -f D:/vtnet/applications/kyuubi-helm/values-operator.yaml | grep -n "frontend.protocols\|jwt.password"
  ```
  Kỳ vọng `kyuubi.frontend.protocols=REST,THRIFT_BINARY,THRIFT_HTTP`.

Không tự commit.

## B5. Phía dbt và Keycloak

### dbt profile khuyến nghị (THRIFT_BINARY in-cluster, không cần TLS)

```yaml
dev:
  type: spark
  method: thrift
  host: kyuubi-operator-experiment-thrift-binary.kyuubi-operator-experiment.svc.cluster.local
  port: 10009
  schema: default
  auth: LDAP            # PyHive dùng PLAIN cho LDAP; Kyuubi nhận user/password
  user: token
  password: "{{ env_var('KYUUBI_TOKEN') }}"
  connect_retries: 3
  connect_timeout: 30
```

Job lấy token trước khi chạy dbt (client credentials của client service-account, ví dụ `dbt-etl`):

```bash
export KYUUBI_TOKEN=$(curl -s -X POST http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=dbt-etl -d client_secret="$DBT_CLIENT_SECRET" | jq -r .access_token)
dbt run --target dev
```

Issuer của token này là URL in-cluster, hợp lệ nhờ danh sách issuer ở Phần A.

### dbt profile tuỳ chọn (THRIFT_HTTP, chỉ khi đã làm B2.4 + TLS)

```yaml
dev:
  type: spark
  method: http
  host: <host TLS của kyuubi thrift http>
  port: 443
  schema: default
  organization: "0"
  cluster: kyuubi
  token: "{{ env_var('KYUUBI_TOKEN') }}"
```

### Keycloak (Admin Console, realm `lakehouse`)

- Client `dbt-etl`: Client authentication ON, Service accounts roles ON, Standard flow OFF. Thêm mapper **Audience** → Included Client Audience = `kyuubi`.
- Client `dbt-etl` → Advanced → **Access Token Lifespan**: đặt lớn hơn thời gian chạy dbt dài nhất (ví dụ 2h) để kết nối mới giữa chừng không fail. Chỉ override ở client này, không đổi realm.
- Service account roles: gán role/group tương ứng để Ranger authz nhận user `service-account-dbt-etl`.

## B6. Kiểm chứng bổ sung

1. **Binary + password JWT**:
   ```bash
   beeline -u "jdbc:hive2://kyuubi-operator-experiment-thrift-binary.kyuubi-operator-experiment:10009/default" -n token -p "$(cat tok_incluster)" -e "select current_user()"
   ```
   Kỳ vọng trả `service-account-dbt-etl` (hoặc `preferred_username` của token), không phải `token`.
2. **HTTP + Basic JWT**:
   ```bash
   beeline -u "jdbc:hive2://kyuubi-operator-experiment-thrift-http.kyuubi-operator-experiment:10010/default;transportMode=http;httpPath=cliservice" -n token -p "$(cat tok_ip)" -e "select current_user()"
   ```
3. **HTTP + Bearer** (giữ hoạt động):
   ```bash
   beeline -u "jdbc:hive2://kyuubi-operator-experiment-thrift-http.kyuubi-operator-experiment:10010/default;transportMode=http;httpPath=cliservice;http.header.Authorization=Bearer $(cat tok_ip)" -e "select 1"
   ```
4. **Negative**: binary với `-n token -p matkhauthuong` → bị từ chối với thông báo Deny; user khác subject (`-n alice -p <JWT của bob>`) → bị từ chối.
5. Lặp 1 và 2 với token lấy qua từng đường có trong danh sách issuer.
6. Chạy job dbt `TestSparkETL` với profile `method: thrift` ở B5 → pass.
7. **SSA**: hai kết nối THRIFT_BINARY mở phiên được, biến môi trường dựng ra đúng; REST API trả 200 với Bearer.
8. **DBeaver**: kết nối JDBC OIDC qua THRIFT_BINARY chạy lại như trước khi đổi image, không đổi driver.
9. Log Kyuubi: không có stack trace; có WARN cho các case negative.

## B7. Rollback bổ sung

- `kyuubi.authentication.jwt.password.enabled=false` đưa về hành vi Deny mà không cần đổi image.
- Rollback image về `v0.0-operator` thì phải tắt lại `thriftBinary.enabled` (image cũ chặn PLAIN) và bỏ hai key `jwt.password.*`.

## B8. Tiêu chí hoàn thành bổ sung

- [ ] `JwtPasswordAuthenticationProvider` thay `Deny...`, bật/tắt bằng `jwt.password.enabled`, unit test Bước 9 pass.
- [ ] Bearer và Basic trên HTTP dùng chung một verifier.
- [ ] Identity phiên luôn là `preferred_username` của token; `current_user()` xác nhận.
- [ ] Values bật cả THRIFT_BINARY và THRIFT_HTTP, render đúng, chưa commit.
- [ ] 9 bước kiểm chứng B6 pass với token của từng đường trong danh sách issuer.
- [ ] dbt chạy qua với profile `method: thrift` + password = JWT.
- [ ] SSA và DBeaver nối lại được qua THRIFT_BINARY mà không đổi cấu hình phía client ngoài password = JWT.

