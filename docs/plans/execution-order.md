# Thứ tự thực hiện cho AI agent

Gộp hai plan: `sync-custom-remove-ui-s3.md` (gọi tắt **SYNC**) và `thrift-binary-oidc.md` (gọi tắt
**THRIFT**). Làm **đúng thứ tự**, bước sau chỉ bắt đầu khi "Xong khi" của
bước trước đạt. Gặp điều không khớp plan → dừng, ghi vào `docs/plans/execution-notes.md`, báo lại.

Repo: `ssa-kyuubi`, branch `dev_redunce`.

**Git chỉ do chủ dự án thao tác.** Agent **không** chạy `git add`, `commit`, `push`, `pull`, `checkout`,
`stash`, `reset`, `rm` (dùng `rm`/xóa file thường thay cho `git rm`). Agent chỉ sửa file trong working tree
và được dùng lệnh git **chỉ đọc** (`status`, `diff`, `log`, `show`). Cột "Commit" dưới đây là **message
đề xuất** để chủ dự án tự commit sau khi xem diff.

| Bước | Việc | Làm theo | Xong khi | Commit message đề xuất |
|---|---|---|---|---|
| 1 | Kiểm 14 file nguồn từng bị `.gitignore` nuốt đã có trên đĩa và không còn bị ignore (chủ dự án tự `git add`; không tính `resources/dist/`) | SYNC 1.2 C0 | `diff -r` với `kyuubi-custom` rỗng; `build/mvn -pl kyuubi-metrics,kyuubi-hive-jdbc -am -DskipTests compile` xanh | `Track source packages previously hidden by .gitignore` |
| 2 | Port lõi OIDC: `AuthMethods`, `AuthenticationProviderFactory` + 2 test | SYNC C1–C4 (= THRIFT 1.1, chỉ làm một lần) | `diff` 4 file rỗng; `KyuubiAuthenticationFactorySuite` xanh | `Add OIDC to AuthMethods (port from kyuubi-custom)` |
| 3 | Copy phần còn lại từ custom + merge tay | SYNC C5–C11, M1, M2, kiểm 1.6 | 1.6 đạt; `PySparkTests` xanh | `Sync OIDC extension, JDBC client and engine worker from kyuubi-custom` |
| 4 | Gỡ Web UI | SYNC 2a (U1–U13) | kiểm 2.2 đạt; `build/dist` không còn `--web-ui` | `Remove Kyuubi Web UI` |
| 5 | Giữ BFF, sửa redirect `/ui`, `authentication/config` trả thêm `thrift` | SYNC 2b (B1–B6) | kiểm 2.3 đạt | `Point OIDC BFF redirects away from removed Web UI` |
| 6 | Provider JWT-làm-password + suite 5 case | THRIFT 1.2 | `build/mvn -pl extensions/server/kyuubi-oidc-auth test` xanh | `Accept OIDC access token as SASL password for Thrift binary` |
| 7 | Thêm `hadoop-aws` + `aws-java-sdk-bundle` vào `docker/Dockerfile` | SYNC Phần 3 (chỉ sửa Dockerfile) | `docker/Dockerfile` có 2 build-arg + `sha256sum -c`; không cần build | `Add hadoop-aws to server image` |
| 8 | Sinh lại tài liệu cấu hình, format | `dev/gen/gen_all_config_docs.sh`, `dev/reformat` | không còn diff lạ; scalastyle xanh | `Regenerate settings.md` |

**Phạm vi: chỉ viết và sửa code.** Không viết tài liệu (`docs/client/`, `docs/security/`, `AGENTS.md`,
`README.md`, `product-implement.md`), không build dist/image, không triển khai, không đụng Keycloak.
Chủ dự án tự build và chạy.

Riêng bước 8 vẫn làm vì `settings.md` là file sinh tự động: `AllKyuubiConfiguration` sẽ đỏ nếu nó lệch
với `KyuubiConf`.

## Kết thúc: in hướng dẫn chạy ra terminal (không ghi file)

Sau bước 8, agent **in ra terminal** (không tạo file tài liệu) một khối hướng dẫn gồm:

1. Danh sách file đã đổi theo từng bước (`git status --short`), kèm commit message đề xuất cho từng nhóm.
2. Lệnh build dist và image mà chủ dự án sẽ tự chạy (theo README hiện có).
3. Cấu hình Kyuubi cần thêm: `kyuubi.frontend.protocols` có `THRIFT_BINARY`;
   `kyuubi.authentication.custom.class=org.apache.kyuubi.auth.oidc.JwtPasswordAuthenticationProvider`;
   hai key redirect mới của bước 5 (nếu muốn đổi mặc định).
4. Lệnh kiểm nhanh sau khi chạy, mỗi lệnh kèm kết quả mong đợi:
   - `curl -s <rest>/api/v1/authentication/config | jq .` → có `thrift.port`, không có secret
   - `curl -H "Authorization: Bearer $TOKEN" <rest>/api/v1/ping` → `pong`
   - `curl -u a:b <rest>/api/v1/ping` → 401
   - `beeline -u "jdbc:kyuubi://<host>:10009/default" -n "$USER" -p "$TOKEN" -e "SELECT 1"` → 1 dòng
   - như trên với `-n` sai → lỗi "does not match"
   - `docker run --rm <image> ls /opt/kyuubi/jars | grep -E "hadoop-aws|aws-java-sdk"` → 2 jar
5. Những việc đã hoãn hoặc chỗ còn nghi ngờ trong lúc làm.

## Điều kiện dừng (không tự xử lý)

- Bước 3: `PySparkTests` đỏ sau khi copy `execute_python.py`.
- Bước 5: test liên quan BFF/cookie đỏ.
- Bước 6: case "user không khớp" hoặc "chỉ tải JWKS một lần" trong suite không đạt.
- Bất kỳ bước nào cần sửa Ranger: plan không cho phép.

## Ngoài phạm vi

dbt, backend notebook (`notebook-api`), Helm chart/operator, gỡ REST, tài liệu, build/triển khai, Keycloak.
