# Phase D — Kubernetes DBT Runner, proxy-user và lifecycle run

## Trạng thái

**Đã triển khai demo slice:** Kubernetes Job runner, ConfigMap project resolver,
rendered dbt profile từ Engine Profile snapshot, status/log/cancel API,
reconciler và stack PostgreSQL/Helm demo tách riêng. Runner vẫn tắt mặc định.
Phần production (Secret-backed credential, Git resolver immutable, persistent
log artifact, NetworkPolicy và non-wildcard proxy allowlist) cần được triển
khai sau khi company cung cấp hạ tầng/security policy.

Phase C đã hoàn thành contract bền vững cho DBT Workspace, Job, Run và
Engine Profile snapshot. Endpoint run hiện cố ý trả
`DBT_RUNNER_NOT_CONFIGURED`. Phase D thay `DisabledDbtRunner` bằng runner
thật chạy trong Kubernetes; không dựng DBT Studio UI.

## 1. Mục tiêu

Khi owner bấm Preview hoặc Run một DBT job, Kyuubi server phải tạo một
Kubernetes Job chạy process `dbt`. Runner kết nối Kyuubi Thrift bằng identity
dịch vụ được tin cậy và proxy sang đúng owner của run. Vì vậy DBT, Notebook và
SQL Editor của cùng user, cùng Engine Profile có cùng engine key:

```text
effective Kyuubi user + profile subdomain + SPARK_SQL
```

Nếu Spark engine còn sống, DBT phải reuse engine mà Notebook/SQL Editor đang
dùng; không tạo một engine mang user cố định `dbt` hoặc `dbt-runner`.

MVP Phase D hỗ trợ:

- `PREVIEW` → `dbt show --select <selector>`;
- `RUN_MODEL` → `dbt run --select <selector>`;
- `RUN_PROJECT` → `dbt run`;
- trạng thái, log có phân trang offset và cancel;
- project source lấy từ registry server-controlled;
- một Kubernetes Job riêng cho mỗi DBT run.

Không thuộc Phase D: UI DBT Studio, scheduling/cron, retry tự động, `dbt
test/build`, Git UI, chỉnh sửa source trên browser, hay terminate shared Spark
engine khi cancel DBT run.

## 2. Điều kiện bắt buộc trước khi code

### 2.1. Metadata store dùng chung

Hai replica Kyuubi phải dùng JDBC metadata database chung (MySQL hoặc
PostgreSQL). SQLite file cục bộ trong từng pod không đủ an toàn cho runner,
vì pod `kyuubi-0` có thể submit Job còn API status/log được xử lý bởi
`kyuubi-1`.

### 2.2. Proxy-user được security review và cấu hình ở Kyuubi/Hadoop

Runner không được đăng nhập Kyuubi bằng username của browser user. Nó dùng
service identity, ví dụ `dbt-runner`, với credential đặt trong Kubernetes
Secret. Trong lúc mở Thrift session, runner gửi:

```text
authenticated user:        dbt-runner
hive.server2.proxy.user:   alice
```

Kyuubi/Hadoop phải allow impersonation **chỉ** từ identity này, ví dụ các
property triển khai do platform quản lý:

```text
hadoop.proxyuser.dbt-runner.hosts=<CIDR/DNS của runner pods>
hadoop.proxyuser.dbt-runner.groups=<các group được phép>
```

Không dùng `hosts=*` hoặc `groups=*` trong production. Cần test từ runner
rằng session/engine thực sự mang `user=alice`, và request với user không được
allow bị từ chối. Với test cluster `authentication=NONE`, có thể dùng
`dbt-runner` và allowlist test để xác nhận wire flow; đó không thay thế auth
production.

### 2.3. Registry project server-controlled

`DbtWorkspace.projectRef` hiện là opaque ID. Phase D chỉ resolve ID qua
`DbtProjectResolver` ở backend. Browser không được gửi Git URL, branch, SSH
key, filesystem path hoặc credential khi bấm Run.

MVP nên dùng registry cấu hình server-side:

```text
projectRef=phase-c-demo
  -> Git URL allowlisted + immutable revision + credential Secret reference
```

Cho integration test, registry có thể map tới một public read-only Git repo
test hoặc ConfigMap source nhỏ. Production dùng Git repository/revision đã
được platform quản lý; credential Git chỉ là Secret reference, không lưu trong
workspace/job/run database.

## 3. Luồng thực thi đích

```text
Browser
  │ POST /dbt-jobs/{id}:run  (không có Spark config)
  ▼
DbtWorkspaceService
  │ authorize owner → snapshotForUse(profile, caller)
  │ tạo DbtJobRun immutable: QUEUED, effectiveUser=alice
  ▼
KubernetesJobDbtRunner
  │ resolve projectRef từ registry
  │ tạo ConfigMap profiles.yml không chứa secret
  │ tạo Kubernetes Job có labels dbt-run-id / workspace-id
  ▼
DBT runner pod
  │ init container: checkout immutable DBT project vào emptyDir
  │ main container: dbt show/run, profiles.yml tạm, credential từ Secret
  │ Thrift OpenSession user=dbt-runner + hive.server2.proxy.user=alice
  │ server_side_parameters = immutable EngineProfileSnapshot
  ▼
Kyuubi
  │ xác thực dbt-runner, kiểm tra Hadoop proxy allowlist
  │ effective session user=alice, subdomain=profile snapshot
  ▼
Spark engine key: USER/alice/<subdomain>/SPARK_SQL
  │
  └─ reuse engine Notebook / SQL Editor của Alice nếu engine còn sống
```

`DbtJobRun.profile` là snapshot từ Phase C, nên profile được sửa sau khi Run
đã bấm không thể thay subdomain hoặc resource của run đó.

## 4. Thay đổi domain và persistence

### 4.1. Mở rộng runner port, không để REST gọi Kubernetes trực tiếp

Mở rộng `DbtRunner` thành interface đủ cho lifecycle:

```scala
trait DbtRunner {
  def submit(request: DbtRunRequest): DbtRunnerSubmission
  def status(runnerRunId: String): DbtRunnerStatus
  def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage
  def cancel(runnerRunId: String): Unit
}
```

`DbtRunnerStatus` mang state chuẩn, timestamps và error tóm tắt;
`DbtRunnerLogPage` mang `content`, `nextOffset`, `endOfStream`, `truncated`.
Offset là số byte UTF-8 của stream log đã chuẩn hóa. API phải reject offset âm
và giới hạn page size server-side.

`KubernetesJobDbtRunner` dùng Fabric8 Kubernetes client đã có trong dependency
của `kyuubi-server`; không thêm client riêng ở REST resource.

### 4.2. State transition và crash recovery

Phase C hiện cần điều chỉnh thứ tự dispatch để không mất audit record:

1. Trong transaction, tạo run `QUEUED`, snapshot và `effectiveKyuubiUser`.
2. Claim run atomically: `QUEUED -> SUBMITTED` với dispatch token/idempotency
   key của runner.
3. Runner tạo/đọc Kubernetes Job deterministic `kyuubi-dbt-<run-id-prefix>`.
4. Store ghi `runnerRunId`, Job UID/name. Nếu server chết giữa bước 2–4,
   reconciler tìm Job theo label `kyuubi.apache.org/dbt-run-id=<runId>` rồi
   gắn lại; nếu không có Job, nó retry submit hoặc đánh dấu `FAILED` theo
   policy rõ ràng.

Thêm vào run store các field tối thiểu (tên có thể điều chỉnh theo convention):

```text
runner_run_id, runner_job_name, runner_job_uid, dispatch_token,
state, started_at, finished_at, error_summary, state_updated_at
```

Thêm compare-and-set update state, query active/reconcilable runs và query run
theo `runner_run_id`. Không cho event cũ ghi đè terminal state.

State machine:

```text
QUEUED -> SUBMITTED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
QUEUED | SUBMITTED | RUNNING -> CANCELLING -> CANCELLED | FAILED
```

`DbtRunReconciler` thuộc lifecycle `NotebookManager`: chạy khi startup và theo
poll interval cấu hình. Nó đọc Kubernetes Job/Pod condition và cập nhật store
idempotently. Không cần giữ long-poll HTTP request của browser để run tiếp tục.

### 4.3. Log bền vững và retention

Không chỉ dùng `kubectl logs` trực tiếp: TTL dọn Job/Pod sẽ làm mất log và API
của replica khác không có offset bền vững. Runner/reconciler phải copy stdout
và stderr vào `dbt_job_run_log` (sequence, byte offset, content) hoặc artifact
store đã được platform cung cấp. MVP chọn DB table vì hệ thống hiện đã có
metadata JDBC chung.

- Lưu chunks có sequence/offset, giới hạn tổng bytes mỗi run bằng config.
- Khi vượt giới hạn, giữ phần đầu/cuối theo policy và trả `truncated=true`.
- Log endpoint chỉ trả log của run sau ownership check.
- TTL của Kubernetes Job chỉ chạy sau khi final log/artifact đã được thu thập.

## 5. Kubernetes implementation

### 5.1. Runner image

Thêm image riêng, ví dụ `docker/dbt-runner/Dockerfile`, pin version đã test:

```text
Python 3.12
dbt-core 1.12.0
dbt-spark 1.10.3
pyhive/thrift dependency tương thích
git + CA certificates
bootstrap script không dùng shell eval
```

Image không chứa Kyuubi password, Git deploy key hay cloud credential. Các
secret được mount/env lúc Job chạy. Build image, SBOM/license và scan đi theo
quy trình image của công ty trước production.

### 5.2. Job manifest do server tạo

Mỗi run tạo một `batch/v1 Job` cùng namespace với Kyuubi, có:

- `serviceAccountName: kyuubi-dbt-runner` riêng với Kyuubi server;
- labels: managed-by, DBT run ID, workspace ID, owner hash; không đưa username
  raw vào label nếu có thể vi phạm label charset;
- `backoffLimit: 0`, `restartPolicy: Never`, `activeDeadlineSeconds` và
  `ttlSecondsAfterFinished` có config;
- `securityContext`: non-root, read-only root filesystem khi image hỗ trợ,
  drop capabilities, resource requests/limits;
- `emptyDir` riêng cho project checkout và `profiles.yml` runtime;
- init container checkout commit đã pin từ resolver;
- ConfigMap per-run chứa `profiles.yml` đã render, không có password;
- Secret env/mount chứa credential cho Kyuubi service identity và Git (nếu
  cần); Secret name/key chỉ đến từ config registry server-side;
- network policy chỉ cho runner tới Kyuubi Thrift và Git/artifact endpoints
  cần thiết.

RBAC của **Kyuubi server** chỉ cần CRUD/read/watch Jobs, Pods, ConfigMaps có
label managed-by này trong namespace đã chốt. RBAC của runner pod không cần
quyền Kubernetes API trừ khi project thực sự yêu cầu. Cập nhật Helm chart và
`values-test-local.yaml` riêng cho cấu hình test, không cấp `cluster-admin`.

### 5.3. Render dbt command và profiles.yml

`DbtRunRequest` chỉ tạo ở server từ run snapshot. Runner nhận command bằng
Kubernetes `args` tách riêng, không build command shell từ selector:

| Action | args |
|---|---|
| `PREVIEW` | `dbt show --select <selector>` |
| `RUN_MODEL` | `dbt run --select <selector>` |
| `RUN_PROJECT` | `dbt run` |

`profiles.yml` được server render với YAML serializer an toàn. Nó dùng Thrift
internal endpoint (ví dụ `kyuubi-thrift-binary.kyuubi.svc:10009`), schema/
catalog cấu hình server-side, và:

```yaml
user: dbt-runner
password: "{{ env_var('KYUUBI_DBT_RUNNER_PASSWORD') }}"
server_side_parameters:
  hive.server2.proxy.user: alice
  kyuubi.engine.share.level.subdomain: <snapshot.subdomain>
  # chỉ merge snapshot.sparkConfig đã qua allowlist Phase B
```

Trước khi implementation được coi là hợp lệ, viết integration test xác minh
`dbt-spark` thật gửi `hive.server2.proxy.user` trong OpenSession. Nếu adapter
không truyền key này qua `server_side_parameters`, bootstrap phải dùng cơ chế
connection/session parameter mà Thrift adapter hỗ trợ; không được fallback
sang `user: alice`, vì như vậy runner sẽ cần credential của Alice.

Không bao giờ render raw config từ HTTP request, lưu password vào
`DbtJobRun`, ghi secret vào log, hoặc expose rendered `profiles.yml` qua API.

## 6. Service/API changes

`NotebookManager` là composition root:

- tạo `DbtProjectResolver`, `KubernetesJobDbtRunner`, `DbtRunReconciler` khi
  `kyuubi.dbt.runner.enabled=true`;
- inject runner vào `DbtWorkspaceService` hoặc runner registry, thay vì REST
  resource tự tạo runner;
- start/stop reconciler cùng service lifecycle;
- khi disabled giữ nguyên response `DBT_RUNNER_NOT_CONFIGURED` của Phase C.

Các API không đổi URL/payload tạo run. Thay đổi response/runtime behavior:

```text
POST /dbt-workspaces/{id}:preview  -> 201, DbtJobRun SUBMITTED/QUEUED
POST /dbt-jobs/{id}:run            -> 201, DbtJobRun SUBMITTED/QUEUED
GET  /dbt-job-runs/{id}            -> state mới nhất từ store
GET  /dbt-job-runs/{id}/logs       -> DbtRunnerLogPage
POST /dbt-job-runs/{id}:cancel     -> 202, state CANCELLING hoặc terminal idempotent
```

Cancel chỉ xóa/foreground-delete Kubernetes DBT Job rồi reconciles tới
`CANCELLED`. Nó không gọi terminate Kyuubi engine: engine có thể được Notebook
hoặc SQL Editor của user dùng chung. Nếu DBT query đang chạy, pod process bị
stop, Thrift session đóng và Kyuubi cancels operation theo lifecycle session.

## 7. Cấu hình đề xuất

Đăng ký trong một config class phù hợp (không dùng raw browser config):

```text
kyuubi.dbt.runner.enabled=false
kyuubi.dbt.runner.type=kubernetes
kyuubi.dbt.runner.kubernetes.namespace=kyuubi
kyuubi.dbt.runner.kubernetes.image=<registry>/kyuubi-dbt-runner:<tag>
kyuubi.dbt.runner.kubernetes.service-account=kyuubi-dbt-runner
kyuubi.dbt.runner.kubernetes.job-timeout=1h
kyuubi.dbt.runner.kubernetes.job-ttl=1h
kyuubi.dbt.runner.reconcile-interval=10s
kyuubi.dbt.runner.log-max-bytes=10m
kyuubi.dbt.runner.kyuubi.host=kyuubi-thrift-binary.kyuubi.svc
kyuubi.dbt.runner.kyuubi.port=10009
kyuubi.dbt.runner.kyuubi.credential-secret-ref=<server-configured-ref>
kyuubi.dbt.project-registry-ref=<server-configured-ref>
```

Validation fail-fast khi runner enabled nhưng image, namespace, service
account, credential reference, project registry hoặc Kyuubi endpoint thiếu.
Lỗi configuration xuất hiện lúc server khởi động; không để user tạo run rồi
mới phát hiện thiếu Secret.

## 8. Thứ tự triển khai

1. **Security/environment spike:** xác nhận Kyuubi auth, proxy-user policy và
   adapter wire behavior bằng project dbt tối thiểu; chốt shared JDBC DB.
2. **Store/state migration:** thêm CAS transitions, runner metadata, log
   storage và migration đồng bộ SQLite/MySQL/PostgreSQL; test migration.
3. **Runner core:** implement Fabric8 Job factory, deterministic naming,
   server-side config validation, project resolver và image/bootstrap.
4. **Lifecycle:** inject vào `NotebookManager`, reconciler startup/poll,
   restart recovery, final state/error mapping và cleanup.
5. **Logs/cancel:** collector/page API, idempotent cancellation, limits và TTL
   safety.
6. **Chart/deployment:** Docker build, Helm RBAC/ServiceAccount/NetworkPolicy,
   config + Secret refs; disabled by default.
7. **Tests:** unit, integration K3s và manual end-to-end below.

Không gộp tất cả thành một commit lớn: mỗi bước cần compile/test độc lập và
review security trước khi bật runner trong môi trường dùng chung.

## 9. Test và acceptance criteria

### Unit/service tests

- Owner dùng profile của mình thành công; user khác không thể submit profile đó.
- Snapshot thay đổi sau submit không đổi manifest/config run đã tạo.
- Command mapping không shell-inject selector.
- CAS state transition, duplicate submit event, cancel race và server restart
  không tạo hai Kubernetes Job cho một run.
- Log offset, ownership, max-size/truncation và terminal transitions.

### Kubernetes integration test

1. Tạo profile `alice-small`; chạy notebook SQL với Alice để tạo engine.
2. Tạo DBT workspace/job của Alice dùng cùng profile; POST run.
3. Đợi DBT Job `SUCCEEDED`; kiểm tra output/log và một Spark driver phù hợp.
4. Xác nhận Kyuubi/driver log cho thấy effective user `alice`, subdomain
   `alice-small`; không xuất hiện engine `dbt-runner` riêng.
5. Chạy DBT của Bob cùng tên profile/subdomain (nếu Bob có profile hợp lệ) và
   xác nhận không reuse engine Alice.
6. Sửa profile Alice sau submit, xác nhận run đã submit vẫn dùng snapshot cũ.
7. Chạy model lâu, gọi cancel, xác nhận Job biến mất/terminal `CANCELLED` và
   Spark engine shared không bị terminate.
8. Restart Kyuubi server trong khi Job chạy, xác nhận reconciler nối lại đúng
   Job/state/log; không submit duplicate.
9. Thử proxy sang user không được allow và xác nhận Kyuubi từ chối.

### Hoàn thành Phase D khi

- [ ] Runner Kubernetes bị tắt mặc định, bật chỉ với cấu hình hợp lệ.
- [ ] Không có raw Spark config, subdomain, proxy user hay credential từ browser.
- [ ] Mỗi run có one-and-only-one Kubernetes Job qua idempotency/recovery.
- [ ] DBT kết nối Kyuubi dưới effective user owner qua proxy-user đã kiểm soát.
- [ ] DBT/Notebook/SQL Editor cùng owner/profile reuse đúng Spark engine.
- [ ] State, log, cancel và restart recovery hoạt động qua shared metadata DB.
- [ ] RBAC/Secret handling/NetworkPolicy được review; cancel không terminate shared engine.

## 10. Rủi ro cần chốt khi duyệt

1. Ai cung cấp project registry và Git credential policy? Không có câu trả lời
   này thì chỉ test được bằng sample project ConfigMap/public Git.
2. Kyuubi production auth của runner là LDAP, OIDC hay service identity khác?
   Điều này quyết định Secret/credential reference, không thay đổi DBT domain.
3. Có shared MySQL/PostgreSQL chưa? Nếu chưa, không bật runner trên StatefulSet
   hai replica.
4. Có artifact/object storage chuẩn của công ty không? Nếu có, thay DB log table
   bằng artifact adapter sẽ tốt hơn cho log rất lớn, nhưng API contract giữ nguyên.
