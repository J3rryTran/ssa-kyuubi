<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Vận hành managed compute cho Notebook và dbt

Tài liệu này mô tả phạm vi backend headless hiện tại. Hệ thống phân tách engine `INTERACTIVE` và
`JOB`, nhưng chưa cung cấp cron scheduler, dashboard hay alert rules.

## Ranh giới lưu trữ và coordination

| Thành phần | Nơi lưu | Vai trò |
| --- | --- | --- |
| Engine Profile hiện hành | S3A/SeaweedFS | Cấu hình profile theo owner; vẫn giữ nguyên khi bật managed compute. |
| Allocation, binding, run và cleanup state | PostgreSQL/MySQL dùng chung | Source of truth runtime giữa các Kyuubi replica. Không dùng SQLite cho HA. |
| Lock reserve và engine discovery | ZooKeeper | Serialize quota/reserve và tìm đúng engine của allocation. |
| Python environment | PVC | Package dùng chung của profile; cleanup một JOB không xóa PVC. |

Không cần migrate Engine Profile khỏi S3. Managed compute chỉ bổ sung metadata runtime vào JDBC.
Không được xóa row allocation đang `RESERVED`, `STARTING`, `ACTIVE` hoặc `RELEASING` để xử lý sự
cố; các row đó là bằng chứng để replica khác tiếp quản cleanup.

## Config tối thiểu

Các feature flag đều mặc định tắt. Chỉ bật transport thực sự dùng:

| Config | Mục đích |
| --- | --- |
| `kyuubi.compute.lifecycle.enabled` | Cho replica claim và reconcile allocation bền vững. |
| `kyuubi.compute.managed.ha.required` | Bật validation bắt buộc shared JDBC và ZooKeeper. |
| `kyuubi.dbt.runner.enabled` | Chạy dbt bằng Kubernetes Job. |
| `kyuubi.dbt.managed.cli.enabled` | Cho wrapper CLI mô phỏng scheduled dbt. |
| `kyuubi.notebook.job.runner.enabled` | Cho run-now API chạy pinned Notebook headless. |

Cấu hình policy hoàn toàn ở server, không nhận quota/timeout từ browser:

```properties
kyuubi.compute.managed.ha.required=true
kyuubi.compute.lifecycle.enabled=true
kyuubi.compute.job.max.concurrent.per-user=2
kyuubi.compute.job.max.concurrent.global=10
kyuubi.compute.engine.registration.timeout=PT60S
kyuubi.compute.job.timeout=PT1H
kyuubi.compute.job.heartbeat.timeout=PT2M
kyuubi.compute.binding.ttl=PT65M
kyuubi.compute.attach.reservation.ttl=PT2M
kyuubi.compute.lifecycle.reconcile.interval=PT5S
kyuubi.compute.lifecycle.claim.ttl=PT30S
kyuubi.compute.lifecycle.cleanup.retry.initial-backoff=PT1S
kyuubi.compute.lifecycle.cleanup.retry.max-backoff=PT1M
kyuubi.compute.lifecycle.cleanup.deadline=PT5M
kyuubi.compute.metadata.min.retention=PT24H
```

Managed CLI dùng thêm `kyuubi.dbt.managed.cli.heartbeat.interval=PT10S`; giá trị này phải nhỏ hơn
`kyuubi.compute.job.heartbeat.timeout`. Kubernetes DBT runner và Notebook runner có timeout riêng,
nhưng timeout đó không được lớn hơn `kyuubi.compute.job.timeout`. Mỗi user và toàn cluster lần lượt
bị giới hạn bởi `kyuubi.compute.job.max.concurrent.per-user` và
`kyuubi.compute.job.max.concurrent.global`.

Khi `kyuubi.compute.managed.ha.required=true` và một managed runner được bật, Kyuubi fail startup
nếu metadata là SQLite, thiếu `kyuubi.ha.addresses` hoặc lifecycle controller chưa bật. Startup
cũng từ chối heartbeat/retry/claim TTL không nhất quán. Cấu hình retention là mức tối thiểu;
implementation hiện giữ metadata terminal lâu hơn và không có đường TTL xóa allocation chưa dọn.

Lần rollout đầu tiên phải cho ít nhất một replica chạy với
`kyuubi.notebook.store.schema.init=true`. Bước này tạo các bảng compute và bổ sung
`activated_at`, `release_requested_at` cho schema cũ. Chỉ tắt schema init sau khi đã kiểm tra các
cột tồn tại trong PostgreSQL/MySQL; nếu tắt từ đầu, status mới không thể đọc schema cũ.

`kyuubi.compute.job.timeout` là deadline cứng. Timeout của Notebook runner hoặc DBT Kubernetes Job
không được lớn hơn deadline này trong chế độ HA.

## RBAC và nhãn truy vết

Helm chỉ cấp quyền managed controller khi bật:

```yaml
rbac:
  create: true
  managedWorkloads:
    enabled: true
```

Kyuubi controller cần quyền có phạm vi namespace đối với Jobs, ConfigMaps, Secrets, PVCs, Pods,
Pod logs và Services. DBT runner dùng ServiceAccount riêng và không kế thừa quyền controller.

Tài nguyên runner có các label `kyuubi.apache.org/dbt-run-id`,
`kyuubi.apache.org/compute-attempt-id` và `kyuubi.apache.org/compute-allocation-id`. Spark Driver và
Executor có `kyuubi-compute-allocation-id`, cùng hash của run/attempt. Binding token và dispatch
token không được đặt trong label hoặc status.

Ví dụ tìm engine của một run từ `allocationId` trong status:

```bash
kubectl get pods -n <namespace> \
  -l kyuubi-compute-allocation-id=<allocation-id> -o wide
```

## Điều tra và retry cleanup

1. Gọi `GET /notebook-runs/{runId}/status` hoặc `GET /dbt-job-runs/{runId}/status`.
2. Ghi lại `allocationId`, `engineIdentity`, `cleanupAttempts`, `cleanupAgeMillis` và
   `cleanupDeadlineAt`.
3. Nếu `cleanupState=OVERDUE`, tìm log bằng chuỗi `allocation=<allocationId>`. Log correlation có
   owner, profile, run, attempt, allocation và engine; giá trị credential/token được redact.
4. Kiểm tra Pod theo label allocation. Không xóa row JDBC và không dùng Stop Engine Profile vì nó
   chỉ nhắm engine interactive.
5. Sau khi sửa lỗi Kubernetes/ZooKeeper, gọi endpoint retry:

```bash
curl -X POST -H 'Authorization: Bearer <access-token>' \
  https://<kyuubi-host>/api/v1/notebook-runs/<run-id>:retry-cleanup
```

Retry cleanup chỉ fence claim cũ và cho reconciler terminate lại đúng allocation. Nó không tạo
attempt, không chạy lại dbt/cells, không xóa output table và không xóa Python environment PVC.
