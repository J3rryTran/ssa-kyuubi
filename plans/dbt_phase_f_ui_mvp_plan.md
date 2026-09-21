# Phase F — UI MVP cho DBT Workspace, Job và Run

## Trạng thái

**Đã triển khai MVP:** thêm API lịch sử run theo workspace/job với ownership
check, route/sidebar DBT, workspace list, workspace detail, Job CRUD,
Preview/Run/Cancel, run history và log viewer polling theo offset. Vẫn cần
manual K3s acceptance và Web UI build trong môi trường người dùng trước khi
đánh dấu hoàn thành toàn bộ Phase F.

Phase này biến luồng DBT đã được xác nhận bằng `curl` ở Phase C–E thành một
giao diện người dùng tối thiểu trong Web UI VTNexus. Đây không phải DBT Studio
đầy đủ và không thay đổi cơ chế runner, proxy-user, Engine Profile snapshot
hoặc Spark engine sharing ở backend.

## 1. Mục tiêu

Người dùng có thể thực hiện toàn bộ luồng sau từ UI:

```text
DBT Workspaces
  -> tạo/chọn workspace và Engine Profile
  -> tạo DBT job (Preview / Run model / Run project)
  -> Run hoặc Cancel
  -> theo dõi state, thời gian, profile snapshot và log DBT
```

Mỗi action tiếp tục dùng profile đã lưu ở DBT workspace/job. Browser chỉ gửi
ID workspace/job/profile, selector và version khi cập nhật; không bao giờ gửi
raw `spark.*`, subdomain, proxy-user, `profiles.yml`, credential Kyuubi hay
Git URL/secret.

## 2. Phạm vi MVP

### Có trong Phase F

- Menu/route DBT riêng trong layout hiện tại.
- Danh sách và tạo DBT workspace.
- Chọn/sửa Engine Profile của workspace bằng danh sách profile server trả về.
- Trang chi tiết workspace: jobs, tạo/sửa/xóa job, Preview, Run project/model.
- Run history, state realtime theo polling, Cancel idempotent và log viewer.
- Hiển thị immutable profile snapshot đã dùng bởi từng run.
- Xử lý loading, empty state, lỗi API, optimistic-lock/version conflict.
- Unit test TypeScript/Vitest cho API adapter và state/polling quan trọng.

### Ngoài phạm vi

- DBT Studio/Git browser, editor model SQL, commit/branch/SSH key.
- Scheduler/cron, retry tự động, DAG lineage/graph, artifact docs hoặc test/build
  UI.
- Cho browser cấu hình runner/namespace/ServiceAccount hoặc Spark config.
- Thay đổi production security policy của Phase D/E.

## 3. Khoảng trống API cần bổ sung trước UI

API hiện có thể lấy một run bằng ID nhưng chưa có endpoint để UI tải lịch sử
run sau khi reload trang. Không dùng localStorage làm source of truth; cần bổ
sung endpoint read-only, có ownership check tại backend:

```text
GET /api/v1/dbt-workspaces/{workspaceId}/runs?limit=50
GET /api/v1/dbt-jobs/{jobId}/runs?limit=50
```

Contract đề xuất:

- Chỉ owner/admin hợp lệ của workspace/job đọc được; user khác nhận cùng kiểu
  resource-not-found hiện có, không lộ run ID.
- Sắp xếp `createdAt DESC`, giới hạn server-side (ví dụ tối đa 100); query
  parameter âm/không hợp lệ bị reject.
- Response là `Seq[DbtJobRun]` hiện hữu, gồm `profile` snapshot, state,
  timestamp, `errorSummary`, `logTruncated`; không thêm credential.
- Không cần endpoint backend mới để polling một run: UI dùng
  `GET /dbt-job-runs/{runId}` đã tồn tại.

Nếu review muốn giảm endpoint, chỉ cần `GET workspace runs`; UI có thể lọc
theo `jobId` phía client cho số lượng run MVP. Khuyến nghị vẫn thêm cả hai để
contract rõ ràng và không buộc UI tải lịch sử quá mức.

## 4. Thiết kế UI và routing

### 4.1 Route/menu

Thêm route lazy-loaded dưới layout:

```text
/dbt                         -> redirect /dbt/workspaces
/dbt/workspaces              -> DbtWorkspaceList.vue
/dbt/workspaces/:workspaceId -> DbtWorkspaceDetail.vue
```

Thêm một mục sidebar `DBT` (icon ví dụ `DataAnalysis`/`TrendCharts`) cùng cấp
với Workspace, Notebook, SQL Editor. Không đặt DBT dưới Engine Management vì
Engine Profile là tài nguyên dùng chung, còn DBT Workspace là workload của
người dùng.

### 4.2 Danh sách workspace

`DbtWorkspaceList.vue` hiển thị table/card responsive:

| Cột | Nguồn dữ liệu |
|---|---|
| Tên workspace | `name` |
| Project | `projectRef` (opaque server-controlled ID) |
| Engine Profile | `selectedEngineProfileId` + summary read-only |
| Cập nhật | `updatedAt` |
| Actions | Open, Edit, Delete |

Nút **New DBT Workspace** mở dialog gồm `name`, `projectRef`, và dropdown
Engine Profile. `projectRef` là giá trị registry mà backend cho phép (MVP có
thể cho nhập text `phase-c-demo`; production nên bổ sung API registry để UI
chọn từ allowlist thay vì gõ tự do). Dropdown profile gọi API profile hiện có,
chỉ thấy profile user có quyền dùng.

Sửa/xóa bắt buộc gửi `version`; nếu backend trả conflict, UI thông báo rằng dữ
liệu đã đổi và tải lại thay vì ghi đè im lặng.

### 4.3 Chi tiết workspace

Header: breadcrumb, tên/projectRef, Engine Profile đang chọn, nút edit profile
và nút **Preview**. Phần profile chỉ hiện tên và summary driver/executor;
không dựng form Spark config tại đây. Việc chỉnh resource vẫn đi qua trang
Engine Profiles đã có.

Hai phần chính:

1. **Jobs** — table tên, action, selector, profile override (nếu có), updated
   time; actions Run/Edit/Delete. Dialog job chỉ cho action hợp lệ:

   | Action | Trường bắt buộc |
   |---|---|
   | `PREVIEW` | selector |
   | `RUN_MODEL` | selector |
   | `RUN_PROJECT` | không cần selector |

   `Preview` nhanh ở header gửi selector đến
   `POST /dbt-workspaces/{workspaceId}:preview`; không cần tạo DBT Job bền
   vững nếu user chỉ xem thử.

2. **Recent runs** — table run ID rút gọn, job/action, state, profile snapshot,
   created/started/finished time, duration, lỗi tóm tắt và action View/Cancel.
   Chọn một row mở panel/drawer Run detail với metadata immutable và log.

### 4.4 Run detail/log viewer

- `DbtRunDetailDrawer.vue` nhận `runId`; load status và log từ backend.
- Dùng font monospace, auto-scroll chỉ khi user đang ở đáy; có nút Copy/Clear
  view, không làm mất log server-side.
- Chỉ strip ANSI SGR escape sequence khi render (`\x1b[...m`) để log dbt dễ
  đọc. Raw log trong backend/JDBC không bị biến đổi.
- Hiển thị cảnh báo rõ khi `logTruncated=true`; không giả vờ đây là full log.
- Hiển thị `CANCEL` khi state active; sau click disable button, gửi
  `POST /dbt-job-runs/{id}:cancel`, reload status ngay và tiếp tục polling cho
  đến terminal state.

## 5. API TypeScript và state management

Tạo module riêng, ví dụ:

```text
web-ui/src/api/dbt/types.ts
web-ui/src/api/dbt/index.ts
web-ui/src/views/dbt/use-dbt-runs.ts
```

Khai báo chính xác enum string ở frontend (`QUEUED`, `DISPATCHING`,
`SUBMITTED`, `RUNNING`, `CANCELLING`, `SUCCEEDED`, `FAILED`, `CANCELLED`) và
interface cho Workspace, Job, Run, EngineProfileSnapshot, LogPage. Cần xử lý
đúng JSON enum format hiện tại server serialize (`{ enumClass, value }`) hoặc
chuẩn hóa tại API adapter để component chỉ làm việc với string `value`.

Các function API MVP:

```text
list/create/get/update/deleteDbtWorkspace
list/create/update/deleteDbtJob
previewDbtWorkspace
runDbtJob
listDbtWorkspaceRuns / listDbtJobRuns
getDbtRun / getDbtRunLogs / cancelDbtRun
```

Không tái sử dụng `api/notebook` một cách mơ hồ; có thể tái sử dụng
`listEngineProfiles` nhưng DBT API và type đặt trong module `api/dbt`.

## 6. Polling và tính đúng lifecycle

`use-dbt-runs.ts` là nơi duy nhất quản lý polling:

1. Load run history khi vào workspace.
2. Poll mỗi 2–3 giây đối với các run active và run đang mở drawer.
3. Với log, gọi `GET logs?offset=<nextOffset>&limit=<pageSize>`; nối thêm
   content chỉ một lần theo `nextOffset`, không reload toàn bộ log liên tục.
4. Dừng polling khi state terminal, component unmount/route đổi hoặc drawer
   đóng (trừ khi còn run active trong workspace list).
5. Có `AbortController`/generation ID để response cũ không ghi đè run mới sau
   khi đổi workspace.
6. Khi request tạm lỗi, hiển thị trạng thái reconnect và backoff có giới hạn;
   không tự đổi DBT run sang `FAILED` ở browser.

Frontend chỉ phản ánh state server trả về, không tự suy diễn Job Kubernetes
đã hoàn tất từ việc spinner dừng.

## 7. Xử lý lỗi và quyền

Map error envelope hiện hữu vào thông báo dễ hiểu:

| Backend code/tình huống | UI |
|---|---|
| `DBT_RUNNER_NOT_CONFIGURED` | Banner hướng dẫn admin bật runner; không tạo state giả |
| resource not found/access denied | Quay về list, thông báo workspace/job không còn quyền truy cập |
| version conflict | Reload entity và yêu cầu user thao tác lại |
| validation selector/profile | Giữ dialog mở, highlight field |
| runner failure | State `FAILED`, hiện `errorSummary` và log |

Mọi check quyền thực vẫn do backend thực hiện; việc ẩn nút trong UI chỉ là UX,
không phải authorization.

## 8. Thứ tự triển khai

1. **Backend read API nhỏ:** store/service/resource test cho list run theo
   workspace/job, ownership, ordering và limit.
2. **API layer frontend:** types, API functions và enum normalizer; unit test.
3. **Routing/menu/list:** route DBT, sidebar, workspace list + create/edit/delete
   dialog và Engine Profile dropdown.
4. **Workspace detail/jobs:** CRUD job, preview/run controls, version/error UX.
5. **Runs/log viewer:** run history, centralized polling, incremental log,
   cancel và ANSI presentation cleanup.
6. **Hardening UI:** loading/empty/error states, responsive layout, accessibility,
   cleanup timers/request abort.
7. **Verification:** frontend type-check/lint/unit test, build Web UI and manual
   K3s acceptance.

Mỗi bước nên là commit reviewable; không trộn thay đổi DBT backend lifecycle
với thay đổi CSS lớn không liên quan.

## 9. Test và acceptance criteria

### Backend

- Owner list run của workspace/job mình thành công; user khác không đọc được.
- List sort mới nhất trước, limit được cap/reject hợp lệ.
- Run response không chứa credential/raw runner config.

### Frontend automated

- API enum normalizer và request payload không chứa `sparkConfig`/subdomain.
- Poller không tạo timer kép, ngừng ở terminal/unmount, không nối trùng chunk
  log cùng offset.
- ANSI stripping chỉ áp dụng presentation và `truncated` được hiển thị.

### Manual K3s demo

1. Đăng nhập user A; tạo workspace dùng `phase-c-demo` và Engine Profile A.
2. Tạo Run project; quan sát `SUBMITTED/RUNNING/SUCCEEDED`, log tăng dần và
   profile snapshot đúng A.
3. Reload browser trong lúc run: lịch sử/state/log vẫn lấy lại từ API.
4. Cancel một run đủ lâu; UI chuyển `CANCELLING -> CANCELLED`, Job biến mất,
   Spark engine shared không bị terminate.
5. Sửa profile sau khi submit; run detail vẫn hiện snapshot cũ.
6. Login user B; xác nhận không nhìn/thao tác workspace/run của A và không
   reuse engine A.
7. Tắt runner cấu hình test; UI hiển thị lỗi `DBT_RUNNER_NOT_CONFIGURED` rõ
   ràng, không treo loading.

Phase F MVP hoàn thành khi user thực hiện được end-to-end DBT run/cancel/log
từ browser mà không dùng `curl`, và tất cả quyết định Engine Profile vẫn nằm
ở backend.

## 10. Điểm cần chốt trước khi code

1. MVP có cho nhập tự do `projectRef` hay cần một endpoint registry allowlist
   ngay từ đầu? Khuyến nghị demo giữ `phase-c-demo` input có validation; không
   đưa Git URL vào UI.
2. Có cần hiển thị/cho phép `engineProfileIdOverride` ở cấp Job ngay Phase F
   không? Khuyến nghị hiển thị và hỗ trợ tùy chọn này vì backend đã có, nhưng
   mặc định luôn kế thừa profile của workspace để UX đơn giản.
3. Giữ UI bằng tiếng Anh theo Web UI hiện tại hay thêm i18n Việt? Khuyến nghị
   giữ tiếng Anh trong Phase F để nhất quán với codebase.
