# Task mới: dbt Integration với Kyuubi — Tóm tắt bối cảnh

Tài liệu này tóm tắt những gì đã tìm hiểu được về task mới (sau khi đã hoàn
thành task notebook PySpark/SQL trước đó), dùng để agent nắm bối cảnh trước
khi bắt tay debug/phát triển tiếp.

---

## 1. Mục tiêu task

Mentor mô tả: triển khai dbt kết hợp Kyuubi, sao cho khi chạy dbt job, user
có thể **chọn/config engine** cho dbt chạy trên đó (ví dụ engine nhỏ cho
dev, engine lớn cho batch production).

---

## 2. Cơ chế kết nối — đã xác nhận qua file demo thật của mentor

Mentor đã có sẵn 1 project demo tại `~/project/lakehouse_dbt/`, dùng
`profiles.yml` như sau (đã xem trực tiếp, không phải suy đoán):

```yaml
lakehouse_dbt:
  target: dev
  outputs:
    dev:
      type: spark
      method: thrift
      host: "{{ env_var('KYUUBI_HOST', '103.179.189.33') }}"
      port: "{{ env_var('KYUUBI_PORT', '30009') | int }}"
      schema: dbt
      user: "{{ env_var('KYUUBI_USER', 'lakehouse') }}"
      password: "{{ env_var('KYUUBI_PASSWORD') }}"
      auth: LDAP
      use_ssl: false
      threads: 2
      connect_retries: 3
      connect_timeout: 30
      query_timeout: 300
      server_side_parameters:
        spark.sql.defaultCatalog: lakehouse
        spark.sql.session.timeZone: UTC
```

Điểm mấu chốt đã xác nhận:
- dbt dùng adapter **`dbt-spark`**, phương thức **`method: thrift`** — kết
  nối qua **Thrift Binary port (30009 NodePort)**, cần verify lại xem có đúng port 30009 hay không.
- `server_side_parameters` là cơ chế **có sẵn của dbt-spark** để truyền
  thêm session config tùy ý xuống Kyuubi lúc mở kết nối — đây chính là chỗ
  dùng để set `kyuubi.engine.share.level.subdomain`.

---

## 3. Cơ chế "chọn engine" dự kiến — dựa trên khái niệm Kyuubi có sẵn

`kyuubi.engine.share.level.subdomain` là **session config gốc của Apache
Kyuubi** (không phải tự chế), dùng để tạo các "nhóm" engine tách biệt trong
cùng 1 user — mỗi subdomain khác nhau sẽ có engine riêng, không dùng
chung.

Ý tưởng: mỗi **target** trong `profiles.yml` (dbt hỗ trợ nhiều target
trong cùng 1 file, chọn bằng `dbt run --target <tên>`) set 1 giá trị
`kyuubi.engine.share.level.subdomain` khác nhau trong `server_side_parameters`.
Ví dụ:

```yaml
outputs:
  dev:
    server_side_parameters:
      kyuubi.engine.share.level.subdomain: dbt_dev_small
  prod_large:
    server_side_parameters:
      kyuubi.engine.share.level.subdomain: dbt_prod_large
```

User chọn engine bằng cách chọn `--target` lúc chạy `dbt run`.

---

## 4. Vấn đề khác đã biết — auth LDAP qua dbt-spark

Có 1 bug đã được cộng đồng ghi nhận (GitHub issue #270,
dbt-labs/dbt-spark): code dbt-spark từng BỎ QUA password truyền vào qua
profiles.yml khi dùng `auth: LDAP`, khiến kết nối LDAP qua Thrift thất
bại dù thông tin đúng. Cần xác nhận thực tế xem version dbt-spark/
dbt-adapters đang dùng đã vá lỗi này chưa (`dbt debug` sẽ lộ ra ngay nếu
còn dính). Nếu còn lỗi, có thể tạm test bằng cách chuyển server test sang
`kyuubi.authentication=NONE` (đã có kinh nghiệm làm việc này từ task
trước) để tách riêng vấn đề auth khỏi vấn đề engine profile. Có thể bỏ qua vấn đề auth để tập trung làm task này trước.

---

## 5. Thứ tự kiểm tra đề xuất
```
1. dbt debug --target dev            → xác nhận kết nối cơ bản OK
2. Xác nhận LDAP auth có lỗi bug #270 không (mục 5)
3. dbt run --select <model đơn giản> → xác nhận SQL thực thi qua Thrift
   thành công
4. Test 2 target/2 subdomain khác nhau, kiểm tra Pod driver resource
   thật (mục 4, bước 1) → XÁC ĐỊNH CÓ CẦN MỞ RỘNG CODE HAY KHÔNG
5. Nếu bước 4 xác nhận cần mở rộng → thảo luận phạm vi với mentor trước
   khi code (mục 4, bước 2-3)
```

---

## 6. Kế hoạch triển khai chi tiết

### Giai đoạn 0 — Chốt phạm vi và điều kiện môi trường

Mục tiêu của giai đoạn này là tránh sửa code Kyuubi trước khi biết cơ chế
native đã đáp ứng được yêu cầu hay chưa.

1. Xác nhận phiên bản đang sử dụng:
   - Kyuubi và Spark.
   - Python, `dbt-core`, `dbt-spark`, `pyhive`/`thrift`.
   - Cơ chế xác thực Kyuubi hiện tại là LDAP hay `NONE`.
2. Xác nhận endpoint Thrift mà dbt phải dùng:
   - Service/NodePort/port thực tế.
   - Có thể kiểm tra bằng `kubectl get svc -n kyuubi` và thử mở TCP tới port.
3. Xác nhận metadata database của Kyuubi đang dùng là persistent. Việc này
   không quyết định engine sharing, nhưng giúp loại bỏ nhầm lẫn khi kiểm tra
   execution/profile về sau.
4. Ghi lại trạng thái ban đầu của các Pod driver để làm baseline:

```bash
kubectl get pods -n kyuubi -o wide
kubectl get svc -n kyuubi
```

#### Kết quả kiểm tra Giai đoạn 0 — 2026-09-09

- Kyuubi đang chạy: `1.10.3`, Java 17, Scala 2.12; Spark engine image đang
  chạy Spark `3.5.5`.
- Project `~/project/lakehouse_dbt/` đã có virtualenv: Python `3.12.3`,
  `dbt-core 1.12.0` và `dbt-spark 1.10.3`. Lệnh `dbt --version` chạy được.
- Cluster test hiện đặt `kyuubi.authentication=NONE`, trong khi profile demo
  đang là `auth: LDAP`. Vì vậy phải tạo/chỉnh một target test khớp với `NONE`
  trước khi dùng profile LDAP của môi trường mentor.
- Kyuubi hiện chỉ expose Thrift Binary qua service nội bộ
  `kyuubi-thrift-binary:10009` (`ClusterIP`). Không có NodePort `30009` trong
  cluster hiện tại. Giai đoạn 1 cần chọn một trong hai cách: chạy dbt trong
  cluster, hoặc `kubectl port-forward svc/kyuubi-thrift-binary <local-port>:10009`.
- Không thấy Spark driver pod tại thời điểm lấy baseline; chỉ có `kyuubi-0`
  và `kyuubi-1` đang Running.
- Metadata store đang dùng SQLite mặc định tại
  `/opt/kyuubi/kyuubi_state_store.db`. StatefulSet không mount volume bền
  vững vào `/opt/kyuubi`, nên mỗi replica có database cục bộ riêng và dữ liệu
  có thể mất khi pod bị thay thế. Đây là rủi ro độc lập với dbt nhưng cần được
  xử lý bằng một JDBC metadata database dùng chung trước khi coi môi trường
  hai replica là production-ready.

### Giai đoạn 1 — Dựng project dbt tối thiểu

Tạo một project nhỏ chỉ có một model SQL đơn giản. Chưa thêm custom code hoặc
engine profile ở giai đoạn này.

1. Cài đúng `dbt-core` và `dbt-spark` trong một virtualenv riêng.
2. Tạo `dbt_project.yml`, thư mục `models/` và một model kiểm tra như
   `select 1 as test_value`.
3. Tạo `profiles.yml` với các biến môi trường cho host, port, user và
   password; không hard-code secret vào repository.
4. Dùng target `dev` với `method: thrift`, `threads: 1` để giảm nhiễu khi
   debug kết nối.

Kết quả cần đạt: project được dbt nhận diện và profile hợp lệ trước khi chạy
query thật.

#### Chuẩn bị đã thực hiện — 2026-09-09

Trong `~/project/lakehouse_dbt/` đã thêm:

- target `local_test` trong `profiles.yml`: Thrift tới `127.0.0.1:10009`,
  `auth: NONE`, một thread và vẫn giữ catalog `lakehouse`;
- model `models/smoke/smoke_connection.sql`, materialize thành view từ
  `select 1 as test_value`.

Target `dev` dùng LDAP của mentor không bị chỉnh sửa. Đã chạy thành công:

```bash
DBT_PROFILES_DIR=/root/project/lakehouse_dbt \
  /root/project/lakehouse_dbt/.venv/bin/dbt parse --target local_test
```

Đã xác minh tiếp Giai đoạn 2: `dbt debug --target local_test` kết nối thành
công qua Thrift, và `dbt run --target local_test --select smoke_connection`
tạo thành công view `dbt.smoke_connection`.

### Lệnh chạy Giai đoạn 1 và 2

Mở hai terminal.

Terminal A — mở đường từ máy host vào Thrift Binary service:

```bash
kubectl port-forward -n kyuubi svc/kyuubi-thrift-binary 10009:10009
```

Giữ terminal này chạy. Nếu port local `10009` đã được dùng, thay bằng `10090`:

```bash
kubectl port-forward -n kyuubi svc/kyuubi-thrift-binary 10090:10009
```

Terminal B — chạy dbt với target local:

```bash
cd /root/project/lakehouse_dbt
source .venv/bin/activate
export DBT_PROFILES_DIR="$PWD"
export KYUUBI_HOST=127.0.0.1
export KYUUBI_PORT=10009
export KYUUBI_USER=dbt

dbt debug --target local_test
dbt parse --target local_test
dbt run --target local_test --select smoke_connection
```

Nếu dùng local port `10090`, đặt `export KYUUBI_PORT=10090` trước ba lệnh dbt.
Trong lúc chạy lệnh cuối, theo dõi driver mới ở terminal thứ ba:

```bash
kubectl get pods -n kyuubi -w
```

Kết quả mong đợi: `dbt debug` báo connection OK, còn `dbt run` tạo view
`smoke_connection` trong schema `dbt` thuộc catalog `lakehouse` và tạo Spark
engine driver nếu chưa có engine phù hợp.

### Giai đoạn 2 — Xác minh kết nối và authentication

Chạy theo thứ tự:

```bash
dbt debug --target dev
dbt parse --target dev
dbt run --target dev --select <model-kiem-tra>
```

Phân loại lỗi trước khi sửa:

- Không kết nối được: kiểm tra host, port, NodePort, network và Thrift service.
- LDAP bị từ chối: kiểm tra version `dbt-spark`/`dbt-adapters`, đặc biệt việc
  truyền password; có thể tạm chuyển môi trường test sang `NONE` để cô lập
  lỗi authentication.
- Kết nối thành công nhưng query lỗi: kiểm tra catalog/schema, quyền user và
  cấu hình Spark session.

Chỉ chuyển sang giai đoạn tiếp theo khi một model đơn giản chạy thành công.

### Giai đoạn 3 — Kiểm chứng truyền session parameter

Thêm vào target `dev`:

```yaml
server_side_parameters:
  kyuubi.engine.share.level.subdomain: dbt_dev_small
  spark.sql.session.timeZone: UTC
```

Sau đó chạy lại `dbt debug` và `dbt run`, đồng thời kiểm tra:

1. Log Kyuubi server có nhận session configuration hay không.
2. Engine được tạo có subdomain `dbt_dev_small` hay không.
3. Pod driver được tạo có đúng engine profile/resource dự kiến hay không.
4. Các lần chạy tiếp theo cùng target có dùng lại đúng engine theo sharing
   level hay không.

#### Chuẩn bị đã thực hiện — 2026-09-09

Target `local_test` đã được bổ sung:

```yaml
kyuubi.engine.share.level.subdomain: dbt_dev_small
```

Lệnh kiểm tra:

```bash
# Terminal A: giữ port-forward Thrift đang chạy.
kubectl port-forward -n kyuubi svc/kyuubi-thrift-binary 10009:10009

# Terminal B: trước khi chạy, lưu baseline Pod hiện có.
kubectl get pods -n kyuubi

# Terminal C: từ project dbt.
cd /root/project/lakehouse_dbt
source .venv/bin/activate
export DBT_PROFILES_DIR="$PWD"
export KYUUBI_HOST=127.0.0.1
export KYUUBI_PORT=10009
export KYUUBI_USER=dbt
dbt debug --target local_test
dbt run --target local_test --select smoke_connection

# Terminal B: quan sát driver mới trong khi Terminal C chạy.
kubectl get pods -n kyuubi -w
```

Kỳ vọng: một Spark driver mới xuất hiện cho subdomain `dbt_dev_small` (tên
Kubernetes có thể normalize dấu gạch dưới thành dấu gạch ngang). Chạy lại
lệnh `dbt run` khi driver vẫn còn sống không được tạo thêm một driver cùng
subdomain. Nếu không thấy driver mới hoặc driver vẫn mang subdomain default,
thu thập log Kyuubi trước khi chuyển sang Giai đoạn 4.

#### Kết quả kiểm tra Giai đoạn 3 — 2026-09-09

Đạt yêu cầu. Sau khi chạy dbt với target `local_test`, Kubernetes tạo driver
và executor riêng có tên chứa `dbt-dbt-dev-small`. Driver trước đó của target
không có subdomain vẫn có tên chứa `dbt-default`. Điều này xác nhận đường đi:

```text
profiles.yml server_side_parameters
  → dbt-spark Thrift OpenSession configuration
  → Kyuubi engine share key
  → Spark engine/driver riêng cho dbt_dev_small
```

Kubernetes đã normalize `dbt_dev_small` thành `dbt-dev-small` trong tên Pod.
Kết quả này xác minh **tách engine theo subdomain**, chưa xác minh phân bổ
tài nguyên khác nhau giữa các profile; đó là mục tiêu của Giai đoạn 4.

Nếu parameter không tới được Kyuubi, cần kiểm tra phiên bản adapter và cách
`dbt-spark` tạo `TCLIService` session trước khi thay đổi backend.

### Giai đoạn 4 — Kiểm chứng nhiều target và tài nguyên engine

Tạo ít nhất hai target rõ ràng trong `profiles.yml`:

```yaml
outputs:
  dev:
    server_side_parameters:
      kyuubi.engine.share.level.subdomain: dbt_dev_small
  prod_large:
    server_side_parameters:
      kyuubi.engine.share.level.subdomain: dbt_prod_large
```

Chạy lần lượt:

```bash
dbt run --target dev --select <model-kiem-tra>
dbt run --target prod_large --select <model-kiem-tra>
kubectl get pods -n kyuubi -w
```

Đối chiếu cho từng target:

- engine address/session owner;
- driver Pod và executor Pod;
- driver memory/cores, executor memory/cores, số executor;
- subdomain trong log hoặc thông tin engine discovery;
- việc chạy lại cùng target có reuse engine đúng ý định hay không.

#### Chuẩn bị đã thực hiện — 2026-09-09

Đã thêm target `local_large` vào `~/project/lakehouse_dbt/profiles.yml`:

```yaml
kyuubi.engine.share.level.subdomain: dbt_prod_large
spark.dynamicAllocation.enabled: "false"
spark.driver.memory: 2g
spark.driver.cores: "2"
spark.executor.memory: 3g
spark.executor.cores: "2"
spark.executor.instances: "1"
```

`local_test` vẫn là profile nhỏ/default với subdomain `dbt_dev_small`. Đây là
hai profile cấu hình ngay trong dbt để kiểm chứng Thrift OpenSession truyền
Spark config tới engine. Chúng chưa phải các Engine Profile persist trong UI:
hiện `NotebookEngineProfileService` chỉ được áp dụng bởi notebook runtime.

Lệnh kiểm tra:

```bash
# Terminal A: tiếp tục giữ port-forward Thrift.
kubectl port-forward -n kyuubi svc/kyuubi-thrift-binary 10009:10009

# Terminal B: theo dõi và lưu tên Pod của hai engine.
kubectl get pods -n kyuubi -w

# Terminal C: chạy lần lượt hai target dbt.
cd /root/project/lakehouse_dbt
source .venv/bin/activate
export DBT_PROFILES_DIR="$PWD"
export KYUUBI_HOST=127.0.0.1
export KYUUBI_PORT=10009
export KYUUBI_USER=dbt

dbt run --target local_test --select smoke_connection
dbt run --target local_large --select smoke_connection
```

Sau khi xuất hiện driver/executor có `dbt-dbt-prod-large` trong tên, thay
`<driver-pod>` và `<executor-pod>` bằng tên thực tế để kiểm tra request của
Kubernetes:

```bash
kubectl get pod -n kyuubi <driver-pod> \
  -o jsonpath='{range .spec.containers[*]}{.name}{" cpu="}{.resources.requests.cpu}{" memory="}{.resources.requests.memory}{"\n"}{end}'

kubectl get pod -n kyuubi <executor-pod> \
  -o jsonpath='{range .spec.containers[*]}{.name}{" cpu="}{.resources.requests.cpu}{" memory="}{.resources.requests.memory}{"\n"}{end}'
```

Kỳ vọng: Pod tên `dbt-dbt-prod-large` tách biệt với `dbt-dbt-dev-small` và
request CPU/memory cao hơn profile nhỏ/default. Memory request có thể lớn hơn
con số Spark memory cấu hình vì Spark cộng thêm memory overhead cho container.

#### Kết quả kiểm tra Giai đoạn 4 — 2026-09-09

Đạt yêu cầu. Target `local_large` tạo engine riêng có subdomain
`dbt_prod_large`, được Kubernetes normalize thành `dbt-dbt-prod-large`.
Resource request quan sát trực tiếp từ Pod là:

| Pod | Cấu hình dbt | Kubernetes request thực tế | Kết luận |
|---|---|---|---|
| Driver | `2g`, `2 cores` | `cpu=2`, `memory=2432Mi` | Khớp; `2048Mi + 384Mi` Spark overhead |
| Executor | `3g`, `2 cores` | `cpu=2`, `memory=3456Mi` | Khớp; `3072Mi + 384Mi` Spark overhead |

Vì vậy, với Kyuubi Thrift và `dbt-spark`, mỗi dbt target có thể đồng thời:

1. Chọn engine độc lập bằng `kyuubi.engine.share.level.subdomain`.
2. Cấu hình driver/executor bằng các `spark.*` trong `server_side_parameters`.

Hai cơ chế này hoạt động bằng cấu hình chuẩn, chưa cần thay đổi code Kyuubi.

Đây là checkpoint quyết định. Nếu hai target tạo và sử dụng đúng hai nhóm
engine, task có thể hoàn thành bằng cấu hình dbt, không cần sửa Kyuubi.

### Giai đoạn 5 — Chỉ mở rộng code nếu kiểm thử chứng minh cần thiết

Nếu `server_side_parameters` không đủ để chọn resource profile, debug theo
flow sau:

1. Xác định parameter bị mất ở đâu: `profiles.yml` → dbt adapter → Thrift
   open session → Kyuubi session configuration → engine request.
2. Kiểm tra Kyuubi có đọc `kyuubi.engine.share.level.subdomain` từ session
   config và có truyền subdomain vào engine share key hay không.
3. Nếu Kyuubi đã nhận subdomain nhưng không map được resource allocation,
   xác định điểm mở rộng tối thiểu ở `NotebookSessionService`/
   `NotebookEngineProfileService` hoặc engine startup path.
4. Không đưa logic dbt-specific vào core nếu chỉ cần cấu hình chuẩn của Kyuubi.
   Nếu thật sự phải mở rộng, thiết kế backward-compatible và giữ hành vi
   mặc định của các client khác.
5. Bổ sung test backend cho từng subdomain/profile và test integration bằng
   hai target dbt.

Mọi thay đổi code ở giai đoạn này cần được thống nhất phạm vi với mentor
trước khi triển khai.

### Giai đoạn 6 — Hoàn thiện và bàn giao

1. Chuẩn hóa `profiles.yml` mẫu, thay secret bằng `env_var`.
2. Ghi rõ port, authentication, target và quy ước đặt tên subdomain.
3. Bổ sung hướng dẫn chạy `dbt debug`, `dbt run` và cách kiểm tra Pod.
4. Ghi lại kết quả test cho cả target nhỏ và target lớn.
5. Nếu có sửa Kyuubi, chạy format/test cần thiết, build module liên quan và
   cập nhật image trước khi kiểm thử trên K3s.

## 7. Tiêu chí hoàn thành

Task được xem là đạt khi:

- `dbt debug` thành công qua Thrift với authentication được chọn.
- Một model dbt chạy thành công và tạo được kết quả trên Kyuubi.
- Ít nhất hai target truyền được hai subdomain khác nhau.
- Hai target tạo/reuse đúng engine riêng, với tài nguyên quan sát được đúng
  cấu hình mong muốn.
- Việc đăng nhập lại hoặc chạy lại job không làm mất cấu hình target.
- Có tài liệu cấu hình và quy trình kiểm tra để mentor chạy lại độc lập.

---

## 8. Định hướng sản phẩm mới: DBT Job chọn Engine Profile từ UI

> Phần này thay thế kết luận trước đó rằng không cần mở rộng code. Kết luận
> cũ chỉ đúng cho proof-of-concept, khi người vận hành tự ghi mọi `spark.*`
> vào `profiles.yml`. Yêu cầu sản phẩm mới là UI chọn một Engine Profile đã
> được lưu/policy kiểm soát, rồi nhiều DBT Job có thể dùng profile đó.

### 8.1. Mục tiêu và nguyên tắc

Người dùng tạo Engine Profile một lần, sau đó khi tạo/chạy DBT Job chỉ chọn
profile bằng tên hoặc ID. UI **không gửi** driver memory, executor memory,
cores hoặc raw `server_side_parameters` do người dùng tự nhập.

Quyết định sản phẩm đã chốt: DBT workspace/job lưu `selectedEngineProfileId`.
Mọi action phát sinh từ workspace/job đó — preview, chạy một model, chạy nhóm
model, `dbt test`, `dbt build` hoặc chạy cả project — mặc định kế thừa profile
này. User chỉ đổi profile cho **lần chạy mới**; một run đang hoạt động không
bị chuyển engine giữa chừng.

```text
UI chọn DBT Job + Engine Profile
          │
          ▼
DBT Job Backend xác thực và authorize
          │
          ├─ đọc Engine Profile đã lưu
          ├─ chụp (snapshot) profile/config cho lần chạy
          └─ sinh profiles.yml tạm cho dbt runner
                    │
                    ▼
             dbt-spark / Thrift OpenSession
                    │
                    ▼
           Kyuubi → Spark engine đã chọn
```

Nguyên tắc bảo mật: client chỉ được chọn profile mà mình có quyền dùng.
Backend là nơi duy nhất chuyển profile thành `kyuubi.engine.share.level.subdomain`
và `spark.*`; không tin raw Spark config từ HTTP request/UI.

### 8.2. Quyết định cần chốt với mentor trước khi code

1. **Phạm vi share engine — đã chốt**
   - Giữ `kyuubi.engine.share.level=USER`.
   - Chỉ user sở hữu profile/engine mới được dùng engine đó. Job của user khác
     không reuse engine dù biết cùng tên subdomain.
   - Notebook, SQL Editor và DBT của **cùng user** chọn cùng profile phải
     reuse cùng Spark engine nếu engine còn sống.
2. **Nơi chạy dbt**
   - Khuyến nghị: backend tạo Kubernetes Job/worker chạy image có `dbt-spark`.
   - Không chạy dbt trong browser; không để browser giữ Kyuubi password.
3. **Danh tính và credential tới Kyuubi**
   - DBT worker phải mở Thrift session với **effective Kyuubi user là user đã
     bấm Run**, không phải technical user cố định `dbt`; điều kiện này quyết
     định việc reuse được engine Notebook/SQL Editor ở USER share level.
   - Test local có thể dùng `NONE` với username tương ứng user test.
   - Production cần OIDC token delegation, credential tham chiếu theo user,
     hoặc proxy-user được Kyuubi cho phép. Không lưu password người dùng trong
     DBT Job record.
4. **Chính sách cập nhật profile**
   - Profile bị sửa chỉ áp dụng cho engine mới.
   - Một engine đang sống không thể đổi driver/executor resource tại chỗ.
   - Run phải lưu snapshot profile để audit/retry có kết quả xác định.

### 8.3. Tách Engine Profile thành domain dùng chung

Hiện profile nằm trong `NotebookEngineProfileService` và chỉ
`NotebookSessionService.ensureFor` gọi `resolveSparkConfig`. DBT Job không
được đi qua luồng đó, nên cần tách phần domain sau ra khỏi Notebook:

```text
EngineProfileService
  - create / update / delete / list / get
  - authorize owner, admin và người được cấp quyền dùng
  - validate subdomain + allowed Spark config
  - resolve immutable profile snapshot cho một job run
```

Kế hoạch refactor:

1. Giữ `NotebookStore`/schema hiện có trong bước đầu để tránh migration lớn,
   nhưng đổi tên service thành domain-neutral (ví dụ `EngineProfileService`).
2. `NotebookSessionService` tiếp tục gọi service mới, bảo toàn hành vi Notebook.
3. DBT Job backend gọi cùng service, thay vì đọc trực tiếp SQLite/JDBC hoặc
   gọi vòng qua UI REST API.
4. Chỉ cho phép các config an toàn cần thiết (`spark.driver.*`,
   `spark.executor.*`, một allowlist `spark.*`); tách allowlist engine-launch
   khỏi config operation/session thông thường.
5. Bổ sung `profileVersion` hoặc snapshot JSON vào DBT run. Schema profile
   hiện không có version, nên version/snapshot là cần thiết cho audit.

> Lưu ý: schema hiện dùng `subdomain` làm primary key toàn cục. Nếu cần hai
> user cùng tạo profile trùng tên như `large`, thiết kế mới nên dùng `profile_id`
> bất biến và tạo subdomain duy nhất theo owner/profile, ví dụ
> `u-<owner>-large` hoặc UUID-derived. Không nên để user tự đoán/chiếm
> subdomain của user khác.

### 8.4. Model dữ liệu DBT Job

Thêm domain DBT Job (có thể nằm trong service của company web nếu không muốn
đưa job orchestration vào Kyuubi core):

```text
DbtJob
  id, owner, workspaceId, name, projectRef/gitRef, selectedEngineProfileId,
  command/modelSelector, schedule, createdAt, updatedAt, version

DbtWorkspace
  id, owner, projectRef/gitRef, selectedEngineProfileId,
  createdAt, updatedAt, version

DbtJobRun
  id, jobId, submittedBy, state, startedAt, finishedAt,
  engineProfileSnapshot, kyuubiUser/executionIdentity,
  runnerJobName, dbtArtifactsLocation, errorSummary
```

`selectedEngineProfileId` là lựa chọn bền vững của job. `engineProfileSnapshot`
là config thật đã dùng trong từng run, không bị thay đổi khi profile sau này
được edit hoặc xóa.

Nếu job không override, nó kế thừa `selectedEngineProfileId` của
`DbtWorkspace`. Điều này làm header UI DBT hoạt động giống Notebook: profile
đang chọn luôn nhìn thấy được và nhất quán cho Preview/Run/Test/Build.

### 8.5. API contract dự kiến

UI cần các API tách rõ hai đối tượng:

```text
GET/PUT/DELETE /api/v1/engine-profiles/{id}
GET            /api/v1/engine-profiles

POST           /api/v1/dbt-jobs
GET/PATCH/DELETE /api/v1/dbt-jobs/{id}
POST           /api/v1/dbt-jobs/{id}:run
GET            /api/v1/dbt-jobs/{id}/runs
GET            /api/v1/dbt-job-runs/{runId}
POST           /api/v1/dbt-job-runs/{runId}:cancel
```

Request tạo/sửa DBT Job chỉ chứa `engineProfileId`, không chứa Spark resource
raw. Backend kiểm tra user có quyền `USE` profile trước khi lưu hoặc chạy job.

### 8.6. Luồng chạy một DBT Job

1. User trên company UI bấm **Preview**, **Run**, **Test**, **Build** hoặc
   chạy project. Action lấy `engineProfileId` đã lưu ở DBT workspace/job;
   user có thể đổi lựa chọn trước khi tạo run mới.
2. Backend xác thực caller, lấy DBT Job và authorize quyền chạy job.
3. Backend resolve Engine Profile; kiểm tra quyền `USE`, profile còn tồn tại,
   config hợp lệ và subdomain hợp lệ.
4. Backend tạo `DbtJobRun` với snapshot profile/config.
5. Backend tạo Kubernetes Job/worker cho dbt. Worker nhận project source,
   dbt command, credential tham chiếu và file `profiles.yml` **tạm**. Credential
   đó phải đại diện cho chính user bấm action (hoặc proxy thành user đó).
6. Backend sinh output target tạm tương đương:

```yaml
server_side_parameters:
  kyuubi.engine.share.level.subdomain: <profile-subdomain>
  spark.driver.memory: <profile-config-value>
  spark.driver.cores: <profile-config-value>
  spark.executor.memory: <profile-config-value>
  spark.executor.cores: <profile-config-value>
  spark.executor.instances: <profile-config-value>
```

7. `dbt-spark` gửi map đó trong Thrift `OpenSession`; Kyuubi tìm/reuse hoặc
   launch engine theo execution identity + share level + subdomain.
8. Worker stream log/artifact (`run_results.json`, `manifest.json`) về object
   storage/database, cập nhật trạng thái `DbtJobRun` cho UI.
9. Khi user cancel, backend hủy DBT worker và đóng/cancel Kyuubi operation;
   chỉ terminate shared engine khi chính sách profile cho phép, vì engine có
   thể đang được job/notebook khác dùng.

### 8.7. Quy tắc reuse và lifecycle engine

| Tình huống | Hành vi cần có |
|---|---|
| Notebook, SQL Editor và DBT cùng user + cùng profile/subdomain | Reuse cùng engine nếu engine còn sống |
| Hai DBT job cùng user + cùng profile/subdomain | Reuse cùng engine nếu engine còn sống |
| Hai user khác nhau, dù chọn profile có cùng tên | Engine tách biệt ở `USER` share level |
| Hai job/notebook khác profile/subdomain | Engine tách biệt |
| Profile update khi engine đang sống | Engine cũ giữ config cũ; run mới phải chọn policy reuse hoặc tạo engine mới |
| Profile delete đang có job chạy | Chặn xóa hoặc yêu cầu xác nhận; không tự kill shared engine mù quáng |
| DBT run bị cancel | Cancel job/operation trước; engine để idle timeout tự dọn trừ khi user chọn terminate riêng |

Để profile update có hiệu lực rõ ràng, khuyến nghị version subdomain theo
profile version (ví dụ `team-etl-large-v3`) hoặc terminate engine cũ theo
workflow có xác nhận. Không dùng cùng subdomain với resource khác và kỳ vọng
Spark đổi resource runtime.

### 8.8. Khả năng dùng chung giữa Notebook, SQL Editor và DBT

Ba client không cần dùng chung HTTP session hay cùng process để share engine.
Chúng chỉ cần tạo Kyuubi session với cùng bộ định danh engine:

```text
effective Kyuubi user = alice
kyuubi.engine.share.level = USER
kyuubi.engine.share.level.subdomain = alice-prod-large
engine type = SPARK_SQL
```

Kyuubi discovery sẽ trả về cùng Spark engine cho cả ba client. Mỗi client vẫn
có Kyuubi/Thrift session riêng, nên SQL session state, temporary view,
variables và operation handle không tự chia sẻ; phần được chia sẻ là JVM
Spark driver/executors và resource allocation. Đây là hành vi đúng để một
engine chung không làm lộ state nghiệp vụ giữa Notebook, Editor và DBT.

### 8.9. Thứ tự triển khai

1. Chốt bốn quyết định ở mục 8.2 với mentor.
2. Viết contract/API và model DBT Job/Run; chọn nơi đặt runner orchestration.
3. Refactor Engine Profile thành service dùng chung, giữ regression test cho
   Notebook profile hiện tại.
4. Bổ sung persistence/migration cho DBT Job, Run, profile snapshot/version
   và quyền `USE` profile.
5. Implement DBT runner Kubernetes Job + generation `profiles.yml` tạm từ
   snapshot; secret không nằm trong source hoặc DBT artifact.
6. Implement API run/cancel/status/log/artifact.
7. Company UI tích hợp dropdown Engine Profile và trang theo dõi DBT Job Run.
8. Test integration: hai job cùng profile reuse engine, hai profile tách Pod,
   profile update/delete/cancel, RBAC, retry và idle cleanup.

### 8.10. Tiêu chí nghiệm thu mới

- User chỉ chọn Engine Profile có quyền sử dụng từ UI.
- DBT Job Run lưu profile snapshot và audit được resource/subdomain thật dùng.
- Hai job phù hợp policy reuse dùng chung engine; job khác profile tạo engine
  tách biệt.
- Notebook, SQL Editor và DBT của cùng user/cùng profile cùng nhìn thấy một
  Spark engine; user khác không reuse engine đó.
- Không có raw Spark config hoặc Kyuubi credential nhạy cảm từ browser.
- Notebook hiện tại tiếp tục tạo/reuse engine profile đúng như trước.
- Pod resource thực tế khớp snapshot đã chọn; log/artifact/cancel status xem
  được từ UI.

---

## 9. Roadmap triển khai MVP: backend-first, UI demo tối giản

### 9.1. Scope MVP

MVP cần chứng minh end-to-end rằng một DBT workspace/job đã chọn Engine
Profile sẽ chạy mọi action qua đúng engine của user. Không xây lại dbt Studio
đầy đủ vì company UI chính thức sẽ là nơi tích hợp sản phẩm cuối cùng.

**Trong scope:** profile selection, job/run state, DBT runner, Kyuubi session
identity, preview/run, log/status và test reuse engine.

**Ngoài scope:** editor SQL/DBT, file browser, DAG/lineage, Git UI, scheduler
hoàn chỉnh, artifact explorer đầy đủ và clone giao diện dbt Studio.

### 9.2. Phase A — Chốt contract và identity

Chi tiết contract, runner abstraction, API payload và acceptance checklist nằm
trong [dbt_phase_a_contract.md](dbt_phase_a_contract.md).

1. Chốt DBT runner sẽ chạy ở đâu: Kubernetes Job là lựa chọn mặc định.
2. Chốt cách runner mở Kyuubi session dưới effective user của người bấm Run.
   Đây là prerequisite để share engine với Notebook/SQL Editor ở `USER` level.
3. Chốt một project dbt demo cố định và action MVP:
   - `preview` một model/query;
   - `run model`;
   - `run project`.
4. Viết request/response API trước khi code UI:

```text
PATCH /api/v1/dbt-workspaces/{id}
  { "engineProfileId": "..." }

POST /api/v1/dbt-workspaces/{id}:preview
POST /api/v1/dbt-jobs/{id}:run
GET  /api/v1/dbt-job-runs/{runId}
GET  /api/v1/dbt-job-runs/{runId}/logs
```

**Done khi:** contract xác định rõ caller không được gửi raw `spark.*`, chỉ
chọn Engine Profile.

### 9.3. Phase B — Generalize Engine Profile domain

Chi tiết refactor, ownership policy, migration boundary và regression suite
nằm trong [dbt_phase_b_engine_profile_plan.md](dbt_phase_b_engine_profile_plan.md).

1. Tách phần dùng chung khỏi `NotebookEngineProfileService` thành
   `EngineProfileService`.
2. Giữ API/UI Notebook hiện có hoạt động không đổi; Notebook chỉ đổi sang gọi
   service mới.
3. Bổ sung API/domain method:
   - `requireUsable(profileId, principal)`;
   - `snapshot(profileId, principal)`;
   - validation allowlist engine-launch Spark config;
   - version/snapshot metadata.
4. Không để DBT code đọc `NotebookStore` hoặc database trực tiếp.
5. Viết regression tests cho create/update/delete/profile resolution của
   Notebook hiện có.

**Done khi:** cùng một profile snapshot có thể được Notebook và DBT backend
resolve với cùng subdomain/config, đồng thời owner check vẫn đúng.

### 9.4. Phase C — Persist DBT Workspace, Job và Run

1. Thêm schema/store/service cho:
   - `DbtWorkspace`: owner, project reference, `selectedEngineProfileId`;
   - `DbtJob`: workspace, name, dbt command/model selector;
   - `DbtJobRun`: state, profile snapshot, effective user, runner reference,
     timestamps, error summary.
2. Khi đổi Engine Profile ở workspace/job, chỉ action/run tạo sau đó kế thừa
   profile mới.
3. Khi bấm Run, chụp profile snapshot ngay lúc tạo run.
4. Thêm optimistic version và authorization theo owner.

**Done khi:** một run cũ vẫn audit/retry được dù profile hiện tại đã đổi hoặc
bị xóa.

### 9.5. Phase D — DBT runner và generation cấu hình tạm

1. Chuẩn bị image runner riêng có Python, `dbt-core`, `dbt-spark[PyHive]` và
   dependency cần thiết; không cài package lúc mỗi run.
2. Backend tạo Kubernetes Job/worker chứa project source ở dạng read-only.
3. Backend sinh `profiles.yml` tạm trong volume/Secret ngắn hạn, gồm:

```yaml
server_side_parameters:
  kyuubi.engine.share.level.subdomain: <snapshot.subdomain>
  # toàn bộ allowed spark.* lấy từ snapshot.sparkConfig
```

4. Inject credential Kyuubi an toàn theo effective user; không ghi secret vào
   DBT artifact, Pod log hoặc database.
5. Worker chạy action được chọn (`dbt run`, preview query/model, `dbt build`).
6. Thu log stdout/stderr, `run_results.json` và `manifest.json`; cập nhật
   `DbtJobRun`.

**Done khi:** một DBT run tạo/reuse engine theo snapshot và UI/API đọc được
state/log cơ bản.

### 9.6. Phase E — Action service và lifecycle

1. Implement Preview, Run Model và Run Project trên cùng `DbtExecutionService`.
2. Mọi action lấy profile từ workspace/job, không nhận raw config từ request.
3. Implement cancel:
   - hủy Kubernetes Job/dbt process;
   - cancel Kyuubi operation nếu đã có operation handle;
   - không auto terminate shared engine.
4. Profile delete/update phải kiểm tra active DBT runs; chặn, cảnh báo hoặc để
   engine idle cleanup theo chính sách đã chốt.

**Done khi:** preview/run/cancel đều có lifecycle rõ ràng, không ảnh hưởng
engine đang được Notebook/SQL Editor khác dùng.

### 9.7. Phase F — Integration tests quan trọng

1. Cùng user + cùng profile:
   - run Notebook;
   - run SQL Editor;
   - run DBT model;
   - xác minh chỉ một Spark driver subdomain được dùng.
2. Cùng user + hai profile: xác minh hai driver/resource khác nhau.
3. Hai user khác nhau + cùng tên profile: xác minh engine tách biệt.
4. Profile đổi resource: run mới có snapshot mới, engine cũ không tự đổi
resource.
5. Permission: user không thể chạy job bằng profile của user khác.
6. Cancel DBT run: worker dừng, engine shared vẫn không bị kill nhầm.
7. Verify Kubernetes resource request khớp profile snapshot.

### 9.8. Phase G — UI demo mỏng

Chỉ sau khi API/integration test ổn định, tạo màn hình demo nhỏ:

```text
DBT Workspace demo
  - dropdown Engine Profile
  - chọn action: Preview / Run model / Run project
  - nút Run / Cancel
  - trạng thái, log, profile snapshot, engine subdomain/driver pod
```

UI này là test harness/demo cho mentor, không phải dbt Studio. Company UI
sau này chỉ cần dùng API đã ổn định ở các phase trước.

### 9.9. Thứ tự thực hiện khuyến nghị

```text
Phase A → B → C → D → F (runner integration) → E → G
```

Đưa integration test lên trước UI demo giúp phát hiện sớm lỗi identity,
subdomain, profile snapshot hoặc sharing; đây đều là phần khó nhất của task.
