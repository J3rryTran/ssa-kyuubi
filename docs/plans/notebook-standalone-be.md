Đã bị thay thế bởi `remove-notebook-sql-editor.md`.

# Plan: tách Notebook BE thành service riêng, nối Kyuubi qua Thrift Binary để chạy PySpark

Trạng thái: chưa thực hiện. Đối tượng thực thi: AI agent. Phạm vi: module Maven mới
`kyuubi-notebook-server`, `kyuubi-server/web-ui`, `docker/`, `charts/`.

---

## 0. Bối cảnh — đọc trước khi sửa bất cứ dòng nào

Notebook hiện là subsystem **in-process** của `kyuubi-server`, 8.309 LOC Scala:

| Gói | LOC | Tách được? |
|---|---|---|
| `notebook/store/` (JDBC 1475, FileSystem 691, Records 307, trait 202) | 2.675 | Được nguyên vẹn |
| `notebook/service/` (Document 682, Execution 508, Session 476, Content 279, …) | 2.300 | Được nguyên vẹn |
| `notebook/api/` (models, enums, errors, views, requests) | 1.056 | Được nguyên vẹn |
| `notebook/runtime/` (PySpark 381, KyuubiSql 331, trait 136, …) | 983 | **Phải viết lại tầng gọi backend** |
| `notebook/routing/` | 464 | Được, nhưng đổi ý nghĩa (xem §6) |
| `notebook/NotebookManager.scala` + `NotebookConf.scala` | 437 | Sửa composition root |
| `server/api/v1/Notebook*Resource.scala` + `NotebookApiSupport` | 816 | Sửa `fe` → context mới |

Điểm nối duy nhất với phần còn lại của Kyuubi là hai lambda truyền vào `NotebookManager`
([NotebookManager.scala:43](../../kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/NotebookManager.scala)):

```scala
class NotebookManager(backendService: () => BackendService, instanceUri: () => String)
```

`backendService` được dùng **chỉ** trong 2 adapter. Đây chính là đường nối (seam) cho plan này.

### 0.1 Toàn bộ bề mặt BackendService mà notebook dùng

Liệt kê đầy đủ (grep `backendService()\.` trong `runtime/`), và ánh xạ sang Thrift TCLIService:

| Gọi hiện tại | File:dòng | Thrift tương đương | Rủi ro |
|---|---|---|---|
| `openSession(protoV11, user, "", ip, confMap)` | PySpark:74, Sql:68 | `TOpenSession` | Thấp — conf map truyền được nguyên vẹn |
| `executeStatement(h, sql, overlay, runAsync=true, timeout)` | PySpark:101, Sql:101 | `TExecuteStatement` + `confOverlay` | Thấp |
| `getOperationStatus(h, None)` | PySpark:125, Sql:125 | `TGetOperationStatus` | **Cao — xem §0.2** |
| `fetchResults(h, FETCH_FIRST, n, fetchLog=false)` | PySpark:174, Sql:239 | `TFetchResults` fetchType=0 | Thấp |
| `getResultSetMetadata(h)` | Sql:204, 246 | `TGetResultSetMetadata` | Thấp |
| `cancelOperation(h)` / `closeOperation(h)` / `closeSession(h)` | cả hai | `TCancelOperation` / `TCloseOperation` / `TCloseSession` | Thấp |
| `sessionManager.operationManager.getOperationLogRowSet(h, FETCH_FIRST, n)` | PySpark:183, Sql:183 | `TFetchResults` **fetchType=1** | Trung bình — ngữ nghĩa offset khác |
| `sessionManager.getSession(SessionHandle)` — probe liveness | PySpark:82, Sql:82 | **không có** → dùng `TGetInfo(CLI_SERVER_NAME)` làm ping | Trung bình |
| `sessionManager.operationManager.getOperation(h)` | Sql:294 | **không có** → suy ra từ `TGetOperationStatus` | Trung bình |

Kết luận: **7/9 ánh xạ 1-1**, 2 cái phải thay bằng hành vi tương đương. Không có cái nào chặn đứng.

### 0.2 Rủi ro số 1 phải spike trước khi code

`PySparkResponseCodec.errorContent` moi JSON của python worker ra khỏi **message của exception**
(`status.exception.map(_.getMessage)`), tìm `{`, parse `response.content.ename/evalue/traceback`
([PySparkRuntimeAdapter.scala:289-301](../../kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/runtime/PySparkRuntimeAdapter.scala)).

Qua in-process, đó là `Throwable` nguyên vẹn. Qua Thrift, lỗi về dưới dạng
`TGetOperationStatusResp.errorMessage` + `infoMessages` (stack trace đã bị serialize thành
`List[String]`). **Phải xác minh bằng thực nghiệm** rằng chuỗi JSON còn nguyên, không bị cắt.
Nếu bị cắt → traceback trong cell mất, chỉ còn 1 dòng. Đây là tiêu chí Go/No-Go của Phase 0.

---

## Phase 0 — Spike xác minh (BẮT BUỘC, không được bỏ qua)

Mục tiêu: chứng minh Thrift binary chuyển tải đủ cho notebook **trước khi** viết module.

### Task 0.1 — Spike PySpark qua beeline
Dựng Kyuubi + Spark engine hiện có. Chạy:

```bash
$KYUUBI_HOME/bin/beeline -u "jdbc:kyuubi://<host>:10009/;#kyuubi.engine.spark.output.mode=NOTEBOOK" \
  -n <user> -e "set kyuubi.operation.language=PYTHON"
```

Rồi trong phiên đó chạy lần lượt và ghi lại **chuỗi thô** trả về:
1. `print("hello")` — kỳ vọng: 1 hàng, 1 cột, giá trị là mime bundle JSON có `text/plain`.
2. `import pandas as pd; pd.DataFrame({"a":[1]})` — kỳ vọng bundle có `text/html`.
3. `1/0` — **quan trọng nhất**: ghi lại nguyên văn `errorMessage` nhận được. Kiểm tra có chứa
   `"ename"`, `"evalue"`, `"traceback"` hay không.

### Task 0.2 — Go/No-Go
- Nếu 0.1.3 giữ nguyên JSON → tiếp Phase 1.
- Nếu bị cắt/mất → **dừng**, báo lại. Phương án B: sửa engine
  (`externals/kyuubi-spark-sql-engine`) để python error trả về như một *hàng kết quả thành công*
  có cột `error_bundle`, thay vì ném exception. Việc đó mở rộng phạm vi sang engine, phải quyết
  riêng.

### Task 0.3 — Spike `kyuubi.engine.spark.output.mode` qua TOpenSession
Comment trong `startRuntime` nói rõ: output mode **phải** đặt ở session conf, confOverlay của
operation bị bỏ qua. Xác minh conf map của `TOpenSession` (tức phần `?k=v` / `#k=v` trong JDBC URL)
thực sự tới được `SparkOperation.outputMode`. Nếu không, mọi cell python sẽ trả text thường và
rich output chết im lặng.

### Task 0.4 — Spike operation log qua fetchType=1
Xác minh `TFetchResults(fetchType=1, FETCH_FIRST, n)` trả log. Ghi nhận: nó trả về **toàn bộ log
từ đầu** hay chỉ phần chưa đọc. `fetchLogs` hiện tại giả định "từ đầu" rồi tự `drop(offset)`
(PySparkRuntimeAdapter.scala:183-191) — nếu Thrift có con trỏ riêng thì phải đổi thuật toán, nếu
không log sẽ nhảy cóc.

**Deliverable Phase 0**: file `docs/plans/notebook-standalone-spike.md` ghi nguyên văn output của
4 task, kèm kết luận Go/No-Go.

---

## Phase 1 — Module `kyuubi-notebook-server`

### Task 1.1 — Tạo module
`kyuubi-notebook-server/pom.xml`, thêm `<module>` vào `pom.xml` gốc (cạnh `kyuubi-server`, dòng 85).

Dependencies — **không** phụ thuộc `kyuubi-server`:
```xml
kyuubi-common        (KyuubiConf, Logging, Utils, ThreadUtils, JdbcUtils)
kyuubi-ha            (DiscoveryClient — để tìm Kyuubi instance và cho session registry)
kyuubi-hive-jdbc     (client Thrift binary)
kyuubi-relocated-hive-service-rpc  (TCLIService, dùng trực tiếp thay vì qua JDBC)
kyuubi-events, kyuubi-metrics
jetty + jersey + jackson  (sao danh sách từ kyuubi-server/pom.xml)
```

### Task 1.2 — Di chuyển code nguyên trạng
`git mv` các gói sau, đổi package `org.apache.kyuubi.server.notebook` → `org.apache.kyuubi.notebook`:

- `notebook/api/`, `notebook/service/`, `notebook/store/`, `notebook/routing/`
- `notebook/NotebookConf.scala`, `notebook/NotebookManager.scala`
- `notebook/runtime/` **trừ** phần gọi BackendService (Task 1.4)
- `api/v1/Notebooks*.scala`, `NotebookFoldersResource`, `NotebookCollectionResources`,
  `NotebookRuntimeResources`, `NotebookApiSupport`

Giữ nguyên tên class và cấu trúc thư mục. Mọi thay đổi logic ở bước này là lỗi.

### Task 1.3 — `KyuubiThriftBackend`: client Thrift binary
File mới `notebook/kyuubi/KyuubiThriftBackend.scala`. Đây là trái tim của plan.

```scala
/** Session/operation handle phía Kyuubi, nhìn từ xa. Thay cho SessionHandle/OperationHandle. */
case class RemoteSession(handle: TSessionHandle, instance: String)
case class RemoteOperation(handle: TOperationHandle)

trait KyuubiThriftBackend {
  def openSession(user: String, conf: Map[String, String]): RemoteSession
  def pingSession(s: RemoteSession): Boolean          // TGetInfo
  def closeSession(s: RemoteSession): Unit
  def executeStatement(s: RemoteSession, stmt: String,
                       overlay: Map[String, String]): RemoteOperation
  def getOperationStatus(op: RemoteOperation): RemoteOperationStatus
  def getResultSetMetadata(op: RemoteOperation): TTableSchema
  def fetchResults(op: RemoteOperation, orientation: TFetchOrientation,
                   maxRows: Int): TRowSet
  def fetchLogs(op: RemoteOperation, maxRows: Int): TRowSet   // fetchType = 1
  def cancelOperation(op: RemoteOperation): Unit
  def closeOperation(op: RemoteOperation): Unit
}
```

Yêu cầu bắt buộc:
- `RemoteOperationStatus` phải giữ **nguyên văn** `errorMessage` + `infoMessages` để
  `PySparkResponseCodec` còn parse được (xem §0.2). Không được rút gọn.
- Handle phải **serialize được thành String** để cất vào `NotebookRuntime.internalRuntimeHandle`
  và `CellExecution.internalOperationHandle` (cột DB, kiểu String, không được đổi schema).
  Dùng `TSessionHandle.sessionId.guid/secret` base64. Viết `encode`/`decode` + unit test round-trip.
- Mỗi `RemoteSession` giữ **một** `TCLIService.Client` + `TTransport` riêng, không share socket
  giữa các session (Thrift client không thread-safe). Pool theo session, đóng khi `closeSession`.
- Timeout socket đọc phải **lớn hơn** thời gian chạy cell dài nhất, hoặc dùng `runAsync=true`
  (code hiện đã truyền `runAsync = true`) rồi poll `getOperationStatus` — giữ đúng mô hình này.

### Task 1.4 — Viết lại 2 adapter
`PySparkRuntimeAdapter` và `KyuubiSqlRuntimeAdapter`: đổi constructor từ
`backendService: () => BackendService` sang `backend: KyuubiThriftBackend`, thay từng call theo
bảng §0.1. **Không** đổi `NotebookRuntimeAdapter` trait, không đổi `PySparkResponseCodec`,
không đổi `ThriftRowSetConverter` (nó vốn đã làm việc trên `TRowSet`).

Hai chỗ không ánh xạ 1-1:
- `getRuntimeStatus`: thay `sessionManager.getSession` bằng `backend.pingSession`. Ping thất bại →
  `RuntimeState.LOST` (giữ đúng ngữ nghĩa cũ).
- `KyuubiSqlRuntimeAdapter:294` `getOperation(...)`: đọc kỹ nó dùng để làm gì rồi thay bằng
  `getOperationStatus`. Nếu nó cần thứ không có trong status → ghi lại và báo.

### Task 1.5 — Resolve Kyuubi instance
`notebook/kyuubi/KyuubiDiscovery.scala`: chọn instance Kyuubi để mở session.

- Nếu `kyuubi.ha.addresses` có → dùng `DiscoveryClient` của `kyuubi-ha` lấy danh sách node
  trong `kyuubi.ha.namespace`, chọn theo round-robin.
- Nếu không → đọc `kyuubi.notebook.kyuubi.thrift.addresses` (danh sách `host:port`).
- **Pin session vào instance**: `RemoteSession.instance` lưu lại địa chỉ đã dùng; mọi thao tác
  trên session/operation của nó phải đi lại đúng instance đó (Kyuubi giữ session trong JVM).
  Lưu `instance` vào `NotebookRuntime` (cột `kyuubiInstance` đã có sẵn trong store — tái dùng).

### Task 1.6 — Composition root mới
`NotebookServer.scala` + `NotebookRestFrontendService.scala`: dựng Jetty + Jersey, đăng ký đúng
các resource notebook, `AuthenticationFilter`, `NotebookRoutingFilter`, `NotebookExceptionMapper`.
Sao khung từ `KyuubiRestFrontendService.startInternal()` (dòng 128-150) nhưng bỏ batch, admin,
sessions, operations, engine-ui proxy.

`NotebookManager` đổi constructor: `(backend: KyuubiThriftBackend, instanceUri: () => String)`.

### Task 1.7 — Xóa khỏi `kyuubi-server`
Gỡ `notebookManager` khỏi `KyuubiRestFrontendService`, gỡ `NotebookRoutingFilter`, gỡ các resource
notebook khỏi `ApiRootResource`. `kyuubi-server` sau bước này **không còn** biết gì về notebook.

Thêm `kyuubi.notebook.enabled` deprecated warning: nếu ai bật nó trên kyuubi-server, log cảnh báo
chỉ sang service mới.

---

## Phase 2 — Xác thực và danh tính (phần dễ làm sai nhất)

### Task 2.1 — Auth phía vào (người dùng → notebook BE)
`NotebookApiSupport.principal` hiện lấy từ `fe.getRealUser()` + `fe.isAdministrator(user)`.
Trong service mới, `fe` không còn. Phải:
- Tái dùng `AuthenticationFilter` + `KyuubiHttpAuthenticationFactory` từ `kyuubi-common`/
  `kyuubi-server/http` — nếu chúng nằm trong `kyuubi-server`, **di chuyển xuống `kyuubi-common`**
  (hoặc module `kyuubi-http-auth` mới) chứ không copy.
- OIDC: `extensions/server/kyuubi-oidc-auth` phải nạp được vào notebook BE y như vào kyuubi-server.
- `isAdministrator`: đọc `kyuubi.server.administrators` trực tiếp từ conf.

### Task 2.2 — Impersonation phía ra (notebook BE → Kyuubi) — ⚠ RANH GIỚI ĐẶC QUYỀN
Hiện `openSession(..., runtime.owner, ...)` tin tưởng tuyệt đối vì cùng JVM. Qua Thrift, notebook BE
là **một client như mọi client khác** — không thể tự xưng là user khác trừ khi được cấu hình làm
proxy user.

Phải làm:
1. Notebook BE xác thực với Kyuubi bằng identity riêng của nó (Kerberos keytab, hoặc `LDAP`,
   hoặc `kyuubi.authentication=NONE` nếu mạng đã đóng — ghi rõ lựa chọn vào values.yaml).
2. Truyền user thật qua `hive.server2.proxy.user=<owner>` trong conf của `TOpenSession`.
3. Cấu hình phía Kyuubi cho phép: `hadoop.proxyuser.<notebook-principal>.users=*` và
   `hadoop.proxyuser.<notebook-principal>.hosts=<cidr của notebook pod>`.

**Hệ quả bảo mật phải ghi vào plan và nói với người vận hành**: notebook BE trở thành superuser
của Kyuubi. Một lỗ hổng trong tầng auth của nó = mạo danh được mọi user. Trước đây rủi ro này
không tồn tại vì không có ranh giới mạng nào để vượt. Bù lại: `hosts` CIDR và NetworkPolicy phải
được siết (Task 4.4).

---

## Phase 3 — Web UI

### Task 3.1 — Quyết định cách phục vụ UI
Web UI hiện là SPA Vue build bởi `kyuubi-server/web-ui`, gọi API với `baseURL: '/'`
([utils/request.ts:23](../../kyuubi-server/web-ui/src/utils/request.ts)), tức **cùng origin**.
Phần notebook của UI đã có sẵn: `src/views/notebook/`, `src/api/notebook/`, `src/router/notebook/`.

Phương án chọn: **notebook BE tự phục vụ SPA**. Lý do: giữ `baseURL: '/'`, không CORS, không
preflight, cookie session không phải cross-site (`SameSite` không cần nới) — tức không đổi một
dòng nào trong `request.ts`.

### Task 3.2 — Sidebar và router
`src/layout/components/aside/types.ts` đang liệt kê notebook trong menu chung. Khi tách:
- Nếu chạy 2 UI riêng: gỡ mục notebook khỏi menu của kyuubi-server UI, build riêng bundle notebook.
- Nếu muốn một UI duy nhất: giữ menu, nhưng mục notebook trỏ sang URL tuyệt đối của notebook BE
  (`import.meta.env.VITE_NOTEBOOK_BASE_URL`). Khi đó cần SSO chung — chỉ khả thi nếu đã dùng OIDC.

Agent phải **hỏi lại** trước khi chọn, vì hai hướng này không đảo ngược rẻ.

### Task 3.3 — Build
`scripts/verify-notebook-css.mjs` (đã được gắn vào `npm run build`) phải vẫn chạy. Nếu tách bundle,
sửa script cho đúng đường dẫn mới.

---

## Phase 4 — Image và Helm chart

### Task 4.1 — `docker/notebook/Dockerfile`
Image notebook BE **không cần Spark, không cần Hadoop binary** — nó không spark-submit, chỉ nói
Thrift. Đây là lợi ích thực tế của việc tách: image nhỏ hơn hẳn `docker/Dockerfile`.

```dockerfile
ARG BASE_IMAGE=eclipse-temurin:17-jre-focal
FROM ${BASE_IMAGE}

ARG notebook_uid=10010
ENV NOTEBOOK_HOME=/opt/kyuubi-notebook \
    NOTEBOOK_LOG_DIR=/opt/kyuubi-notebook/logs \
    NOTEBOOK_CONF_DIR=/opt/kyuubi-notebook/conf

RUN set -ex && \
    apt-get update && \
    apt-get install -y --no-install-recommends bash tini procps ca-certificates krb5-user && \
    useradd -u ${notebook_uid} -g root notebook -d /home/notebook -m && \
    mkdir -p ${NOTEBOOK_HOME}/jars ${NOTEBOOK_LOG_DIR} ${NOTEBOOK_CONF_DIR} && \
    rm -rf /var/lib/apt/lists/*

COPY jars          ${NOTEBOOK_HOME}/jars
COPY web-ui/dist   ${NOTEBOOK_HOME}/web-ui
COPY bin           ${NOTEBOOK_HOME}/bin
COPY conf          ${NOTEBOOK_CONF_DIR}

RUN chmod +x ${NOTEBOOK_HOME}/bin/* && chown -R ${notebook_uid}:root ${NOTEBOOK_HOME}

USER ${notebook_uid}
WORKDIR ${NOTEBOOK_HOME}
EXPOSE 10099
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/kyuubi-notebook/bin/notebook-server"]
```

`bin/notebook-server`: script bash gọi `java -cp "$NOTEBOOK_HOME/jars/*"
org.apache.kyuubi.notebook.NotebookServer`. Mẫu: `bin/kyuubi` hiện có.

Thêm target vào `build/dist` để sinh `dist/notebook/{jars,bin,conf,web-ui}`.

### Task 4.2 — conf mẫu `conf/notebook-defaults.conf`
```properties
# Nối tới Kyuubi qua Thrift binary
kyuubi.notebook.kyuubi.thrift.addresses       kyuubi.<ns>.svc.cluster.local:10009
kyuubi.notebook.kyuubi.thrift.auth            NONE
# hoặc: KERBEROS + kyuubi.notebook.kyuubi.thrift.principal / .keytab
kyuubi.notebook.kyuubi.proxy.user.enabled     true
kyuubi.notebook.kyuubi.socket.timeout         PT30S
kyuubi.notebook.kyuubi.login.timeout          PT20S

# Conf gắn vào mọi session PySpark — BẮT BUỘC, xem Task 0.3
kyuubi.engine.spark.output.mode               NOTEBOOK
kyuubi.engine.type                            SPARK_SQL
spark.kubernetes.container.image              <registry>/spark-py311:<tag>

# HTTP
kyuubi.frontend.rest.bind.port                10099
kyuubi.notebook.enabled                       true

# Lưu trữ — nhiều replica thì phải FileSystemNotebookStore hoặc JDBC chung
kyuubi.notebook.store.class    org.apache.kyuubi.notebook.store.JDBCNotebookStore
kyuubi.metadata.store.jdbc.url                jdbc:postgresql://<host>:5432/kyuubi
```

Ghi chú bắt buộc trong file: image PySpark là `docker/python-for-notebook/Dockerfile.spark-py311`
đã có sẵn — package Python của notebook đến từ image **đó**, không phải image notebook BE
(xem `limits = Map("packagesSource" -> "spark-image")` trong `PySparkRuntimeAdapter`).

### Task 4.3 — Chart
Thêm vào `charts/kyuubi/templates/`: `notebook-deployment.yaml`, `notebook-service.yaml`,
`notebook-configmap.yaml`, `notebook-serviceaccount.yaml`, gate bằng `.Values.notebook.enabled`.

Notebook BE **không cần RBAC pod nào cả** — nó không tạo pod. ServiceAccount của nó không gắn
Role. Điều này ăn khớp với [plan RBAC](rbac-spark-kyuubi.md): thêm identity thứ ba, đặc quyền
bằng không.

Dùng `Deployment` chứ không `StatefulSet` — session affinity đã do `NotebookSessionRegistry` +
ZooKeeper lo, không cần định danh pod ổn định.

### Task 4.4 — NetworkPolicy
Vì Task 2.2 biến notebook BE thành proxy superuser, thêm NetworkPolicy: chỉ notebook pod được nối
port 10009 của Kyuubi bằng identity proxy. Ghi vào `values.yaml` dưới `notebook.networkPolicy`.

---

## Phase 5 — Test

| Mức | Nội dung |
|---|---|
| Unit | `KyuubiThriftBackend` handle encode/decode round-trip; `PySparkResponseCodec` với **output Thrift thật ghi lại từ Phase 0** (không dùng fixture bịa) |
| Unit | Toàn bộ test hiện có của `notebook/service/` và `notebook/store/` phải pass nguyên trạng sau khi `git mv` — nếu phải sửa, nghĩa là Task 1.2 đã làm sai |
| Integration | Mở session → chạy `print("hi")` → đọc output; chạy `1/0` → kiểm traceback; chạy cell dài → cancel; restart runtime → kiểm biến đã mất |
| Integration | Giết pod Kyuubi giữa lúc chạy → kỳ vọng `RUNTIME_LOST` chứ không treo |
| E2E | 2 replica notebook BE + ZK: mở session ở replica A, gọi API qua replica B → `NotebookRoutingFilter` forward đúng |

---

## §6. Những thứ plan này KHÔNG giải quyết — phải nói rõ với người dùng

1. **Routing vẫn còn, và phức tạp hơn.** Trước: 1 lớp affinity (session ↔ kyuubi instance). Sau:
   2 lớp (user ↔ notebook replica, và notebook replica ↔ kyuubi instance). `NotebookRoutingFilter`
   giữ nguyên nhưng giờ phải đúng ở cả hai tầng.
2. **Thêm một network hop vào mọi poll.** UI poll trạng thái cell liên tục; mỗi poll trước đây là
   một method call, giờ là một RTT Thrift. Cell chạy 100ms sẽ thấy rõ. Cần đo.
3. **Mất khả năng đọc operation log in-memory.** `getOperationLogRowSet` truy cập trực tiếp
   `operationManager`; Thrift chỉ cho `fetchType=1` với ngữ nghĩa khác (Task 0.4).
4. **Không giảm được coupling schema.** Notebook store vẫn ngồi chung DB với
   `kyuubi.metadata.store.jdbc.*`. Tách DB là một plan khác.
