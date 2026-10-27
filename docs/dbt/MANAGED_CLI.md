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

# Mô phỏng scheduled dbt job bằng CLI local

Đường chạy này dùng project và môi trường dbt đang có trên máy người dùng, nhưng compute được Kyuubi
cấp phát như một scheduled job thật:

```text
CLI wrapper → prepare SCHEDULE/JOB → nhận binding một lần → dbt local → heartbeat/terminal
                                                        └→ Spark engine job-* riêng
```

Nó không tạo Kubernetes DBT runner Job, không upload project vào ConfigMap và không triển khai cron
hoặc UI scheduler.

## Cấu hình server

Tính năng mặc định tắt. Cấu hình Kyuubi test cần có:

```properties
kyuubi.compute.lifecycle.enabled=true
kyuubi.compute.binding.trusted.runner.users=dbt-runner
kyuubi.dbt.runner.kyuubi.user=dbt-runner
kyuubi.dbt.managed.cli.enabled=true
# Plain timeConf values are milliseconds; ISO-8601 such as PT10S is also valid.
kyuubi.dbt.managed.cli.heartbeat.interval=10000
```

`kyuubi.dbt.runner.enabled` có thể giữ `false`; flag đó chỉ điều khiển Kubernetes DBT runner.
Identity `dbt-runner` phải được cấu hình proxy-user phù hợp với cơ chế xác thực của deployment.
Khi rollout server mới, giữ schema initialization bật một lần để thêm cột
`dbt_job_run.execution_transport`. Shared JDBC và ZooKeeper hiện có vẫn được dùng cho allocation,
binding và phối hợp nhiều Kyuubi replica.

## profiles.yml local

Profile không chứa allocation hoặc token cố định. Wrapper cấp các biến này cho đúng process dbt:

```yaml
kyuubi_dbt:
  target: local_test
  outputs:
    local_test:
      type: kyuubi
      method: thrift
      host: 127.0.0.1
      port: 10009
      user: "{{ env_var('KYUUBI_USER') }}"
      auth: NONE
      schema: dbt
      server_side_parameters:
        # Keep the same non-managed Spark session settings as the direct CLI target.
        spark.sql.defaultCatalog: lakehouse
        spark.sql.session.timeZone: UTC
        hive.server2.proxy.user: "{{ env_var('KYUUBI_EFFECTIVE_USER') }}"
        kyuubi.engine.profile.id: "{{ env_var('KYUUBI_ENGINE_PROFILE_ID') }}"
        kyuubi.engine.profile.run.id: "{{ env_var('KYUUBI_MANAGED_RUN_ID') }}"
        kyuubi.compute.attempt.id: "{{ env_var('KYUUBI_MANAGED_ATTEMPT_ID') }}"
        kyuubi.compute.allocation.id: "{{ env_var('KYUUBI_MANAGED_ALLOCATION_ID') }}"
        kyuubi.compute.binding.id: "{{ env_var('KYUUBI_MANAGED_BINDING_ID') }}"
        kyuubi.compute.binding.token: "{{ env_var('KYUUBI_MANAGED_BINDING_TOKEN') }}"
```

`spark.sql.defaultCatalog`, timezone and any other ordinary session parameters must match the
direct CLI target. The managed binding selects and fences the compute allocation; it does not
silently copy target-specific dbt session settings from another output in `profiles.yml`.

Không in các biến `KYUUBI_MANAGED_*` ra log hoặc lưu chúng vào shell profile.

## Chạy

Cần tạo DBT workspace/job metadata trước và lấy `jobId`. Job quyết định lệnh được chạy:

- `PREVIEW` → `dbt show --select ...`
- `RUN_MODEL` → `dbt run --select ...`
- `RUN_PROJECT` → `dbt run`

Tại thư mục repository Kyuubi:

```bash
export KYUUBI_MANAGED_API_BEARER_TOKEN='<token-api-neu-dung-bearer-auth>'

./bin/kyuubi-managed-dbt \
  --api-base http://127.0.0.1:10099/api/v1 \
  --job-id '<job-id>' \
  --project-dir /root/project/lakehouse_dbt \
  --profiles-dir /root/project/lakehouse_dbt \
  --target local_test
```

Với môi trường `auth: NONE`, không cần export API bearer token. Wrapper giữ nguyên stdout/stderr và
exit code của dbt. `Ctrl+C`/`SIGTERM` được báo thành `CANCELLED`; nếu wrapper bị kill cứng, workload
heartbeat hết hạn và compute lifecycle reconciler sẽ dọn engine.

## API được wrapper sử dụng

```text
POST /api/v1/dbt-jobs/{jobId}:prepare-managed-cli
POST /api/v1/dbt-job-runs/{runId}:heartbeat-managed-cli
POST /api/v1/dbt-job-runs/{runId}:complete-managed-cli
```

Prepare trả binding token đúng một lần. `GET /dbt-job-runs/{runId}` không trả token. Mọi endpoint
đều lấy owner từ authenticated principal; body không được khai effective user, profile, allocation
hoặc Spark launch config.

## Kỳ vọng kiểm thử

1. Chạy `dbt run` trực tiếp trước để tạo hoặc tái sử dụng engine interactive `ep-*`.
2. Chạy wrapper với cùng Engine Profile; allocation phải có `triggerType=SCHEDULE`, `mode=JOB` và
   subdomain `job-*` khác engine interactive.
3. Tất cả SQL/Python connections và threads trong run dùng cùng allocation ID.
4. Sau `SUCCEEDED`, `FAILED` hoặc `CANCELLED`, binding bị revoke và allocation chuyển sang cleanup.
5. Engine interactive và Persistent Python Environment không bị xóa theo job.

Đây chỉ là mô phỏng một schedule occurrence. Nó không kiểm thử cron, timezone, misfire, overlap hay
việc scheduler tự động phát occurrence.
