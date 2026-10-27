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

# Plan: Điều phối engine dùng chung cho Notebook và dbt

Ngày lập: 2026-10-15.

Điều chỉnh phạm vi: 2026-10-16 — chỉ triển khai backend phân luồng engine và kiểm thử headless; chưa xây UI hoặc bộ lập lịch tự động.

Trạng thái: **Task 0–7 đã hoàn thành và được người dùng kiểm thử; Task 9 đã hoàn tất phần code và
regression test, đang chờ người dùng chạy test cùng kiểm chứng PVC trên Kubernetes.**

Kết quả Task 0: `docs/plans/2026-10-16-shared-compute-task-0-contract.md`.

## 1. Mục tiêu và phạm vi

Một Engine Profile có thể cấp nhiều engine với cùng cấu hình, nhưng khác mục đích và vòng đời:

- Interactive: user làm việc trực tiếp bằng SQL Editor, Notebook hoặc dbt CLI; tái sử dụng engine theo user/profile, thu hồi theo idle timeout.
- Job: một lần thực thi Notebook hoặc dbt dùng engine riêng; khi thành công, thất bại hoặc bị hủy thì thu hồi engine của lần thực thi đó.
- Schedule là một nguồn kích hoạt Job. Giai đoạn này dùng test harness/script gọi backend để mô phỏng việc scheduler dispatch một lần chạy, không cần cron hoặc nút Run Job Now trên UI.
- Chỉ mô phỏng nguồn kích hoạt; allocation, xác thực, runner Notebook/dbt, engine Spark và cleanup trong bài test end-to-end phải chạy thật.

**Điểm dừng của giai đoạn này:** chạy được script/test chứng minh interactive và job dùng engine khác nhau, toàn bộ cells/models của một job dùng đúng engine được cấp, job kết thúc thì engine riêng được dọn và engine interactive vẫn dùng được. Không cần mở giao diện demo scheduler để nghiệm thu.

Ví dụ:

```text
Alice / Profile A
  ├── Interactive engine: Notebook UI + SQL Editor + dbt CLI trực tiếp
  ├── Job engine N: toàn bộ cells của notebook attempt N
  └── Job engine D: toàn bộ SQL/Python models của dbt attempt D
```

Tách engine không đồng nghĩa tách dữ liệu: các workload vẫn có thể ghi cùng bảng. Không cam kết exactly-once cho thao tác ghi dữ liệu hoặc tự retry workload sau lỗi không rõ kết quả commit.

### Các ràng buộc giữ nguyên

- Engine Profile vẫn là một cấu hình hiện hành trên S3; không khôi phục profile revision hoặc index profile trong JDBC.
- Không tự stop engine khi user Save profile. Server chặn update và yêu cầu chủ động dừng các compute liên quan.
- Python Environment và dbt run metadata hiện vẫn dùng shared JDBC; plan này mở rộng metadata điều phối, không chuyển chúng sang S3.
- Notebook document tiếp tục dùng store đã cấu hình; không chuyển hoặc xóa dữ liệu notebook trong thay đổi này.
- Notebook và dbt dùng chung lớp cấp phát engine, nhưng có runner riêng.
- Không sửa cơ chế OIDC hoặc suy ra owner từ request body. Quyền được kiểm tra từ authenticated principal/trusted runner identity.
- Triển khai tuần tự từng task. Chỉ sửa code/tài liệu; người dùng chủ động chạy build/test và cung cấp kết quả.
- Không sửa frontend hoặc tạo màn hình lịch sử job/lịch chạy. Giữ API interactive tương thích; bổ sung backend submit/status/cancel tối thiểu nếu cần cho test và tích hợp scheduler sau này.

### Ngoài phạm vi ban đầu

- Scheduler tự động: cron, timezone/DST, tính nextRunAt, catch-up/misfire, claim occurrence và overlap policy theo lịch.
- UI scheduler, nút Run Job Now, dashboard đa engine và màn hình lịch sử job mới.
- Nền tảng quản lý artifacts, dashboard/alerting đầy đủ và hàng đợi công bằng theo quota. Chỉ cần giới hạn an toàn, log và kết quả test đủ để xác minh.
- Chia sẻ engine giữa các scheduled attempts để giảm cold start.
- Chuyển toàn bộ runtime metadata khỏi JDBC.
- Tự động retry mọi lỗi ghi bảng, resume notebook từ cell bất kỳ sau khi mất Python session.
- Điều phối transaction giữa notebook và dbt cùng ghi một bảng.
- Hỗ trợ mọi loại engine khác Spark SQL/Python.

## 2. Hiện trạng cần xử lý

Các đường dẫn Scala trong plan được viết tương đối với `kyuubi-server/src/main/scala/org/apache/kyuubi/server/`, trừ khi ghi khác.

- `engineprofile/EngineProfileSessionConfigResolver.scala`: dbt qua Thrift được resolve profile và subdomain ở đây.
- `notebook/runtime/KyuubiSqlRuntimeAdapter.scala`: `SparkNotebookRuntimeAdapter` mở backend session trực tiếp. Chỉ sửa Thrift resolver không bao phủ notebook.
- `notebook/service/NotebookSessionService.scala`: chứa `NotebookRuntimeService`, chọn profile/config khi tạo hoặc tái sử dụng runtime.
- `engineprofile/EngineProfileRuntimeService.scala`: quản lý engine theo profile; cần phân biệt engine interactive với từng job allocation.
- `engineprofile/EngineProfileRunSnapshotService.scala`: lưu run binding/lease nhưng resolve hiện đọc lại profile; chưa chốt đầy đủ launch config cho một job.
- `docker/dbt-kyuubi/src/dbt/adapters/kyuubi/connections.py`: tự sinh và ghi đè run ID; cần phân biệt local invocation với managed job.
- `dbt/KubernetesJobDbtRunner.scala`: cancel runner Job và cleanup ConfigMap chưa phải cleanup Spark engine.
- `notebook/service/NotebookScheduleService.scala`: mới có persistence/validation lịch, chưa có vòng tự kích hoạt và thực thi notebook; giữ nguyên phạm vi này, không triển khai cron trong giai đoạn hiện tại.
- `notebook/api/NotebookRuntimeModels.scala`: có model `NotebookRun`, nhưng cần hoàn thiện orchestration để chạy cả notebook ngoài UI.
- `engineprofile/PythonEnvironmentService.scala`: guard engine và mount PVC phải tính cả job engine; PVC hiện có cấu hình `ReadWriteOnce` cần kiểm chứng khi chạy đa node.

Các nhận định này là baseline lúc lập plan; khi bắt đầu task phải đọc lại diff hiện có, không ghi đè công việc chưa commit.

## 3. Contract đề xuất

### 3.1. Ba thuộc tính độc lập

- `workloadType`: `NOTEBOOK`, `DBT`, `SQL`.
- `triggerType`: `MANUAL`, `SCHEDULE`, `API`.
- `computeMode`: `INTERACTIVE`, `JOB`.

Server quyết định mode theo API/policy. Người dùng không được dùng raw subdomain hoặc mode để attach tùy ý vào allocation khác.

`triggerType=SCHEDULE` chỉ là metadata do đường dispatch được cấp quyền thiết lập, không phải bằng chứng quyền. Test harness đóng vai bên dispatch; không thêm endpoint bỏ qua auth, không cho browser tự khai owner/token và không chèn allocation trực tiếp vào DB trong bài E2E.

### 3.2. Định danh

- `runId`: lần chạy nghiệp vụ; hiện được tạo qua backend submit, sau này scheduler gọi cùng đường đó cho mỗi occurrence.
- `attemptId`: một lần thử thực thi của run; retry tạo attempt mới.
- `allocationId`: cấp phát compute cho attempt; Job ban đầu có quan hệ một attempt/một allocation.
- `sessionId` và `operationId`: kết nối và thao tác nằm bên trong allocation.
- dbt invocation ID trong artifacts vẫn giữ ý nghĩa của dbt; lưu mapping, không bắt buộc bằng backend run ID.

Interactive giữ subdomain hiện hành `ep-<profile-id>` theo chuẩn sinh tên thực tế của code. Built-in default giữ hành vi định danh tương tác hiện có. Job dùng subdomain server sinh từ allocation ID, ví dụ `job-<allocation-id>`.

Engine identity phải bao gồm effective user, share level, engine type, subdomain và discovery namespace thực tế; không coi subdomain riêng lẻ là toàn bộ engine key.

### 3.3. State và quyền sở hữu

```text
Allocation: RESERVED → STARTING → ACTIVE → RELEASING → RELEASED
                                    ↘ cleanup retry nếu chưa thu hồi được

Workload:   QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED
```

- Allocation lỗi trước hoặc trong launch cũng phải đi qua reconciliation/cleanup; không giả định chưa thấy engine tức là không có tài nguyên.
- Kết quả workload và trạng thái cleanup độc lập. Workload thành công có thể đang chờ cleanup.
- Chỉ allocation ACTIVE/STARTING phù hợp policy mới cho attach; RELEASING/RELEASED không nhận attach mới.
- Một khoảng trống giữa hai cell/model không phải job kết thúc. Không dùng session count bằng 0 làm tín hiệu duy nhất để kill job engine.
- Engine bị mất trong lúc job chạy làm attempt thất bại; không âm thầm launch engine khác rồi tiếp tục notebook ở cell sau.

### 3.4. Quyết định mặc định chờ duyệt

- Dùng shared PostgreSQL/MySQL hiện có cho allocation/attempt/claim; SQLite chỉ cho unit test hoặc development đơn replica.
- Chưa triển khai overlap/misfire policy của schedule. Test chủ động submit hai jobs khác nhau cùng profile để chứng minh isolation; cùng một submit idempotency key không tạo hai attempts.
- Chưa tự retry workload; retry hạ tầng cleanup được phép và phải idempotent.
- Job snapshot cấu hình và source khi bắt đầu dispatch, không giữ khóa profile suốt thời gian nằm trong queue.
- Chặn update/delete profile từ lúc reserve allocation đến khi xác nhận engine đã dọn; không chặn chỉ vì tồn tại một lịch chạy tương lai.
- Cả interactive và job allocations được kiểm tra khi cập nhật Python environment hoặc dọn PVC.
- Chưa cho người dùng tự chọn `JOB` qua raw Thrift parameters. Managed backend dispatch cấp binding, script/test sử dụng cùng auth và policy như runner thật.
- Scheduler tương lai chỉ gọi submit/status/cancel contract này; không tự sinh subdomain hoặc tự kill engine.

## 4. Danh sách task triển khai

Mỗi task gồm code và regression test. Checkbox chỉ đánh dấu hoàn thành khi người dùng đã chạy kiểm thử tương ứng và kết quả được ghi lại.

### Task 0 — Chốt contract và kiểm kê entry points

Phụ thuộc: duyệt plan.

- [x] Liệt kê toàn bộ đường mở engine/session: SQL Editor, notebook interactive, Thrift dbt, Start Engine UI, managed dbt runner và notebook job.
- [x] Xác nhận nguồn authenticated user, proxy-user policy và các config có thể override routing trên từng đường.
- [x] Chốt quy tắc built-in default, profile bị xóa/đổi quyền trước dispatch, retry và submit idempotency; chưa thiết kế cron/overlap.
- [x] Chốt job timeout, giới hạn số engine thử nghiệm, payload source và retention metadata tối thiểu; chưa làm hệ thống artifacts/quota queue đầy đủ.
- [x] Chốt giao diện headless submit/status/cancel và quyền của test harness; ưu tiên reuse API/service hiện có, không tạo test-only auth bypass.
- [x] Lưu baseline interactive/idle-timeout tests để không làm mất các bản vá hiện có.

File khảo sát: `NotebookManager.scala`, `EngineProfileSessionConfigResolver.scala`, `EditorSessionsResource.scala`, các service/runner đã liệt kê ở mục 2.

Đạt khi: mỗi entry point có một chủ thể xác thực, một policy chọn allocation và không có đường mở session bỏ qua guard.

### Task 1 — Tạo model và shared store cho compute allocation

Phụ thuộc: Task 0.

File dự kiến:

- Thêm `compute/ComputeModels.scala`, `compute/ComputeStore.scala`, `compute/JDBCComputeStore.scala`.
- Thêm DDL/migration tương ứng trong `src/main/resources/sql/notebook/{mysql,postgresql,sqlite}/`.
- Wire initialize/close ở `notebook/NotebookManager.scala`.

Công việc:

- [x] Lưu allocation ID, owner, profile ID, mode, run/attempt reference, full engine identity và launch snapshot.
- [x] Lưu trạng thái, version CAS, controller claim/expiry, heartbeat, application ID, cleanup retry và lỗi gần nhất.
- [x] Lưu launch config không chứa password/token; secret giữ reference và resolve qua cơ chế riêng.
- [x] Tạo unique constraints chống hai allocation cho cùng job attempt; interactive có thể có nhiều lịch sử allocation nhưng chỉ một allocation mở cho một engine identity.
- [x] Viết transaction/CAS cho reserve, transition, claim cleanup và release; không dùng lock trong JVM làm bảo đảm HA.
- [x] Migration additive, chạy lại an toàn; không drop profile legacy hoặc dữ liệu hiện có trong task này.

Kiểm thử: hai store instance cùng reserve/claim, CAS thất bại, process restart, đọc lại snapshot và migration trên cả ba dialect.

Đạt khi: hai replica nhìn cùng allocation và chỉ một bên sở hữu transition/claim tại một thời điểm.

Trạng thái ngày 2026-10-16: người dùng đã xác nhận `ComputeStoreSuite` chạy thành công. Store mới
dùng shared JDBC metadata, snapshot JSON bất biến, unique key cho attempt/open engine, version CAS
và controller-generation fencing. Schema mới chỉ thêm bảng `compute_allocation`; không sửa hoặc
xóa bảng legacy.

### Task 2 — Cấp phát compute và chốt cấu hình an toàn

Phụ thuộc: Task 1.

File dự kiến: thêm `compute/ComputeAllocationService.scala`; sửa `EngineProfileService.scala`, `EngineProfileRuntimeService.scala`, `NotebookManager.scala`.

- [x] Implement acquire interactive và reserve job; phân biệt việc tạo allocation với thực sự launch engine.
- [x] Kiểm tra owner/quyền profile và chốt Spark config, timeouts, Python env ID, runtime/image reference tại thời điểm reserve.
- [x] Giữ profile revisionless; version nội bộ chỉ phục vụ CAS, không xuất hiện lại trên UI như revision.
- [x] Sinh routing config từ allocation; chặn raw client overrides đối với user/share/subdomain/launch config đã chốt.
- [x] Dùng cùng coordination boundary cho reserve và profile update/delete, tránh check-then-launch race giữa S3 profile và JDBC allocation.
- [x] Quy định lock ordering và recovery khi crash giữa ghi S3 và metadata; không giả định có transaction xuyên S3/JDBC.
- [x] Tính allocations RESERVED/STARTING/ACTIVE/RELEASING trong guard Save/Delete/Stop và publication Python environment.
- [x] Đặt giới hạn concurrency an toàn bằng claim atomic trước launch; khi hết giới hạn trả lỗi rõ ràng. Hàng đợi/quota fairness hoãn sang giai đoạn sau.

Kiểm thử: reserve đối đầu update/delete, hai job cùng profile, user khác, default profile và snapshot không đổi giữa các session.

Đạt khi: một profile có thể cấp nhiều engine job riêng nhưng không bị sửa/xóa trong khoảng launch hoặc đang sử dụng.

Quy tắc coordination đã triển khai trong Task 2:

- Reserve JOB lấy khóa global ZooKeeper trước, sau đó lấy khóa owner của Engine Profile, rồi mới
  kiểm tra quota, chụp cấu hình và ghi allocation JDBC.
- Update/delete/publication của profile chỉ lấy khóa owner rồi kiểm tra các allocation đang mở;
  không có chiều khóa owner → khóa global, tránh deadlock.
- Interactive dựa vào unique open-engine key trong JDBC để hội tụ về một allocation khi hai
  replica reserve đồng thời.
- S3 và JDBC không được coi là một transaction. Nếu ghi allocation thành công rồi replica chết,
  allocation vẫn là bản ghi recovery ở trạng thái `RESERVED`; profile bị chặn thay đổi cho đến khi
  controller ở Task 5 claim và cleanup/release bản ghi đó.

Trạng thái ngày 2026-10-19: người dùng đã xác nhận toàn bộ test Task 2 thành công. Task này chỉ
reserve và sinh routing config, chưa launch/attach engine.

### Task 3 — Execution binding và quyền attach của managed job

Phụ thuộc: Task 2.

File dự kiến: thêm `compute/ComputeBindingService.scala`; sửa `EngineProfileSessionConfigResolver.scala`, config liên quan trong `NotebookConf.scala`.

- [x] Cấp binding cho attempt gồm allocation ID và credential ngắn hạn có scope; ID riêng lẻ không phải quyền truy cập.
- [x] Kiểm tra binding với authenticated/trusted runner identity, effective user, profile và trạng thái attempt trước attach.
- [x] Lưu hash token hoặc dùng cơ chế ký/verify được quản lý server-side; không đưa credential vào logs, profile S3, artifacts hay API trả cho user khác.
- [x] Có expiry/rotation đủ cho job dài; completion/cancel thu hồi binding ngay.
- [x] Reserve attach trước OpenSession và xác nhận sau OpenSession; nếu release thắng race thì đóng session vừa mở. Việc quét/dọn engine launch muộn thuộc reconciler Task 5.
- [x] Hết hạn hoặc sai binding phải fail rõ ràng, không fallback sang interactive engine.
- [x] Identity của managed dispatcher/test harness không được giả danh user tùy ý; reuse proxy authorization hiện có, giới hạn quyền ở job được cấp. Scheduler sau này phải tuân thủ cùng contract.

Trạng thái ngày 2026-10-19: đã hoàn tất code Task 3 và bổ sung
`ComputeBindingServiceSuite`. Credential chỉ trả một lần, JDBC chỉ lưu SHA-256; OpenSession dùng
handshake reserve/confirm và tự đóng session nếu allocation bị release trong lúc attach. Người
dùng đã xác nhận toàn bộ test Task 3 thành công.

Kiểm thử: token sai owner/profile/attempt, replay sau terminal, expired token, attach đồng thời với cancel và routing config giả mạo.

Đạt khi: không thể đoán run ID hoặc sửa `profiles.yml` để dùng engine của job khác.

### Task 4 — Nối các đường session vào lớp compute chung

Phụ thuộc: Task 2, 3.

File chính: `EngineProfileSessionConfigResolver.scala`, `NotebookSessionService.scala`, `KyuubiSqlRuntimeAdapter.scala`, `EditorSessionsResource.scala`, `EngineProfileRuntimeService.scala`.

- [x] Thrift resolver nhận allocation từ binding; interactive không có managed binding vẫn giữ hành vi tương thích.
- [x] Notebook runtime service nhận allocation rõ ràng; adapter chỉ dùng server-generated routing, không tự chọn mode.
- [x] SQL Editor và thao tác Start/Stop UI đăng ký/tra allocation interactive nhất quán.
- [x] Job notebook không đọc lại profile đang chỉnh trong document mỗi lần mở/restart session.
- [x] Đóng một session không release cả allocation có session/workflow khác.
- [x] Health-check session vẫn hoạt động; recovery interactive giữ hành vi cũ, job không âm thầm resume mất Python state.
- [x] Bảo vệ cả các config cell/runtime có thể thay đổi identity hoặc launch resources.

Trạng thái ngày 2026-10-19: runtime Notebook lưu `computeAllocationId` bền vững; Notebook
interactive, SQL Editor, Start Engine và Thrift/dbt interactive dùng chung allocation theo
user/profile. Managed Notebook nhận allocation rõ ràng và không tự khởi tạo lại Python state khi
runtime bị mất. Runtime/cell chỉ được giữ `spark.sql.*`; identity, subdomain và launch resources do
server sinh. Đã bổ sung `NotebookComputeRoutingSuite`; chờ người dùng xác nhận test.

Kiểm thử: SQL + Python trên cùng allocation, notebook và dbt interactive cùng profile, hai user, nhiều Kyuubi replica và regression idle-timeout.

Đạt khi: mọi đường in-scope sử dụng cùng quyết định routing, nhưng không thay đổi trải nghiệm interactive hiện tại.

### Task 5 — Reconciler dọn engine theo allocation

Phụ thuộc: Task 1–4. Hoàn thành trước khi bật dedicated job engine cho người dùng.

File dự kiến: thêm `compute/ComputeLifecycleReconciler.scala`; refactor phần start/stop thực tế từ `EngineProfileRuntimeService.scala`; wire lifecycle trong `NotebookManager.scala`.

- [x] Terminal/cancel tạo cleanup intent durable; claim cleanup bằng CAS có timeout, replica khác tiếp quản được sau crash.
- [x] Dừng nhận attach, cancel/close operations và sessions thuộc allocation rồi kill đúng engine application.
- [x] Lưu namespace, application ID/pod UID và attempt label khi có thể; không kill chỉ theo profile name/subdomain scan mơ hồ.
- [x] Xác nhận application/Driver/Executor thuộc allocation đã kết thúc; discovery node biến mất chưa đủ đánh dấu RELEASED.
- [x] Retry idempotent với backoff; giữ cleanup PENDING khi Kubernetes/ZooKeeper lỗi và expose lỗi cho vận hành.
- [x] Phát hiện engine launch trễ sau cancel/timeout; chặn launch claim cũ và quét tài nguyên mang allocation label.
- [x] Kiểm tra claim generation trước side effect; lease hết hạn không tự hủy được API call đang bay, nên cần identity bất biến và reconciliation sau đó.
- [x] Job engine không bị idle-kill giữa các cell/model khi attempt còn hợp lệ; có heartbeat, deadline và orphan policy hữu hạn.
- [x] Không kết luận job chết chỉ vì TTL OpenSession hết; mất kết nối control plane tạm thời không phải bằng chứng workload đã dừng.
- [x] Cleanup không xóa output tables, shared Python PVC hoặc tài nguyên interactive.

Trạng thái ngày 2026-10-19: lifecycle controller được feature-flag bằng
`kyuubi.compute.lifecycle.enabled` và mặc định tắt để rollout schema an toàn. Allocation lưu riêng
controller heartbeat với workload heartbeat/deadline, engine reference, application ID và pod UID.
Cleanup revoke binding trước, đóng session cục bộ, kill theo engine reference/allocation label rồi
chỉ chuyển `RELEASED` sau khi không còn Driver/Executor mang label. Lỗi hạ tầng giữ allocation ở
`RELEASING`, có backoff và cho replica khác takeover sau khi claim hết hạn. API heartbeat và cleanup
intent đã sẵn sàng; việc nối callback terminal/heartbeat của dbt runner và notebook scheduler nằm ở
Task 6 và Task 7.

Kiểm thử: success/failure/cancel, kill replica ở từng bước, Kubernetes unavailable, duplicate cleanup, engine đăng ký muộn và job dài không mở session mới.

Đạt khi: không có đường terminal bỏ qua cleanup intent; engine job được thu hồi sau khi hạ tầng phục hồi mà không ảnh hưởng engine khác.

### Task 6 — Tích hợp managed dbt runner và adapter

Phụ thuộc: Task 3–5.

File chính:

- `dbt/DbtModels.scala`, `DbtStore.scala`, `JDBCDbtStore.scala`, `DbtServices.scala`, `KubernetesJobDbtRunner.scala`.
- `docker/dbt-kyuubi/src/dbt/adapters/kyuubi/connections.py`, `python_submissions.py` và tests.
- `docker/dbt-runner/` khi cần chuyển binding vào runner an toàn.

- [x] Thêm attempt/allocation reference và mode; không gộp backend run ID với dbt invocation ID.
- [x] Adapter phân biệt local invocation với managed binding; không ghi đè định danh backend hợp lệ và cũng không tin raw client-supplied binding.
- [x] SQL connection pool và Python helper dùng cùng binding; không sinh allocation cho từng model/thread.
- [x] Cấp credential bằng Kubernetes Secret hoặc cơ chế runtime bảo mật tương đương, không để trong ConfigMap/project source.
- [x] Runner báo start/heartbeat/terminal; backend reconcile được ngay cả khi callback mất hoặc runner bị kill.
- [x] Đóng runner không đồng nghĩa engine đã dọn; kết quả job và cleanup trạng thái riêng.
- [x] Lưu log/artifacts cần thiết trước TTL runner; failure thu artifacts không được ngăn cleanup compute.
- [x] Threads được cấu hình server-side có giới hạn; regression CLI local vẫn dùng mode interactive.
- [x] Ghi rõ tập commands managed runner hỗ trợ; không mặc định mọi command CLI đã có API runner tương ứng.

Trạng thái ngày 2026-10-20: một managed DBT run tạo một `attemptId` và một JOB allocation trước
khi dispatch. Binding dùng chung cho mọi SQL/Python connection được chuyển qua Kubernetes Secret;
`profiles.yml` chỉ chứa lời gọi `env_var`. Adapter chỉ giữ định danh managed khi đủ toàn bộ binding,
còn CLI local tiếp tục tự sinh run ID interactive. Reconciler heartbeat allocation khi run còn active,
revoke toàn bộ binding và tạo cleanup intent trước khi ghi trạng thái terminal. Runner lưu log cùng
`manifest.json`/`run_results.json` trước TTL và tách `dbt invocation_id` khỏi backend run ID.
Trạng thái terminal của workload và `runnerResourcesCleaned` được lưu riêng; lỗi xóa Secret/
ConfigMap được giữ lại và retry sau restart mà không đổi kết quả workload.
Threads do server đặt bằng `kyuubi.dbt.runner.threads`, bị chặn bởi
`kyuubi.dbt.runner.threads.max`. Managed API hiện chỉ cam kết `dbt show --select`,
`dbt run --select` và `dbt run`; command matrix đầy đủ vẫn là contract của CLI local, không tự động
trở thành contract của Kubernetes runner.

Kiểm thử: SQL → Python → SQL, threads 2/4, hai jobs cùng profile, CLI chạy đồng thời, Python failure, SIGTERM và cancel.

Đạt khi: một managed dbt attempt dùng đúng một allocation; khi attempt hết, engine riêng được dọn và interactive engine còn hoạt động.

### Task 6b — Mô phỏng scheduled dbt job bằng CLI local

Phụ thuộc: Task 3–6. Đây là đường kiểm thử bổ sung cho managed dbt, không thay thế runner
Kubernetes nếu production vẫn chọn Kubernetes Job làm execution transport.

Mục tiêu của task này là chạy trực tiếp một project dbt có sẵn trên máy kiểm thử, nhưng vẫn đi qua
đúng contract cấp phát `JOB` như một lần schedule dispatch. Không cần DBT runner image, Kubernetes
Job, ConfigMap chứa project, UI scheduler hoặc cron controller.

File dự kiến:

- Bổ sung API/service tối thiểu trong `dbt/DbtServices.scala` và resource dbt tương ứng để chuẩn bị,
  heartbeat và kết thúc một managed CLI attempt.
- Thêm script `bin/kyuubi-managed-dbt` hoặc công cụ tương đương để bọc lệnh dbt local.
- Tái sử dụng managed binding trong
  `docker/dbt-kyuubi/src/dbt/adapters/kyuubi/connections.py`; không tạo một cơ chế routing thứ hai.
- Thêm fixture và hướng dẫn trong `tests/integration/compute-isolation/`.

- [x] Tách rõ hai cách chạy cùng một project:
  - `dbt run` trực tiếp là `INTERACTIVE` và tiếp tục reuse engine theo user/profile.
  - `kyuubi-managed-dbt --job-id ...` lấy action/selector từ DBT job, gọi backend dispatch với
    `triggerType=SCHEDULE`, nhận một allocation `JOB` riêng rồi mới thực thi dbt CLI.
- [x] Endpoint prepare xác thực principal/quyền scheduler, snapshot Engine Profile hiện hành, tạo
  run/attempt/allocation và phát binding có TTL. Client không được tự chọn owner, subdomain,
  allocation ID hoặc raw Spark launch config.
- [x] Không cho ordinary Thrift client tự nâng thành scheduled job chỉ bằng cách gửi
  `triggerType=SCHEDULE`. Chỉ đường dispatch được cấp quyền hoặc chế độ test server-side rõ ràng mới
  được tạo binding này.
- [x] Binding token chỉ trả một lần trên kết nối được xác thực, không xuất hiện trong status API,
  dbt artifacts, command output hoặc log. Server chỉ lưu hash/digest cần thiết, không lưu token dạng
  rõ.
- [x] Script truyền đầy đủ managed context cho adapter qua environment của process con:
  `KYUUBI_EFFECTIVE_USER`, `KYUUBI_ENGINE_PROFILE_ID`, `KYUUBI_MANAGED_RUN_ID`,
  `KYUUBI_MANAGED_ATTEMPT_ID`, `KYUUBI_MANAGED_ALLOCATION_ID`,
  `KYUUBI_MANAGED_BINDING_ID` và `KYUUBI_MANAGED_BINDING_TOKEN`.
- [x] Dùng `profiles.yml` local cố định với `env_var`; không sinh ConfigMap hoặc ghi token/profile raw
  vào project source.
- [x] Script gửi heartbeat trong khi dbt còn chạy, chuyển tiếp stdout/stderr, giữ nguyên exit code của
  dbt và báo terminal trong khối cleanup cho cả success, failure, `SIGINT` và `SIGTERM`.
- [x] Nếu script bị kill mà không báo terminal, heartbeat/deadline và lifecycle reconciler phải đánh
  dấu attempt mất, revoke binding và dọn allocation; không phụ thuộc shell còn sống để cleanup.
- [x] Các SQL/Python connections và mọi thread trong cùng invocation phải giữ nguyên một binding;
  không mở allocation mới cho từng model hoặc Python helper session.
- [x] Tách lifecycle chung khỏi chi tiết Kubernetes runner: prepare/heartbeat/terminal/cleanup dùng
  chung service, còn local process và Kubernetes Job chỉ là hai execution transports khác nhau.
- [x] Ghi mapping giữa backend run/attempt và `dbt invocation_id`; lưu hoặc chỉ đường dẫn tới
  `manifest.json`, `run_results.json` và dbt log mà không bắt buộc upload artifacts trong task này.

Các ca kiểm thử bắt buộc:

1. Chạy `dbt run` trực tiếp với Profile A, xác nhận allocation `INTERACTIVE` và engine `ep-*`.
2. Chạy cùng project qua managed CLI với Profile A và trigger SCHEDULE, xác nhận allocation `JOB`
   cùng engine/application ID khác interactive.
3. Chạy DAG SQL → Python → SQL với `--threads 2`, sau đó `--threads 4`; toàn bộ connections dùng
   cùng một allocation/application ID.
4. Chạy hai managed CLI invocations đồng thời cùng Profile A; chúng có hai JOB allocations riêng và
   không dùng engine interactive đang sống.
5. Kiểm tra success, Python failure, `Ctrl+C`, `SIGTERM` và kill cứng wrapper; mọi trường hợp cuối
   cùng đều revoke binding và dọn đúng engine JOB.
6. Binding thiếu, giả mạo, hết hạn hoặc thuộc user khác bị từ chối; adapter không được fallback sang
   engine interactive.
7. Kiểm tra log/status/artifacts không chứa binding token và interactive query vẫn chạy sau cleanup.

Đạt khi: từ project dbt local có thể dùng một lệnh wrapper để mô phỏng một scheduled attempt thật,
server tạo engine JOB riêng, dbt CLI chạy workload thật trên engine đó và engine được dọn sau terminal
hoặc sau orphan timeout. Không có Kubernetes runner/ConfigMap/UI/cron trong đường kiểm thử này.

Giới hạn: task này chứng minh contract dispatch và lifecycle của một lần schedule occurrence; không
kiểm thử cách tính lịch, timezone, misfire, overlap hoặc scheduler tự động phát occurrence.

Trạng thái ngày 2026-10-20: đã thêm execution transport `LOCAL_CLI`, API prepare/heartbeat/terminal,
feature flag mặc định tắt, schema migration, wrapper `bin/kyuubi-managed-dbt`, service tests và tài
liệu `docs/dbt/MANAGED_CLI.md`. Chưa đánh dấu các ca E2E ở trên là đạt cho đến khi người dùng build
server mới và chạy wrapper với project dbt thật.

### Task 7 — Mô phỏng scheduled Notebook bằng source revision đã ghim

Phụ thuộc: Task 4, 5. Task này chứng minh một occurrence của scheduled Notebook chạy thật mà
không cần UI, cron controller hoặc Kubernetes CronJob. Nó không triển khai bộ tính lịch.

File dự kiến:

- Mở rộng `NotebookSchedule`, `NotebookRun` và `NotebookStore` cùng DDL của ba dialect.
- Mở rộng `NotebookScheduleService.scala` và `NotebookRevisionService.scala` để quản lý source
  revision đã ghim.
- Thêm `notebook/service/NotebookRunService.scala`; tích hợp có kiểm soát với
  `NotebookExecutionService.scala`, `NotebookSessionService.scala` và compute allocation chung.
- Thêm API dispatch/poll/cancel tối thiểu và `bin/kyuubi-managed-notebook` hoặc test client tương
  đương để mô phỏng một occurrence SCHEDULE.

#### Contract ghim mã nguồn

- [ ] Khi tạo schedule lần đầu, server đọc notebook và toàn bộ cells hiện hành, tạo một
  `NotebookRevision` bất biến rồi lưu vào schedule các trường `sourceRevisionId`,
  `sourceRevisionNumber` và `sourceChecksum`. Client không được tự gửi document snapshot hoặc giả
  mạo revision thuộc notebook khác.
- [ ] Revision được schedule tham chiếu phải được bảo vệ khỏi trim/delete. Xóa schedule không được
  xóa revision còn được `NotebookRun` tham chiếu; cần cơ chế reference/retention rõ ràng thay vì dựa
  vào việc revision tình cờ còn tồn tại.
- [ ] Sửa tên, cấu hình hoặc cells trên UI sau khi đặt lịch không được tự dịch chuyển source đã
  ghim. Sửa notebook hiện hành và source của schedule là hai nhánh độc lập.
- [ ] Update cron/timezone/policy mặc định vẫn giữ nguyên source revision. Chỉ action được cấp quyền
  kiểu `pin-current-version`/`refreshSource=true` mới tạo revision mới từ notebook hiện hành và thay
  pin bằng optimistic concurrency trên `schedule.version`.
- [ ] Mỗi occurrence sao chép `scheduleId`, source revision, checksum và tham số đã ghim vào
  `NotebookRun` trước khi cấp compute. Run không đọc lại `notebook_cell` để quyết định code cần chạy,
  kể cả khi user sửa notebook trước hoặc trong lúc run.
- [ ] Tại dispatch phải tải đúng immutable revision, kiểm tra notebook ID/checksum và parse
  `documentSnapshot`; thiếu hoặc sai revision phải fail trước khi tạo engine, không fallback sang
  source hiện hành.
- [ ] Phân biệt source pin với compute snapshot: schedule giữ Engine Profile ID; mỗi occurrence lấy
  một snapshot cấu hình profile hiện hành khi dispatch. Đổi profile ID của schedule cần update
  schedule có kiểm soát, nhưng không tự repin source notebook.

#### Dispatch và thực thi headless

- [ ] Thêm đường dispatch được xác thực để chạy revision đã ghim với
  `workloadType=NOTEBOOK`, `triggerType=SCHEDULE`, `computeMode=JOB`; không cho client tự chọn owner,
  subdomain, allocation ID hoặc raw Spark launch config.
- [ ] Test client chỉ yêu cầu chạy schedule/notebook đã lưu. Server tự lấy revision và profile từ
  schedule, tạo run/attempt/allocation rồi trả ID dùng để poll; client không submit source trong
  request chạy. Notebook runner chạy trong Kyuubi nên attach allocation trực tiếp và không phát
  binding token ra ngoài như managed dbt CLI.
- [ ] Tạo Notebook session/runtime riêng cho attempt, không reuse hoặc cập nhật session tương tác
  đang mở. Scheduled run cùng Profile A phải dùng engine `job-*` khác engine interactive `ep-*`.
- [ ] Materialize cells của run từ `NotebookDocument` đã ghim, cấp execution ID riêng và thực thi
  tuần tự executable cells; bỏ qua markdown. SQL/Python phải dùng runtime của attempt để giữ state
  theo semantics đã cam kết, gồm biến Python dùng qua nhiều cell.
- [ ] Áp dụng `STOP_ON_ERROR` mặc định; lưu trạng thái, source snapshot, output và log từng cell theo
  run. Không ghi đè source, execution hoặc output của notebook interactive.
- [ ] Có heartbeat/deadline như managed dbt. Cancel phải ngắt cell hiện tại, không submit cell sau,
  chuyển run về terminal, revoke binding và luôn tạo cleanup intent.
- [ ] Nếu mất engine/session, fail attempt rõ ràng và không chạy tiếp trên session trắng. Sau restart,
  controller chỉ tiếp tục theo dõi khi chứng minh session và state còn nguyên; nếu không thì failed
  và cleanup.

#### Ca kiểm thử bắt buộc

1. Tạo notebook source A và đặt schedule; xác nhận revision/checksum A được ghim.
2. Sửa notebook hiện hành thành source B rồi dispatch mô phỏng; kết quả vẫn phải do source A tạo ra.
3. Trong khi scheduled run A đang chạy, tiếp tục sửa/chạy notebook interactive; hai luồng không đổi
   source/output/session của nhau và dùng hai application ID khác nhau.
4. Gọi action repin có chủ đích, dispatch lần sau và xác nhận source B mới được dùng; run cũ vẫn audit
   được revision A.
5. Chạy fixture SQL → Python → SQL và nhiều Python cells dùng chung biến; kiểm tra thứ tự, kết quả và
   source snapshot của từng execution.
6. Thử revision thiếu, checksum sai, revision thuộc notebook khác và client giả mạo revision; tất cả
   phải fail trước khi launch engine.
7. Thử cell failure, cancel, timeout, kill test client và restart Kyuubi giữa run; run phải về trạng
   thái xác định và engine JOB cuối cùng được dọn.
8. Dispatch lặp cùng idempotency key không được tạo hai run/allocation; hai occurrences hợp lệ độc
   lập phải có hai JOB allocations riêng.
9. Revision đang được schedule/run tham chiếu không bị revision trimming hoặc API delete xóa mất.

Đạt khi: bằng API/test client có thể mô phỏng một scheduled Notebook occurrence thật; workload luôn
chạy đúng revision được ghim lúc đặt lịch, không bị ảnh hưởng bởi source đang được sửa trên UI, dùng
session và engine JOB riêng, có output/audit độc lập và tự dọn compute ở mọi đường terminal.

Giới hạn: task này chưa xác nhận cron, timezone, misfire, overlap hay catch-up. Nó chỉ xác nhận source
pinning, dispatch, execution và lifecycle của một occurrence; scheduler production sau này gọi lại
chính đường dispatch này.

### Task 8 — Test harness mô phỏng dispatch, chạy workload thật

Phụ thuộc: Task 6, 6b, 7, 9, 10, 11. Thứ tự thực hiện đầy đủ ở mục 5.

File dự kiến: `tests/integration/compute-isolation/` chứa fixture Notebook/dbt và hướng dẫn; `bin/test-compute-isolation.py` hoặc script tương đương. Chốt vị trí khi triển khai, không coi các file này là đã tồn tại.

- [ ] Script nhận endpoint, thông tin auth qua env/secret, profile ID và test namespace/prefix; không hardcode credential hoặc owner.
- [ ] Tạo hoặc mở interactive session làm đối chứng, chạy query/cell thật và ghi nhận engine application ID.
- [ ] Gọi backend dispatch được cấp quyền để submit job với nguồn SCHEDULE mô phỏng. Không sleep chờ cron và không dựng bảng schedule mới.
- [ ] Tái sử dụng managed CLI harness của Task 6b cho nhánh dbt; không bắt buộc Kubernetes runner
  hoặc project ConfigMap để chứng minh routing và cleanup của dbt scheduled job.
- [ ] Chạy fixture dbt SQL → Python → SQL, có hai Python models độc lập; chạy fixture notebook có SQL/Python và biến dùng qua nhiều cells.
- [ ] Submit notebook job và dbt job cùng profile khi interactive còn sống; dùng barrier/poll trạng thái có timeout để bảo đảm workloads thực sự chồng thời gian.
- [ ] Lấy application ID qua runtime/backend evidence; so sánh engine thật, không chỉ so sánh run ID, subdomain hoặc session ID.
- [ ] Ghi report JSON/log gồm run/attempt/allocation/application IDs, config fingerprint không chứa secret, kết quả dữ liệu và thời gian cleanup.
- [ ] Có cases success, model/cell failure, cancel, duplicate submit; failure/cancel phải đi qua backend lifecycle thật, không do script tự kill Spark thay cho backend.
- [ ] Sau terminal, poll cleanup với deadline; kiểm tra Driver/Executor của allocation đã hết và query/cell đối chứng interactive vẫn chạy được.
- [ ] Kiểm tra shared Python PVC còn tồn tại; chỉ dọn test fixtures thuộc test invocation bằng target rõ ràng, không prune tài nguyên theo prefix rộng.
- [ ] Script trả exit code khác 0 khi assertion fail, auth/setup thiếu hoặc cleanup timeout; thiếu hạ tầng không được báo PASS.
- [ ] Viết lệnh chạy cụ thể, dependencies và expected output khi script hoàn thành; người dùng tự chạy E2E trên cluster test.

Đạt khi: không mở UI vẫn chứng minh được cả Notebook và dbt sử dụng đúng nhánh điều phối JOB, thực thi thật và tự dọn engine; report đủ để phân biệt routing sai với lỗi workload.

Giới hạn: bài test này xác nhận contract tiếp nhận scheduled workload, không xác nhận scheduler cron, timezone, overlap hoặc catch-up đã hoạt động.

### Task 9 — Python environment và profile guard đa engine

Phụ thuộc: Task 2, 5; triển khai song song về mặt thiết kế nhưng làm tuần tự trước rollout job.

File chính: `PythonEnvironmentService.scala`, `EngineProfileService.scala`, `EngineProfileRuntimeService.scala` và tests.

- [x] Chốt environment ID, PVC và path vào allocation; không đổi environment giữa các models/cells.
- [x] Tính mọi allocation khi publish environment, delete profile hoặc xóa PVC. Chưa có background
  GC tự xóa environment directory; vì vậy không có đường GC bỏ qua guard.
- [x] Giữ guard trong cùng coordination boundary với reserve để không xóa PVC trong khoảng launch.
- [x] Không xóa profile khi job engine còn cleanup pending hoặc trạng thái tài nguyên chưa xác định.
- [ ] Kiểm chứng Driver/Executor trên các node dùng đúng interpreter/package và cùng environment.
- [x] Cho phép cấu hình access mode; công bố RWO chỉ phù hợp single-node/co-located placement và
  yêu cầu RWX khi Driver/Executor có thể chạy đa node. PVC hiện hữu được validate trước build.
- [x] Không tự chuyển StorageClass/access mode hoặc xóa PVC hiện có khi nâng cấp.

Trạng thái Task 9 ngày 2026-10-22:

- `ComputeLaunchSnapshot` lưu environment ID và toàn bộ mount overlay bất biến; PVC và relative
  path nằm trong overlay này. Việc resolve chỉ diễn ra một lần dưới owner lock khi reserve
  allocation mà không thay đổi schema/bytecode của snapshot.
- Publication, profile deletion và PVC deletion đều tính `RESERVED`, `STARTING`, `ACTIVE` và
  `RELEASING`; cleanup JOB không chạm vào PVC dùng chung.
- Thêm `kyuubi.notebook.python.environment.pvc.access-mode`, mặc định giữ
  `ReadWriteOnce`. Kyuubi chỉ dùng policy này khi tạo claim mới và fail rõ ràng nếu claim cũ không
  tương thích, không tự patch/delete.
- Còn phải chạy smoke test Kubernetes để chứng minh Driver và Executor thực tế cùng nhìn thấy
  interpreter/package, và kiểm tra placement theo StorageClass production.

Đạt khi: nhiều engine dùng cùng environment an toàn; kết thúc job không làm mất environment của profile.

### Task 10 — Backend contract tối thiểu cho submit/status/cancel

Phụ thuộc: Task 6, 7, 9. Không phụ thuộc Task 8 hoặc frontend.

File chính: `api/v1/` resources liên quan Notebook/Dbt/Engine Profile và service tương ứng. Reuse endpoints có sẵn; chỉ thêm phần thiếu để gọi headless. Không sửa `web-ui/`.

- [x] Submit trả run/attempt reference, có idempotency key scoped theo principal; reuse key với payload khác phải bị reject.
- [x] Cùng request tới hai replica chỉ tạo một attempt/allocation; hai requests khác key được phép tạo hai engines riêng theo giới hạn.
- [x] Status trả mode, workload type, run/attempt/allocation, engine identity được phép xem, workload state và cleanup state; không lộ credential.
- [x] Cancel Job idempotent, kết thúc đúng attempt và tạo cleanup intent; Stop Interactive hiện có không được kill job engine.
- [x] Save/Delete trả lỗi có mã rõ ràng khi compute còn hoạt động; giữ response tương thích cho frontend hiện có, không bắt UI mới để bảo đảm an toàn.
- [x] Lấy được kết quả cells/log hoặc dbt artifacts tối thiểu để assert; chưa xây hệ thống browse artifacts đầy đủ.
- [x] Dispatch SCHEDULE chỉ nhận từ principal có quyền tương ứng; ordinary client không tự nâng quyền bằng trường triggerType.
- [x] Document request/response và ví dụ gọi script/curl với placeholder secrets, không trả token của runner trong status response.

Đạt khi: test harness và scheduler tương lai dùng cùng backend contract; có thể submit, theo dõi, hủy và xác minh cleanup không cần UI.

### Task 11 — Cấu hình và bằng chứng vận hành tối thiểu

Phụ thuộc: Task 5, 6, 7, 9, 10. Không phụ thuộc test harness Task 8.

File chính: `NotebookConf.scala` hoặc config class compute riêng theo convention; Helm values/templates; tài liệu triển khai.

- [x] Config cho feature flag, giới hạn concurrency, heartbeat, launch/job timeout, cleanup retry và retention metadata tối thiểu; tên/default chốt trong Task 0.
- [x] Fail startup rõ nếu bật managed HA jobs mà thiếu shared database/coordination cần thiết.
- [x] Log correlation theo owner/profile/run/attempt/allocation/engine/session; redact credentials và token.
- [x] Log/status đủ để script đo launch latency, cleanup pending/age và nguyên nhân fail; chưa xây dashboard hoặc bộ metrics/alert rules đầy đủ.
- [x] Giữ metadata chưa cleanup xong, không TTL-delete mất dấu; cleanup vượt deadline phải hiện qua status/log và làm test fail.
- [x] RBAC cho controller/runner đủ nhưng tối thiểu; tài nguyên mới có label để truy vết.
- [x] Tài liệu phân biệt S3 profile, runtime JDBC, ZooKeeper và PVC; không yêu cầu migrate profile khỏi S3.

Trạng thái Task 11 ngày 2026-10-23:

- Lifecycle cleanup dùng initial/max backoff cấu hình được, giữ `releaseRequestedAt` và không ngừng
  retry khi vượt deadline. Status trả launch latency, heartbeat/deadline, cleanup age/deadline,
  attempts và `OVERDUE` mà không trả raw backend error.
- `kyuubi.compute.managed.ha.required=true` buộc managed workload dùng MySQL/PostgreSQL dùng chung,
  ZooKeeper và lifecycle controller; timeout/TTL không nhất quán làm startup fail rõ.
- Thêm endpoint retry cleanup theo Notebook/dbt run. Endpoint fence claim cũ và đánh thức cleanup
  idempotent, không submit lại workload.
- Helm giữ quyền controller managed ở flag riêng; runner ServiceAccount không kế thừa quyền đó.
  Runner/Spark/Python builder resources có allocation/run/attempt/profile labels, không đặt token
  vào label.
- Runbook `docs/notebook/MANAGED_COMPUTE_OPERATIONS.md` mô tả storage boundary, config, RBAC,
  status, tra Pod và retry. Metadata terminal hiện được giữ vô thời hạn nên thỏa mức tối thiểu 24
  giờ; chưa có background TTL deletion và không có đường xóa allocation cleanup-pending.

Đạt khi: vận hành tìm được engine của run, biết vì sao chưa dọn và có cách retry cleanup an toàn.

### Task 12 — Regression và nghiệm thu hai replica

Phụ thuộc: Task 1–11, gồm Task 6b và harness Task 8. Không yêu cầu scheduler/UI mới.

- [ ] Unit store/service: claims/CAS, auth/binding, profile mutation race và state transitions.
- [ ] Adapter unit: local vs managed credentials, cùng binding SQL/Python, token hết hạn không fallback.
- [ ] Integration backend: hai replica dùng cùng metadata, restart controller, cleanup takeover.
- [ ] E2E dbt/notebook: chạy workload thật và kiểm tra application/pods thực tế, không chỉ kiểm tra state trong DB.
- [ ] Chạy lại regression Python table/incremental hiện có và notebook idle/session recovery.
- [ ] Chạy format, Scala tests với Java 17 và Python tests bằng venv đúng adapter. Không thêm UI test suite vì không sửa frontend; kiểm tra tương thích API interactive bằng backend regression. Người dùng tự chạy các lệnh được bàn giao theo mỗi task.

Các ca bắt buộc:

1. Alice dùng notebook/dbt interactive profile A; notebook/dbt job do harness dispatch như scheduled job cùng profile chạy trên engine khác.
2. Hai job cùng profile có application ID khác nhau; cùng một dbt attempt threads 2/4 dùng chung allocation.
3. Bob không dùng được binding của Alice; service account không tự đổi effective user.
4. Success/failure/cancel đều dọn đúng engine job, giữ engine interactive và shared environment PVC.
5. Kill runner hoặc Kyuubi controller; replica còn lại tiếp tục reconciliation, không tạo job trùng.
6. Cancel trong lúc launch; engine đăng ký muộn vẫn bị thu hồi.
7. Mất Kubernetes/ZooKeeper tạm thời; cleanup vẫn còn pending và tự tiếp tục khi phục hồi.
8. Job dài hơn TTL OpenSession không bị kết luận đã chết chỉ vì không mở kết nối mới.
9. Update/delete profile hoặc GC PVC đối đầu reserve allocation không gây launch bằng cấu hình/tài nguyên đã bị xóa.
10. Notebook job nhận trigger SCHEDULE chạy từ session sạch, giữ biến giữa cells và không dùng output/state từ phiên UI.
11. Sửa notebook/project/profile lúc job đang queue/running tuân thủ đúng thời điểm snapshot đã công bố.
12. Hai job cùng ghi một bảng được báo đúng semantics của workload; tài liệu không tuyên bố compute isolation giải quyết data conflict.
13. Hai replicas nhận cùng submit idempotency key chỉ tạo một attempt; hai key khác nhau tạo hai allocations, không cần chờ tới cron tick.

Đạt khi: có report máy đọc được và bằng chứng cho từng ca, ghi version/image/config đã kiểm thử. Kết luận nghiệm thu là backend phân luồng/cleanup headless hoạt động trong phạm vi đã test, không tuyên bố scheduler hoặc toàn sản phẩm production-ready.

### Task 13 — Hướng dẫn bật thử nghiệm và rollback

Phụ thuộc: Task 12.

- [ ] Migration schema trước, feature mặc định tắt; xác nhận code cũ vẫn đọc dữ liệu cũ được trong cửa sổ rollout.
- [ ] Nâng cấp toàn bộ Kyuubi replica trước khi bật routing/binding mới; không để mixed-version replica bỏ qua allocation guard.
- [ ] Nâng adapter/runner theo compatibility contract; runner cũ không hiểu binding phải bị từ chối cho JOB mode.
- [ ] Bật backend dispatch cho nhóm thử nghiệm và chạy harness; không bật cron hoặc yêu cầu UI mới.
- [ ] Rollback bằng ngừng nhận job mới, drain/cancel các allocations và giữ cleanup controller đến khi hết tài nguyên.
- [ ] Không rollback về code cũ khi vẫn có dedicated jobs mà code cũ không quản lý được.
- [ ] Không drop bảng allocation hoặc xóa profile/PVC để rollback; retention sau cùng là thao tác quản trị riêng.

Đạt khi: có runbook để người dùng tự bật thử nghiệm/rollback mà không mất dấu engine. Triển khai cluster production thực tế, dashboard và scheduler là công việc sau, không phải điều kiện hoàn thành scope này.

## 5. Thứ tự thực hiện và mốc bàn giao

Thứ tự đề xuất, làm tuần tự để dễ review:

```text
Task 0 → 1 → 2 → 3 → 4 → 5 → 9 → 6 → 6b → 7 → 10 → 11 → 8 → 12 → 13
```

- **Mốc A — Nền tảng chung:** Task 0–5 và 9. Có allocation, auth, routing, guard và cleanup; chưa bật scheduler.
- **Mốc B — Backend job headless:** Task 6, 6b, 7, 10–11. dbt có cả Kubernetes transport và
  managed CLI harness; notebook/dbt chạy bằng engine riêng, có contract submit/status/cancel và
  thông tin để kiểm chứng.
- **Mốc C — Chứng minh phân luồng:** Task 8, 12. Harness mô phỏng dispatch scheduled job nhưng chạy workload/engine thật; nghiệm thu isolation, cleanup và recovery hai replica.
- **Mốc D — Bàn giao:** Task 13. Runbook bật thử nghiệm/rollback. Không cần UI hay scheduler tự động để kết thúc giai đoạn.

Test của từng task phải đi cùng implementation, không chờ đến Task 12 mới thêm test. Task 12 là nghiệm thu tích hợp toàn hệ thống.

Giữ số task cũ để dễ đối chiếu: Task 8 thay scheduler bằng harness; Task 10 bỏ UI, chỉ giữ backend contract; Task 11 và 13 thu hẹp về cấu hình/bàn giao cần thiết. Không có task frontend ẩn trong các dependencies.

### Sản phẩm bàn giao để người dùng tự kiểm tra

- Code backend/adapter và migrations cần thiết; không thay đổi frontend.
- Fixture notebook, dbt project nhỏ và script headless có tài liệu cách chạy từ thư mục nào, env/auth
  nào cần đặt. Nhánh dbt phải chạy được trực tiếp từ local project, không bắt buộc runner image hoặc
  ConfigMap.
- Unit/integration tests và hướng dẫn hai replica; failure injection chỉ chạy trên cluster test và phải opt-in với target rõ ràng.
- Report cho từng case: các engine application IDs, bằng chứng cấu hình đúng profile, kết quả workload, cleanup và query đối chứng interactive sau cleanup.
- Không coi mock test hoặc hai session IDs khác nhau là bằng chứng engine isolation; thiếu hạ tầng phải ghi NOT RUN/FAIL, không báo đã nghiệm thu.

## 6. Checklist duyệt trước khi triển khai

- [ ] Đồng ý Engine Profile chỉ là cấu hình; allocation/run là đơn vị điều phối mới.
- [ ] Đồng ý interactive giữ hành vi reuse hiện tại, mỗi managed job attempt có engine riêng.
- [ ] Đồng ý bổ sung runtime metadata vào shared JDBC, không đưa profile config trở lại JDBC.
- [ ] Đồng ý snapshot source/config lúc dispatch; update/delete bị chặn từ reserve đến cleanup hoàn tất.
- [ ] Đồng ý chưa tự retry workload; cron/overlap/misfire của scheduler để giai đoạn sau.
- [ ] Đồng ý chỉ làm backend phân luồng và test headless; không UI, không scheduler tick tự động.
- [ ] Đồng ý harness chỉ mô phỏng nguồn dispatch, còn auth/routing/Notebook/dbt/Spark/cleanup phải chạy đường thực tế.
- [ ] Chốt giải pháp PVC đa engine/đa node trước nghiệm thu production.
- [ ] Đồng ý chỉ sửa code và cung cấp lệnh test; người dùng tự build/test/rollout.

## 7. Theo dõi tiến độ

Cập nhật ngày 2026-10-27:

- Task 0–6, 9 và 10 đã triển khai; người dùng đã xác nhận các lượt test tương ứng thành công.
- Task 6b đã có wrapper `bin/kyuubi-managed-dbt`; người dùng đã chạy thử trên Kubernetes và xác
  nhận luồng scheduled tạo engine riêng với luồng interactive khi dùng cùng Engine Profile.
- Task 7 đã có Notebook job runner, API dispatch và wrapper `bin/kyuubi-managed-notebook`. Luồng
  scheduled dùng source revision đã ghim, session và engine JOB riêng; người dùng đã chạy thử và
  đánh giá luồng cơ bản ổn định. Nghiệm thu tích hợp đầy đủ vẫn thuộc Task 12.
- Task 11 đã hoàn thiện code, cấu hình, status/retry cleanup và tài liệu vận hành; chờ người dùng
  xác nhận kết quả test và rollout sau các thay đổi cuối.
- Task 8, 12 và 13 chưa hoàn tất toàn bộ phạm vi trong plan. Mục tiêu demo hiện tại tập trung
  chứng minh hai luồng chạy đồng thời trên engine riêng, scheduled dùng đúng source đã ghim và
  cleanup JOB không ảnh hưởng interactive. Chưa kết luận đã nghiệm thu đầy đủ HA/recovery hoặc
  scheduler tự động.

Sau mỗi task sẽ ghi: file thay đổi, test cần chạy, kết quả người dùng xác nhận, giới hạn còn lại và task tiếp theo.
