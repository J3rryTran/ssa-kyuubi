# Phase B — Engine Profile domain dùng chung cho Notebook, SQL Editor và DBT

## Trạng thái và đầu vào đã chốt

**Code Phase B đã được triển khai và kiểm tra.** Phần DBT Workspace/Job, REST
endpoint DBT và runner vẫn nằm ngoài phạm vi Phase B.

Đã chạy bằng Java 17:

```bash
mvn -pl :kyuubi-server_2.12 test -DskipTests=false \
  -DwildcardSuites=org.apache.kyuubi.server.notebook.EngineProfileServiceSuite
```

Kết quả: 5 tests pass. Root POM đã map `-DwildcardSuites` vào
`scalatest-maven-plugin`, nên lệnh targeted test được ghi trong tài liệu nay
thực sự giới hạn discovery theo suite yêu cầu.

Phase B bắt đầu sau Phase A. Các quyết định đầu vào:

- Kyuubi giữ `kyuubi.engine.share.level=USER`.
- Engine Profile thuộc về user tạo profile; user khác không được dùng engine
  profile đó.
- Notebook, SQL Editor và DBT của cùng user/cùng profile phải reuse cùng
  Spark engine nếu engine còn sống.
- DBT runner dùng **Kyuubi proxy-user** để Kyuubi session user là caller.
- DBT runner cụ thể (Kubernetes Job hay platform khác) chưa implement trong
  Phase B; Phase B chỉ chuẩn bị domain profile để runner dùng sau này.

## 1. Mục tiêu Phase B

Tách phần Engine Profile đang nằm trong notebook subsystem thành domain service
có thể được gọi bởi ba client/runtime:

```text
NotebookSessionService ─┐
SQL Editor backend      ├─→ EngineProfileService
DBT Job backend         ┘        │
                                  ▼
                       EngineProfile snapshot hợp lệ/được authorize
```

Sau Phase B, Notebook hiện tại không đổi hành vi. DBT/SQL Editor chưa cần có
UI hoặc runner, nhưng đã có service contract để resolve profile theo caller
một cách an toàn.

## 2. Hiện trạng cần refactor

Hiện code có:

```text
NotebookEngineProfileService
  → NotebookStore.notebook_engine_profile
  → NotebookSessionService.ensureFor(...).resolveSparkConfig(subdomain)
```

Vấn đề khi dùng cho DBT:

1. Tên service và dependency là notebook-specific.
2. `resolveSparkConfig(subdomain)` không mang principal, nên DBT backend không
   thể chứng minh caller có quyền dùng profile.
3. `get(subdomain)` hiện có thể đọc profile theo subdomain mà không kiểm tra
   owner; điều này không khớp policy mới “chỉ owner dùng profile”.
4. Config validator hiện cho phép mọi key bắt đầu bằng `spark.` hoặc
   `kyuubi.`; policy engine profile cần allowlist chặt hơn trước khi DBT runner
   dùng config để launch engine.

## 3. Boundary refactor được đề xuất

### 3.1. Service mới

Đổi `NotebookEngineProfileService` thành `EngineProfileService` tại package
domain-neutral, ví dụ:

```text
org.apache.kyuubi.server.engineprofile.EngineProfileService
```

Không để DBT service đọc `NotebookStore` hay JDBC trực tiếp. Trong Phase B,
store/schema hiện hữu có thể giữ nguyên để migration nhỏ nhất.

API service dự kiến:

```scala
trait EngineProfileService {
  def list(principal: EngineProfilePrincipal): Seq[EngineProfile]
  def getOwned(profileId: String, principal: EngineProfilePrincipal): EngineProfile
  def upsert(profileId: String, principal: EngineProfilePrincipal,
             request: UpsertEngineProfileRequest): EngineProfile
  def delete(profileId: String, principal: EngineProfilePrincipal): Unit

  /** Dùng cho Notebook, SQL Editor và DBT trước khi tạo Kyuubi session. */
  def snapshotForUse(
      profileId: String,
      principal: EngineProfilePrincipal): EngineProfileSnapshot
}
```

`EngineProfilePrincipal` có thể bắt đầu từ `NotebookPrincipal(user, admin)`
để giảm thay đổi Phase B, rồi tách type chung ở phase sau nếu DBT domain được
đưa ra ngoài notebook package.

### 3.2. Profile ID ở Phase B

Schema hiện dùng `subdomain` là primary key. Để không phá API/UI Notebook đã
có, **Phase B dùng `profileId == subdomain`**. Khi DBT Workspace/Job persistence
được thêm ở Phase C, `selectedEngineProfileId` tạm thời lưu giá trị này.

Việc thêm UUID `profile_id`, version và hỗ trợ hai user trùng display name là
migration tách riêng, không trộn vào Phase B. Nếu company yêu cầu profile name
trùng giữa user ngay từ đầu, phải nâng migration đó lên Phase B trước khi code.

### 3.3. Ownership và quyền USE

Policy Phase B:

| Caller | list/get/use/update/delete profile của owner | profile user khác |
|---|---|---|
| Owner | Cho phép | Không áp dụng |
| Admin | Cho phép | Cho phép theo policy admin |
| User khác | Từ chối | Từ chối `NOT_FOUND` hoặc `ACCESS_DENIED` theo API policy |

Khuyến nghị trả `ACCESS_DENIED` trong service/audit, còn REST public có thể
trả `NOT_FOUND` để không tiết lộ profile tồn tại. Quan trọng là
`snapshotForUse` bắt buộc nhận principal và không có overload resolve config
chỉ bằng subdomain cho DBT.

Notebook shared cho user khác không tự cấp quyền dùng engine profile của owner.
Người đó cần chọn profile của chính mình; điều này khớp yêu cầu engine chỉ
owner được dùng.

## 4. Policy config Engine Profile

Tách validation thành `EngineProfilePolicy`, dùng cùng cho UI Notebook và DBT.

### 4.1. Key cần cho MVP

```text
spark.driver.memory
spark.driver.cores
spark.executor.memory
spark.executor.cores
spark.executor.instances
spark.dynamicAllocation.enabled
spark.dynamicAllocation.minExecutors
spark.dynamicAllocation.initialExecutors
spark.dynamicAllocation.maxExecutors
```

Có thể bổ sung một allowlist SQL/Spark cần thiết sau, nhưng không cho phép
blanket `spark.*` hoặc `kyuubi.*` từ profile. Đặc biệt không đưa vào profile
các key thay image, jar/classpath, Kubernetes service account, secret reference,
proxy-user, authentication, file upload path hoặc namespace.

### 4.2. Validation giá trị

- Memory dùng Spark memory syntax hợp lệ, ví dụ `512m`, `2g`.
- Cores và instances là integer dương trong giới hạn server policy.
- Dynamic allocation có validation nhất quán giữa min/initial/max.
- Subdomain phải là Kubernetes DNS label hợp lệ và không rỗng.
- Tất cả config được canonicalize về `String` trước khi snapshot.

### 4.3. Precedence khi tạo engine

```text
server Spark defaults
  < Engine Profile config
  < server-controlled per-run policy (nếu có)
```

Browser, Notebook cell và DBT action không được override resource profile qua
raw `spark.*`. Nếu cần exception, admin tạo profile khác thay vì truyền config
từng run.

## 5. Tích hợp lại Notebook và chuẩn bị SQL Editor/DBT

### 5.1. Notebook regression path

`NotebookSessionService.ensureFor` đổi dependency từ
`NotebookEngineProfileService` sang `EngineProfileService`:

```text
notebook.runtimeProfile
  → snapshotForUse(profileId, notebook owner/session principal)
  → snapshot.subdomain + snapshot.config
  → Kyuubi session configuration
```

Không đổi thứ tự tạo session/runtime và không đổi REST payload hiện hữu trong
Phase B. Xóa các warning debug tạm `[NOTEBOOK-ENGINE-DEBUG]` nếu không còn cần
cho test regression.

### 5.2. SQL Editor contract

Đã thêm `POST /api/v1/editor-sessions`, chỉ nhận body:

```json
{ "engineProfileId": "<profile-id>" }
```

SQL Editor backend gọi `snapshotForUse(profileId, caller)` và inject:

```text
kyuubi.engine.share.level.subdomain = snapshot.subdomain
<snapshot.config>
```

UI không còn gửi `spark.*`, `kyuubi.*` hoặc subdomain trong request mở session.
Điều này đưa SQL Editor vào cùng engine key với Notebook/DBT của user.

### 5.3. DBT contract sau Phase B

DBT service ở Phase C/D gọi:

```scala
val snapshot = engineProfiles.snapshotForUse(selectedProfileId, caller)
```

Rồi runner chuyển snapshot thành `server_side_parameters` trong `profiles.yml`.
Proxy-user không bao giờ là config thuộc Engine Profile. Runner tự thêm:

```yaml
hive.server2.proxy.user: <authenticated UI caller>
```

Kyuubi xác thực real user của runner, sau đó kiểm tra proxy policy và dùng
caller làm session user. `hive.server2.proxy.user` được chọn cho DBT Thrift
Binary vì Kyuubi frontend xử lý key tương thích HiveServer2; phải có integration
test trước production. `kyuubi.session.proxy.user` không dùng làm contract
Thrift của Phase B vì doc config hiện mô tả nó cho REST.

## 6. Proxy-user precondition ngoài code Phase B

Trước khi DBT runner thật chạy, platform team phải cấu hình Hadoop proxy-user
rules cho real runner identity. Ý nghĩa logic:

```text
hadoop.proxyuser.<runner-real-user>.hosts = <runner-source-hosts/CIDRs theo policy>
hadoop.proxyuser.<runner-real-user>.groups = <nhóm user được phép impersonate>
```

Tên key/value chính xác phụ thuộc Hadoop config distribution và security policy
của company. Không dùng wildcard rộng trong production. Kyuubi gọi
`ProxyUsers.authorize(...)`; nếu rule không cho phép, OpenSession phải fail
`403`/authentication error thay vì tạo engine của runner.

Test tối thiểu ở phase sau:

1. Runner real user được phép proxy Alice → Kyuubi session user/engine path là
   Alice.
2. Runner yêu cầu proxy Bob khi policy không cho phép → request bị từ chối.
3. Alice Notebook + Alice DBT cùng subdomain → một Spark driver.

## 7. Thứ tự thay đổi code Phase B

1. Thêm domain-neutral principal/snapshot/policy types và unit tests trước.
2. Đổi tên/extract service, cập nhật `NotebookManager` dependency injection.
3. Đổi `NotebookSessionService` sang snapshot API; giữ REST endpoint hiện hữu.
4. Siết ownership/use check và config allowlist.
5. Cập nhật JDBC/FileSystem store adapters nếu method/type thay đổi.
6. Bổ sung test suite EngineProfileService và Notebook regression suite.
7. Chạy formatter, targeted Scala test và UI type-check nếu REST view thay đổi.

Không tạo DBT Workspace/Job table, REST resource hay DBT runner ở Phase B.

## 8. Test matrix và acceptance criteria

| Test | Kết quả yêu cầu |
|---|---|
| Owner list/get/update/delete profile | Thành công |
| User khác snapshot/use profile | Bị từ chối |
| Admin thao tác profile user khác | Theo admin policy |
| Config memory/cores hợp lệ | Lưu và snapshot đúng |
| Config key nguy hiểm/ngoài allowlist | Bị từ chối |
| Notebook owner chọn profile | Subdomain/config được merge như hiện tại |
| Notebook user khác mở shared notebook | Không dùng profile owner tự động |
| Snapshot immutable | Map config trả về không bị caller mutate state service |
| Existing UI Engine Profile CRUD | Không regression |

Phase B hoàn thành khi Engine Profile trở thành domain service dùng chung, có
ownership/use validation rõ ràng, và Notebook hiện tại vẫn build/test đúng.

## 9. Ngoài phạm vi Phase B

- DBT workspace/job/run schema và API.
- DBT runner image/Kubernetes Job.
- Cấu hình proxy-user thật trên môi trường production.
- UI DBT workspace.
- Migration `profile_id` UUID/version, trừ khi company yêu cầu profile name
  trùng giữa user ngay từ đầu.
