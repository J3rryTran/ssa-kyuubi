# Phase C — DBT Workspace, Job và Run persistence/API

## Trạng thái triển khai

**Đã triển khai phần core Phase C:** JDBC schema SQLite/MySQL/PostgreSQL,
DBT Workspace/Job/Run model-store-service, REST CRUD/read contract, profile
preflight/snapshot và runner port mặc định bị vô hiệu hóa. Chưa có DBT runner
thật, nên endpoint Preview/Run trả `DBT_RUNNER_NOT_CONFIGURED` trước khi ghi
run; đây là hành vi chủ đích cho đến Phase D.

Đã thêm `DbtWorkspaceServiceSuite` cho ownership, immutable snapshot và
disabled-runner. Cần chạy suite này trong full Java 17 reactor build của môi
trường vì test classpath của module server phụ thuộc các module reactor khác.

## 1. Mục tiêu và phạm vi

Phase B đã biến Engine Profile thành domain dùng chung và bảo đảm Notebook,
SQL Editor có thể resolve profile theo caller ở backend. Phase C bổ sung DBT
domain để một user lưu lựa chọn Engine Profile cho DBT Workspace/Job một cách
bền vững, có phân quyền và có snapshot tại thời điểm bấm chạy.

Sau Phase C, backend phải có thể trả lời chính xác các câu hỏi sau, dù chưa
chạy process `dbt` thật:

- Workspace/job này thuộc user nào và trỏ đến project nào?
- Profile nào đang được workspace/job chọn?
- Caller có quyền đổi profile hoặc tạo run không?
- Một run đã được tạo đã chụp chính xác profile/subdomain/config nào?
- Nếu profile bị sửa sau đó, run cũ có vẫn giữ nguyên snapshot để audit/retry
  hay không?

**Không thuộc Phase C:** DBT Studio UI, image dbt, Kubernetes Job, tải Git
project/artifact, stream log thực tế, và cấu hình Hadoop proxy-user production.
Các phần thực thi này thuộc Phase D, sau khi contract và persistence ổn định.

## 2. Đầu vào bắt buộc

- `EngineProfileService.snapshotForUse(profileId, principal)` của Phase B là
  con đường duy nhất để biến profile thành subdomain/Spark configuration.
- Engine sharing tiếp tục là `USER`. Khi Phase D chạy DBT runner bằng
  proxy-user, `effective Kyuubi user` phải là owner/caller đã tạo run.
- `profileId == subdomain` trong giai đoạn hiện tại. Không thiết kế DBT schema
  phụ thuộc vào việc tên profile phải là UUID; migration riêng có thể làm sau.
- Không nhận `spark.*`, `kyuubi.*`, subdomain, Kyuubi password/token từ request
  DBT. Request chỉ chọn profile ID đã tồn tại.

## 3. Thiết kế domain

Tạo package mới, tách khỏi `notebook.service`, ví dụ:

```text
org.apache.kyuubi.server.dbt
  model/       DbtWorkspace, DbtJob, DbtJobRun, DbtAction, DbtRunState
  service/     DbtWorkspaceService, DbtJobService, DbtRunService
  store/       DbtStore (hoặc mở rộng NotebookStore có chủ đích)
  api/         DbtWorkspacesResource, DbtJobsResource, DbtJobRunsResource
```

`NotebookManager` vẫn là composition root của custom server subsystem trong
fork hiện tại. Nó khởi tạo DBT store/service và REST resources chỉ đi qua
manager, không tự mở JDBC connection hay gọi `EngineProfileService` trực tiếp.

### 3.1. Model bền vững

```scala
case class DbtWorkspace(
  id: String,
  owner: String,
  name: String,
  projectRef: String,
  selectedEngineProfileId: String,
  createdAt: Long,
  updatedAt: Long,
  version: Long)

case class DbtJob(
  id: String,
  workspaceId: String,
  owner: String,
  name: String,
  action: DbtAction,
  selector: Option[String],
  engineProfileIdOverride: Option[String],
  createdAt: Long,
  updatedAt: Long,
  version: Long)

case class DbtJobRun(
  id: String,
  workspaceId: String,
  jobId: Option[String],
  submittedBy: String,
  effectiveKyuubiUser: String,
  action: DbtAction,
  selector: Option[String],
  projectRef: String,
  profile: EngineProfileSnapshot,
  state: DbtRunState,
  runnerRunId: Option[String],
  createdAt: Long,
  startedAt: Option[Long],
  finishedAt: Option[Long],
  errorSummary: Option[String])
```

Quy tắc chọn profile:

```text
DbtJob.engineProfileIdOverride (nếu có)
  > DbtWorkspace.selectedEngineProfileId
```

Việc override chỉ thay lựa chọn cho các lần chạy mới của job; nó không sửa
workspace và không thay đổi run đang chạy. API không cho action run truyền
profile ID tạm thời, để tránh một client bỏ qua persistence/audit.

### 3.2. Project reference an toàn

`projectRef` là định danh opaque do server/platform kiểm soát (ví dụ project
ID hoặc Git repository reference đã được allowlist), không phải đường dẫn bất
kỳ do browser gửi như `/root/...` hay URL Git có credential. Phase C chỉ lưu
và trả lại reference này; Phase D mới resolve nó thành source checkout bằng
project provider được server cấu hình.

### 3.3. Snapshot run và state machine

Ngay trong transaction tạo run, service phải:

1. tải workspace/job, kiểm tra owner/admin và optimistic-lock version;
2. resolve effective profile ID theo quy tắc trên;
3. gọi `EngineProfileService.snapshotForUse(profileId, caller)`;
4. ghi immutable `EngineProfileSnapshot` (profile ID, owner, subdomain,
   normalized config, captured time) vào run;
5. ghi `effectiveKyuubiUser = caller.user` và state `QUEUED`.

`QUEUED` là trạng thái hợp lệ ở Phase C nhưng chưa được dispatch. Production
endpoint run phải trả `501 DBT_RUNNER_NOT_CONFIGURED` **trước khi tạo run** nếu
không có runner implementation. Do đó không tồn tại run “mồ côi” không thể
thực thi. Test service có thể dùng fake runner để xác minh state transition.

State dự kiến cho Phase D:

```text
QUEUED -> SUBMITTED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
QUEUED | SUBMITTED | RUNNING -> CANCELLING -> CANCELLED | FAILED
```

State chỉ tiến về phía trước; transition phải atomic và idempotent theo run
ID/runner event. Không cho `CANCELLED` quay về `RUNNING`.

## 4. Database migration và store

Tạo DDL cho **cả ba** dialect của subsystem notebook:

```text
kyuubi-server/src/main/resources/sql/notebook/mysql/dbt.sql
kyuubi-server/src/main/resources/sql/notebook/postgresql/dbt.sql
kyuubi-server/src/main/resources/sql/notebook/sqlite/dbt.sql
```

Hoặc tách `dbt_workspace.sql`, `dbt_job.sql`, `dbt_job_run.sql` nếu convention
store hiện có thuận tiện hơn; tên/columns/index phải đồng bộ cả ba dialect.

Các bảng tối thiểu:

| Bảng | Mục đích | Index/ràng buộc tối thiểu |
|---|---|---|
| `dbt_workspace` | workspace và profile mặc định | PK `id`, index `(owner, updated_at)` |
| `dbt_job` | job thuộc workspace | PK `id`, index `(workspace_id, updated_at)` |
| `dbt_job_run` | audit/state/run snapshot | PK `id`, index `(workspace_id, created_at)`, `(job_id, created_at)`, `(state, created_at)` |

Snapshot config lưu bằng JSON/text canonicalized theo dialect (không dùng Java
serialization). Khi đọc lại phải parse chặt, map immutable và báo lỗi dữ liệu
rõ ràng nếu JSON hỏng. Không dùng foreign key cascade làm mất audit run khi
xóa workspace/job/profile.

Tất cả update workspace/job dùng `WHERE id = ? AND version = ?`, tăng version
khi thành công. Trả `CONFLICT` nếu version đã cũ, thay vì silently overwrite
lựa chọn profile của tab/browser khác.

## 5. API REST Phase C

Các resource nằm dưới `/api/v1`, lấy caller bằng `NotebookApiSupport` hoặc
base support chung có cùng nguyên tắc: không nhận owner/admin từ body/query.
Mọi lỗi domain đi qua `NotebookException`/error mapper hiện hữu.

### 5.1. Workspace

```text
POST   /api/v1/dbt-workspaces
GET    /api/v1/dbt-workspaces
GET    /api/v1/dbt-workspaces/{workspaceId}
PATCH  /api/v1/dbt-workspaces/{workspaceId}
DELETE /api/v1/dbt-workspaces/{workspaceId}
```

Request tạo/update chỉ có `name`, `projectRef`, `engineProfileId`, `version`
theo operation. `POST/PATCH` phải gọi `snapshotForUse` như một preflight
authorization, nhưng chỉ lưu ID vào workspace; không lưu config snapshot ở
workspace vì profile được snapshot khi tạo run.

`DELETE workspace` bị chặn nếu tồn tại run active (`QUEUED`, `SUBMITTED`,
`RUNNING`, `CANCELLING`). Job/run hoàn tất vẫn giữ audit; lựa chọn khuyến nghị
là soft-delete workspace hoặc cấm xóa khi còn job/run history. Phase C chốt
một behavior và test rõ, không cascade xóa dữ liệu âm thầm.

### 5.2. Job

```text
POST   /api/v1/dbt-workspaces/{workspaceId}/jobs
GET    /api/v1/dbt-workspaces/{workspaceId}/jobs
GET    /api/v1/dbt-jobs/{jobId}
PATCH  /api/v1/dbt-jobs/{jobId}
DELETE /api/v1/dbt-jobs/{jobId}
```

Actions MVP: `PREVIEW`, `RUN_MODEL`, `RUN_PROJECT`. Selector là bắt buộc với
`PREVIEW`/`RUN_MODEL`, không có selector với `RUN_PROJECT`. Nếu job có
`engineProfileIdOverride`, service preflight ownership bằng
`snapshotForUse`; nếu không, effective profile lấy từ workspace.

### 5.3. Run/readiness endpoint

Contract cuối cùng giữ như Phase A:

```text
POST /api/v1/dbt-workspaces/{workspaceId}:preview
POST /api/v1/dbt-jobs/{jobId}:run
GET  /api/v1/dbt-job-runs/{runId}
GET  /api/v1/dbt-job-runs/{runId}/logs?offset=N
POST /api/v1/dbt-job-runs/{runId}:cancel
```

Trong Phase C, hai POST tạo run chỉ được bật khi có injected `DbtRunner`; nếu
runner chưa được cấu hình, trả lỗi có mã ổn định `DBT_RUNNER_NOT_CONFIGURED`
và không ghi record. GET run có thể được test qua fixture/store; GET logs và
cancel trả cùng lỗi runner-not-configured khi cần thao tác runner.

Điều này tránh đưa một API “trông như chạy được” nhưng tạo record QUEUED vĩnh
viễn trên môi trường hiện tại.

## 6. Runner port, không implement Kubernetes Job ở Phase C

Thêm interface domain nhỏ để service không phụ thuộc Kubernetes:

```scala
trait DbtRunner {
  def submit(request: DbtRunRequest): DbtRunnerSubmission
  def status(runnerRunId: String): DbtRunnerStatus
  def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage
  def cancel(runnerRunId: String): Unit
}
```

`DbtRunRequest` chỉ được tạo server-side từ snapshot. Nó mang action,
selector, project reference, `effectiveKyuubiUser`, Kyuubi connection
reference và snapshot; không mang credential plaintext. Phase C chỉ có
`DisabledDbtRunner` (mặc định) và fake runner trong unit test. Không thêm
Kubernetes client dependency, RBAC, Docker image hay `profiles.yml` writer ở
phase này.

## 7. Authorization, consistency và lifecycle

- Owner được CRUD workspace/job/run của mình. Admin theo policy admin hiện có.
- User khác nhận `NOT_FOUND` ở public REST hoặc `ACCESS_DENIED` trong audit;
  không làm lộ `projectRef`, profile snapshot hay error detail của owner khác.
- `PATCH` profile/workspace/job không tác động run đã có snapshot.
- Xóa Engine Profile phải bị chặn nếu `DbtJobRun` active snapshot profile đó;
  Phase D mới quyết định UI confirmation/terminate engine. Ít nhất service
  phải có query `hasActiveRunsForProfile` để profile delete flow dùng được.
- Cancel DBT run không terminate shared Spark engine mặc định. Đó là lifecycle
  engine, không phải lifecycle của DBT runner.
- Log/artifact path về sau phải authorize theo owner trước khi trả ra API.

## 8. Thứ tự triển khai đề xuất

1. Đọc schema/store notebook hiện tại và chọn DDL naming/migration hook;
   thêm schema của Phase C đồng bộ MySQL/PostgreSQL/SQLite.
2. Tạo model, enum action/state và store contract; viết store tests trên
   SQLite theo `NotebookTestBase` hoặc base tương đương.
3. Tạo `DbtWorkspaceService`/`DbtJobService`, optimistic locking và ownership
   tests trước khi đăng ký REST.
4. Tích hợp `EngineProfileService.snapshotForUse` tại create/update workspace
   và job override; test owner, user khác, admin và profile `default`.
5. Tạo `DbtRunService`, snapshot serialization và `DbtRunner`/disabled
   implementation; test snapshot immutable và state transition bằng fake
   runner.
6. Thêm REST request/response types và resources vào `ApiRootResource`; cập
   nhật `docs/notebook/API.md` hoặc tài liệu DBT API mới.
7. Chạy formatter/RAT/Spotless, targeted Scala suite, regression Notebook và
   build server module bằng Java 17.

## 9. Test matrix và acceptance criteria

| Trường hợp | Kết quả yêu cầu |
|---|---|
| Tạo workspace với profile owner | Thành công, chỉ profile ID được persist |
| Tạo/update workspace bằng profile người khác | Bị từ chối |
| Admin thao tác dữ liệu user khác | Theo policy admin |
| Update version cũ | `CONFLICT`, không mất dữ liệu mới |
| Job không override profile | Kế thừa workspace profile |
| Job có override hợp lệ | Run chọn override |
| Tạo run | Snapshot profile immutable, effective user = caller |
| Sửa/xóa profile sau run | Snapshot run không đổi; active run bảo vệ delete |
| Raw `spark.*`/subdomain trong HTTP body | Không có field contract, request bị reject |
| Runner chưa cấu hình | Run action trả `DBT_RUNNER_NOT_CONFIGURED`, không có queued run mồ côi |
| Caller khác đọc workspace/job/run | Bị từ chối/ẩn tồn tại |
| Notebook/SQL Editor regression | Phase B flow vẫn hoạt động |

Phase C hoàn thành khi các workspace/job/profile binding và run snapshot được
lưu bền vững, REST có ownership/optimistic locking, và core domain vẫn không
phụ thuộc Kubernetes/dbt executable.

## 10. Bàn giao sang Phase D

Phase D chỉ bắt đầu khi Phase C review/build/test pass. Đầu vào Phase D:

1. `KubernetesJobDbtRunner` thực thi `DbtRunner`.
2. DBT runner image và `profiles.yml` tạm từ `EngineProfileSnapshot`.
3. Kyuubi proxy-user allowlist cho runner real identity được security review.
4. Project source/artifact/log storage provider và RBAC Kubernetes.
5. Integration test chứng minh Alice DBT, Alice Notebook và Alice SQL Editor
   cùng profile reuse một Spark driver; Bob không reuse được driver đó.
