<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Kiểm thử scheduled Notebook không cần UI

Đường chạy này mô phỏng một lần scheduler kích hoạt Notebook. Nó chưa tính cron hay timezone.
Client chỉ gửi Notebook ID và idempotency key bắt buộc; Kyuubi tự lấy source revision và Engine
Profile đã lưu trong schedule.

## Contract

- Lần đầu lưu schedule tạo một revision bất biến và ghim ID, số revision, SHA-256 vào schedule.
- Sửa cell trên UI không đổi source đã ghim. Chỉ `schedule:pin-current` hoặc
  `refreshSource=true` mới chuyển schedule sang source hiện hành.
- Mỗi lần dispatch tạo session và allocation `NOTEBOOK/SCHEDULE/JOB` riêng. Nó không reuse engine
  interactive `ep-*` dù dùng cùng Engine Profile.
- Runner nằm trong Kyuubi và attach allocation trực tiếp; không tạo binding token dành cho external
  runner như luồng managed dbt CLI.
- Các cell chạy tuần tự trên cùng runtime của attempt. Markdown không được submit.
- Success, failure, cancel và timeout đều dừng session và tạo yêu cầu dọn JOB allocation.
- Gửi lại cùng `clientRequestId` trả về cùng run; unique index JDBC ngăn hai replica cùng claim.
- Revision được ghim là protected và được giữ lại để audit kể cả sau khi xóa schedule. Việc thu gom
  revision protected cũ cần một retention/GC riêng, không được xóa theo trim thông thường.

## Cấu hình server

```properties
kyuubi.notebook.job.runner.enabled=true
kyuubi.notebook.job.runner.threads=4
kyuubi.notebook.job.poll.interval=250
kyuubi.notebook.job.run.timeout=PT1H
```

`poll.interval` có thể dùng số millisecond. Với các giá trị có đơn vị nên dùng ISO-8601 như
`PT1H`, tránh dạng `1h` hoặc `10s`.

Ví dụ trên dùng timeout một giờ, bằng giá trị mặc định của `kyuubi.compute.job.timeout`.
Khi bật `kyuubi.compute.managed.ha.required=true`, timeout của Notebook job không được lớn hơn
compute job timeout; cấu hình không nhất quán sẽ bị từ chối khi Kyuubi khởi động. Nếu cần chạy
lâu hơn, phải tăng cả hai timeout cho phù hợp.

## Chuẩn bị schedule

Tạo Notebook và lưu schedule bằng API hiện có:

```http
PUT /api/v1/notebooks/<notebook-id>/schedule
Content-Type: application/json

{
  "cronExpression": "0 3 * * *",
  "timezone": "Asia/Ho_Chi_Minh",
  "enabled": true,
  "runtimeProfile": "<profile-id>"
}
```

Sau khi sửa source trên UI, schedule vẫn dùng bản cũ. Khi muốn triển khai source mới:

```http
POST /api/v1/notebooks/<notebook-id>/schedule:pin-current
Content-Type: application/json

{"version": <schedule-version>}
```

## Chạy một occurrence từ CLI

```bash
export KYUUBI_MANAGED_API_BEARER_TOKEN='<token>'

bin/kyuubi-managed-notebook \
  --api-base http://<kyuubi-host>:10099/api/v1 \
  --notebook-id '<notebook-id>' \
  --client-request-id 'manual-occurrence-001'
```

Với môi trường test không dùng bearer, có thể đặt `KYUUBI_MANAGED_API_USER` và
`KYUUBI_MANAGED_API_PASSWORD`, hoặc `KYUUBI_MANAGED_API_COOKIE`.

Wrapper trả `0` khi thành công, `2` khi run thất bại, `130` khi bị cancel và `70` khi API/setup
lỗi. `Ctrl+C` gọi API cancel thay vì chỉ giết client.

## Kiểm tra kết quả

```http
GET /api/v1/notebook-runs/<run-id>
GET /api/v1/notebook-runs/<run-id>/executions
GET /api/v1/executions/<execution-id>/events
```

Run phải chứa `sourceRevisionId`, `sourceRevisionNumber`, `sourceChecksum`, `attemptId` và
`computeAllocationId`. Danh sách executions phải có đúng source snapshot của revision được ghim,
không phải source Notebook đang được sửa trên UI. Khi cell terminal, trường `auditSnapshot` giữ bản
JSON có giới hạn dung lượng của log, output và kết quả SQL để vẫn đọc được sau khi JOB engine bị
dọn.

Trong Kubernetes, scheduled run phải tạo Driver tên nhánh `job-*`; session interactive cùng
profile vẫn ở nhánh `ep-*`. Sau khi run terminal, poll đến khi Driver/Executor của allocation JOB
biến mất, trong khi engine interactive vẫn chạy được.

## Giới hạn

Đây là run-now API/test harness cho một occurrence. Cron controller, misfire, catch-up và giao diện
scheduler không nằm trong phạm vi này; scheduler production sau này gọi lại cùng API dispatch.
