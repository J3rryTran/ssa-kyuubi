# Phase A — Contract backend cho DBT Workspace và Engine Profile

## Trạng thái

Tài liệu này là đầu ra reviewable của Phase A. Nó chốt contract và ranh giới
giữa DBT domain với runner, nhưng **chưa** thêm endpoint, database migration
hoặc Kubernetes Job. Sau khi được review, Phase B mới bắt đầu sửa code domain
Engine Profile.

## 1. Mục tiêu MVP

Một DBT workspace/job có một Engine Profile đang chọn. Tất cả action tạo từ
workspace/job đó dùng profile đã chọn:

- Preview model/query.
- Chạy một hoặc nhiều model.
- Chạy cả project.
- `dbt test` / `dbt build` khi được bật ở phase sau.

User chỉ đổi profile trước một action/run mới. Profile không được đổi đối với
run đang chạy.

```text
DBT Workspace (owner=alice, selectedProfile=alice-prod-large)
       │
       ├─ Preview → profile alice-prod-large
       ├─ Run model → profile alice-prod-large
       └─ Run project → profile alice-prod-large
                         │
                         ▼
          Kyuubi USER / alice / alice-prod-large / SPARK_SQL
                         │
                         ▼
                  cùng Spark engine nếu còn sống
```

## 2. Quyết định kiến trúc

### 2.1 Engine sharing

- Giữ `kyuubi.engine.share.level=USER`.
- Engine key hiệu dụng là: `effective Kyuubi user + subdomain + engine type`.
- Notebook, SQL Editor và DBT của cùng user chọn cùng Engine Profile sẽ reuse
  cùng engine nếu engine còn sống.
- User khác không được reuse engine đó, kể cả biết tên profile/subdomain.

### 2.2 Runner

`DbtRunner` là abstraction chạy process dbt; runner **không** phải Spark
engine. Kubernetes Job là implementation tham chiếu để integration test vì
mỗi run được cô lập và có lifecycle/log/cancel riêng. Core domain không phụ
thuộc trực tiếp vào Kubernetes Job.

```scala
trait DbtRunner {
  def submit(request: DbtRunRequest): DbtRunnerSubmission
  def status(runnerRunId: String): DbtRunnerStatus
  def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage
  def cancel(runnerRunId: String): Unit
}
```

Implementation sau này có thể là `KubernetesJobDbtRunner`, worker service,
Airflow/Argo adapter hoặc runner của company platform mà không làm đổi DBT
Workspace/Engine Profile domain.

### 2.3 Effective identity — điều kiện bắt buộc

DBT runner có thể chạy bằng service account Kubernetes, nhưng khi mở Thrift
session tới Kyuubi nó phải có **effective user bằng caller**, ví dụ `alice`.
Không được dùng cố định username `dbt` nếu kỳ vọng reuse engine Notebook/SQL
Editor của Alice.

```text
Kubernetes service account: dbt-runner       (quyền tạo/đọc Pod runner)
Kyuubi effective user:        alice           (quyền và engine USER share)
```

**Quyết định đã chốt: dùng Kyuubi proxy-user.** DBT runner xác thực vào
Kyuubi bằng real user/service identity được server tin cậy, sau đó gửi
`hive.server2.proxy.user=<caller>` khi mở Thrift session. Kyuubi phải kiểm
tra Hadoop proxy-user allowlist trước khi session user trở thành caller.
Password/token không được lưu trong DBT workspace, run record, dbt artifact
hoặc browser.

Test local với `kyuubi.authentication=NONE` có thể giả lập identity bằng
username Thrift `alice`; điều này chỉ dùng để xác nhận engine sharing logic.

### 2.4 Profile là server-controlled configuration

Browser/request action chỉ gửi Engine Profile ID hoặc dùng ID đã lưu trong
workspace. Backend resolve profile, kiểm tra quyền `USE`, rồi tạo snapshot.
Request không nhận raw `spark.*`, `kyuubi.*`, Kyuubi password hay token.

## 3. Domain model dự kiến

```scala
case class DbtWorkspace(
  id: String,
  owner: String,
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
  version: Long)

case class EngineProfileSnapshot(
  profileId: String,
  subdomain: String,
  config: Map[String, String],
  capturedAt: Long)

case class DbtJobRun(
  id: String,
  jobId: String,
  submittedBy: String,
  effectiveKyuubiUser: String,
  profile: EngineProfileSnapshot,
  state: DbtRunState,
  runnerRunId: Option[String],
  createdAt: Long,
  startedAt: Option[Long],
  finishedAt: Option[Long],
  errorSummary: Option[String])
```

`EngineProfileSnapshot` là bắt buộc: thay đổi profile sau khi bấm Run không
được làm thay đổi resource/subdomain của run đã tạo.

## 4. Action MVP và dbt command mapping

| Action API | Mục đích | dbt command dự kiến |
|---|---|---|
| Preview | Xem sample/executable result của model/query | `dbt show --select <selector>` hoặc `dbt show --inline <sql>` |
| Run model | Materialize model được chọn | `dbt run --select <selector>` |
| Run project | Materialize toàn project | `dbt run` |

`dbt show` đã tồn tại trong `dbt-core 1.12.0` đang cài ở project demo. Tất cả
action dùng cùng profile snapshot generation, chỉ khác command/selector.

## 5. API contract MVP

Tên endpoint có thể chỉnh theo convention company UI, nhưng payload/ownership
rules sau là contract cần giữ:

```text
PATCH /api/v1/dbt-workspaces/{workspaceId}
  request:  { "engineProfileId": "<profile-id>", "version": 3 }
  response: DbtWorkspace

POST /api/v1/dbt-workspaces/{workspaceId}:preview
  request:  { "selector": "model_name" }  // profile lấy từ workspace
  response: DbtJobRun

POST /api/v1/dbt-jobs/{jobId}:run
  request:  {}                              // profile lấy từ workspace/job
  response: DbtJobRun

GET /api/v1/dbt-job-runs/{runId}
GET /api/v1/dbt-job-runs/{runId}/logs?offset=<n>
POST /api/v1/dbt-job-runs/{runId}:cancel
```

`PATCH workspace` xác thực owner/admin và kiểm tra profile có quyền `USE`.
`POST preview/run` chỉ có thể tạo run từ profile đã lưu; không có field
`sparkConfig` hoặc `subdomain` trong request.

## 6. Handoff từ domain sang runner

Sau khi authorize và snapshot, DBT service tạo request nội bộ:

```scala
case class DbtRunRequest(
  runId: String,
  action: DbtAction,
  selector: Option[String],
  projectRef: String,
  identity: DbtExecutionIdentity,
  kyuubiConnection: KyuubiConnectionReference,
  profile: EngineProfileSnapshot)
```

Runner sinh `profiles.yml` tạm, không commit vào Git/project source:

```yaml
type: spark
method: thrift
host: <internal-kyuubi-host>
port: 10009
user: <effective-user-or-proxy-user>
auth: <server-controlled-auth-mode>
server_side_parameters:
  kyuubi.engine.share.level.subdomain: <snapshot.subdomain>
  # merge toàn bộ allowlisted snapshot.config, gồm spark.driver/executor.*
```

Runner cần trả về `runnerRunId`, status và log offset; DBT service ghi chúng
vào `DbtJobRun`, không phụ thuộc cách runner thực thi phía dưới.

## 7. Quy tắc thay đổi và cancel

- Đổi Engine Profile chỉ có hiệu lực cho run tạo sau update thành công.
- Profile update không thay đổi Driver/Executor của engine đang sống.
- Delete profile bị chặn hoặc yêu cầu xác nhận nếu còn DBT run active.
- Cancel run trước hết cancel dbt runner; chỉ cancel Kyuubi operation nếu có
  handle. Không terminate shared engine mặc định.
- Cùng subdomain không được dùng với config resource khác nhau; cần profile
  version/subdomain mới hoặc lifecycle terminate có kiểm soát.

## 8. Acceptance criteria của Phase A

- [x] Có action MVP và dbt command mapping rõ ràng.
- [x] Có rule `USER` sharing cho Notebook, SQL Editor và DBT.
- [x] Có runner abstraction, không khóa core domain vào Kubernetes Job.
- [x] Có contract effective user tách với Kubernetes service account.
- [x] UI/API không truyền raw Spark config.
- [x] Có API payload và handoff request giữa DBT service với runner.
- [x] Chọn Kyuubi proxy-user cho production runner identity.
- [ ] Company cấu hình và security-review Hadoop proxy-user allowlist trước
      khi triển khai DBT runner thật.

## 9. Ngoài phạm vi Phase A

- Không thêm Scala/TypeScript production code.
- Không tạo database schema hay REST resource.
- Không build image DBT runner.
- Không dựng DBT Studio UI.

Các phần trên bắt đầu từ Phase B sau khi contract này được review.
