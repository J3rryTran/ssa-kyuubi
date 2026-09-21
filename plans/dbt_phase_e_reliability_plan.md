# Phase E — Độ tin cậy lifecycle, log bền vững và xác minh engine sharing DBT

## Trạng thái

**Đã triển khai phần backend cốt lõi:** DBT run có `DISPATCHING`, dispatch token,
Kubernetes Job UID, optimistic locking theo version/state, recovery bằng label
run ID, log UTF-8 lưu trong JDBC metadata store, phân trang/giới hạn log, cancel
idempotent, cleanup ConfigMap profile và Job timeout/TTL. Các schema SQLite,
MySQL và PostgreSQL đã được mở rộng kèm migration idempotent cho database cũ.

Đã có targeted test SQLite cho ownership/snapshot, dispatch, persist log và CAS
log offset. K3s acceptance (restart replica giữa run, cancel long-running run,
và xác minh Notebook → SQL Editor → DBT reuse một driver) cần chạy sau khi user
rebuild/deploy image mới.

## Trạng thái đầu vào

Phase D demo đã chạy thành công end-to-end:

```text
DBT Kubernetes Job
  -> dbt-spark / Thrift
  -> Kyuubi proxy-user (test: anonymous)
  -> Engine Profile snapshot / subdomain
  -> Spark driver và executor
```

Một ZooKeeper dùng chung đã được thêm cho demo. Nhờ đó hai Kyuubi replica cùng
nhìn thấy engine đã đăng ký và DBT run mới chỉ tạo một Spark driver cho cùng
`effective user + subdomain`.

Tuy nhiên implementation hiện tại vẫn là demo lifecycle:

- `GET /dbt-job-runs/{id}/logs` đọc trực tiếp Pod log qua Kubernetes API. Log
  mất khi Pod/Job bị xóa và không an toàn khi API request đi qua replica khác.
- Submit chưa có compare-and-set (CAS), dispatch token hoặc Job UID. Server
  restart/crash giữa ghi `QUEUED` và tạo Job có thể tạo trạng thái mơ hồ.
- Reconciler chỉ poll state cơ bản; chưa tự khôi phục run không có
  `runnerRunId`, chưa xử lý event cũ ghi đè state terminal.
- Cancel mới xóa Job và set `CANCELLING`; chưa có contract đầy đủ cho retry,
  Job đã mất, log cuối và cleanup ConfigMap.
- Engine sharing giữa Notebook, SQL Editor và DBT chưa có integration test
  tự động/ghi nhận rõ ràng.

Phase E đóng các khoảng trống này. Nó **không** dựng DBT Studio UI và **không**
đưa cấu hình demo `authentication=NONE`/proxy wildcard vào production.

## 1. Mục tiêu

Sau Phase E, một DBT Run có lifecycle bền vững, idempotent và có thể audit:

```text
QUEUED -> DISPATCHING -> SUBMITTED -> RUNNING
       -> SUCCEEDED | FAILED | CANCELLED

QUEUED | DISPATCHING | SUBMITTED | RUNNING
       -> CANCELLING -> CANCELLED | FAILED
```

Các bảo đảm bắt buộc:

1. Mỗi `runId` tương ứng tối đa một Kubernetes Job logic, kể cả khi Kyuubi
   restart hoặc hai replica cùng reconcile.
2. Log DBT còn đọc được qua API sau khi Pod đã `Completed`/bị xóa.
3. Cancel idempotent, không terminate Spark engine dùng chung.
4. Profile snapshot, owner và effective Kyuubi user không thay đổi trong suốt
   lifecycle của run.
5. Một profile của cùng owner được Notebook, SQL Editor và DBT reuse đúng một
   Spark engine khi engine còn sống.

## 2. Phạm vi và quyết định thiết kế

### 2.1. Giữ runner abstraction

`DbtWorkspaceService` chỉ giao tiếp với `DbtRunner`. Fabric8 Kubernetes client
tiếp tục nằm trong `KubernetesJobDbtRunner`; REST resource không được gọi
Kubernetes trực tiếp.

### 2.2. Metadata JDBC là source of truth

PostgreSQL/MySQL là điều kiện khi runner bật với nhiều Kyuubi replica. SQLite
chỉ còn phù hợp unit test hoặc server đơn replica. State, dispatch binding và
log chunks đều được lưu trong JDBC metadata store chung.

### 2.3. Kubernetes Job chỉ là execution record tạm thời

Job/Pod không phải nguồn audit lâu dài. Khi log cuối đã được thu thập và state
terminal đã được lưu, Job/ConfigMap per-run có thể TTL/cleanup theo policy.

### 2.4. Scope project source

Vẫn giữ `ConfigMapDbtProjectResolver` cho demo. Git checkout immutable,
Secret credential, NetworkPolicy và proxy-user policy production thuộc
**Phase F**, vì cần hạ tầng/security policy của công ty.

## 3. Thiết kế persistence và state machine

### 3.1. Bổ sung DDL đồng bộ ba dialect

Cập nhật đồng thời:

```text
kyuubi-server/src/main/resources/sql/notebook/sqlite/
kyuubi-server/src/main/resources/sql/notebook/mysql/
kyuubi-server/src/main/resources/sql/notebook/postgresql/
```

Mở rộng `dbt_job_run` tối thiểu với:

| Cột | Mục đích |
|---|---|
| `dispatch_token` | UUID sinh server-side cho một lần dispatch logic |
| `runner_job_name` | tên Job deterministic |
| `runner_job_uid` | UID Kubernetes, phân biệt Job cùng tên bị recreate |
| `state_updated_at` | chống event/reconcile cũ ghi đè state mới |
| `log_next_offset` | offset byte UTF-8 đã persist |
| `version` | optimistic locking/CAS run-level |

Thêm bảng `dbt_job_run_log`:

```text
run_id, sequence, start_offset, end_offset, content, created_at
```

- PK: `(run_id, sequence)`.
- Index: `(run_id, start_offset)`.
- `content` chỉ là stdout/stderr DBT đã chuẩn hóa; tuyệt đối không ghi
  `profiles.yml`, token, password hay secret.

Migration cần idempotent và tương thích SQLite/MySQL/PostgreSQL. Không cascade
xóa log/run khi xóa Workspace/Job.

### 3.2. Transition có CAS

Thay `updateRun(run)` không điều kiện bằng các store operation có expected
state/version, ví dụ:

```scala
def claimDispatch(runId: String, expectedVersion: Long, token: String): Boolean
def bindRunnerJob(runId: String, token: String, binding: RunnerJobBinding): Boolean
def transitionRun(runId: String, expected: Set[DbtRunState.Value], next: DbtRunUpdate): Boolean
def appendLog(runId: String, expectedOffset: Long, chunk: DbtRunLogChunk): Boolean
```

State terminal (`SUCCEEDED`, `FAILED`, `CANCELLED`) là immutable. Poll/event
cũ không được đưa run terminal về `RUNNING` hoặc đổi error summary đã chốt.

### 3.3. Submit idempotent

Luồng submit mới:

```text
create immutable run QUEUED + snapshot
  -> CAS claim QUEUED -> DISPATCHING, tạo dispatchToken
  -> runner ensureJob(runId, dispatchToken)
       - tìm Job đã có bằng label run-id
       - nếu có: đọc UID/binding và return, không tạo Job mới
       - nếu không: tạo đúng một Job deterministic
  -> CAS bind Job UID/name, DISPATCHING -> SUBMITTED
```

Nếu server chết giữa các bước, reconciler dùng label
`kyuubi.apache.org/dbt-run-id` để attach Job đã tồn tại. Nếu Job không tồn tại,
retry chỉ được phép khi dispatch lease hết hạn và CAS vẫn thuộc token hiện tại.

## 4. Reconciler, log collector và cancel

### 4.1. Reconciler

`NotebookManager` start một `DbtRunReconciler` duy nhất mỗi replica. Mỗi vòng:

1. Query run active/recoverable từ JDBC.
2. Claim reconciliation lease bằng CAS để hai replica không poll/append cùng
   run đồng thời.
3. Với run `DISPATCHING` chưa có binding: tìm Job theo label, attach hoặc retry
   theo lease timeout.
4. Lấy Job UID/condition/startTime/completionTime thực tế; map sang state.
5. Thu log mới trước khi chốt terminal state hoặc cleanup.
6. Ghi transition qua CAS; event đến muộn bị bỏ qua.

`startedAt`/`finishedAt` dùng Kubernetes `status.startTime`/`completionTime`,
không dùng thời điểm poll của server.

### 4.2. Log persistence và pagination

`KubernetesJobDbtRunner` đọc log theo Pod/container hiện tại, nhưng service
append byte mới vào `dbt_job_run_log` qua `log_next_offset` CAS.

Contract API:

```text
GET /api/v1/dbt-job-runs/{runId}/logs?offset=<byte>&limit=<bytes>
```

- API chỉ đọc JDBC log store sau ownership check, không gọi Pod log trực tiếp.
- Offset tính theo UTF-8 bytes; reject offset/limit âm và cap limit server-side.
- Có config `kyuubi.dbt.runner.log.max-bytes-per-run`.
- Khi quá giới hạn, đánh dấu `truncated=true`, giữ policy rõ ràng (MVP: giữ
  phần đầu đến giới hạn; production có thể chuyển artifact store).
- Terminal run phải thực hiện một lần final collection trước cleanup.

### 4.3. Cancel idempotent

Luồng cancel:

```text
CAS active state -> CANCELLING
  -> Kubernetes delete Job (404 xem là đã xóa)
  -> reconciler collect log cuối
  -> CANCELLED khi Job/Pod không còn hoặc condition cancellation được quan sát
```

- Cancel run terminal trả nguyên run, không lỗi.
- Cancel được gọi lặp lại không tạo Job mới và không thay đổi terminal state.
- Không gọi Engine terminate API. Session DBT đóng sẽ cancel operation của DBT;
  Spark engine shared còn sống theo idle timeout thường lệ.

### 4.4. Cleanup an toàn

Sau terminal + final log collection:

- Xóa ConfigMap `kyuubi-dbt-profile-*` chứa profile render per-run.
- Kubernetes Job dùng `ttlSecondsAfterFinished` có cấu hình; chỉ bật TTL sau
  khi kiểm tra log persistence đã hoàn tất.
- Không xóa Workspace, Job hoặc `DbtJobRun` audit record.

## 5. Chỉnh Kubernetes manifest và cấu hình

Thêm config có validation fail-fast khi runner enabled:

```text
kyuubi.dbt.runner.reconcile-interval=10s
kyuubi.dbt.runner.dispatch-lease-timeout=2m
kyuubi.dbt.runner.kubernetes.job-timeout=1h
kyuubi.dbt.runner.kubernetes.job-ttl=1h
kyuubi.dbt.runner.log.max-bytes-per-run=10m
kyuubi.dbt.runner.log.page-max-bytes=256k
```

Job factory cần thêm:

- `activeDeadlineSeconds`, `ttlSecondsAfterFinished`, `backoffLimit: 0`.
- label run ID, workspace ID và dispatch token/hash để recovery; không đưa raw
  username hoặc secret vào label.
- Job UID được read-back sau create và lưu vào JDBC.
- Cleanup/reconciler chỉ thao tác resource đúng UID/label managed-by.

Service account demo hiện dùng chung `kyuubi`; Phase E chỉ giữ để test. Service
account riêng least-privilege là phần chuẩn bị bắt buộc cho Phase F production.

## 6. Kiểm chứng engine sharing xuyên workload

Tạo integration scenario với `USER` share level và ZooKeeper shared:

1. Alice tạo Profile `alice-small`.
2. Alice chạy Notebook với `alice-small`; ghi nhận driver Pod/engine ID `E1`.
3. Alice chạy SQL Editor với `alice-small`; assert không xuất hiện driver mới,
   session gắn `E1`.
4. Alice chạy DBT Workspace/Job dùng `alice-small`; DBT Job `SUCCEEDED`,
   không có driver mới và Kyuubi log/session gắn `E1`.
5. Bob cố dùng profile Alice: bị từ chối từ `EngineProfileService`/DBT API.
6. Alice dùng profile khác `alice-large`: tạo engine `E2` độc lập.

Demo `authentication=NONE` có thể dùng `anonymous` thay Alice chỉ để xác minh
wire flow. Kiểm thử production identity được để Phase F.

## 7. Kế hoạch triển khai theo lát cắt

1. **Store migration và unit test**
   - DDL ba dialect, model run/binding/log chunk, CAS store APIs.
   - Test SQLite: version conflict, terminal immutability, append log offset.

2. **Dispatch idempotent và recovery**
   - `DISPATCHING`, token, deterministic Job binding/UID.
   - Unit fake runner + Fabric8 mock: submit lặp, server crash point, Job đã tồn tại.

3. **Reconciler và state mapping**
   - Lease, status timestamps, recovery startup, out-of-order update.
   - Test hai service instance cùng store chỉ có một dispatcher/reconciler thắng CAS.

4. **Log persistence/API**
   - Collector, DB pagination/limit/truncation, final collection.
   - API test ownership, offset Unicode, Pod đã xóa vẫn đọc được log.

5. **Cancel và cleanup**
   - Idempotent cancel; delete Job only after state transition.
   - Terminal cleanup profile ConfigMap + TTL policy; test không đụng Spark engine.

6. **K3s integration acceptance**
   - Scenario Notebook → SQL Editor → DBT reuse `E1`.
   - Restart một Kyuubi replica khi DBT Job đang chạy; run cuối cùng chính xác,
     Job không duplicate, log còn đọc được.
   - Cancel một long-running DBT run; state `CANCELLED`, engine shared vẫn sống.

## 8. Tiêu chí hoàn thành

- [ ] Mọi update lifecycle dùng CAS/version; không còn update state không điều kiện.
- [ ] Một `runId` không thể tạo hai Job sau retry/restart/đa replica.
- [ ] `GET logs` vẫn trả DBT log sau Pod/Job cleanup.
- [ ] Cancel idempotent, state terminal chính xác, không terminate shared engine.
- [ ] Timestamps lấy từ Kubernetes Job condition thay vì thời điểm poll.
- [ ] K3s test xác nhận DBT Job thành công và reuse engine từ Notebook/SQL Editor.
- [ ] Unit/integration tests chạy thật trong Java 17 reactor.
- [ ] Runner vẫn disabled mặc định; overlay demo vẫn tách riêng.

## 9. Ngoài phạm vi Phase E

- DBT Studio UI, scheduler/cron, retry policy nghiệp vụ và notification.
- Git URL/branch từ browser, Git credential, SSH key hoặc source editor.
- Secret-backed Kyuubi credential, OIDC delegation, LDAP production test.
- Proxy-user wildcard production, NetworkPolicy production, service account
  least-privilege và ZooKeeper HA ensemble.
- Artifact store/S3 log retention dài hạn.

Các nội dung trên là Phase F — production security và project source registry,
chỉ bắt đầu sau khi mentor/platform chốt authentication, Secret convention,
Git provider và hạ tầng vận hành.
