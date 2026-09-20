# Kế hoạch Thiết kế & Triển khai: Tùy chỉnh Cấu hình Engine & Chọn Subdomain Engine cho Notebook

Tài liệu này tổng hợp toàn bộ phân tích kiến trúc, luồng hoạt động từng hàm code, cơ chế Zookeeper Discovery, tính cách ly đa người dùng, thiết kế giao diện UX tách biệt Quản lý Engine, xử lý lỗi Khóa Lạc quan Optimistic Locking, danh sách các file mã nguồn thực tế cần chỉnh sửa và phân chia các task nhỏ để triển khai tính năng **Tùy chỉnh Cấu hình Engine & Chọn Subdomain Engine cho từng Notebook** trên hệ thống Kyuubi.

---

## 📌 1. Bối cảnh & Mục tiêu (Context & Objectives)

### Bối cảnh hiện tại:
Hiện tại, khi một người dùng đăng nhập vào hệ thống và tạo/mở Notebook, Kyuubi Server sẽ tự động gán phiên làm việc của người dùng đó vào một Pod Spark Driver mặc định (`default`). Người dùng chưa có khả năng:
- Tự tùy chỉnh thông số RAM, CPU hoặc Spark Configs cho Engine của mình.
- Khởi tạo nhiều Engine song song cho các mục đích công việc khác nhau (ví dụ: Notebook nhẹ chạy ETL nhanh, Notebook nặng chạy Data Science/ML).
- Lựa chọn linh hoạt Engine nào sẽ phục vụ cho Notebook nào.

### Mục tiêu triển khai:
1. **Quản lý Đa Engine theo Người dùng (Multi-Engine Subdomain):** Cho phép một người dùng tự khởi tạo và sở hữu song song nhiều Engine Pods độc lập trên Kubernetes với tên nhận diện (`subdomain`) và thông số cấu hình riêng biệt.
2. **Phân tách Trách nhiệm Giao diện (Separation of UX Concerns):**
   - **Quản lý Engine độc lập ở bên ngoài:** Cung cấp giao diện Quản lý Engine Preset (`EngineConfigDialog.vue`) giúp người dùng định nghĩa và lưu các cấu hình Engine độc lập.
   - **Lựa chọn & Đổi Engine linh hoạt trong Notebook:** Cho phép người dùng gán hoặc đổi mẫu Engine (`runtimeProfile`) ngay trên thanh Header Bar khi đang soạn thảo Notebook.
3. **Kiểm soát Input Chặt chẽ & Xử lý Khóa Lạc quan:** Áp dụng Form Validation nghiêm ngặt (K8s DNS label pattern, Memory unit `2g`/`512m`, Cores range) và xử lý triệt để lỗi Optimistic Locking khi đổi Engine.
4. **Chia sẻ Thư viện & Hỗ trợ `%pip list` (`sys.path` & `magic_pip` patch):** Đảm bảo các Notebook chạy chung một Pod Engine tự động nhận diện thư viện cài qua `%pip install` và hỗ trợ người dùng kiểm tra danh sách thư viện bằng câu lệnh `%pip list`.

---

## 🔍 2. Khảo sát Kiến trúc Kyuubi Core & Cơ chế Zookeeper Discovery

### 2.1. Tính Khả thi: **HOÀN TOÀN KHẢ THI 100% (High Feasibility)**

Hệ thống **Apache Kyuubi Core (Backend)** đã tích hợp sẵn tính năng gọi là **`Subdomain Engine`**. 

Biến cấu hình cốt lõi trong `KyuubiConf.scala`:
👉 **`kyuubi.engine.share.level.subdomain`**

### 2.2. Cơ chế Zookeeper Discovery & Đường dẫn Naming Standard
Kyuubi Server quản lý vị trí các Pod Engine đang chạy trên cụm theo đường dẫn Zookeeper chuẩn dạng cây:
```text
/kyuubi_1.10.3_USER_SPARK_SQL / <username> / <subdomain>
```

- **Ví dụ đối với tài khoản `anonymous`:**
  - Engine mặc định: `/kyuubi_1.10.3_USER_SPARK_SQL/anonymous/default`
  - Engine 8GB RAM bạn vừa đặt tên `my-spark-8g`:  
    `/kyuubi_1.10.3_USER_SPARK_SQL/anonymous/my-spark-8g`

### 2.3. Giải đáp về Tính Cách ly Đa Người dùng (Multi-User Isolation)

- **Trường hợp 1: CÙNG User + CÙNG Subdomain (Dùng chung Engine):**  
  Notebook A và Notebook B của cùng user `Alice` đều chọn `my-spark-8g` -> Cả 2 dùng chung 1 Pod Engine `my-spark-8g`. Giúp tiết kiệm RAM/CPU K8s và tự động chia sẻ các thư viện `%pip install`.
- **Trường hợp 2: KHÁC User + CÙNG Subdomain (Cách ly 100%):**  
  User `Alice` chọn `my-spark-8g` và User `Bob` cũng chọn `my-spark-8g`. Do Kyuubi phân tách Zookeeper Path theo `<username>` (`/alice/my-spark-8g` vs `/bob/my-spark-8g`), hệ thống sinh ra **2 Pod K8s hoàn toàn riêng biệt**, bảo mật và bộ nhớ RAM/CPU cách ly tuyệt đối 100%!

---

## 🎨 3. Quy trình Người dùng Tinh gọn & Xử lý Lỗi Khóa Lạc quan (Optimistic Locking Fix)

### 3.1. Thiết kế Luồng Giao diện Phân tách (Decoupled UX Flow):
1. **Quản lý Engine Profile bên ngoài:** Người dùng có nút **⚙️ Engine Settings** ở Sidebar / Header để mở `EngineConfigDialog.vue` tạo mới hoặc chỉnh sửa các mẫu Engine (`default`, `engine-small`, `engine-heavy`).
2. **Form Tạo Notebook tinh gọn (`NotebookSidebar.vue`):** Chỉ gồm Tên, Ngôn ngữ và ô Dropdown chọn mẫu Engine sẵn có.
3. **Đổi Engine linh hoạt trên Header Bar (`index.vue`):** Mở Notebook nào cũng có Dropdown `el-select` chọn Engine. Khi người dùng đổi sang Engine khác -> Gọi API cập nhật `runtimeProfile` và khởi động lại Session với Engine mới.

### 3.2. Xử lý Lỗi Khóa Lạc quan khi Đổi Engine (`the object was modified since it was read`):
- **Bối cảnh lỗi:** Khi tự động lưu cell, `notebook.version` trong DB bị tăng lên. Việc gửi `version` cũ từ Frontend sẽ kích hoạt ngoại lệ `versionConflict`.
- **Giải pháp:**
  1. Trong `onEngineProfileChange` (`index.vue`), bỏ tham số `version` bắt buộc trong payload PATCH để Backend tự dùng `expectedVersionOf(null, current)` từ DB.
  2. Gán `notebook.value = updated` ngay sau khi gọi `updateNotebook` để đồng bộ lại model và `version` mới nhất trong Vue reactive state.

### 3.3. Cải tiến Kiến trúc: Khởi tạo Phiên Trì hoãn & Gắn Engine Theo Yêu cầu (Lazy Session & On-demand Compute Binding):
- **Bối cảnh & Vấn đề:** Khi tạo Notebook ban đầu, hệ thống tạo ngầm một `NotebookSession` nối với `default`. Khi người dùng đổi sang Engine khác (`light-engine`/`heavy-engine`), phiên `default` cũ chạy ngầm dễ làm dính cache `lazy val engine` trong Kyuubi Core.
- **Giải pháp Tối ưu (Đã Thống nhất):**
  1. **Tạo Notebook nhẹ nhàng:** Bỏ chọn Engine ở Dialog Tạo Notebook mới. Không sinh Session Compute ngầm khi vừa tạo document.
  2. **Mở Notebook & Chọn Engine trên Header:** Người dùng vào Notebook sẽ thấy Trình chọn Engine trên Header Bar. Chưa kích hoạt Session Compute nào dưới Cluster cho tới khi có thao tác thực thi.
  3. **Khởi tạo Compute On-demand khi Bấm Run lần đầu:** Khi người dùng bấm **Run cell**, hệ thống mới chính thức tạo `NotebookSession` đầu tiên mang đúng Subdomain của Engine được chọn. Đảm bảo 100% không bao giờ sinh ra Pod `default` thừa hoặc bị dính cache phiên cũ!

### 3.4. Tương thích Tương thích Song song 2 Key Subdomain (`subdomain` & `sub.domain`) & Lưu trữ LocalStorage:
- **Bối cảnh & Vấn đề:** Qua các phiên bản Kyuubi (từ 1.2.0 đến 1.4.0+), key Subdomain đổi tên từ `kyuubi.engine.share.level.sub.domain` sang `kyuubi.engine.share.level.subdomain`. Một số lớp Kyuubi Core vẫn tra cứu theo key cũ dẫn tới rơi về `default`.
- **Giải pháp:** Cập nhật `PySparkRuntimeAdapter.scala`, `KyuubiSqlRuntimeAdapter.scala` và `NotebookSessionService.scala` truyền đồng thời cả 2 key:
  - `kyuubi.engine.share.level.subdomain` -> `subdomain`
  - `kyuubi.engine.share.level.sub.domain` -> `subdomain`
- **Ghi nhớ Presets ở Frontend:** Sử dụng `localStorage` (`'kyuubi_notebook_engine_profiles'`) lưu trữ các mẫu Engine do người dùng tự tạo để tự động khôi phục lên Dropdown sau mỗi lần F5/reload trang.

---

## 🌐 4. Sơ đồ Luồng Chạy Chi tiết từng Hàm Code (Code Call Graph)

```text
[Web UI] (Click Run Cell)
   │
   ▼ POST /api/v1/notebooks/executions/submit
[NotebookExecutionsResource.scala] -> submit()
   │
   ▼
[NotebookExecutionService.scala] -> submit() -> startExecution()
   │
   ▼
[NotebookSessionService.scala] -> ensureFor()  <-- (Trích xuất runtimeProfile & chèn Subdomain overlay)
   │
   ▼
[PySparkRuntimeAdapter.scala] -> startRuntime() <-- (Đóng gói kyuubi.engine.share.level.subdomain)
   │
   ▼
[BackendService / SessionManager.scala] -> openSession()
   │
   ▼
[EngineRef.scala] -> getOrCreate()  <-- (Tra cứu Zookeeper & Gọi K8s API)
   │
   ├─► (Nếu đã chạy): Nối TCP tới Pod IP sẵn có trên Zookeeper
   └─► (Nếu chưa có): Gọi K8s API sinh Pod `kyuubi-user-spark-sql-anonymous-my-spark-8g-...-driver`
```

---

## 🛠️ 5. Danh sách các File Mã nguồn Cần Chỉnh sửa & Trạng thái (Progress)

---

### 5.1. Backend Service & DTO Layer (`kyuubi-server/.../notebook`) [✅ HOÀN THÀNH 100%]

- [`NotebookDocumentService.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/service/NotebookDocumentService.scala) [✅ Đã có sẵn]
- [`NotebookSessionService.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/service/NotebookSessionService.scala) [✅ ĐÃ CẬP NHẬT]
- [`PySparkRuntimeAdapter.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/runtime/PySparkRuntimeAdapter.scala) [✅ ĐÃ CẬP NHẬT]
- [`KyuubiSqlRuntimeAdapter.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/runtime/KyuubiSqlRuntimeAdapter.scala) [✅ ĐÃ CẬP NHẬT]

---

### 5.2. Python Engine Worker Patch (`externals/kyuubi-spark-sql-engine`) [✅ HOÀN THÀNH 100%]

- [`execute_python.py`](file:///root/kyuubi-custom/externals/kyuubi-spark-sql-engine/src/main/resources/python/execute_python.py) [✅ ĐÃ CẬP NHẬT]

---

### 5.3. Web UI Frontend (`kyuubi-server/web-ui`) [✅ HOÀN THÀNH 100%]

Phát triển giao diện người dùng trên Vue 3 + Element Plus.

- [`types.ts`](file:///root/kyuubi-custom/kyuubi-server/web-ui/src/api/notebook/types.ts) [✅ ĐÃ CẬP NHẬT]
- [`index.ts`](file:///root/kyuubi-custom/kyuubi-server/web-ui/src/api/notebook/index.ts) [✅ ĐÃ CẬP NHẬT]
- [`EngineConfigDialog.vue`](file:///root/kyuubi-custom/kyuubi-server/web-ui/src/views/notebook/components/EngineConfigDialog.vue) [✅ ĐÃ TẠO MỚI]
- [`NotebookSidebar.vue`](file:///root/kyuubi-custom/kyuubi-server/web-ui/src/views/notebook/components/NotebookSidebar.vue) [✅ ĐÃ CẬP NHẬT]
- [`index.vue`](file:///root/kyuubi-custom/kyuubi-server/web-ui/src/views/notebook/index.vue) [✅ ĐÃ CẬP NHẬT]
  - **Nội dung sửa:** Bổ sung Dropdown chọn Engine & Nút Settings trên Header Bar. Đổi Engine tự động lưu DB & restart session. Xử lý triệt để lỗi Optimistic Locking.

---

## 📋 6. Phân chia Task Chi tiết (Task Breakdown)

### **Giai đoạn 1: Backend Routing & Session Integration [✅ HOÀN THÀNH 100%]**
- [x] **Task 1.1:** Cập nhật `PySparkRuntimeAdapter.scala` và `KyuubiSqlRuntimeAdapter.scala` chèn `kyuubi.engine.share.level.subdomain` từ `configuration` vào `openSession`.
- [x] **Task 1.2:** Cập nhật `NotebookSessionService.scala` truyền `session.runtimeProfile` làm Subdomain overlay vào `configuration` khi mở Session/Runtime.

### **Giai đoạn 2: Python Engine Worker Patch (`execute_python.py`) [✅ HOÀN THÀNH 100%]**
- [x] **Task 2.1:** Áp dụng patch tự nạp `kyuubi-session-pip` vào `sys.path` khi Python Worker bật lên.
- [x] **Task 2.2:** Cập nhật hàm `magic_pip` trong `execute_python.py` để hỗ trợ lệnh `%pip list`.

### **Giai đoạn 3: Frontend Web UI Development [✅ HOÀN THÀNH 100%]**
- [x] **Task 3.1:** Cập nhật TypeScript interfaces trong `api/notebook/types.ts` và API helpers trong `api/notebook/index.ts`.
- [x] **Task 3.2:** Tạo Form Dialog Cấu hình Engine tùy chỉnh độc lập (`EngineConfigDialog.vue`) kèm quy tắc Validation kiểm soát input chặt chẽ.
- [x] **Task 3.3:** Tích hợp nút ⚙️ Engine Settings trên Sidebar & ô chọn Engine vào Dialog tạo Notebook tại `NotebookSidebar.vue`.
- [x] **Task 3.4:** Thêm Dropdown chọn Engine và nút ⚙️ Engine Settings trên Header Bar tại `notebook/index.vue`.
- [x] **Task 3.5:** Xử lý sự kiện đổi Engine trên UI -> Gọi API update `runtimeProfile` & restart session (Đã fix lỗi Optimistic Locking).

### **Giai đoạn 4: Integration Testing & Verification [⏹ BẮT ĐẦU THỰC HIỆN]**
- [ ] **Task 4.1:** Tạo Notebook A (runtimeProfile: `default`) và Notebook B (runtimeProfile: `heavy-engine`).
- [ ] **Task 4.2:** Kiểm tra `kubectl get pods -n kyuubi` xác nhận 2 Pod Spark Driver độc lập được sinh ra.
- [ ] **Task 4.3:** Thử nghiệm `%pip install` và `%pip list` ở Notebook A và xác nhận các Notebook cùng Subdomain tự động dùng chung thư viện.

---

## 🏗️ 7. Chi tiết các Bước Sửa đổi Code theo Kiến trúc Mới (Lazy Session & On-Demand Compute)

### 7.1. Chỉnh sửa Web UI Frontend (`kyuubi-server/web-ui`):

1. **`NotebookSidebar.vue` (Dialog Tạo Notebook mới):**
   - Loại bỏ ô chọn Engine trong Form "Tạo Notebook mới".
   - Khi submit form tạo Notebook, chỉ truyền `name` và `language`, mặc định `runtimeProfile = null`.

2. **`index.vue` (Trình soạn thảo Notebook & Header Bar):**
   - **Tách biệt Mở Notebook và Mở Session:** Khi người dùng mở trang Notebook, hệ thống chỉ tải dữ liệu Notebook (`getNotebook`), **không gọi `ensureSession()` ngầm**.
   - **Đổi Engine Profile linh hoạt (`onEngineProfileChange`):**
     - Cập nhật `notebook.value.runtimeProfile = newProfile`.
     - Gọi `api.updateNotebook(id, { runtimeProfile: newProfile })` để lưu CSDL.
     - Nếu đang có Session chạy ngầm, gọi `stopSession()` để ngắt compute cũ.
   - **Khởi tạo Compute Trì hoãn (Lazy Run):**
     - Khi người dùng bấm **Run cell**, kiểm tra nếu chưa có Session active -> Gọi `ensureSession()`.
     - `ensureSession()` sẽ tạo Session mới mang đúng `runtimeProfile` vừa chọn, ép Kyuubi Server sinh đúng Pod Engine K8s theoSubdomain.

### 7.2. Chỉnh sửa Backend Service (`kyuubi-server/.../notebook`):

1. **`NotebookSessionService.scala`:**
   - Đảm bảo trong `create(...)`, `runtimeProfile` luôn ưu tiên lấy từ `notebook.runtimeProfile` mới nhất trong CSDL.
   - Trong `ensureFor(...)`, nếu Session chưa có Runtime nào active, khởi tạo Runtime mới với `mergedConfig = Map("kyuubi.engine.share.level.subdomain" -> effectiveProfile)`.

---

## 🗄️ 8. Lưu Engine Profile vào DB — Spark Config Backend-driven

### 8.1. Vấn đề Phát hiện (sau khi Giai đoạn 1–3 hoàn thành)

Sau khi xác nhận engine mới được tạo đúng subdomain, phát hiện thêm 2 bug:

1. **Spark resource config không được áp dụng:** Khi chạy `spark.conf.get("spark.driver.memory")`, kết quả luôn là giá trị mặc định Kyuubi, không phải giá trị đã cấu hình trong Engine Profile (`driverMemory`, `executorMemory`...).
2. **Default engine UI ≠ backend thực tế:** Thông số hiển thị cho engine "default" trên Frontend bị hard-code (`2g/4g`) không khớp với giá trị thực tế Kyuubi Server đang dùng.

**Root cause:** `driverMemory`, `executorMemory`, `driverCores`, `executorCores` chỉ tồn tại trong **frontend localStorage**. Backend hoàn toàn không biết đến các thông số này — chỉ có `subdomain` được truyền vào `openSession()`, còn Spark resource configs bị bỏ qua hoàn toàn.

### 8.2. Giải pháp: Lưu Engine Profile vào DB

#### Luồng mới sau khi sửa:
```
Frontend → PUT /api/v1/engine-profiles/{subdomain}
         { sparkConfig: {"spark.driver.memory":"4g", "spark.executor.memory":"8g", ...} }
                │
DB: notebook_engine_profile { subdomain PK, owner, spark_config JSON, ... }
                │
NotebookSessionService.ensureFor()
    → store.getEngineProfile(subdomain)
    → mergedConfig += spark.driver.memory, spark.executor.memory, ...
                │
PySparkRuntimeAdapter.startRuntime(configuration)
    → openSession() với đầy đủ Spark config → Engine khởi động đúng
```

### 8.3. Danh sách File Cần Thay đổi

#### **DDL — Thêm bảng mới (3 dialect)**
- [`notebook-store-schema-1.0.0.sqlite.sql`](file:///root/kyuubi-custom/kyuubi-server/src/main/resources/sql/notebook/sqlite/notebook-store-schema-1.0.0.sqlite.sql) [⏹ CẦN CẬP NHẬT]
- [`notebook-store-schema-1.0.0.mysql.sql`](file:///root/kyuubi-custom/kyuubi-server/src/main/resources/sql/notebook/mysql/notebook-store-schema-1.0.0.mysql.sql) [⏹ CẦN CẬP NHẬT]
- [`notebook-store-schema-1.0.0.postgresql.sql`](file:///root/kyuubi-custom/kyuubi-server/src/main/resources/sql/notebook/postgresql/notebook-store-schema-1.0.0.postgresql.sql) [⏹ CẦN CẬP NHẬT]

```sql
-- Thêm vào cuối mỗi file DDL:
CREATE TABLE IF NOT EXISTS notebook_engine_profile (
    subdomain  TEXT PRIMARY KEY NOT NULL,
    owner      TEXT NOT NULL,
    spark_config TEXT NOT NULL,   -- JSON: {"spark.driver.memory":"4g", ...}
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

#### **Backend Scala**
- [`NotebookModels.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/api/NotebookModels.scala) [⏹ CẦN CẬP NHẬT] — Thêm `case class EngineProfile`
- [`NotebookRequests.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/api/NotebookRequests.scala) [⏹ CẦN CẬP NHẬT] — Thêm `class UpsertEngineProfileRequest`
- [`NotebookStore.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/store/NotebookStore.scala) [⏹ CẦN CẬP NHẬT] — Thêm 4 method: `upsertEngineProfile`, `getEngineProfile`, `listEngineProfiles`, `deleteEngineProfile`
- [`JDBCNotebookStore.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/store/JDBCNotebookStore.scala) [⏹ CẦN CẬP NHẬT] — Implement 4 method trên
- [`FileSystemNotebookStore.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/store/FileSystemNotebookStore.scala) [⏹ CẦN CẬP NHẬT] — Implement stub cho 4 method
- `NotebookEngineProfileService.scala` [⏹ TẠO MỚI] — Service CRUD + `resolveSparkConfig(subdomain)`
- [`NotebookSessionService.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/service/NotebookSessionService.scala) [⏹ CẦN CẬP NHẬT] — `ensureFor()` tích hợp `engineSparkConfig` vào `mergedConfig`
- [`NotebookManager.scala`](file:///root/kyuubi-custom/kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/NotebookManager.scala) [⏹ CẦN CẬP NHẬT] — Khởi tạo `NotebookEngineProfileService` trong `initialize()`
- `NotebookEngineProfilesResource.scala` [⏹ TẠO MỚI] — REST endpoints CRUD `/api/v1/engine-profiles`
- `ApiRootResource.scala` [⏹ CẦN CẬP NHẬT] — Đăng ký sub-resource locator

#### **Frontend**
- [`types.ts`](file:///root/kyuubi-custom/kyuubi-server/web-ui/src/api/notebook/types.ts) [✅ HOÀN THÀNH] — `EngineProfile` thêm field `sparkConfig`
- [`index.ts`](file:///root/kyuubi-custom/kyuubi-server/web-ui/src/api/notebook/index.ts) [✅ HOÀN THÀNH] — Thêm `listEngineProfiles`, `upsertEngineProfile`, `deleteEngineProfile`
- [`EngineConfigDialog.vue`](file:///root/kyuubi-custom/kyuubi-server/web-ui/src/views/notebook/components/EngineConfigDialog.vue) [✅ HOÀN THÀNH] — Migrate từ localStorage sang API; translate form fields → Spark keys khi save
- [`index.vue`](file:///root/kyuubi-custom/kyuubi-server/web-ui/src/views/notebook/index.vue) [✅ HOÀN THÀNH] — `refreshEngineProfiles()` gọi API thay vì đọc localStorage

### 8.4. Quy tắc Translate Form Fields → Spark Config Keys

| Form Field (UI) | Spark Config Key |
|---|---|
| `driverMemory` | `spark.driver.memory` |
| `driverCores` | `spark.driver.cores` |
| `executorMemory` | `spark.executor.memory` |
| `executorCores` | `spark.executor.cores` |
| `executorInstances` | `spark.executor.instances` |
| `customConfigs` (map) | merge trực tiếp |

### 8.5. Task Breakdown — Giai đoạn 5

#### **Giai đoạn 5: Engine Profile DB Storage [▶ ĐANG THỰC HIỆN]**
- [x] **Task 5.1:** Thêm DDL `notebook_engine_profile` vào cả 3 dialect (sqlite, mysql, postgresql). [✅ HOÀN THÀNH]
- [x] **Task 5.2:** Thêm `case class EngineProfile` + `UpsertEngineProfileRequest` vào model/request files. [✅ HOÀN THÀNH]
- [x] **Task 5.3:** Thêm 4 method vào `NotebookStore` trait + implement trong `JDBCNotebookStore` + stub trong `FileSystemNotebookStore`. [✅ HOÀN THÀNH]
- [x] **Task 5.4:** Tạo `NotebookEngineProfileService.scala` với `resolveSparkConfig()`. [✅ HOÀN THÀNH]
- [x] **Task 5.5:** Cập nhật `NotebookSessionService.ensureFor()` — lookup profile config và merge vào `mergedConfig`. [✅ HOÀN THÀNH]
- [x] **Task 5.6:** Tạo `NotebookEngineProfilesResource.scala` + đăng ký trong `ApiRootResource`. [✅ HOÀN THÀNH]
- [x] **Task 5.7:** Cập nhật Frontend — `types.ts`, `api/index.ts`, `EngineConfigDialog.vue`, `index.vue`. [✅ HOÀN THÀNH]
- [ ] **Task 5.8:** Verification: Tạo profile "heavy" với `spark.driver.memory=4g`, chạy `spark.conf.get("spark.driver.memory")`, xác nhận output là `4g`.
