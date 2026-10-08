# Plan: gỡ Notebook và SQL Editor khỏi Kyuubi server + Web UI

Trạng thái: chưa thực hiện. Đối tượng thực thi: AI agent. Người nghiệm thu: chủ repo, theo §8.

Kiểm kê trong plan này được chụp trên branch `dev_redunce`, HEAD `9840eb1` (2026-09-20). Số dòng
có thể lệch; **tên file và tên symbol là thứ để bám**, số dòng chỉ để định hướng.

---

## 0. Mục tiêu và ranh giới — đọc kỹ, đây là phần dễ làm sai nhất

### 0.1 Mục tiêu

Kyuubi trở lại đúng vai trò **execution engine**: nhận session/operation qua REST + Thrift, chạy
trên Spark/JDBC engine. Mọi thứ thuộc về *sản phẩm notebook* (tài liệu, cell, revision, quyền,
lịch, store, routing giữa instance) và *trang SQL Editor* trên Web UI bị gỡ bỏ. Một backend
notebook bên ngoài sẽ dùng REST `/api/v1/sessions` + `/api/v1/operations` thay thế.

### 0.2 GIỮ NGUYÊN — tuyệt đối không đụng vào

Danh sách này quan trọng hơn danh sách xoá. Backend notebook bên ngoài phụ thuộc vào từng dòng
dưới đây.

| Giữ | Vì sao |
|---|---|
| `kyuubi-server/.../api/v1/SessionsResource.scala`, `OperationsResource.scala`, `BatchesResource.scala`, `AdminResource.scala`, `ApiRootResource.scala` (phần không phải notebook) | Đây **là** API mà backend ngoài sẽ gọi. SQL Editor chỉ là UI gọi vào đây; server-side không có gì riêng của editor để xoá |
| `kyuubi-common/.../KyuubiConf.scala`: `ENGINE_SPARK_OUTPUT_MODE`, enum `EngineSparkOutputMode.NOTEBOOK`, `ENGINE_SPARK_PYTHON_MAGIC_ENABLED` | Backend ngoài **bắt buộc** đặt `kyuubi.engine.spark.output.mode=NOTEBOOK` để nhận mime bundle. Tên có chữ "NOTEBOOK" nhưng là tính năng engine, không phải subsystem notebook |
| `externals/kyuubi-spark-sql-engine/.../operation/ExecutePython.scala` toàn bộ, gồm `NOTEBOOK_WORKER_CONFS` và nhánh `case NOTEBOOK => JsonUtils.toJson(data)` (`:479`) | Engine-side. Chính là thứ sinh ra mime bundle cho PySpark |
| `kyuubi-server/src/test/.../engine/spark/PySparkTests.scala` test "Support python magic syntax for python notebook" (`:135`) | Test của engine, không phải của subsystem notebook |
| `kyuubi-server/pom.xml` dependency `jersey-media-multipart` (`:205`) | `BatchesResource` dùng cho upload batch. **Không** phải chỉ notebook dùng |
| `docker/python-for-notebook/**`, `bin/build-spark-image.sh`, `docs/spark-image-python.md` | Image Spark có Python vẫn cần cho PySpark qua backend ngoài. Tên thư mục có "notebook" nhưng nội dung là Spark image |
| `extensions/server/kyuubi-oidc-auth/**`, `AuthenticationFilter.scala` (trừ nhánh §2.4) | Xác thực bearer JWT — backend ngoài sẽ dùng |
| `kyuubi-server/.../api/v1/ArrowRowSetConverter.scala` | Dùng cho result format arrow của REST nói chung; chỉ có một **comment** nhắc notebook (`:45`), sửa comment thôi |
| Web UI: `views/overview`, `views/management/**`, `views/detail`, `views/swagger`, `views/callback`, `views/contact`, toàn bộ `utils/oidc.ts`, `utils/pkce.ts` | Phần Web UI còn lại |
| Dữ liệu đang có: bảng notebook trong JDBC metadata DB, object dưới `s3a://kyuubi-notebook/...` | Plan này **không xoá dữ liệu**. Xem §7 |

### 0.3 Về `engine-profiles` — quyết định mặc định và điều kiện dừng

`NotebookEngineProfileService` nằm trong gói notebook, nhưng `EngineProfileDialog.vue` được gọi
từ trang **Engine management** (`views/management/engine/index.vue:221-237`), là trang được giữ.

Trên checkout này, profile **chỉ** được tiêu thụ ở một chỗ: `NotebookRuntimeService.ensureFor`
→ `engineProfiles.map(_.resolveSparkConfig(profile))`
(`NotebookSessionService.scala:287`). Không còn notebook runtime thì profile không còn tác dụng
lên bất kỳ session nào. **Quyết định mặc định: gỡ cả engine-profiles**, gồm dialog ở trang Engine.

**Điều kiện dừng**: trước khi xoá, chạy

```bash
grep -rn "resolveSparkConfig\|EngineProfileService\|getEngineProfile" --include=*.scala kyuubi-server/src/main \
  | grep -v "/server/notebook/\|NotebookEngineProfilesResource"
```

Nếu ra bất kỳ dòng nào → profile đang được dùng ngoài notebook trên branch thật → **dừng, báo
lại**, không tự quyết.

### 0.4 CẢNH BÁO lệch branch — phải xử lý ở Phase 0

`docs/plans/product-implement.md` mô tả hệ thống đang chạy có `S3EngineProfileStore`,
`EngineProfileOwnerLock`, "Python env builder pod + PVC". **Không symbol nào trong số đó tồn tại
trên checkout này.** Image `kyuubi-custom` đang chạy được build từ một branch/commit khác.

Hệ quả: kiểm kê trong plan này **đúng cho `dev_redunce@9840eb1`**, và có thể thiếu file trên
branch thật. Phase 0 bắt buộc chạy lại kiểm kê bằng đúng các lệnh grep ở §1 trên branch sẽ sửa.

---

## 1. Kiểm kê — lệnh tái tạo được

Mọi lệnh chạy từ gốc repo. Agent **phải chạy lại** ở Phase 0 và so với danh sách bên dưới.

```bash
# server: gói notebook + resource
find kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook -type f
ls kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/ | grep -i notebook

# server: tham chiếu notebook NGOÀI gói
grep -rln "notebook" --include=*.scala kyuubi-server/src/main/scala | grep -v "/server/notebook/"

# server: test + resource
grep -rli "notebook\|engineprofile" --include=*.scala kyuubi-server/src/test
find kyuubi-server/src/main/resources/sql/notebook -type f

# web-ui
grep -rli "notebook" kyuubi-server/web-ui/src kyuubi-server/web-ui/scripts \
  kyuubi-server/web-ui/package.json kyuubi-server/web-ui/vite.config.ts
grep -rli "editor" kyuubi-server/web-ui/src

# build / docs
grep -n -i "notebook" docker/Dockerfile
grep -rli "notebook" docs --include=*.md | grep -v docs/plans
```

### 1.1 Server — xoá nguyên file (41 file)

```
kyuubi-server/src/main/scala/org/apache/kyuubi/server/notebook/           # 32 file, toàn bộ cây
kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/NotebookApiSupport.scala
kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/NotebookCollectionResources.scala
kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/NotebookEngineProfilesResource.scala
kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/NotebookFoldersResource.scala
kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/NotebookRuntimeResources.scala
kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/NotebooksResource.scala
kyuubi-server/src/main/resources/sql/notebook/                            # 6 file SQL schema
```

Lưu ý: `NotebookCollectionResources.scala` chứa `CurrentUserResource` (`/me`). Trên checkout này
Web UI **không** gọi `/me` (`grep -rn "api/v1/me" web-ui/src` rỗng), nên xoá theo. Nếu branch
thật có gọi → giữ lại class đó bằng cách tách ra file riêng, bỏ `extends NotebookApiSupport`.

### 1.2 Server — sửa trong file (6 file)

| File | Việc |
|---|---|
| `server/KyuubiRestFrontendService.scala` | Xoá import `:39-41`; xoá `lazy val notebookManager` `:73-78`; xoá `notebookManager.foreach(addService)` `:116`; xoá khối `notebookManager.foreach { manager => ... NotebookRoutingFilter ... }` `:134-141` **cùng comment 2 dòng phía trên nó** |
| `server/api/v1/ApiRootResource.scala` | Xoá 9 locator: `notebook-folders`, `notebooks`, `notebooks:import`, `notebooks:search`, `me`, `notebook-status`, `runtime-specs`, `notebook-sessions`, `notebook-runtimes`, `executions`, `engine-profiles` (`:206-240`) và comment đi kèm. **Giữ** `sessions`, `operations`, `batches`, `admin` |
| `server/api/OpenAPIConfig.scala` | Bỏ `NotebookExceptionMapper` khỏi import `:23` và `register` `:30` cùng comment `:28-29`. **Giữ** `MultiPartFeature` |
| `server/http/authentication/AuthenticationFilter.scala` | Xoá import `:34-35`. Xoá nhánh "notebook request forwarded by a peer" `:210-221`: đây là một `match` trên `NotebookProxyIdentity.acceptedUser(...)`; gỡ sao cho luồng đi thẳng vào xác thực bình thường (nhánh `None`/fallthrough hiện có). Chạy test suite của filter sau khi sửa |
| `server/api/v1/ArrowRowSetConverter.scala` | Sửa comment `:45` — bỏ cụm "so notebook cells come back empty", không đổi code |
| `src/test/.../config/AllKyuubiConfiguration.scala` | Xoá import `:29` và phần tử `NotebookConf` `:60` |

### 1.3 Server — test xoá (16 file)

```
kyuubi-server/src/test/scala/org/apache/kyuubi/server/notebook/     # toàn bộ cây
```

### 1.4 Web UI — xoá nguyên file

```
src/views/notebook/                       # toàn bộ, gồm components/ và use-notebook.ts
src/views/editor/                         # toàn bộ, gồm styles/ và components/
src/views/workspace/                      # browser file notebook — chỉ gọi @/api/notebook
src/api/notebook/
src/router/notebook/
src/router/editor/
src/router/workspace/
src/pinia/editor/
src/components/monaco-editor/             # chỉ Editor.vue import
src/views/management/engine/components/EngineProfileDialog.vue   # theo §0.3
scripts/verify-notebook-css.mjs
```

### 1.5 Web UI — sửa trong file

| File | Việc |
|---|---|
| `src/router/index.ts` | Bỏ 3 import `editorRoutes`, `notebookRoutes`, `workspaceRoutes` và 3 spread trong `children`. Đổi `redirect: 'workspace'` → `redirect: 'overview'` |
| `src/layout/components/aside/types.ts` | Bỏ 3 mục `Workspace`, `Notebook`, `SQL Editor` khỏi `MENUS`. Giữ `Management` |
| `src/layout/components/aside/index.vue` | Bỏ dropdown "New Notebook" (`:32`) và handler `router.push({ path: '/notebook' ... })` (`:69`). Nếu dropdown chỉ còn mục đó → bỏ cả dropdown |
| `src/layout/components/header/index.vue` | Bỏ 2 dòng map title `/notebook` → 'Notebook', `/editor` → 'SQL Editor' (`:88-89`) |
| `src/views/management/engine/index.vue` | Bỏ `<EngineProfileDialog>` (`:221`), import (`:236-238`), cột `engineProfileOf` (`:96`, `:266`), và toàn bộ state/handler profile (`:307-417`: `profiles`, `selectedProfile`, `handleEditProfile`, `handleDeleteProfile`, gọi `listEngineProfiles/upsertEngineProfile/deleteEngineProfile`) |
| `package.json` | `build`: bỏ `&& node scripts/verify-notebook-css.mjs`. Gỡ dependency `monaco-editor`, `sql-formatter` (xác nhận lại bằng `grep -rn "monaco-editor\|sql-formatter" src` rỗng sau khi xoá). Cập nhật lockfile |
| `vite.config.ts` | Bỏ khối `build: { cssCodeSplit: false }` cùng comment `:40-43` — nó được thêm riêng cho notebook |

Test Web UI hiện có (`src/test/unit/**`) **không** tham chiếu notebook/editor/workspace;
`menu.spec.ts` dùng prop `menus` tự mock, không đọc `MENUS`. Kỳ vọng: không phải sửa test, chỉ
cần chúng vẫn pass.

### 1.6 Build / docs

| File | Việc |
|---|---|
| `docker/Dockerfile` | Xoá khối `RUN grep -Rqs ... '\.cell-stream-output' ... '\.cell-result-log'` (`:66-68`) cùng comment. Nếu để lại, **image build sẽ fail** vì CSS không còn |
| `docs/notebook/` | Xoá cả thư mục (5 file). Không có TOC nào trỏ vào (`grep -rn notebook docs/index.rst docs/*/index.rst` rỗng) |
| `docs/configuration/settings.md` | **Không sửa tay.** Sinh lại bằng `dev/gen/gen_all_config_docs.sh` (đọc header của `AllKyuubiConfiguration.scala:40` để biết biến `KYUUBI_UPDATE`). Sau khi sinh: không còn dòng `kyuubi.notebook.*`, **vẫn còn** `kyuubi.engine.spark.output.mode` |
| `docs/plans/notebook-standalone-be.md` | Thêm một dòng đầu file: "Đã bị thay thế bởi `remove-notebook-sql-editor.md`". Không xoá, để giữ lịch sử quyết định |

---

## 2. Thứ tự thực hiện

Mỗi phase là **một commit riêng**, build xanh ở cuối phase. Không gộp.

### Phase 0 — Xác lập branch và kiểm kê lại
1. Tạo branch từ branch mà image `kyuubi-custom` đang chạy được build ra. Nếu không xác định
   được → hỏi, không đoán.
2. Chạy toàn bộ lệnh §1. Ghi diff so với §1.1–1.6 vào đầu commit message của Phase 1.
3. Chạy lệnh §0.3. Nếu có kết quả → dừng.
4. Build baseline để có mốc so sánh:
   ```bash
   build/mvn -pl kyuubi-server -am -DskipTests install
   (cd kyuubi-server/web-ui && pnpm install && pnpm run build-check && pnpm test -- --run)
   ```

### Phase 1 — Server
1. Xoá theo §1.1, §1.3. Sửa theo §1.2.
2. `build/mvn -pl kyuubi-server -am -DskipTests compile` — phải xanh. Lỗi compile còn lại chính là
   tham chiếu còn sót; sửa cho đến khi hết, **không** thêm stub để lách.
3. Chạy test REST còn lại để chắc không vỡ `ApiRootResource`:
   ```bash
   build/mvn -pl kyuubi-server test -Dtest=none \
     -DwildcardSuites=org.apache.kyuubi.server.api.v1,org.apache.kyuubi.server.http.authentication,org.apache.kyuubi.config.AllKyuubiConfiguration
   ```
4. Sinh lại `settings.md` (§1.6).

### Phase 2 — Web UI
1. Xoá theo §1.4. Sửa theo §1.5.
2. `pnpm install` (cập nhật lockfile sau khi gỡ dependency) → `pnpm run build-check` →
   `pnpm test -- --run`. Cả ba xanh.
3. Mở `dist/index.html` bằng `vite preview`, bấm qua từng mục menu còn lại: Overview, 4 trang
   Management, Swagger. Không có route nào 404, console không có lỗi import.

### Phase 3 — Build artefact
1. Sửa `docker/Dockerfile` (§1.6).
2. Build dist + image theo README gốc:
   ```bash
   ./build/dist --tgz --web-ui --spark-provided --flink-provided --hive-provided
   ./dist/bin/docker-image-tool.sh -r <repo> -i ssa-kyuubi -t remove-notebook -S /opt/spark \
     -b BASE_IMAGE=eclipse-temurin:17-jdk-focal build
   ```
3. Chạy container, kiểm tra §8.3.

### Phase 4 — Docs
Xoá `docs/notebook/`, sửa header `notebook-standalone-be.md`, viết §6 (ghi chú cho chart ngoài).

---

## 3. Những chỗ dễ sai — đọc trước khi bắt đầu Phase 1

1. **Xoá nhầm `ENGINE_SPARK_OUTPUT_MODE` hoặc `ExecutePython`.** Tên có chữ NOTEBOOK. Đây là lỗi
   nghiêm trọng nhất có thể mắc: build vẫn xanh, test vẫn pass, nhưng backend ngoài mất PySpark.
   Kiểm tra bằng §8.1 mục 4.
2. **Xoá `jersey-media-multipart`.** `BatchesResource` dùng. Compile sẽ báo, nhưng đừng "sửa" bằng
   cách bỏ multipart khỏi BatchesResource.
3. **`AuthenticationFilter`**: nhánh notebook proxy đứng *trước* xác thực thường. Gỡ phải giữ
   nguyên hành vi với request không có header proxy. Chạy suite của package này sau khi sửa.
4. **`vite.config.ts` `cssCodeSplit: false`**: bỏ nó làm bundle CSS tách ra theo route. Các trang
   còn lại không phụ thuộc vào behaviour này, nhưng hãy mở từng trang ở Phase 2.3 để chắc.
5. **Không thêm `@deprecated` / warning cho `kyuubi.notebook.*`.** `KyuubiConf` chấp nhận key lạ
   và bỏ qua; deployment còn khai báo `kyuubi.notebook.*` vẫn khởi động được. Việc dọn key là của
   chart (§6), không phải của server.

---

## 4. Tiêu chí hoàn thành của từng phase (agent tự kiểm)

| Phase | Lệnh | Kỳ vọng |
|---|---|---|
| 1 | `grep -rn "notebook" --include=*.scala kyuubi-server/src \| grep -v -i "python magic\|PySparkTests"` | rỗng |
| 1 | `grep -rn "NotebookConf\|NotebookManager\|NotebookRoutingFilter\|NotebookExceptionMapper" --include=*.scala .` | rỗng |
| 1 | `ls kyuubi-server/src/main/resources/sql/` | không còn `notebook/` |
| 1 | `grep -c "kyuubi.notebook" docs/configuration/settings.md` | `0` |
| 1 | `grep -c "kyuubi.engine.spark.output.mode" docs/configuration/settings.md` | `1` |
| 2 | `grep -rn -i "notebook\|/editor\|workspace\|monaco\|sql-formatter" kyuubi-server/web-ui/src kyuubi-server/web-ui/package.json kyuubi-server/web-ui/vite.config.ts` | rỗng |
| 2 | `grep -c "label:" kyuubi-server/web-ui/src/layout/components/aside/types.ts` | `5` (Management + 4 con) |
| 3 | `grep -n "cell-stream-output" docker/Dockerfile` | rỗng |
| 4 | `ls docs/notebook` | không tồn tại |

---

## 5. Commit

Một commit mỗi phase, message tiếng Anh, dạng:

```
Remove notebook subsystem from kyuubi-server (phase 1/4)

- delete org.apache.kyuubi.server.notebook and Notebook*Resource
- drop notebook wiring from KyuubiRestFrontendService, ApiRootResource,
  OpenAPIConfig, AuthenticationFilter
- regenerate settings.md

Inventory diff vs docs/plans/remove-notebook-sql-editor.md §1: <none | list>

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## 6. Việc ở chart ngoài (`kyuubi-helm`, repo khác) — chỉ ghi chú, không làm trong repo này

Sau khi image mới chạy, chart cần dọn. Liệt kê để người vận hành không phải tự tìm:

| Gỡ khỏi values / templates | Lý do |
|---|---|
| Mọi key `kyuubi.notebook.*` trong `kyuubi-defaults.conf` | Không còn ai đọc. Để lại chỉ gây hiểu nhầm |
| Placeholder `__NOTEBOOK_PROXY_SECRET__` trong `render-kyuubi-conf` + key `notebook-proxy-secret` trong Secret `kyuubi-secrets-config` | Routing giữa instance không còn |
| "Python env builder pod + PVC Longhorn 8Gi", image `kyuubi-python-environment-builder` | Thuộc notebook. **Lưu ý**: không tồn tại trên checkout này — xác nhận trên branch thật |
| Bucket/prefix `s3a://kyuubi-notebook/kyuubi-test` (notebook store) | Xem §7 trước khi xoá |
| `pinToPodOrdinal: 0` trên Service `rest` | **Không** gỡ trong đợt này. Nó tồn tại vì ZooKeeper nhúng chưa quorum (xem `PLAN-ZOOKEEPER-EMBEDDED-HA.md`), không vì notebook |

**Giữ**: mọi `spark.*`, `spark.kubernetes.container.image` (image Spark có Python), toàn bộ OIDC,
Kerberos, Ranger, catalog.

---

## 7. Dữ liệu

Plan này không xoá dữ liệu người dùng. Sau khi deploy:

- Bảng `notebook_*` trong JDBC metadata DB vẫn còn, không ai đọc/ghi. Drop hay không là quyết
  định vận hành, làm sau khi backend ngoài đã import xong (nếu có nhu cầu giữ notebook cũ).
- Object dưới `s3a://kyuubi-notebook/kyuubi-test` tương tự.

Nếu muốn giữ notebook cũ để backend ngoài import: **export trước khi deploy image mới**, vì REST
`GET /api/v1/notebooks/{id}` biến mất cùng image.

---

## 8. Nghiệm thu — checklist cho người review

### 8.1 Mã nguồn
1. Toàn bộ lệnh §4 cho kết quả đúng kỳ vọng.
2. `git diff --stat <base>..HEAD` **không** chạm vào: `externals/`, `kyuubi-common/`,
   `extensions/`, `docker/python-for-notebook/`, `bin/build-spark-image.sh`,
   `SessionsResource.scala`, `OperationsResource.scala`, `BatchesResource.scala`.
3. `ApiRootResource.scala` còn đúng 4 locator: `sessions`, `operations`, `batches`, `admin`.
4. `grep -n "NOTEBOOK" externals/kyuubi-spark-sql-engine/src/main/scala/org/apache/kyuubi/engine/spark/operation/ExecutePython.scala`
   vẫn ra kết quả (`:38`, `:276`, `:479`).

### 8.2 Build
5. `build/mvn -pl kyuubi-server -am -DskipTests install` xanh.
6. Suite §2 Phase 1.3 xanh.
7. `pnpm run build-check && pnpm test -- --run` xanh; `node_modules` không còn `monaco-editor`.
8. Image build xanh **không** cần sửa gì thêm ở Dockerfile ngoài §1.6.

### 8.3 Chạy thật (container từ Phase 3, `kyuubi.authentication=NONE` cho nhanh)
9. `GET /api/v1/notebooks` → **404**. `GET /api/v1/engine-profiles` → **404**.
10. Luồng PySpark qua REST còn nguyên — đây là test quan trọng nhất:
    ```bash
    S=$(curl -s -XPOST localhost:10099/api/v1/sessions -H 'Content-Type: application/json' \
      -d '{"configs":{"kyuubi.engine.spark.output.mode":"NOTEBOOK"}}' | jq -r .identifier)
    O=$(curl -s -XPOST localhost:10099/api/v1/sessions/$S/operations/statement \
      -H 'Content-Type: application/json' \
      -d '{"statement":"print(1+1)","runAsync":false,"queryTimeout":0,"confOverlay":{"kyuubi.operation.language":"PYTHON"}}' | jq -r .identifier)
    curl -s "localhost:10099/api/v1/operations/$O/rowset?maxrows=1&fetchorientation=FETCH_FIRST"
    ```
    Kỳ vọng: một hàng, một cột, giá trị là JSON mime bundle có key `text/plain` = `"2\n"` (hoặc
    `"2"`). Nếu nhận `2` trần → `output.mode` không tới engine → **không đạt**.
11. Web UI `/ui`: menu có Overview + Management (Engine/Session/Operation/Server) + Swagger.
    Không có Workspace, Notebook, SQL Editor. Vào từng trang không lỗi console.
12. `GET /api/v1/sessions`, `GET /api/v1/admin/engine`, `POST /api/v1/batches` hoạt động như
    trước (so với container từ image hiện tại nếu có).

### 8.4 Hành vi với config cũ
13. Khởi động container với `kyuubi-defaults.conf` **còn nguyên** các key `kyuubi.notebook.*` →
    server lên bình thường, không crash. (Có warning về key lạ là chấp nhận được.)
