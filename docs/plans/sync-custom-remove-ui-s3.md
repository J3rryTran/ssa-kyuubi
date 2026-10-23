# Plan: (1) đồng bộ code `kyuubi-custom` → `ssa-kyuubi`, (2) gỡ Web UI của Kyuubi, (3) thêm thư viện S3/AWS vào image server

Trạng thái: chưa thực hiện. Đối tượng thực thi: AI agent. Repo đích: `ssa-kyuubi` (branch `dev_redunce`).
Repo nguồn chỉ để **đọc**: `/home/j3rrytran/Documents/trungtm_VT/kyuubi-custom` (branch `notebook`).
Kiểm kê trong plan chụp ngày 2026-10-22; trước khi làm, agent chạy lại các lệnh `diff` ở mỗi mục.

`.gitattributes`, `.gitignore`, `build/dist`, `pom.xml` đã được chủ dự án commit; agent **được sửa**
các file này theo plan.

Ba phần độc lập, làm theo thứ tự 1 → 2 → 3, mỗi phần một commit.

---

## Phần 1. Đồng bộ code từ `kyuubi-custom`

### 1.1 Nguyên tắc

`kyuubi-custom` là nguồn của image đang chạy production, nhưng `ssa-kyuubi` đã **cố ý** khác ở nhiều chỗ
(gỡ notebook, thêm K8s utils, sửa build). Vì vậy **không copy cả thư mục**; copy theo danh sách dưới đây,
file nào không có trong danh sách thì giữ `ssa-kyuubi`.

Hai repo **không có commit chung** nên không dùng `git merge`/`cherry-pick`; dùng `cp` từng file rồi
`diff` kiểm.

### 1.2 Danh sách COPY (nguồn → đích cùng đường dẫn)

**C0 (làm trước tiên).** `.gitignore` đã được neo (`/dist/`, `/logs/`, `/metrics/`), nên 15 file nguồn
trước đây bị nuốt giờ hiện là untracked. Agent chỉ kiểm (chủ dự án tự `git add`) các đường sau, `diff -r` với `kyuubi-custom`
phải rỗng: `kyuubi-metrics/src/main/`, `kyuubi-metrics/src/test/scala/`,
`kyuubi-hive-jdbc/src/main/java/org/apache/kyuubi/jdbc/hive/logs/`,
`kyuubi-hive-beeline/src/main/java/org/apache/hive/beeline/logs/`. Riêng
`kyuubi-server/src/main/resources/dist/` **không add** (Phần 2a xóa nó).

| # | File / thư mục | Nội dung khác | Ghi chú |
|---|---|---|---|
| C1 | `kyuubi-common/src/main/scala/org/apache/kyuubi/service/authentication/AuthMethods.scala` | thêm `OIDC` | **Bắt buộc**: sửa lỗi REST `Basic` nổ 500 trên `ssa` (`BasicAuthenticationHandler.scala:83` gọi `AuthMethods.withName("OIDC")`), và là tiền đề của plan Thrift binary |
| C2 | `kyuubi-common/src/main/scala/org/apache/kyuubi/service/authentication/AuthenticationProviderFactory.scala` | 2 `case AuthMethods.OIDC` | Bắt buộc, đi cùng C1 |
| C3 | `kyuubi-common/src/test/scala/org/apache/kyuubi/service/authentication/KyuubiAuthenticationFactorySuite.scala` | 2 dòng | |
| C4 | `kyuubi-common/src/test/scala/org/apache/kyuubi/config/KyuubiConfSuite.scala` | 1 dòng | |
| C5 | `extensions/server/kyuubi-oidc-auth/src/main/scala/org/apache/kyuubi/auth/oidc/*.scala` (2 file) + `src/test/.../JwtTokenAuthenticationProviderSuite.scala` | scaladoc + vị trí import | Chỉ tài liệu/mỹ thuật; copy để hai repo đồng nhất. `pom.xml` của module chỉ khác header license: **không** copy |
| C6 | `kyuubi-hive-jdbc/src/main/java/org/apache/kyuubi/jdbc/hive/auth/oidc/` (cả thư mục, 15 file) + `src/test/.../auth/oidc/OidcClientTest.java` | javadoc, 1-18 dòng/file | Copy cả thư mục. Sau đó `diff -r` hai thư mục phải rỗng |
| C7 | `kyuubi-hive-jdbc/src/main/java/org/apache/kyuubi/jdbc/hive/KyuubiConnection.java` | 11 dòng javadoc | |
| C8 | `externals/kyuubi-spark-sql-engine/src/main/resources/python/execute_python.py` | **có khác hành vi**, xem 1.4 | Copy từ custom theo yêu cầu; đọc 1.4 trước |
| C9 | `externals/kyuubi-spark-sql-engine/src/main/scala/org/apache/kyuubi/engine/spark/operation/ExecutePython.scala` | 5 dòng comment | |
| C10 | `docs/requirements.txt` | chỉ có ở custom | Cho build docs |
| C11 | `docs/Add Function/*.md` (4 file: `add_oicd.md`, `comment.md`, `OIDC_SSO_DEPLOYMENT.md`, `OIDC_SSO_FEASIBILITY_AND_DESIGN.md`) | chỉ có ở custom | Tài liệu thiết kế OIDC; copy vào `docs/plans/oidc/` (không giữ tên thư mục có dấu cách). Thêm `<exclude>docs/plans/**/*.md</exclude>` đã có sẵn trong RAT nên không cần sửa `pom.xml` |

### 1.3 Danh sách MERGE tay (không copy đè)

| # | File | Lấy từ custom | Giữ của ssa |
|---|---|---|---|
| M1 | `.rat-excludes` | 3 khối comment + entry `dist/**`, docker context, build scripts (dòng 36-37, 61, 64, 66 bên custom) | 5 entry `kyuubi-oidc-auth/**`, `RangerServiceProfile*`, `jdbc/hive/auth/oidc/**` (dòng 64-68 bên ssa). Kết quả = hợp của hai |
| M2 | `pom.xml` | property `<maven.project.sourceRoots.warningsDisabled>true</...>` (dòng 119-124 bên custom) | mọi thứ còn lại: spotless `<excludes>` cho oidc, `docs/security/**` trong RAT. **Không** lấy `docs/Add Function/**` vì C11 đổi thư mục |

### 1.4 `execute_python.py`: khác biệt hành vi phải biết trước khi copy

| Hành vi | custom (sẽ copy) | ssa (hiện tại) |
|---|---|---|
| `%pip list` | **không hỗ trợ**, chỉ `%pip install` | hỗ trợ |
| `kyuubi-session-pip` trên `sys.path` | tự xử lý ở chỗ khác ("already imported, no need to patch sys.path") | chèn tường minh sau `_pip_target_dir` |
| Thứ tự import `kyuubi_util` / `ast.Module` | sau khi chuẩn bị `sys.path` | đầu file |

Vì image engine production chạy bản custom, copy custom là đưa `ssa` về đúng hành vi đang chạy. Hệ quả duy
nhất người dùng thấy: `%pip list` không còn. Ghi dòng này vào commit message. Sau khi copy chạy
`PySparkTests` (`build/mvn -pl externals/kyuubi-spark-sql-engine test -DwildcardSuites=org.apache.kyuubi.engine.spark.PySparkTests`
hoặc suite tương đương trong `kyuubi-server`).

### 1.5 Danh sách GIỮ `ssa` (tuyệt đối không copy từ custom)

`docker/Dockerfile`, `docker/python-for-notebook/Dockerfile.spark-py311`, `bin/build-spark-image.sh`,
`bin/docker-image-tool.sh` (ssa chỉ khác comment), `build/dist`, `charts/kyuubi/values.yaml` (ssa có
RBAC đầy đủ hơn), `.gitignore`, `.gitattributes`, `AGENTS.md`, `README.md`,
`kyuubi-common/.../KyuubiConf.scala` (ssa có `ENGINE_FORCE_EXIT_ON_STOP`),
`kyuubi-server/.../util/KubernetesUtils.scala` (ssa có in-cluster namespace/SA),
`kyuubi-server/.../engine/spark/SparkProcessBuilder.scala`, toàn bộ `kyuubi-server/src/main/scala/.../server/api/**`,
`http/authentication/AuthenticationFilter.scala`, `KyuubiRestFrontendService.scala`,
`src/test/.../AllKyuubiConfiguration.scala`, `docs/configuration/settings.md` (file sinh, sinh lại bằng
`dev/gen/gen_all_config_docs.sh` sau khi xong), `docs/security/index.rst`.

**Không copy** bất cứ gì có "notebook" hoặc "editor" trong đường dẫn, và không copy `kyuubi-server/web-ui`
(Phần 2 gỡ nó).

### 1.6 Kiểm sau Phần 1

```bash
for f in <C1..C9>; do diff kyuubi-custom/$f ssa-kyuubi/$f; done        # rỗng
diff -r kyuubi-custom/kyuubi-hive-jdbc/src/main/java/org/apache/kyuubi/jdbc/hive/auth/oidc ssa-kyuubi/...  # rỗng
build/mvn -pl kyuubi-common,kyuubi-hive-jdbc,extensions/server/kyuubi-oidc-auth -am test   # xanh
grep -rn "notebook" --include=*.scala --include=*.java --include=*.py kyuubi-server/src kyuubi-hive-jdbc/src | grep -vi "PySparkTests\|python magic"   # rỗng
```

---

## Phần 2. Gỡ Web UI của Kyuubi

### 2.1 Phạm vi

Gỡ **giao diện web** (SPA Vue dưới `kyuubi-server/web-ui`) và mọi thứ chỉ tồn tại để phục vụ nó. **Giữ**
REST API `/api/v1/**`, Swagger (`/api/v1/openapi`), Thrift. Không copy code hay bước build web-ui từ
`kyuubi-custom`.

BFF OIDC là **backend REST**, không phải frontend: **giữ lại** toàn bộ. Phần 2b chỉ sửa những chỗ BFF đang trỏ vào Web UI đã bị gỡ.

### 2.2 Việc 2a: gỡ UI (làm ngay)

| # | Ở đâu | Làm gì |
|---|---|---|
| U1 | `kyuubi-server/web-ui/` | Xóa cả thư mục bằng `rm -rf` (không dùng `git rm`; chủ dự án tự stage) |
| U2 | `kyuubi-server/pom.xml:450-490` | Xóa plugin `frontend-maven-plugin` (3 execution dùng `${webui.skip}`) |
| U3 | `pom.xml:2198-2203` profile `web-ui`, property `webui.skip` (tìm `grep -n webui.skip pom.xml`), `node.version`/`pnpm.version` (dòng 226-227) nếu không còn ai dùng | Xóa |
| U4 | `build/dist` | Xóa `ENABLE_WEBUI`, cờ `--web-ui` (dòng 34, 47, 52, 72-73), `-Pweb-ui` (214-215), `mkdir web-ui` (278), copy dist (369-371). Số dòng có thể đã lệch sau commit mới nhất, tìm bằng `grep -n -i webui build/dist` |
| U5 | `docker/Dockerfile:57` | Xóa `COPY web-ui ${KYUUBI_HOME}/web-ui` |
| U6 | `kyuubi-server/.../KyuubiRestFrontendService.scala` | Xóa `proxyHandler` + `getEngineUIProxyHandler` (dòng 126-127), khối `if (conf.get(FRONTEND_REST_UI_ENABLED)) installWebUI()` (128-130), hàm `installWebUI` (132-~150), import `FRONTEND_REST_UI_ENABLED`, `JettyUtils`, `ErrorPageErrorHandler` nếu không còn dùng |
| U7 | `kyuubi-server/.../api/v1/ApiRootResource.scala` | Xóa `getEngineUIProxyHandler`; xóa file `server/api/EngineUIProxyServlet.scala`; xóa các conf `FRONTEND_REST_PROXY_JETTY_CLIENT_*` khỏi `KyuubiConf` (dòng 605-650) **chỉ khi** `grep` không còn chỗ dùng |
| U8 | `kyuubi-server/src/main/resources/dist/index.html` | Xóa (placeholder cho static handler `"dist"`). **Giữ** rule `/dist/` trong `.gitignore` (nó chặn output của `build/dist` ở gốc repo, không liên quan file này) |
| U9 | `kyuubi-common/.../KyuubiConf.scala` `FRONTEND_REST_UI_ENABLED` (dòng 667) | Giữ entry, thêm vào danh sách `deprecatedConfigs` với thông điệp "Web UI has been removed; this key has no effect". Không xóa để config cũ không gây lỗi |
| U10 | `.rat-excludes:50-51` (`**/web-ui/dist/**`, `**/web-ui/coverage/**`) | Xóa |
| U11 | `AGENTS.md` mục "### Web UI" (và câu nhắc BFF/`KYUUBI_SESSION` nếu làm 2b); `README.md` cờ `--web-ui` trong lệnh `build/dist` | Cập nhật |
| U12 | `kyuubi-server/src/test/scala/org/apache/kyuubi/server/ui/JettyUtilsSuite.scala` | Giữ nếu chỉ test `JettyUtils` chung; sửa phần test static `/ui` nếu có |
| U13 | `dev/reformat`, `.gitattributes`, `.dockerignore` | `grep -n web-ui` → hiện không có gì; xác nhận lại |

Kiểm 2a:

```bash
grep -rn "web-ui\|/ui\b\|installWebUI\|EngineUIProxy\|FRONTEND_REST_UI_ENABLED" --include=*.scala --include=*.xml --include=Dockerfile* --include=*.sh . | grep -v "deprecatedConfigs\|docs/plans"   # rỗng
build/mvn -pl kyuubi-server -am -DskipTests package      # xanh, không còn bước pnpm
./build/dist --tgz --spark-provided --flink-provided --hive-provided -Dspark.version=3.5.5 -Dhive.version=3.1.3   # không còn --web-ui
# container từ image mới:
curl -s localhost:10099/            # 404 (không còn redirect /ui)
curl -s localhost:10099/api/v1/ping # pong (sau khi auth)
curl -s localhost:10099/api/v1/openapi | head -c 100   # swagger còn
```

### 2.3 Việc 2b: giữ BFF OIDC ở backend REST, gỡ phụ thuộc vào Web UI

**Giữ nguyên, không xóa**:

- REST endpoint `GET /api/v1/authentication/config`, `GET /api/v1/authentication/login`,
  `GET /api/v1/authentication/callback`, `POST /api/v1/authentication/logout`.
- Package `kyuubi-server/.../server/http/authentication/oidc/` (`OidcBffService`, `OidcSessionStore`,
  `TokenEndpointClient`, `OidcBffUtils`, `OidcBffConf`) và 3 suite test của nó.
- Nhánh cookie `KYUUBI_SESSION` trong `AuthenticationFilter`. Lý do: cookie chỉ là khóa trỏ tới phiên
  phía server; phiên đó giữ token Keycloak và refresh qua Keycloak. Bỏ nhánh này thì đăng nhập BFF
  không còn tác dụng. `AuthenticationFilter` cũng được `ThriftHttpServlet` dùng, nên **không sửa** gì
  trong filter ở plan này.
- Client Keycloak `kyuubi` (confidential) và redirect URI
  `http://<IP>:<port REST>/api/v1/authentication/callback`: **giữ**, vẫn là IP:port của REST.

**Phải sửa** (vì đang trỏ vào `/ui` đã bị gỡ ở 2a, để lại sẽ đưa người dùng tới 404):

| # | Ở đâu | Hiện tại | Sửa thành |
|---|---|---|---|
| B1 | `OidcBffUtils.scala:34` `DEFAULT_REDIRECT_PATH` | `"/ui/overview"` | đọc từ key mới `kyuubi.authentication.oidc.post.login.redirect` (đọc raw bằng `conf.getOption`, theo nguyên tắc fork); không đặt thì trả về `/api/v1/authentication/config` để client thấy `authenticated=true` |
| B2 | `ApiRootResource.scala:157` `postLogoutRedirect` | `${origin}/ui` | key mới `kyuubi.authentication.oidc.post.logout.redirect`; không đặt thì `${origin}/api/v1/authentication/config` |
| B3 | `OidcBffUtils.sanitizeRedirectPath` | chỉ nhận đường dẫn cục bộ | giữ nguyên quy tắc; B1/B2 là đường dẫn cục bộ hoặc URL đã khai trong config (không lấy từ query) |
| B4 | `authenticationConfig` | trả issuer, clientId, scope, flow, authenticated, username | **thêm** `thrift: { host, port }` (từ `kyuubi.frontend.advertised.host` hoặc host bind, và `kyuubi.frontend.thrift.binary.bind.port`) để client biết nối Thrift binary ở đâu. Không đổi trường cũ |
| B5 | Test | | cập nhật suite có assert `/ui`; thêm case cho B1/B2 có và không có key |
| B6 | `docs/security/oidc.md` | | ghi hai key mới và việc Web UI đã gỡ |

**Quan hệ với Thrift binary**: BFF không cấp token cho client (token nằm phía server, client chỉ có
cookie), còn Thrift binary cần client cầm token làm `password` (`thrift-binary-oidc.md`). Hai đường độc
lập: BFF phục vụ client HTTP cùng origin với REST; Thrift binary dùng token lấy thẳng từ Keycloak
(`client_credentials` cho dbt, hoặc PKCE qua một client public nếu sau này có client tương tác cần
Thrift). Plan này không thêm client public.

Kiểm 2b:

```bash
curl -s <rest>/api/v1/authentication/config | jq .   # có thrift.port, không có secret
# Đăng nhập qua trình duyệt: <rest>/api/v1/authentication/login -> Keycloak -> callback
#   -> kết thúc ở URL của B1 (không phải /ui), cookie KYUUBI_SESSION được đặt
curl -s -b "KYUUBI_SESSION=<cookie>" <rest>/api/v1/sessions   # 200 bằng cookie
grep -rn '"/ui' --include=*.scala kyuubi-server/src/main      # rỗng
```

R1–R3 và R5 của `thrift-binary-oidc.md` vẫn đạt. R4 đổi thành: đăng nhập BFF xong gọi REST bằng cookie
thành công (Web UI không còn để kiểm).

---

## Phần 3. Thư viện S3/AWS cho image **Kyuubi server**

### 3.1 Hiện trạng và lý do

- Image **Spark** (`Dockerfile.spark-py311`) đã tải `hadoop-aws-3.3.4`, `aws-java-sdk-bundle-1.12.262`,
  `iceberg-aws-bundle-1.10.0` → engine đọc/ghi S3 bình thường. **Không thiếu ở đây.**
- Image **Kyuubi server** (`docker/Dockerfile`) chỉ tải `mysql-connector-j`. Classpath của JVM server
  (`/opt/kyuubi/jars`) không có `hadoop-aws`, nên bất kỳ thao tác `s3a://` nào **do chính server** làm
  (upload resource của batch API `kyuubi.batch.resource.upload.path`, main resource engine đặt trên S3,
  store nào sau này đọc S3 bằng `KyuubiServer.getHadoopConf`) đều lỗi `ClassNotFoundException:
  org.apache.hadoop.fs.s3a.S3AFileSystem`. Spark trong `/opt/spark` (mount từ initContainer) không giúp
  được vì server không nạp jar của Spark vào classpath của mình.
- Chart production đã truyền `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`AWS_ENDPOINT_URL*` cho
  container server (`product-implement.md`), tức có ý định cho server chạm S3.

### 3.2 Phiên bản phải khớp **Hadoop của server**, không phải của Spark

Server phụ thuộc `hadoop-client-api/runtime` **3.3.6** (`pom.xml:159`), còn Spark image dùng Hadoop
3.3.4. `hadoop-aws` phải cùng version với `hadoop-client-runtime` trên classpath, nếu không lỗi
`NoSuchMethodError` lúc chạy.

| Jar | Version | Lý do |
|---|---|---|
| `org.apache.hadoop:hadoop-aws` | **3.3.6** | = `hadoop.version` của server |
| `com.amazonaws:aws-java-sdk-bundle` | **1.12.367** | version Hadoop 3.3.6 pin trong POM của `hadoop-aws` 3.3.6 (kiểm lại bằng `mvn dependency:tree` trước khi chốt) |

Không thêm `iceberg-aws-bundle`: server không chạy Iceberg.

### 3.3 Cách làm: thêm vào `docker/Dockerfile`, ngay sau khối `mysql-connector-j`

```dockerfile
ARG MAVEN_MIRROR=https://repo1.maven.org/maven2
ARG INCLUDE_AWS=true
ARG HADOOP_AWS_VERSION=3.3.6
ARG AWS_SDK_BUNDLE_VERSION=1.12.367
RUN if [ "${INCLUDE_AWS}" = "true" ]; then \
      curl -fSL -o ${KYUUBI_HOME}/jars/hadoop-aws-${HADOOP_AWS_VERSION}.jar \
        ${MAVEN_MIRROR}/org/apache/hadoop/hadoop-aws/${HADOOP_AWS_VERSION}/hadoop-aws-${HADOOP_AWS_VERSION}.jar && \
      curl -fSL -o ${KYUUBI_HOME}/jars/aws-java-sdk-bundle-${AWS_SDK_BUNDLE_VERSION}.jar \
        ${MAVEN_MIRROR}/com/amazonaws/aws-java-sdk-bundle/${AWS_SDK_BUNDLE_VERSION}/aws-java-sdk-bundle-${AWS_SDK_BUNDLE_VERSION}.jar ; \
    fi
```

- `MAVEN_MIRROR` để trỏ sang Nexus nội bộ (môi trường đang dùng Nexus cho pip; Maven Central có thể bị
  chặn). Truyền qua `docker-image-tool.sh -b MAVEN_MIRROR=http://<nexus>/repository/maven-public`.
- `INCLUDE_AWS=false` cho image không cần S3.
- Thêm `sha256sum -c` cho hai jar (lấy checksum từ Maven Central `.sha256`) để build không âm thầm nhận
  jar hỏng.
- `README.md`: thêm hai build-arg vào ví dụ lệnh build.

Phương án thay thế (không chọn): thêm dependency `hadoop-aws` vào `kyuubi-server/pom.xml` qua profile.
Không chọn vì làm `build/dist` nặng thêm ~280 MB cho mọi người dùng dist, và image server là nơi duy nhất cần nó.

### 3.4 Kiểm

```bash
docker run --rm <image> ls /opt/kyuubi/jars | grep -E "hadoop-aws|aws-java-sdk-bundle"
docker run --rm <image> bash -c 'java -cp "/opt/kyuubi/jars/*" org.apache.hadoop.fs.s3a.S3AFileSystem 2>&1 | head -1'
#   kỳ vọng: không phải ClassNotFoundException (lỗi "no main method" là chấp nhận được)
# Trên cụm thử nghiệm: bật kyuubi.batch.resource.upload.path=s3a://<bucket>/kyuubi-upload,
# POST /api/v1/batches với resource upload → object xuất hiện trong bucket.
```

---

## Thứ tự và tiêu chí xong

| Bước | Việc | Xong khi |
|---|---|---|
| 1 | Phần 1 (C0–C11, M1, M2) | 1.6 đạt; message đề xuất "Sync OIDC core/jdbc/engine sources from kyuubi-custom" |
| 2 | Phần 2a (U1–U13) | 2.2 kiểm đạt; message đề xuất "Remove Kyuubi Web UI" |
| 3 | Phần 2b | 2.3 kiểm đạt; message đề xuất "Point OIDC BFF redirects away from removed Web UI" |
| 4 | Phần 3 | 3.4 đạt; message đề xuất "Add hadoop-aws to server image" |
| 5 | `dev/gen/gen_all_config_docs.sh` sinh lại `settings.md`; `dev/reformat` | không còn diff lạ |

Commit message kết thúc bằng:

```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
