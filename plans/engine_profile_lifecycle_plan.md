# Engine Profile: tên riêng theo user, môi trường Python bền vững và lifecycle

## Mục tiêu

Hoàn thiện Engine Profile để một profile là một tài nguyên compute bền vững
của từng user:

- Alice và Bob đều có thể tạo profile hiển thị là `small`.
- Một profile có identity bất biến, còn resource/environment là các revision
  bất biến theo thời gian.
- Python packages của profile vẫn tồn tại sau khi Spark engine Driver đã bị
  idle shutdown và được tạo lại vào ngày khác.
- User chọn được idle timeout của engine qua profile, trong giới hạn policy
  server đặt ra.
- Cập nhật profile không giết nhầm engine/shared session đang chạy.

Phạm vi là Notebook, SQL Editor và DBT cùng sử dụng một Engine Profile
domain. Browser vẫn chỉ chọn profile; không gửi raw `spark.*`, PVC name,
Kubernetes volume config, hoặc Kyuubi internal config.

## 1. Hiện trạng và nguyên nhân

Hiện schema `notebook_engine_profile` có:

```text
subdomain PRIMARY KEY
owner
spark_config
```

`EngineProfileService` cũng dùng `profileId == subdomain`. Do đó `small` là
khóa toàn cục: sau khi Alice tạo `small`, Bob không thể tạo `small` dù hai
người đã được cách ly bằng `kyuubi.engine.share.level=USER`.

Đây không chỉ là lỗi UI. DBT workspace/job, Notebook runtime và SQL Editor
đều đang lưu/gửi giá trị này như `engineProfileId`, nên phải migration cả
domain thay vì chỉ đổi unique index.

Kyuubi đã có `kyuubi.session.engine.idle.timeout`: engine tự dừng khi không
còn session dùng nó. Notebook runtime idle timeout là lớp khác: nó đóng
runtime/session notebook trước. Phần Spark shutdown đã có đảm bảo Driver pod
exit sau idle shutdown, nên không cần viết thêm pod reaper cho nhu cầu này.

## 2. Quyết định kiến trúc đề xuất

### 2.1. Tách ID, tên hiển thị và Kyuubi subdomain

Mỗi profile có ba định danh khác nhau:

| Trường | Ví dụ | Vai trò |
|---|---|---|
| `id` | UUID `1fa...` | khóa bất biến dùng ở API, Notebook và DBT |
| `name` | `small` | tên user nhìn thấy; unique theo `(owner, normalized_name)` |
| `subdomain` | `ep-1fa...-r2` | khóa runtime do server sinh, unique toàn cục |

Vì `subdomain` do server sinh, Alice và Bob có thể cùng đặt tên `small` mà
không va chạm. Đồng thời client không thể đoán/chọn subdomain để gắn vào
engine của người khác.

`default` giữ là built-in profile đặc biệt như hiện tại, không phải bản ghi
user profile. Nếu sau này cần profile mặc định có resource riêng, sẽ tạo
system profile có ID riêng thay vì cho user chiếm tên `default`.

### 2.2. Revision là đơn vị launch engine

Không sửa resource của engine đang sống. Mỗi thay đổi làm ảnh hưởng launch
(Spark resource, idle timeout, Python environment) tạo một revision mới:

```text
Profile heavy (id=P)
  revision 1  -> subdomain ep-P-r1 -> engine cũ
  revision 2  -> subdomain ep-P-r2 -> engine mới
```

Tên profile và mô tả là cosmetic update, không tạo revision. Còn driver/
executor config, dynamic allocation, idle timeout và environment revision là
launch update, bắt buộc tạo revision mới.

Profile ID vẫn không đổi, vì vậy Notebook/SQL Editor/DBT chỉ lưu ID. Lần mở
session/run mới resolve `currentRevision`; DBT Run tiếp tục lưu full snapshot
của revision tại thời điểm submit như hiện nay.

### 2.3. Không tự động xóa engine cũ

Khi profile được cập nhật:

1. revision mới được publish và session/run tạo sau đó dùng subdomain mới;
2. engine revision cũ chuyển trạng thái **draining**: session hiện có tiếp tục
   chạy, không có session mới được attach thông qua profile hiện tại;
3. engine cũ tự dừng khi tất cả session đóng và idle timeout của chính
   revision cũ hết hạn;
4. UI chỉ có thể hiện nút **Terminate old engine now** khi có xác nhận rõ
   ràng, cảnh báo số notebook/SQL/DBT session active. Đây là action tách biệt,
   không phải side effect của Save.

Đây là lựa chọn an toàn vì cùng engine có thể đang được Notebook, SQL Editor
và DBT của owner dùng chung. Tự kill ngay khi Save sẽ làm mất query/runtime
state và có thể hỏng DBT run.

## 3. Data model và migration

Thay thế bảng profile phẳng bằng các bảng profile, revision và environment.
DDL cần được tạo đồng bộ cho SQLite, MySQL và PostgreSQL.

```text
engine_profile
  id                 UUID/text primary key
  owner              varchar not null
  name               varchar not null
  normalized_name    varchar not null
  current_revision   bigint not null
  created_at, updated_at, version
  UNIQUE(owner, normalized_name)

engine_profile_revision
  profile_id          foreign key/logical reference
  revision            bigint
  subdomain           varchar unique not null
  spark_config        text/json not null
  engine_idle_timeout_ms nullable
  python_environment_revision_id nullable
  state               ACTIVE | DRAINING | RETIRED
  created_at
  PRIMARY KEY(profile_id, revision)

python_environment_revision
  id                  UUID/text primary key
  profile_id          foreign key/logical reference
  revision            bigint
  pvc_name            varchar not null
  relative_path       varchar not null
  state               BUILDING | READY | FAILED | RETIRED
  requirements_lock   text/json
  metadata             text/json
  content_checksum    varchar
  base_image_digest   varchar not null
  created_at, ready_at, retired_at
  UNIQUE(profile_id, revision)

python_environment_change_request
  id                  UUID/text primary key
  profile_id          foreign key/logical reference
  requested_by        varchar not null
  operation           INSTALL | UNINSTALL
  requested_packages  text/json not null
  expected_profile_version bigint not null
  state               PENDING | BUILDING | READY | FAILED
  hot_install_state   NOT_REQUESTED | RUNNING | SUCCEEDED | FAILED
  resulting_environment_revision_id nullable
  error_summary       text nullable
  created_at, updated_at
```

Không xóa record revision khi engine còn có thể được attach/audit. `DbtJobRun`
giữ snapshot đầy đủ; Notebook runtime mới nên ghi thêm `engineProfileId` và
`engineProfileRevision` để màn hình runtime/audit hiển thị chính xác revision
đã launch.

`pvc_name` và `relative_path` đều do server sinh; browser không được gửi hai
field này. `base_image_digest` giúp từ chối mount một environment được build
bằng Python ABI/image không tương thích với Spark engine hiện tại. Package
request là audit/idempotency record và là nguồn để reconciler tiếp tục build
sau server restart; `requirements.lock` chỉ được ghi từ kết quả resolver của
builder, không được browser sửa trực tiếp.

### Migration an toàn

1. Thêm bảng mới, chưa xóa bảng cũ.
2. Với mỗi record cũ, tạo profile UUID, `name = subdomain`, revision 1 và
   giữ nguyên subdomain cũ để engine đang tồn tại không bị orphan.
3. Chuyển reference cũ trong Notebook/DBT từ subdomain sang UUID trong cùng
   transaction/migration. Profile `default` vẫn là reserved sentinel.
4. Backend đọc được cả legacy ID trong một release chuyển tiếp, trả warning/
   metric; UI chỉ ghi UUID.
5. Sau khi migration production và rollback window qua, bỏ legacy lookup và
   bảng cũ theo migration riêng. Không dùng `clean install` hay reset DB để
   "migration" dữ liệu.

Vì hiện Kyuubi test demo có PostgreSQL còn các suite dùng SQLite, cần có test
migration cho cả ba dialect; production không được tiếp tục dùng SQLite local
cho deployment nhiều replica.

## 4. API và service thay đổi

API mới dùng ID bất biến:

```text
POST   /api/v1/engine-profiles
GET    /api/v1/engine-profiles
GET    /api/v1/engine-profiles/{profileId}
PATCH  /api/v1/engine-profiles/{profileId}     (optimistic version)
DELETE /api/v1/engine-profiles/{profileId}
GET    /api/v1/engine-profiles/{profileId}/revisions
POST   /api/v1/engine-profiles/{profileId}/revisions/{n}:terminate
POST   /api/v1/engine-profiles/{profileId}/python-packages:install
POST   /api/v1/engine-profiles/{profileId}/python-packages:uninstall
GET    /api/v1/engine-profiles/{profileId}/environment-builds/{requestId}
```

Create/PATCH body có `name`, resource allowlist và structured
`idleTimeout`. Không nhận `subdomain`, raw Kubernetes volume keys hay secret.
Trong transition có thể giữ `PUT /engine-profiles/{legacy-subdomain}` chỉ để
đọc/update record legacy của chính owner, nhưng UI mới không gọi nó.

`EngineProfileSnapshot` mở rộng thành:

```scala
profileId, profileName, owner, revision, subdomain,
sparkConfig, engineIdleTimeoutMs, pythonEnvironmentRevisionId, capturedAt
```

`snapshotForUse(profileId, caller)` là điểm duy nhất chuyển profile thành
overlay mở session. Notebook, SQL Editor và DBT tiếp tục gọi đúng method này;
không client nào tự tạo subdomain.

Package request chỉ nhận danh sách requirement đã chuẩn hóa, optimistic
profile version và client request ID cho idempotency. Không nhận pip option,
index URL, PVC/path, arbitrary command hoặc environment variable. `%pip` từ
Notebook đi qua cùng service contract nội bộ; endpoint không cho caller đánh
dấu build `READY` hay chọn `resulting_environment_revision_id`.

## 5. Idle timeout theo profile

### Semantics

- Engine Profile có **hai field structured**, không nhận raw `kyuubi.*` từ
  browser:

  | Field API/UI | Kyuubi behavior | Phạm vi |
  |---|---|---|
  | `notebookRuntimeIdleTimeout` | Backend Notebook reaper dùng thay mức mặc định `kyuubi.notebook.runtime.idle.timeout` khi quyết định đóng runtime và Kyuubi session. | Chỉ Notebook/PySpark/SQL cell trong Notebook. SQL Editor và DBT không có Notebook runtime nên field này không áp dụng. |
  | `engineIdleTimeout` | Backend đưa `kyuubi.session.engine.idle.timeout` vào session overlay từ profile snapshot. | Notebook, SQL Editor và DBT. |

- Khi runtime Notebook idle quá `notebookRuntimeIdleTimeout`, reaper đóng
  runtime/session. Khi mọi Kyuubi client session đã đóng, Spark engine bắt đầu
  đếm `engineIdleTimeout` rồi tự dừng. Đây là hai pha nối tiếp, không phải hai
  timer cùng kill một Pod.
- "Idle" của runtime là không có activity Notebook theo rule reaper hiện hữu;
  "idle" của engine nghĩa là Kyuubi engine không còn client session. Cả hai
  không phải thời gian không click chuột hoặc không có cell output.
- Hai timeout thuộc profile revision. Engine revision cũ giữ
  `engineIdleTimeout` cũ; runtime/session mở từ revision mới dùng giá trị mới.
  Backend không được sửa global `KyuubiConf` khi user đổi profile.
- `inherit platform default` là một giá trị hợp lệ cho từng field. Nó khiến
  runtime dùng `kyuubi.notebook.runtime.idle.timeout` hiện có, còn engine bỏ
  overlay để dùng `kyuubi.session.engine.idle.timeout` hiện có.

### Validation và policy

UI cho phép chọn riêng `5m`, `15m`, `30m`, `1h`, `4h`, `24h` hoặc `inherit
platform default` cho mỗi field. API chỉ nhận ISO-8601 duration hoặc format
chuẩn đã chốt (ví dụ `PT15M`); UI có thể hiển thị nhãn ngắn `15m`. Server parse
và enforce floor/ceiling riêng (khuyến nghị demo: 5 phút đến 24 giờ). Giá trị
`0`/vô hạn chỉ dành cho admin policy, không mở đại trà vì gây leak resource.

`EngineProfilePolicy` vẫn chỉ allowlist Spark resource keys. Hai timeout không
trở thành raw key trong `sparkConfig`: `EngineProfileService` validate, lưu vào
revision snapshot và tạo session overlay server-side. `NotebookRuntimeService`
nhận deadline runtime đã resolve; SQL Editor/DBT chỉ nhận engine overlay. Giá
trị profile luôn ưu tiên config/request của caller.

## 6. Python package persistence qua PVC

### 6.1. Đánh giá kiến trúc một PVC theo user/profile

Kiến trúc đề xuất `user -> engine profile -> PVC` là hợp lý và được chọn làm
hướng triển khai. Cần diễn đạt chính xác rằng PVC thuộc **Engine Profile**,
không thuộc một Spark Driver pod cụ thể. Driver cũ có thể bị idle shutdown,
nhưng PVC vẫn tồn tại và được Driver mới của cùng profile mount lại.

```text
User hieu
  ├─ Engine Profile heavy (profileId=P1)
  │    ├─ PVC pyenv-<P1-hash>                 (sống độc lập với Driver)
  │    │    └─ /python-env/environments/
  │    │         ├─ env-1/
  │    │         │    ├─ site-packages/
  │    │         │    ├─ requirements.lock
  │    │         │    ├─ metadata.json
  │    │         │    └─ COMPLETE
  │    │         └─ env-2/ ...
  │    ├─ Spark Driver  ───────── mount env được chọn
  │    ├─ Spark Executors ─────── mount cùng env được chọn
  │    └─ Notebook sessions A/B/C dùng chung engine
  └─ Engine Profile small (profileId=P2)
       └─ PVC pyenv-<P2-hash>
```

Tên vật lý nên là `pyenv-<profile-id-hash>`, không phải
`pyenv-hieu-engine-heavy`. UUID/hash không đổi khi rename profile, tránh ký tự
Kubernetes không hợp lệ, tránh collision và không lộ username trong tên tài
nguyên. Quan hệ owner/name/profile/PVC được lưu trong database và Kubernetes
labels dành cho quản trị.

PVC `kyuubi-runtime` hiện đã được mount vào cả Driver và Executor tại
`/opt/runtime`, nhưng đây là PVC dùng chung toàn deployment cho jar/upload.
Không dùng PVC đó làm nơi cài thư viện riêng của user; nếu dùng sẽ phá vỡ
isolation giữa các Engine Profile.

### 6.2. Vì sao cả Driver và Executor đều cần thấy environment

Trong code hiện tại, `%pip install` chạy trong Python worker trên Driver và
cài bằng `--target` vào `kyuubi-session-pip` dưới working directory. Comment
trong `execute_python.py` xác nhận thư mục này cố ý mất cùng Driver pod. Vì
vậy hành vi hiện tại chưa đáp ứng persistence.

Chỉ mount PVC vào Driver đủ cho các import/chuyển đổi chạy tại Driver, nhưng
không đủ khi function Python được Spark gửi xuống Executor. Spark hỗ trợ cấu
hình PVC riêng cho cả Driver và Executor; backend phải sinh cả hai nhóm
`spark.kubernetes.{driver,executor}.volumes.persistentVolumeClaim.*` từ
profile snapshot. Các key này là server-controlled, không được thêm vào
allowlist raw `sparkConfig` của browser.

Mỗi engine dùng một environment revision cụ thể:

```text
spark.pyspark.driver.python=/python-env/environments/env-2/bin/python
spark.pyspark.python=/python-env/environments/env-2/bin/python
```

Nếu chọn lưu chỉ `site-packages` thay vì full venv, backend phải inject cùng
`PYTHONPATH` vào Driver và Executor. Khuyến nghị lưu/đóng gói full environment
vì package có thể có executable, native library và metadata ngoài
`site-packages`; environment phải được build bằng đúng Python ABI và base
image của Spark engine.

### 6.3. Dual-Path: dùng ngay trên Driver và build snapshot bền vững

Mục tiêu là giữ trải nghiệm `%pip install` quen thuộc nhưng không tuyên bố
package đã sẵn sàng cho distributed execution khi Executor chưa dùng cùng
environment. Hệ thống tách hai kết quả:

- **Hot path:** best-effort cho Python worker của session hiện tại trên
  Driver; không phải environment chính thức của profile.
- **Durable path:** environment revision bất biến, dùng nhất quán cho Driver,
  Executor, Notebook mới và DBT run mới.

#### 6.3.1. Luồng xử lý

```text
User chạy: %pip install seaborn
       │
       ├─ Notebook backend parse/validate package request
       │    └─ persist EngineEnvironmentChangeRequest=PENDING vào JDBC
       │
       ├─ Hot path trong Driver session
       │    ├─ pip --target vào thư mục tạm theo session
       │    ├─ sys.path.insert(0, target)
       │    └─ importlib.invalidate_caches()
       │         → import trên Driver có thể dùng ngay
       │
       └─ Durable path theo Engine Profile
            ├─ debounce/merge các request PENDING theo profileId
            ├─ builder lock profile và build env-N+1.staging trên PVC
            ├─ resolve dependency + smoke test + lockfile + checksum
            ├─ publish env-N+1 với marker COMPLETE
            └─ tạo Profile revision mới tham chiếu env-N+1
                         │
                         ▼
               Engine/runtime mới mount env-N+1 read-only
               Driver và Executor dùng cùng environment
```

Dependency intent phải được ghi vào database **trước** khi chạy hot install.
Nếu Driver/pod/node chết, builder vẫn có thể tiếp tục hoặc retry request.
Không đợi tới idle shutdown mới ghi nhận package vì shutdown hook không phải
delivery mechanism đáng tin cậy.

Để server và Python worker không parse hai ngữ nghĩa khác nhau, MVP chỉ nhận
`%pip install`/`%pip uninstall` như một standalone code cell. Notebook backend
parse và chuẩn hóa request, tạo `requestId`, rồi truyền package list đã
validate cùng `requestId` xuống operation nội bộ; worker không tự đọc hoặc
tin lại raw pip options từ cell. Structured result của worker chỉ cập nhật
`hotInstallState` cho đúng request, còn durable package intent đã nằm trong
JDBC. Cell trộn `%pip` với Python khác bị reject kèm hướng dẫn tách cell.

Backend đánh dấu riêng kết quả của hai nhánh:

```text
hotInstallState:       NOT_REQUESTED | RUNNING | SUCCEEDED | FAILED
environmentBuildState: PENDING | BUILDING | READY | FAILED
```

Hai nhánh có kết quả độc lập. Hot install thất bại không tự làm durable build
thất bại; builder vẫn có thể resolve/build thành công trong môi trường chuẩn.
Ngược lại, hot install thành công nhưng durable build thất bại chỉ có hiệu lực
tạm trong session hiện tại; profile revision hiện hành không thay đổi.

#### 6.3.2. Phạm vi bảo đảm của hot path

Thư mục hot target nằm trên local working directory/`emptyDir` của Driver,
không nằm trong PVC chứa revision chính thức. Path phải chứa Kyuubi session ID,
ví dụ `kyuubi-session-pip/<session-id>`, để nhiều Notebook Session trên cùng
engine không cùng ghi một directory.

Sau khi pip thành công, Python worker thêm target vào `sys.path` như code hiện
tại. Điều này chỉ bảo đảm best-effort cho code chạy trong Driver process.
Không gọi `SparkContext.addPyFile(target)` với nguyên `site-packages`:
`addPyFile` chỉ phù hợp với Python source/`.zip`/`.egg`, không phân phối được
một environment tổng quát chứa wheel, executable hoặc native library.

Hot path chỉ được tải wheel/package từ index và policy do server kiểm soát;
ưu tiên wheel đã pin hash và không build source distribution trong Driver.
Package cần compiler/native system dependency bỏ qua hot path và chỉ được
builder xử lý. Không truyền package-index credential hoặc Kubernetes secret
nhạy cảm vào output/log của Python worker.

UI/output phải nói rõ:

```text
Installed for the current Driver session.
Persistent environment env-N+1 is being built.
Restart the runtime after it is ready to use the package on Spark executors.
```

Không dùng tỷ lệ `95%` hay coi C-extension là trường hợp restart duy nhất.
Pure Python module đã nằm trong `sys.modules`, dependency bị upgrade/downgrade,
Executor Python worker đã khởi động và binary ABI đều có thể cần restart.

Có thể nghiên cứu hot distribution cho pure-Python archive ở phase riêng sau
khi nhận diện package type và test worker reuse. Nó không thuộc MVP và không
thay thế environment revision.

#### 6.3.3. Durable build, publish và DBT

Các package request của cùng profile được serialize bằng lock/CAS. Debounce
trong một khoảng ngắn có thể gộp nhiều request, nhưng mỗi request đã nằm trong
JDBC và có trạng thái riêng. Builder là process duy nhất mount PVC read-write:

1. lấy current READY environment làm base;
2. áp dụng tập dependency mong muốn vào `env-N+1.staging`;
3. kiểm tra dependency conflict, import, Python ABI và quota;
4. ghi `requirements.lock`, `metadata.json`, checksum và `COMPLETE`;
5. atomic publish environment, rồi atomic publish Profile revision mới.

Không update symlink/current marker trước khi database publish thành công.
Reconciler phải xử lý staging/build bị bỏ dở sau restart.

DBT run chỉ snapshot environment revision ở trạng thái `READY`. Nếu một build
mới đang pending, mặc định run dùng current READY revision và response cho UI
biết có environment update chưa publish. Nếu action bắt buộc package mới, UI
phải chờ build READY rồi mới submit; DBT run không tự kích hoạt compaction.

#### 6.3.4. Retention, flattening và base image

- Mỗi environment revision là standalone venv hoặc environment hoàn chỉnh,
  không tạo chuỗi `sys.path` qua nhiều revision. Có thể dùng copy-on-write/
  hard-link optimization nếu StorageClass hỗ trợ, nhưng correctness không phụ
  thuộc optimization này.
- Giữ tối thiểu một số revision gần nhất theo policy, nhưng chỉ xóa revision
  đã hết retention và không còn Profile current, active engine/runtime, DBT
  retry hoặc artifact reference. Metadata/lockfile có thể được giữ lâu hơn
  binary environment để audit.
- Các package phổ biến có thể được bake vào Spark image. Chỉ dùng
  `--system-site-packages` nếu environment builder thực sự tạo venv tương thích
  với Python/base image đó. Danh sách package và tỷ lệ cache-hit cần lấy từ
  telemetry thực tế; không giả định một tỷ lệ cố định trong contract.
- Package build thực thi code bên thứ ba, nên builder không mount service
  account token/credential không cần thiết, chạy non-root, có CPU/memory/time/
  disk quota và chỉ được truy cập package index allowlisted.

### 6.4. Điều kiện hạ tầng

- Một PVC được cấp cho mỗi profile, không phải mỗi lần Driver start và cũng
  không phải mỗi Notebook Session. Các environment revision nằm bên trong
  cùng PVC để package tiếp tục đi theo profile khi resource revision đổi.
- Production multi-node cần StorageClass **ReadWriteMany** nếu builder,
  Driver và Executor có thể nằm trên các node khác nhau. `ReadWriteOnce`
  cho phép nhiều pod dùng volume chỉ khi chúng cùng một node; `local-path`
  của K3s phù hợp demo single-node nhưng không phải production multi-node.
- PVC provisioning, labels owner/profile hash, quota, encryption/access mode,
  retention và cleanup do backend/controller/Helm server-side quản lý.
- Builder mount PVC read-write. Hot target của Driver dùng local
  working-directory/`emptyDir`; engine Driver/Executor chỉ mount environment
  revision đã publish ở chế độ read-only. Dùng init container để kiểm tra
  marker `COMPLETE`, ABI, checksum và permissions trước Spark startup.
- Cần size quota per user/profile, package allow/deny list, private index/CA
  policy và audit manifest. Không bake secret index vào profile snapshot.

Trong MVP K3s single-node có thể dùng StorageClass hiện tại và PVC per profile
sau khi xác nhận Driver, Executor và builder được scheduler đặt trên node giữ
PV. Nếu cluster nhiều node nhưng chưa có RWX, fallback đúng là builder lưu
environment archive bất biến vào object storage; init container của từng pod
tải/unpack archive vào `emptyDir`. Không dùng ConfigMap cho môi trường Python
vì giới hạn kích thước và không phù hợp binary/native package.

### 6.5. Lifecycle PVC

- Idle shutdown chỉ xóa/complete Driver và Executor; không xóa PVC.
- Rename profile không đổi PVC.
- Update CPU/RAM/idle timeout không đổi PVC và có thể reuse environment
  revision hiện tại.
- Delete profile trước hết chuyển profile sang `RETIRED`; PVC chỉ được xóa
  sau khi không còn engine/run/revision cần audit, hết retention và có
  confirmation hoặc policy cleanup rõ ràng.
- Không đặt ownerReference PVC vào Driver pod, vì Kubernetes sẽ garbage
  collect storage khi Driver biến mất và làm hỏng chính yêu cầu persistence.

## 7. Xử lý update/delete profile và environment

| Hành động | Engine/revision hiện có | Hành vi đề xuất |
|---|---|---|
| Đổi tên/mô tả | không ảnh hưởng | update metadata, không revision mới |
| Đổi CPU/RAM/dynamic allocation/idle timeout | có thể đang active | tạo revision + subdomain mới; revision cũ draining |
| Cài/gỡ Python package | có thể đang active | Persist request; hot-install best-effort ở Driver; build env revision mới; Executor dùng sau restart |
| Xóa profile | có engine/run active | đánh dấu retired, chặn tạo run mới; không kill ngay |
| Xóa environment/PVC | còn revision/run reference | chặn thao tác |
| Terminate old revision | owner xác nhận | gọi lifecycle terminate có kiểm tra active sessions/runs; chỉ target old subdomain |

Một profile update không nên mặc định "reuse engine cũ với config mới", vì
Spark không thể đổi Driver/Executor resource runtime. Subdomain theo revision
giải quyết rõ ràng: run mới chắc chắn chạy đúng config mới.

## 8. Các phase triển khai

### Phase P0 — Chốt policy và capability hạ tầng

**Trạng thái: đã khảo sát code; hạ tầng RWX/PVC quota vẫn cần xác nhận trên
cluster đích trước P3.**

- Chốt display-name normalization/case sensitivity, reserved `default`, giới
  hạn timeout, quota PVC và retention.
- Kiểm tra StorageClass K3s và production có RWX hay không; xác định quyền
  tạo PVC/Job, node topology và namespace isolation. `kyuubi-runtime` hiện
  hữu chỉ là baseline mount, không dùng làm user environment.
- Baseline đã xác nhận `%pip` hiện cài vào
  `<driver-working-directory>/kyuubi-session-pip` bằng `pip --target` và mất
  khi Driver chết. Bổ sung smoke test chứng minh package hiện chỉ chắc chắn
  import được tại Driver, rồi test đích phải import được cả trong Spark task
  ở Executor sau khi engine được tạo lại.
- Chốt UX: Save resource tạo revision, hiển thị "new sessions use revision N";
  action terminate old engine luôn explicit.

### Phase P1 — Profile identity + schema migration

**Trạng thái: đang triển khai.** Đã thêm bảng profile/revision thế hệ mới cho
SQLite, MySQL và PostgreSQL; migration idempotent import profile legacy với
`profileId = subdomain` cũ để không làm hỏng notebook/DBT đã lưu. Profile mới
nhận UUID riêng, `UNIQUE(owner, name)` và subdomain server-generated theo
revision. Notebook, SQL Editor, DBT UI và Engine Profile UI đang chuyển sang
lưu/chọn `profileId`; REST đã có `POST`, `PATCH` và endpoint đọc revision.

- DDL/migration SQLite, MySQL, PostgreSQL; JDBC store API theo profile ID và
  revision.
- Refactor `EngineProfileService`, Notebook session, SQL Editor, DBT
  workspace/job/run reference và REST/API/UI.
- Backward compatibility legacy subdomain + migration test dữ liệu thật.

### Phase P2 — Revision và per-profile idle timeout

**Trạng thái: đã triển khai code.** Profile/revision/snapshot và UI management
đã có hai override timeout; Notebook, SQL Editor và DBT nhận đúng server-side
overlay/snapshot. Revision hiện hành được trả về là `ACTIVE`; mọi immutable
revision cũ là `DRAINING`. Việc terminate revision cũ là action explicit,
không bao giờ là side effect của Save profile.

- [x] Publish revision mới atomically với optimistic version; revision trước đó
  được giữ immutable để drain/audit, không bị sửa resource tại chỗ.
- [x] Thêm `notebookRuntimeIdleTimeout` và `engineIdleTimeout` vào profile,
  revision và immutable snapshot; migration giữ hành vi `inherit platform
  default` cho profile cũ.
- [x] Resolve `notebookRuntimeIdleTimeout` vào Notebook runtime reaper, không
  sửa global config; merge `engineIdleTimeout` thành
  `kyuubi.session.engine.idle.timeout` trong session overlay của Notebook,
  SQL Editor và DBT.
- [x] UI Engine Profile management create/edit hiển thị riêng hai timeout
  cùng mô tả phạm vi. DBT/SQL Editor không hiển thị hoặc không hứa áp dụng
  runtime timeout Notebook.
- [x] `GET /engine-profiles/{profileId}/revisions` trả revision cùng state
  `ACTIVE`/`DRAINING` được suy ra từ `currentRevision`, để state không thể
  lệch với profile hiện hành.
- [x] `POST /engine-profiles/{profileId}/revisions/{n}:terminate` bắt buộc
  `confirm=true`, chỉ nhận revision cũ, target chính xác engine key
  `USER/owner/subdomain-revision/SPARK_SQL`, và từ chối khi còn DBT run active
  snapshot revision đó. `PATCH` profile không gọi endpoint này.
- Test engine cũ không bị kill khi update; engine mới tạo bằng subdomain mới;
  old engine tự dọn đúng `engineIdleTimeout`; Notebook runtime tự đóng đúng
  `notebookRuntimeIdleTimeout` rồi mới cho engine đi vào idle.

### Phase P3 — Persistent Python environment

**Trạng thái: đã triển khai demo slice.** Một Python environment bền vững được
build theo profile vào PVC; request `%pip` được persist, build publish theo
revision và package có thể dùng ngay trong Python workers của cùng Driver đang
sống. Environment `READY` chỉ được promote cho engine mới sau khi discovery
xác nhận Driver cũ của profile đã dừng. Demo local dùng `local-path`; production
vẫn phải xác nhận policy RWX/artifact store, quota và retention.

- Một PVC cho mỗi profile ID; environment registry/revision, package manifest,
  lock theo profile và environment build Job là writer duy nhất.
- Notebook backend persist package request trước dispatch; handler `%pip` hot
  install vào session target tạm trên Driver, không đưa raw `site-packages`
  qua `addPyFile`.
- Debounce request theo profile, build/publish `env-N+1` và yêu cầu restart để
  Driver/Executor dùng nhất quán; DBT chỉ snapshot revision `READY`.
- Áp dụng reference-aware retention và standalone environment revision để
  bảo vệ inode/dung lượng mà không xóa artifact còn được dùng.
- PVC lifecycle controller + Spark Driver/Executor read-only mount injection
  solely from snapshot; security/RBAC/quotas.
- Profile revision references environment revision; install/uninstall creates
  new revision only after build success.

### Phase P4 — UI và docs

**Trạng thái: đã triển khai P4-lite.**

- [x] Profile list hiển thị `name`, revision current, resource và persistent
  environment; UI không dùng raw subdomain như input user.
- [x] Lifecycle dialog đọc immutable engine revisions cùng Python environment
  states/package manifest, giải thích rõ Driver sống/restart runtime và
  persistent PVC environment.
- [x] Terminate old revision là thao tác explicit, có confirm dialog và chỉ
  áp dụng cho revision `DRAINING`.
- [x] Thêm runbook tại
  [ENGINE_PROFILE_PYTHON_ENVIRONMENT.md](../docs/notebook/ENGINE_PROFILE_PYTHON_ENVIRONMENT.md)
  cho `%pip`, quan sát builder, recovery và giới hạn StorageClass demo.
- [ ] Deferred: package installer/history management đầy đủ trên browser,
  retention UI và tự dọn PVC theo policy production.

### Phase P5 — Test, rollout và cleanup

- Unit/integration/e2e theo checklist bên dưới.
- Bật migration canary, đo metrics, rollback app code vẫn đọc schema mới;
  không rollback bằng xóa database/PVC.
- Chỉ dọn legacy table/PVC revision sau retention và backup xác nhận.

## 9. Acceptance tests

1. Alice và Bob cùng tạo tên `small`; cả hai list thấy profile của mình,
   profile ID/subdomain runtime khác nhau; Bob không get/use/update Alice.
2. Notebook, SQL Editor và DBT của Alice dùng một profile revision thì reuse
   một engine; Bob không reuse engine đó.
3. Profile `heavy` install package, engine idle shutdown/Driver Completed,
   khởi động lại sau đó và import package thành công ở driver **và executor**.
4. Sau `%pip install`, Driver session có thể import hot package; output nói rõ
   Executor chưa được bảo đảm. Một Spark task dùng package chỉ được coi là
   supported sau khi environment build `READY` và runtime/engine restart.
5. Package request đã persist vẫn được reconciler xử lý sau khi Kyuubi hoặc
   Driver restart; không phụ thuộc idle shutdown hook.
6. Hai install cùng profile được serialized/debounce; build lỗi không đổi
   current environment/profile revision và staging được cleanup an toàn.
7. DBT submit khi có environment build pending dùng đúng current READY
   revision hoặc bị giữ theo explicit wait policy; không snapshot staging.
8. Profile có `notebookRuntimeIdleTimeout=PT5M` đóng runtime/session Notebook
   sau 5 phút inactivity; profile có `engineIdleTimeout=PT1H` chỉ dừng engine
   một giờ sau khi session cuối cùng đóng. Hai giá trị không bị global default
   ngắn hơn ghi đè và không có active session bị kill.
9. Update resource `heavy` tạo `r2`; run mới dùng Driver resource/subdomain
   `r2`, query trên `r1` tiếp tục được; r1 biến mất chỉ sau drain/idle hoặc
   explicit terminate.
10. DBT run snapshot cũ sau profile update vẫn audit được đúng revision/env/
   idle timeout đã dùng.
11. Delete profile/PVC bị từ chối khi có active session/run hoặc revision đang
   còn retention; owner/admin audit actions đầy đủ.

## 10. Các điểm cần xác nhận trước khi code

1. Storage production có RWX hay platform muốn dùng object storage/artifact
   store thay PVC? Đây là điều kiện quyết định Phase P3.
2. Có cho user install package từ UI không, hay chỉ chọn environment template
   do admin publish? Phương án template giảm đáng kể rủi ro supply-chain.
3. Giới hạn idle timeout và quota mặc định công ty muốn cấp là bao nhiêu?
4. Khi update profile có bắt buộc user bấm "apply/terminate old engine" hay
   đồng ý policy draining mặc định như trên?

Các câu trả lời này không chặn P1/P2, nhưng bắt buộc phải chốt trước P3 PVC
và package installation production.
