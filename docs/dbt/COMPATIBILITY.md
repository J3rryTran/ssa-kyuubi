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

# Tương thích dbt CLI

Tài liệu này là compatibility contract cho adapter `dbt-kyuubi` trong bản phân
phối này. Trạng thái **Cam kết** nghĩa là đường chạy đặc thù Kyuubi đã được cài
đặt và là workflow production được hỗ trợ. **Chưa cam kết** nghĩa là dbt có thể
nhận lệnh hoặc cấu hình đó, nhưng không được coi là workflow production cho đến
khi có kiểm thử end-to-end trên catalog, cơ chế xác thực và Spark image triển
khai thực tế.

## Tập version được hỗ trợ

|         Thành phần          |    Version cam kết     |                                          Ghi chú                                          |
|-----------------------------|------------------------|-------------------------------------------------------------------------------------------|
| `dbt-kyuubi`                | `0.1.1`                | Adapter nội bộ, đóng gói từ `docker/dbt-kyuubi`.                                          |
| dbt Core                    | `1.12.x`               | Runner image ghim `1.12.0`; dependency của adapter là `>=1.12,<1.13`.                     |
| dbt-spark                   | `1.10.3`               | Cài với extra `PyHive`.                                                                   |
| Python của dbt CLI / runner | `3.12`                 | `docker/dbt-runner/Dockerfile` dùng `python:3.12-slim`.                                   |
| Python runtime của Spark    | `3.10`                 | Spark image tham chiếu được build từ `docker/python-for-notebook/Dockerfile.spark-py310`. |
| Kyuubi server               | Bản custom từ `1.10.3` | Có Engine Profile resolver và Python-operation support.                                   |
| Spark                       | `3.5.5`                | Kyuubi và Spark image phải được build trên cùng Spark distribution `3.5.5`.               |
| Catalog / table format      | Iceberg                | Deployment tham chiếu cấu hình Iceberg catalog `lakehouse`.                               |

Không trộn tùy ý các minor version Python, Spark hoặc dbt trong production. Một
Persistent Python Environment chỉ hợp lệ với base Spark Python image đã cấu
hình; Kyuubi sẽ từ chối mount environment có base-image identity không tương
thích. Python code của model cũng phải tương thích cú pháp Python 3.10, dù dbt
runner đang dùng Python 3.12 để compile và submit.

## Hợp đồng kết nối

Adapter chỉ hỗ trợ Python model thông qua Kyuubi Thrift session. Profile phải
dùng `type: kyuubi`, `method: thrift` và truyền Engine Profile ID trong
`server_side_parameters`:

```yaml
server_side_parameters:
  kyuubi.engine.profile.id: "{{ env_var('KYUUBI_ENGINE_PROFILE_ID') }}"
  spark.sql.defaultCatalog: lakehouse
```

ID là UUID bất biến của profile trên UI, không phải subdomain
`ep-<profile-id>`. Không gửi `kyuubi.engine.profile.revision`, raw Spark
resource setting, Python mount setting hay subdomain tự ghép từ `profiles.yml`.
Kyuubi xác thực effective user, resolve Engine Profile hiện hành và tự áp dụng
cấu hình khởi tạo do server quản lý.

Với production runner dùng service identity, service user đã xác thực và
proxy/effective user (nếu có) phải được cấp quyền dùng Engine Profile đã chọn.
Triển khai local `auth: NONE` thường dùng cùng Kyuubi user với owner của
profile.

### Managed Kubernetes runner

Managed runner không dùng run ID interactive do adapter tự sinh. Backend tạo
riêng `runId`, `attemptId`, JOB allocation và binding có thời hạn; toàn bộ SQL
threads và Python model trong attempt dùng chung binding đó. Token chỉ được
đưa vào Kubernetes Secret và bị Kyuubi kiểm tra theo runner identity,
effective user, profile, run, attempt và allocation. `profiles.yml` do server
tạo chỉ tham chiếu các giá trị bằng `env_var`, không chứa token.

Số threads của managed runner lấy từ `kyuubi.dbt.runner.threads` và không được
vượt `kyuubi.dbt.runner.threads.max`. CLI local vẫn lấy threads từ target hoặc
tham số CLI và tiếp tục dùng allocation interactive.

API managed hiện chỉ cam kết ba dạng lệnh: `dbt show --select` cho Preview,
`dbt run --select` cho Run Model và `dbt run` cho Run Project. Ma trận lệnh CLI
bên dưới không tự động trở thành contract của managed runner. Trước khi pod
kết thúc, runner phát `manifest.json` và `run_results.json` qua kênh log có
định dạng nội bộ để server lưu bền vững; `dbt invocation_id` được lưu riêng,
không thay thế backend run ID.

## Ma trận lệnh dbt

|        Lệnh         |  Trạng thái  |                                                                Phạm vi và hợp đồng                                                                 |
|---------------------|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `dbt debug`         | Cam kết      | Kiểm tra Thrift connection và Engine Profile session resolution; không chạy Python model.                                                          |
| `dbt parse`         | Cam kết      | Parse source SQL/Python ở local, không cần kết nối Kyuubi.                                                                                         |
| `dbt compile`       | Cam kết      | Compile artifact SQL/Python ở local; không xác nhận Spark runtime.                                                                                 |
| `dbt run`           | Cam kết      | Chạy SQL qua dbt-spark và Python dưới dạng Kyuubi `PYTHON` operation, theo ma trận materialization bên dưới. Hỗ trợ dependency SQL → Python → SQL. |
| `dbt build`         | Cam kết      | Chạy `run` được hỗ trợ, sau đó chạy dbt SQL test thông thường. Python model chịu cùng giới hạn với `dbt run`.                                      |
| `dbt test`          | Cam kết      | Chạy schema/data test của dbt bằng SQL operation; không phải Python unit-test framework.                                                           |
| `dbt seed`          | Chưa cam kết | Kế thừa đường SQL của dbt-spark, nhưng chưa có compatibility suite end-to-end với Kyuubi Engine Profile.                                           |
| `dbt snapshot`      | Chưa cam kết | Chưa nghiệm thu snapshot SQL và catalog semantics cho Iceberg deployment tham chiếu.                                                               |
| `dbt run-operation` | Chưa cam kết | SQL macro có thể chạy qua dbt-spark, nhưng chưa có production contract hoặc Python macro path.                                                     |
| `dbt docs generate` | Chưa cam kết | Artifact tạo ở local, nhưng catalog/database introspection chưa được nghiệm thu với Kyuubi.                                                        |
| `dbt retry`         | Chưa cam kết | Cần artifact dbt được giữ lại và recovery semantics sau engine/network failure; chưa nghiệm thu.                                                   |

Mục **Chưa cam kết** không có nghĩa lệnh chắc chắn fail ngay. Nó chỉ có nghĩa
không được dựa vào kết quả đó trong production.

## Ma trận materialization cho Python model

|                                  Cấu hình model                                   |  Trạng thái  |                                                        Điều kiện                                                         |
|-----------------------------------------------------------------------------------|--------------|--------------------------------------------------------------------------------------------------------------------------|
| `materialized='table'`                                                            | Cam kết      | Iceberg là default của adapter. Spark catalog/image phải có Iceberg runtime.                                             |
| `materialized='table', file_format='iceberg'`                                     | Cam kết      | Dạng khai báo tường minh nên dùng cho project cần portability.                                                           |
| `materialized='incremental', file_format='iceberg', incremental_strategy='merge'` | Cam kết      | Phải có `unique_key`; `on_schema_change` chỉ là `ignore` hoặc `append_new_columns`.                                      |
| Python `incremental`, `file_format='iceberg'`, strategy `append`                  | Cam kết      | Không cần `unique_key`; mỗi lần chạy append toàn bộ DataFrame của model nên model phải tự giới hạn dữ liệu mới.          |
| Python `incremental`, `file_format='iceberg'`, strategy `insert_overwrite`        | Cam kết      | Bắt buộc `partition_by`; dùng dynamic partition overwrite của Spark/Iceberg.                                             |
| Python `incremental`, `file_format='iceberg'`, strategy `microbatch`              | Cam kết      | Bắt buộc `partition_by`, `event_time`, `begin`, `batch_size`; batch chạy tuần tự và temp relation có suffix batch.       |
| Python `incremental` với strategy khác                                            | Từ chối      | Chỉ mở các capability đã được nghiệm thu trong adapter.                                                                  |
| Python model có `packages` theo từng model                                        | Không hỗ trợ | Dùng package baked vào Spark image hoặc Persistent Python Environment ở trạng thái `READY` được chọn bởi Engine Profile. |
| Python `table`/`incremental` với `file_format='delta'`                            | Chưa cam kết | Chỉ hợp lệ sau khi Spark image có Delta runtime/catalog tương thích và từng strategy được nghiệm thu riêng.              |

Capability registry đã có bốn strategy Python incremental Iceberg mà macro
`dbt-spark` cung cấp: `append`, `merge`, `insert_overwrite` và `microbatch`.
Cả bốn strategy đã vượt qua integration suite trên deployment tham chiếu.
Format/strategy ngoài registry vẫn bị từ chối trước khi submit Python.

Với Iceberg `merge`, mỗi batch Python phải có đầy đủ cột `unique_key`; giá trị
key không được null và không được trùng trong cùng batch. Adapter từ chối batch
vi phạm trước khi ghi bảng tạm, vì null key có thể bị insert lặp lại còn source
key trùng làm kết quả `MERGE` không xác định. Tổ hợp format/strategy hoặc
`on_schema_change` chưa có trong capability registry cũng bị từ chối trước khi
submit Python operation.

Python `incremental` Delta bị capability registry từ chối trước khi submit.
Python `table` Delta hiện chưa có preflight tương tự; nếu Spark image thiếu
Delta runtime, model sẽ fail trong Spark classpath. Project không được dùng
failure đó như cơ chế validation. Dùng Iceberg cho đến khi có Delta image được
nghiệm thu riêng.

## Quy tắc thực thi Python và dependency

- Python model được dbt-spark compile, sau đó submit qua cùng loại Kyuubi
  Thrift connection với operation overlay `kyuubi.operation.language=PYTHON`.
- Python error, bao gồm traceback, làm `dbt run` hoặc `dbt build` kết thúc với
  exit code khác `0`.
- `dbt.ref()` và `dbt.source()` resolve thành Spark relation. SQL model có thể
  đứng upstream hoặc downstream của Python model.
- Package cần chạy trên executor phải xuất hiện ở cả Driver và Executor.
  `%pip` trên notebook driver đang sống không thay thế Persistent Python
  Environment `READY` khi dbt khởi động engine mới.
- Package thay đổi theo profile, không theo model. Cài dependency vào
  Persistent Python Environment của profile đã chọn, chờ trạng thái `READY`,
  rồi khởi động engine tiếp theo từ environment đó.
- Dùng `threads: 1` làm baseline. Model độc lập chỉ nên dùng `threads: 2` sau
  khi concurrent-write behavior của catalog đích đã được nghiệm thu cho project.

## Lệnh nghiệm thu tối thiểu

```bash
dbt debug --target local_test
dbt run --target local_test --select +python_profile_smoke
dbt build --target local_test --select +python_profile_smoke
dbt test --target local_test --select python_profile_smoke
```

Khi project cần không phụ thuộc adapter default, dùng cấu hình Iceberg tường
minh cho Python model:

```python
def model(dbt, session):
    dbt.config(materialized="table", file_format="iceberg")
    return dbt.ref("customer_summary").selectExpr("count(*) as customer_count")
```

## Roadmap hoàn thiện dbt Python model

Các task dưới đây là kế hoạch phát triển, **không** tự động thay đổi trạng thái
cam kết ở các ma trận bên trên. Một capability chỉ chuyển từ *Sẽ triển khai*
hoặc *Chưa cam kết* sang *Cam kết* sau khi có test tự động và nghiệm thu
end-to-end trên Kyuubi, Spark image và Iceberg catalog triển khai thực tế.

### Task 1 — Sửa và kiểm chứng default file format

- Viết integration test xác định `file_format` mặc định hiện đi qua macro và
  config nào; regression cần phát hiện compiled Python code chứa
  `.format("delta")`.
- Ép default Python `table` thành Iceberg qua macro/config adapter. Không chọn
  policy bắt buộc khai báo tường minh, vì contract hiện công bố Iceberg là
  adapter default.
- Kiểm tra compiled Python code và bảng thực tế đều dùng format Iceberg.

**Hoàn thành khi:** Python `table` thiếu `file_format` vẫn tạo bảng Iceberg và
không còn sinh `.format("delta")`.

### Task 2 — Test suite riêng cho `dbt-kyuubi`

- Tạo unit test mock Thrift cho `python_submissions.py`: overlay Python,
  FINISHED, failure, timeout, cancel, close operation và rút gọn traceback.
- Test cleanup bảng tạm incremental khi Python operation thất bại.
- Tạo integration fixture là dbt project nhỏ chạy với Kyuubi thật.

**Hoàn thành khi:** thay đổi adapter được bảo vệ bởi test tự động, không chỉ
phụ thuộc test CLI thủ công.

### Task 3 — Hoàn thiện Python `table`

- Kiểm thử tạo lần đầu, chạy lại khi bảng đã tồn tại và `--full-refresh`.
- Kiểm thử nhiều `dbt.ref()`, `dbt.source()`, Python → SQL downstream và
  schema/catalog khác nhau.
- Kiểm thử failure trước và trong `saveAsTable`, bảo đảm dbt trả exit code khác
  `0` và bảng không ở trạng thái không xác định.
- Kiểm thử Python source của model tương thích Python 3.10.

**Hoàn thành khi:** các case trên chạy ổn định với Iceberg và lỗi không để lại
kết quả dữ liệu mơ hồ.

### Task 4 — Hoàn thiện Python incremental Iceberg `merge`

Giữ giới hạn đang cam kết:

```yaml
file_format: iceberg
incremental_strategy: merge
unique_key: ...
on_schema_change: ignore | append_new_columns
```

- Kiểm thử tạo lần đầu, update ID cũ, insert ID mới, chạy lại không nhân đôi và
  `--full-refresh`.
- Kiểm thử `append_new_columns`, null/duplicate `unique_key`, lỗi tạo bảng tạm
  hoặc `MERGE`, cancel và cleanup bảng tạm.
- Các strategy chưa có trong capability registry phải bị từ chối trước khi
  submit Python operation.

**Hoàn thành khi:** `merge` có kết quả xác định trong mọi case trên và không
để lại bảng tạm khi lỗi/cancel.

### Task 4b — Mở rộng Python incremental Iceberg theo dbt-spark

`dbt-spark` có macro cho bốn strategy Python incremental: `append`, `merge`,
`insert_overwrite` và `microbatch`. Kyuubi triển khai các strategy như sau:

1. `append`: Iceberg, không cần `unique_key`; có test tạo mới, chạy lại và full
   refresh.
2. `insert_overwrite`: Iceberg, bắt buộc `partition_by`; có test dynamic
   partition overwrite, retry và failure giữa lúc ghi.
3. `microbatch`: Iceberg, bắt buộc `partition_by`, `event_time`, `begin` và
   `batch_size`; có test batch retry, idempotency và cleanup bảng tạm có suffix
   batch. Kyuubi chưa công bố concurrent microbatch batches.

Adapter dùng capability registry theo format/strategy thay vì hardcode
validation rải rác. Validation riêng của `merge` cho null/duplicate key và các
điều kiện bắt buộc của partition/microbatch được áp trước khi submit operation.

**Trạng thái:** hoàn thành. Cả ba strategy mở rộng đã vượt qua unit test và
integration test với Kyuubi, Spark 3.5.5 và Iceberg catalog tham chiếu.

### Task 5 — Xác nhận command matrix SQL của dbt

Tạo fixture và expected result riêng cho:

```text
dbt debug       dbt parse         dbt compile
dbt seed        dbt run           dbt test
dbt build       dbt snapshot      dbt run-operation <macro>
dbt docs generate                 dbt retry
```

Sau test, quyết định từng command là *Cam kết* hoặc giữ *Chưa cam kết*; không
coi việc lệnh tình cờ chạy được là production contract.

Fixture `docker/dbt-kyuubi/tests/integration/command_matrix` cung cấp expected
result độc lập cho từng command, bao gồm dữ liệu thực tế cho
seed/run/test/build/snapshot, artifact cho parse/compile/docs và một run lỗi có
chủ đích cho `dbt retry`. Trạng thái trong ma trận chỉ được nâng sau khi toàn bộ
suite này xanh trên deployment tham chiếu.

### Task 6 — Concurrency và profile consistency

- Đưa `threads` của Kubernetes DBT runner thành server-side config có giới hạn,
  không lấy raw value từ browser.
- Kiểm thử SQL + Python với `threads: 2`, sau đó `threads: 4`; bao gồm hai
  Python model độc lập, một model lỗi và concurrent write.
- Kiểm thử profile không thể update/delete khi DBT run active.
- Kiểm thử nhiều Kyuubi replica cùng resolve một DBT run ID.

**Hoàn thành khi:** SQL/Python dùng cùng Engine Profile cho toàn run, không
tạo nhầm engine hoặc trộn profile. `threads: 2`/`4` chỉ thành contract sau khi
catalog đích được nghiệm thu.

### Task 7 — Chính sách Python dependency

- Kiểm thử package baked trong Spark image, package trong Persistent Python
  Environment và Python UDF thực thi thật trên executor.
- Ở giai đoạn đầu, adapter phải reject rõ `packages` theo model và yêu cầu cài
  dependency vào profile environment.
- Giai đoạn sau, nếu hỗ trợ per-model package, phải build/snapshot environment
  trước run; không được `pip install` âm thầm khi model đang chạy.

**Hoàn thành khi:** Driver và Executor nhìn cùng package/version; restart
engine vẫn mount đúng persistent environment.

### Task 8 — Recovery, cancellation và timeout

- Kiểm thử Ctrl+C CLI, Kubernetes Job nhận SIGTERM, mất Thrift connection,
  driver/engine chết khi operation chạy và Python timeout.
- Kiểm thử `dbt retry` sau run lỗi khi command này được nghiệm thu.
- Bảo đảm operation/session được close hoặc server thu hồi; CLI nhận traceback
  ngắn gọn, log chi tiết giữ tại server.

**Hoàn thành khi:** không còn operation/session mồ côi hay bảng tạm còn lại
sau các failure path đã cam kết.

### Task 9 — Hoàn thiện Kubernetes DBT runner

- Đồng bộ runner với local CLI: `type: kyuubi`, Profile ID và run ID.
- Áp dụng server-side bounded `threads`.
- Lưu `manifest.json`, `run_results.json` và dbt log tại nơi API có thể truy
  xuất; không chỉ lưu trong filesystem tạm của Pod.
- Cung cấp API ổn định cho trạng thái, cancel và retry; không ghi credential
  hoặc raw profile config vào workspace source.

**Hoàn thành khi:** cùng một dbt project có behavior tương đương khi chạy local
CLI hoặc Kubernetes runner.

### Task 10 — Packaging, CI và rollout production

- Build wheel `dbt-kyuubi` có version rõ ràng; đồng bộ package metadata và
  adapter runtime version.
- Ghim đúng wheel/version trong DBT runner image.
- CI chạy adapter unit test; integration test chạy ở môi trường có
  Kyuubi/Iceberg.
- Thêm smoke test nâng cấp image, hướng dẫn Profile ID, auth, S3/PVC Python
  Environment, Iceberg catalog và rollback.

**Hoàn thành khi:** có release version, compatibility matrix, regression suite
và quy trình rollout/rollback rõ ràng.

Thứ tự thực hiện: **Task 1 → Task 2 → Task 3 → Task 4 → Task 4b → Task 5 →
Task 6 → Task 7 → Task 8 → Task 9 → Task 10**. Trước khi gọi production-ready,
ưu tiên Task 1, 2, 3, 4 và 6.
