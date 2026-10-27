# Notebook API Documentation

## Base URL

```
/api/v1
```

---

## Folders

|  Method  |            Endpoint            |     Description     |
|----------|--------------------------------|---------------------|
| `GET`    | `/notebook-folders`            | List all folders    |
| `POST`   | `/notebook-folders`            | Create a new folder |
| `PATCH`  | `/notebook-folders/{folderId}` | Rename a folder     |
| `DELETE` | `/notebook-folders/{folderId}` | Delete a folder     |

### Create folder

```typescript
POST /notebook-folders
Body: { name: string, parentId: string | null }
```

### Rename folder

```typescript
PATCH /notebook-folders/{folderId}
Body: { name: string, version: number }
```

---

## Notebooks

|  Method  |             Endpoint             |      Description      |
|----------|----------------------------------|-----------------------|
| `GET`    | `/notebooks`                     | List notebooks        |
| `GET`    | `/notebooks:search`              | Search notebooks      |
| `GET`    | `/notebooks/{notebookId}`        | Get notebook details  |
| `POST`   | `/notebooks`                     | Create a new notebook |
| `PATCH`  | `/notebooks/{notebookId}`        | Update notebook       |
| `DELETE` | `/notebooks/{notebookId}`        | Delete notebook       |
| `POST`   | `/notebooks/{notebookId}:clone`  | Clone notebook        |
| `GET`    | `/notebooks/{notebookId}:export` | Export notebook       |
| `POST`   | `/notebooks:import`              | Import notebook       |

### Create notebook

```typescript
POST /notebooks
Body: { name: string, folderId: string | null, language: 'SQL' | 'PYTHON' }
```

### Clone notebook

```typescript
POST /notebooks/{notebookId}:clone
Body: { name: string }
```

### Export notebook

```typescript
GET /notebooks/{notebookId}:export?format={format}
```

### Import notebook

```typescript
POST /notebooks:import
Body: { name: string, content: string, format?: string }
```

---

## Cells

|  Method  |                 Endpoint                 |    Description    |
|----------|------------------------------------------|-------------------|
| `POST`   | `/notebooks/{notebookId}/cells`          | Create a new cell |
| `PATCH`  | `/notebooks/{notebookId}/cells/{cellId}` | Update cell       |
| `DELETE` | `/notebooks/{notebookId}/cells/{cellId}` | Delete cell       |
| `PUT`    | `/notebooks/{notebookId}/cells:reorder`  | Reorder cells     |

### Create cell

```typescript
POST /notebooks/{notebookId}/cells
Body: { position: number, cellType: 'CODE' | 'MARKDOWN', language: CellLanguage, source: string }
```

### Update cell

```typescript
PATCH /notebooks/{notebookId}/cells/{cellId}
Body: { source?: string, metadata?: Record<string, string> }
```

### Reorder cells

```typescript
PUT /notebooks/{notebookId}/cells:reorder
Body: { cellIds: string[] }
```

---

## Sessions

| Method |                 Endpoint                 |     Description      |
|--------|------------------------------------------|----------------------|
| `POST` | `/notebooks/{notebookId}/sessions`       | Create a new session |
| `GET`  | `/notebooks/{notebookId}/sessions`       | List sessions        |
| `POST` | `/notebook-sessions/{sessionId}:restart` | Restart session      |
| `POST` | `/notebook-sessions/{sessionId}:stop`    | Stop session         |

---

## Executions

| Method |                  Endpoint                   |     Description      |
|--------|---------------------------------------------|----------------------|
| `POST` | `/notebook-sessions/{sessionId}/executions` | Submit execution     |
| `GET`  | `/executions/{executionId}`                 | Get execution status |
| `POST` | `/executions/{executionId}:cancel`          | Cancel execution     |
| `POST` | `/executions/{executionId}:close`           | Close execution      |
| `GET`  | `/executions/{executionId}/logs`            | Get execution logs   |
| `GET`  | `/executions/{executionId}/schema`          | Get result schema    |
| `GET`  | `/executions/{executionId}/results`         | Get results          |
| `GET`  | `/executions/{executionId}/outputs`         | Get outputs (stream) |

### Submit execution

```typescript
POST /notebook-sessions/{sessionId}/executions
Body: { cellId: string, source: string }
```

### Get results

```typescript
GET /executions/{executionId}/results?cursor={cursor}&maxRows={maxRows}
```

### Get logs

```typescript
GET /executions/{executionId}/logs?offset={offset}&maxLines=200
```

---

## Permissions & Revisions

| Method |                           Endpoint                           |   Description    |
|--------|--------------------------------------------------------------|------------------|
| `GET`  | `/notebooks/{notebookId}/permissions`                        | List permissions |
| `PUT`  | `/notebooks/{notebookId}/permissions`                        | Set permissions  |
| `GET`  | `/notebooks/{notebookId}/revisions`                          | Revision history |
| `POST` | `/notebooks/{notebookId}/revisions`                          | Create revision  |
| `POST` | `/notebooks/{notebookId}/revisions/{revisionNumber}:restore` | Restore revision |

### Set permissions

```typescript
PUT /notebooks/{notebookId}/permissions
Body: { permissions: [{ principalType: string, principalId: string, role: string }] }
```

### Create revision

```typescript
POST /notebooks/{notebookId}/revisions
Body: { reason: string }
```

---

## Others

| Method |               Endpoint               |        Description         |
|--------|--------------------------------------|----------------------------|
| `GET`  | `/runtime-specs`                     | List runtime specs         |
| `GET`  | `/me`                                | Get current user info      |
| `GET`  | `/notebooks/{notebookId}/executions` | Notebook execution history |

---

---

*Source: `kyuubi-server/web-ui/src/api/notebook/index.ts` & `types.ts`*

---

## Additional document endpoints

The tables above describe the most common document operations. The following endpoints are also
part of the current contract.

|  Method  |                           Endpoint                            |               Description                |
|----------|---------------------------------------------------------------|------------------------------------------|
| `GET`    | `/notebook-folders/{folderId}`                                | Get one folder.                          |
| `DELETE` | `/notebook-folders/{folderId}?version=`                       | Delete a folder with optimistic locking. |
| `POST`   | `/notebooks/{notebookId}:move`                                | Move a notebook to another folder.       |
| `GET`    | `/notebooks/{notebookId}/cells`                               | List cells in document order.            |
| `GET`    | `/notebooks/{notebookId}/cells/{cellId}`                      | Get one cell.                            |
| `GET`    | `/notebooks/{notebookId}/cells/{cellId}/config`               | Get cell-specific configuration.         |
| `PATCH`  | `/notebooks/{notebookId}/cells/{cellId}/config`               | Update cell-specific configuration.      |
| `GET`    | `/notebooks/{notebookId}/revisions/{revisionNumber}`          | Get one revision.                        |
| `DELETE` | `/notebooks/{notebookId}/revisions/{revisionNumber}?version=` | Delete an unprotected revision.          |
| `GET`    | `/notebooks/{notebookId}/schedule`                            | Get the schedule.                        |
| `POST`   | `/notebooks/{notebookId}/schedule:pin-current`                | Pin the current document as schedule source. |
| `POST`   | `/notebooks/{notebookId}/schedule:run`                        | Dispatch one occurrence without cron.   |
| `GET`    | `/notebooks/{notebookId}/runs?limit=`                         | List scheduled Notebook runs.           |
| `DELETE` | `/notebooks/{notebookId}/schedule`                            | Remove the schedule.                     |

A newly created schedule owns an immutable Notebook revision. Editing cells in the interactive UI
does not change that pin. Ordinary changes to cron, timezone, profile or policy also retain it;
`schedule:pin-current` is the explicit deployment action that moves future occurrences to the
current document. A dispatched run records the revision ID, revision number and SHA-256 checksum
before it reserves dedicated `JOB` compute. The run never reads live cells as executable source.

The run-now endpoint is a test and orchestration transport, not a cron scheduler. It is available
only when `kyuubi.notebook.job.runner.enabled=true`. Observe or cancel the returned run through
`GET /notebook-runs/{runId}` and `POST /notebook-runs/{runId}:cancel`.
Terminal scheduled executions expose a bounded `auditSnapshot` JSON value containing captured
logs, outputs and SQL rows so the result remains available after the short-lived JOB engine is
released.

## Headless workload contract

Notebook và dbt dùng chung projection trạng thái compute không chứa credential:

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/notebook-runs/{runId}/status` | Workload, allocation và cleanup state của Notebook Job. |
| `GET` | `/dbt-job-runs/{runId}/status` | Workload, allocation và cleanup state của dbt Job. |
| `POST` | `/notebook-runs/{runId}:cancel` | Cancel idempotent đúng Notebook attempt. |
| `POST` | `/dbt-job-runs/{runId}:cancel` | Cancel idempotent đúng dbt attempt. |
| `POST` | `/notebook-runs/{runId}:retry-cleanup` | Retry cleanup Notebook allocation đang `RELEASING`. |
| `POST` | `/dbt-job-runs/{runId}:retry-cleanup` | Retry cleanup dbt allocation đang `RELEASING`. |
| `GET` | `/notebook-runs/{runId}/executions` | Cell executions để test kết quả headless. |
| `GET` | `/dbt-job-runs/{runId}/logs?offset=0` | Log dbt phân trang. |
| `GET` | `/dbt-job-runs/{runId}/artifacts/run_results.json` | Artifact kết quả tối thiểu. |
| `GET` | `/dbt-job-runs/{runId}/artifacts/manifest.json` | Manifest của invocation. |

Khi submit dbt Kubernetes Job, scheduler dùng endpoint riêng do server gán trigger `SCHEDULE` và
bắt buộc gửi header `Idempotency-Key` (tối đa 255 ký tự):

```bash
curl -X POST \
  -H 'Authorization: Bearer <access-token>' \
  -H 'Idempotency-Key: <scheduler-occurrence-id>' \
  https://<kyuubi-host>/api/v1/dbt-jobs/<job-id>:schedule-run
```

Response chỉ chứa reference cần để theo dõi, không chứa binding/dispatch credential:

```json
{
  "runId": "<run-id>",
  "attemptId": "<attempt-id>",
  "allocationId": "<allocation-id>"
}
```

Ví dụ status:

```json
{
  "mode": "JOB",
  "workloadType": "DBT",
  "triggerType": "SCHEDULE",
  "runId": "<run-id>",
  "attemptId": "<attempt-id>",
  "allocationId": "<allocation-id>",
  "engineIdentity": "job-<allocation-prefix>",
  "workloadState": "RUNNING",
  "allocationState": "ACTIVE",
  "cleanupState": "NOT_REQUESTED",
  "errorMessage": null,
  "createdAt": 1792700000000,
  "updatedAt": 1792700012000,
  "activatedAt": 1792700012000,
  "launchLatencyMillis": 12000,
  "workloadDeadlineAt": 1792703600000,
  "workloadHeartbeatAt": 1792700020000,
  "cleanupRequestedAt": null,
  "cleanupAgeMillis": null,
  "cleanupDeadlineAt": null,
  "cleanupOverdue": false,
  "cleanupAttempts": 0
}
```

Theo dõi và hủy có thể thực hiện hoàn toàn headless:

```bash
curl -H 'Authorization: Bearer <access-token>' \
  https://<kyuubi-host>/api/v1/dbt-job-runs/<run-id>/status

curl -X POST -H 'Authorization: Bearer <access-token>' \
  https://<kyuubi-host>/api/v1/dbt-job-runs/<run-id>:cancel
```

Gọi cancel lần nữa là hợp lệ và không được tác động tới engine `INTERACTIVE` dùng cùng Engine
Profile. Sau khi run terminal, dùng endpoint artifact/log trong bảng trên để xác minh kết quả.

Nếu workload đã terminal nhưng `cleanupState` là `REQUESTED` hoặc `OVERDUE`, operator có thể yêu
cầu retry an toàn mà không chạy lại workload:

```bash
curl -X POST -H 'Authorization: Bearer <access-token>' \
  https://<kyuubi-host>/api/v1/dbt-job-runs/<run-id>:retry-cleanup
```

Endpoint chỉ chấp nhận allocation `RELEASING`; allocation `RELEASED` trả trạng thái hiện tại và
state còn phục vụ workload bị từ chối. Retry sẽ fence controller claim cũ rồi để lifecycle
reconciler tiếp tục thao tác terminate có tính idempotent.

Khóa được scope theo authenticated principal. Gửi lại cùng khóa và cùng payload trả đúng
`runId`, `attemptId` và `allocationId`; dùng lại khóa với job/payload khác trả
`VERSION_CONFLICT`. Server tạo run/attempt ID ổn định từ principal và khóa, nên hai Kyuubi replica
không tạo hai JOB allocations. Không dùng header này để truyền secret.

`status` chỉ trả mode, workload/trigger type, run/attempt/allocation ID, engine subdomain, workload
state, allocation state, thời gian launch/heartbeat/deadline và cleanup state (`NOT_REQUESTED`,
`REQUESTED`, `OVERDUE`, `COMPLETED`). Binding token
chỉ xuất hiện trong one-time launch response của trusted managed CLI/runner, không xuất hiện trong
status, logs hay artifacts. Scheduled Notebook không nhận `triggerType` từ request; server tự gán
`SCHEDULE` sau khi xác thực owner và schedule đã pin. Mỗi lần dispatch scheduled Notebook bắt buộc
có `clientRequestId`.

Trong lúc profile còn allocation `RESERVED`, `STARTING`, `ACTIVE` hoặc `RELEASING`, thao tác sửa,
xóa hay publish Python environment trả HTTP `409` với error code `COMPUTE_ACTIVE`. Client phải chờ
cleanup hoàn tất hoặc chủ động stop/cancel workload; server không tự dừng engine chỉ vì có request
sửa profile.

Chi tiết config, storage boundary, label và quy trình điều tra cleanup nằm tại
[Managed Compute Operations](MANAGED_COMPUTE_OPERATIONS.md).

## Complete session, runtime, and execution APIs

### Sessions

|  Method  |                      Endpoint                      |             Description             |
|----------|----------------------------------------------------|-------------------------------------|
| `GET`    | `/notebook-sessions/{sessionId}`                   | Get a notebook session.             |
| `DELETE` | `/notebook-sessions/{sessionId}`                   | Stop and delete a notebook session. |
| `POST`   | `/notebook-sessions/{sessionId}:reset`             | Reset the session's runtime state.  |
| `GET`    | `/notebook-sessions/{sessionId}/runtimes`          | List session runtimes.              |
| `POST`   | `/notebook-sessions/{sessionId}/runtimes`          | Create a runtime for the session.   |
| `GET`    | `/notebook-sessions/{sessionId}/executions?limit=` | List session executions.            |

### Runtimes

|  Method  |                  Endpoint                  |          Description           |
|----------|--------------------------------------------|--------------------------------|
| `GET`    | `/runtime-specs/{runtimeSpecId}`           | Get one runtime specification. |
| `GET`    | `/notebook-runtimes/{runtimeId}`           | Get and refresh runtime state. |
| `POST`   | `/notebook-runtimes/{runtimeId}:interrupt` | Interrupt current work.        |
| `POST`   | `/notebook-runtimes/{runtimeId}:restart`   | Restart a runtime.             |
| `POST`   | `/notebook-runtimes/{runtimeId}:stop`      | Stop a runtime.                |
| `DELETE` | `/notebook-runtimes/{runtimeId}`           | Stop and delete a runtime.     |

### Executions

| Method |                               Endpoint                               |              Description               |
|--------|----------------------------------------------------------------------|----------------------------------------|
| `GET`  | `/executions/{executionId}/events?afterSequence=&waitMillis=&limit=` | Long-poll replayable execution events. |
| `GET`  | `/executions/{executionId}/outputs?afterSequence=&limit=`            | Read text and rich output events.      |

`/executions/{executionId}/results` accepts `cursor` and `maxRows`; `/logs` accepts `offset` and
`maxLines`. A browser should use events or outputs to reconnect after a refresh instead of relying
on one long-lived HTTP request.

## Engine Profiles and Python environments

An Engine Profile is a user-owned resource policy with one current Spark configuration, stable
generated engine subdomain, timeout policy, and optional persistent Python environment. The server
derives the owner from the authenticated principal; callers never provide a user-controlled Kyuubi
subdomain.

|  Method  |                           Endpoint                            |                          Description                          |
|----------|---------------------------------------------------------------|---------------------------------------------------------------|
| `GET`    | `/engine-profiles`                                            | List profiles owned by the caller.                            |
| `POST`   | `/engine-profiles`                                            | Create a profile.                                             |
| `GET`    | `/engine-profiles/default/config`                             | Read allowlisted resource settings for the built-in default.  |
| `GET`    | `/engine-profiles/{profileId}`                                | Get a profile.                                                |
| `PATCH`  | `/engine-profiles/{profileId}`                                | Update a stopped profile's current configuration.              |
| `DELETE` | `/engine-profiles/{profileId}`                                | Delete a stopped profile after environment changes finish.    |
| `GET`    | `/engine-profiles/{profileId}/engine`                         | Get current profile engine status.                             |
| `POST`   | `/engine-profiles/{profileId}:start`                          | Warm or start an engine.                                      |
| `POST`   | `/engine-profiles/{profileId}:stop`                           | Stop the active engine.                                       |
| `GET`    | `/engine-profiles/{profileId}/python-environments`            | List persistent Python environment revisions.                 |
| `POST`   | `/engine-profiles/{profileId}/python-packages:install`        | Request package installation.                                 |
| `POST`   | `/engine-profiles/{profileId}/python-packages:uninstall`      | Request package removal.                                      |
| `GET`    | `/engine-profiles/{profileId}/environment-builds/{requestId}` | Get asynchronous environment-build state.                     |
| `PUT`    | `/engine-profiles/{legacyProfileId}`                          | Legacy upsert endpoint retained for compatibility.            |

### Phân biệt Engine Profile và engine instance

Engine Profile và Spark engine đang chạy là hai tài nguyên khác nhau:

- **Engine Profile** là cấu hình được lưu lâu dài, gồm owner, tài nguyên Spark, idle timeout,
  subdomain ổn định và Python environment nếu có.
- **Engine instance** là tài nguyên tính toán tạm thời được tạo từ cấu hình đó. Engine có thể biến
  mất sau khi được dừng, bị cancel, gặp lỗi hoặc hết idle timeout mà không làm mất profile.
- `POST /engine-profiles` chỉ tạo profile, không đồng bộ khởi tạo Spark Driver. Khi cần khởi động
  engine, gọi `POST /engine-profiles/{profileId}:start` hoặc mở một workload interactive.
- Server luôn lấy owner từ danh tính đã xác thực. Client không được tự gửi `owner`, engine
  subdomain, allocation ID hoặc cấu hình định tuyến do server quản lý.

### Tạo và đọc Engine Profile

Tạo một profile:

```bash
curl -X POST \
  -H 'Authorization: Bearer <access-token>' \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "analytics-heavy",
    "sparkConfig": {
      "spark.driver.memory": "4g",
      "spark.executor.memory": "8g",
      "spark.executor.instances": "2"
    },
    "notebookRuntimeIdleTimeout": "PT15M",
    "engineIdleTimeout": "PT30M"
  }' \
  https://<kyuubi-host>/api/v1/engine-profiles
```

Response là cấu hình profile đã được lưu, không phải trạng thái engine đang chạy:

```json
{
  "profileId": "<profile-id>",
  "name": "analytics-heavy",
  "subdomain": "ep-<profile-id-derived-value>",
  "owner": "alice",
  "sparkConfig": {
    "spark.driver.memory": "4g",
    "spark.executor.memory": "8g",
    "spark.executor.instances": "2"
  },
  "notebookRuntimeIdleTimeout": "PT15M",
  "engineIdleTimeout": "PT30M",
  "createdAt": 1792700000000,
  "updatedAt": 1792700000000,
  "pythonEnvironmentRevisionId": null
}
```

Sử dụng `GET /engine-profiles` để liệt kê profile của người gọi và
`GET /engine-profiles/{profileId}` để đọc lại một profile. Administrator có thể thao tác với một
profile ID đã biết, nhưng API list vẫn chỉ trả profile thuộc owner hiện tại.

### Đọc trạng thái engine

```bash
curl -H 'Authorization: Bearer <access-token>' \
  https://<kyuubi-host>/api/v1/engine-profiles/<profile-id>/engine
```

Ví dụ response:

```json
{
  "profileId": "<profile-id>",
  "state": "RUNNING",
  "engineCount": 1,
  "errorSummary": null
}
```

Các state có thể trả về là `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, `FAILED` và `UNKNOWN`.
`STARTING` và `STOPPING` là trạng thái chuyển tiếp bất đồng bộ; client cần poll endpoint này đến
khi nhận được trạng thái ổn định hoặc kết thúc. `engineCount` đếm engine interactive được discovery
trên subdomain ổn định của profile. Engine `JOB` dành riêng cho scheduled/managed workload dùng
identity khác và phải được theo dõi qua API status của run tương ứng.

### Khởi động engine interactive

Endpoint start không có request body:

```bash
curl -X POST \
  -H 'Authorization: Bearer <access-token>' \
  https://<kyuubi-host>/api/v1/engine-profiles/<profile-id>:start
```

Server reserve hoặc tái sử dụng allocation `INTERACTIVE` của profile và mở một bootstrap session
ngắn hạn. Response ngay sau khi gọi thường có `state: STARTING`; chỉ coi engine đã sẵn sàng khi API
status trả `RUNNING`. Việc start profile không cho phép client tự chọn engine identity hoặc
subdomain khác.

Client interactive cũng có thể gián tiếp khởi động hoặc tái sử dụng engine này. Ví dụ,
`POST /editor-sessions` mở SQL Editor session với `engineProfileId`; Notebook interactive sử dụng
cùng chính sách định tuyến theo profile.

### Dừng engine interactive

```bash
curl -X POST \
  -H 'Authorization: Bearer <access-token>' \
  https://<kyuubi-host>/api/v1/engine-profiles/<profile-id>:stop
```

Response có cấu trúc `EngineProfileEngineStatusView` và có thể trả `STOPPING` ngay sau khi gọi.
Client cần poll `GET /engine-profiles/{profileId}/engine` đến khi nhận `STOPPED`. Các interactive
session đang dùng engine đó sẽ không còn sử dụng được và phải kết nối lại hoặc restart runtime.

Endpoint này chỉ release allocation `INTERACTIVE` dùng chung của profile. Không dùng nó để dừng
engine `JOB` riêng của dbt hoặc scheduled Notebook. Các engine `JOB` phải được cancel bằng API của
run tương ứng:

| Loại workload | Khởi động/submit | Cancel và yêu cầu dọn engine |
| --- | --- | --- |
| SQL/Notebook interactive | `POST /engine-profiles/{profileId}:start` hoặc mở session | `POST /engine-profiles/{profileId}:stop` |
| Direct managed dbt Job | `POST /dbt-jobs/{jobId}:run` | `POST /dbt-job-runs/{runId}:cancel` |
| Scheduled dbt Job | `POST /dbt-jobs/{jobId}:schedule-run` | `POST /dbt-job-runs/{runId}:cancel` |
| Scheduled Notebook | `POST /notebooks/{notebookId}/schedule:run` | `POST /notebook-runs/{runId}:cancel` |

### Sửa Engine Profile an toàn

Thao tác lưu profile không tự động dừng engine. Trước khi sửa, cần dừng engine interactive, chờ
đến `STOPPED` và bảo đảm mọi dedicated run đang dùng profile đã hoàn tất:

```bash
curl -X PATCH \
  -H 'Authorization: Bearer <access-token>' \
  -H 'Content-Type: application/json' \
  -d '{
    "sparkConfig": {
      "spark.driver.memory": "6g",
      "spark.executor.memory": "10g"
    },
    "engineIdleTimeout": "PT20M"
  }' \
  https://<kyuubi-host>/api/v1/engine-profiles/<profile-id>
```

Với `PATCH`, field không được gửi sẽ giữ nguyên giá trị hiện tại. Nếu có `sparkConfig`, map mới sẽ
thay thế toàn bộ map cũ, không merge từng key. Gửi `"inherit"` cho một idle timeout để xóa override
và dùng giá trị mặc định của server. Cấu hình mới chỉ áp dụng cho engine được tạo tiếp theo, không
được chèn vào engine đang chạy.

Allocation ở state `RESERVED`, `STARTING`, `ACTIVE` hoặc `RELEASING` sẽ chặn update. Workload guard
trả HTTP `409` với error code `COMPUTE_ACTIVE`; client phải stop/cancel workload và chờ cleanup
thay vì retry update ngay lập tức.

### Xóa Engine Profile an toàn

Xóa profile là thao tác không thể hoàn tác. Trình tự yêu cầu:

1. Dừng engine interactive.
2. Chờ trạng thái engine chuyển thành `STOPPED`.
3. Chờ mọi dbt run hoặc scheduled Notebook run dùng profile hoàn tất cleanup.
4. Chờ mọi thay đổi Python environment đang pending hoàn tất.
5. Gọi API xóa profile.

```bash
curl -i -X DELETE \
  -H 'Authorization: Bearer <access-token>' \
  https://<kyuubi-host>/api/v1/engine-profiles/<profile-id>
```

Thành công trả HTTP `204 No Content`. Thao tác xóa loại bỏ profile đã persist, metadata của Python
environment terminal, builder resource và PVC thuộc profile. Một `JOB` hoàn tất bình thường không
xóa PVC dùng chung này; PVC chỉ được dọn khi profile bị xóa.

Server từ chối xóa khi compute hoặc thay đổi environment còn hoạt động. Allocation guard dùng
`409 COMPUTE_ACTIVE`; validation request có thể dùng `400 INVALID_REQUEST`, ví dụ khi gọi delete
trước khi engine thực sự dừng. Client cần kiểm tra `error.code` có cấu trúc thay vì chỉ dựa vào HTTP
reason phrase.

Profile built-in có ID `default`, lấy cấu hình từ server/Helm và không có profile document được
persist. Profile này có thể dùng để khởi động compute interactive nhưng không thể update/delete như
profile của user. `GET /engine-profiles/default/config` chỉ trả các giá trị launch đã khai báo rõ
cho Driver/Executor memory, cores và executor instances trong `spark-defaults.conf` hoặc cấu hình
Kyuubi; cấu hình Kyuubi được ưu tiên. Field bị thiếu có nghĩa server không đặt giá trị rõ ràng.
Response này không phải snapshot của engine đang chạy và không chứa credential hay Spark property
tùy ý.

Package-change requests accept package names only:

```json
{
  "packages": ["pandas==2.2.3", "seaborn"]
}
```

See [Engine Profile Python Environment Lifecycle](ENGINE_PROFILE_PYTHON_ENVIRONMENT.md) for the
PVC, hot-install, build, and promotion lifecycle.

## SQL Editor session

| Method |      Endpoint      |                             Description                             |
|--------|--------------------|---------------------------------------------------------------------|
| `POST` | `/editor-sessions` | Open a SQL Editor Kyuubi session with an authorized Engine Profile. |

```json
{
  "engineProfileId": "profile-id"
}
```

## Supporting notebook endpoints

| Method |      Endpoint      |                   Description                   |
|--------|--------------------|-------------------------------------------------|
| `GET`  | `/me`              | Get current authenticated user information.     |
| `GET`  | `/notebook-status` | Get Notebook subsystem status and capabilities. |

The implementation sources are the REST resources under
`kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/`: `NotebooksResource`,
`NotebookFoldersResource`, `NotebookCollectionResources`, `NotebookRuntimeResources`,
`NotebookEngineProfilesResource`, `EditorSessionsResource`, and `DbtResources`.
