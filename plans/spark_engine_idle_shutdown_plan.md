# Kế hoạch: Kết thúc dứt điểm JVM Spark Driver sau graceful shutdown

## 1. Mục tiêu

Đảm bảo Spark Driver pod trên Kubernetes thực sự kết thúc sau khi Kyuubi Spark SQL Engine đã được dừng bình thường, đặc biệt sau idle timeout. Mục tiêu là giải phóng CPU/RAM và tránh Driver pod orphan ở trạng thái Running khi người dùng không còn dùng notebook.

Phạm vi là Spark engine process độc lập chạy ở Kubernetes cluster mode. Không thay đổi REST API, Notebook UI hoặc logic Python.

## 2. Sự cố đã xác nhận

Các timeout hiện tại đã hoạt động đúng:

1. kyuubi.notebook.runtime.idle.timeout đóng runtime và Kyuubi session của notebook.
2. kyuubi.session.engine.idle.timeout yêu cầu Spark engine tự kết thúc khi không còn user session.

Log Driver đã xác nhận chuỗi shutdown:

~~~text
Reclaiming runtime ... after 3600000ms idle
Idled for more than 1800000 ms, terminating
SparkContext: Successfully stopped SparkContext
~~~

Tuy nhiên Driver pod vẫn Running. Thread dump cho thấy main thread đã vào DestroyJavaVM, nhưng non-daemon thread, ví dụ pool-32-thread-* thuộc ScheduledThreadPoolExecutor, vẫn còn sống. JVM không thể tự thoát, SparkSubmit vẫn tồn tại và Kubernetes không thể hoàn tất Driver pod.

Đây không phải lỗi Notebook idle reaper. Reaper đã đóng runtime/session đúng lúc; vấn đề là process lifecycle sau khi Kyuubi và SparkContext đã shutdown.

## 3. Vì sao spark.stop() chưa đủ

spark.stop() chỉ dừng SparkContext và thành phần do Spark quản lý. JVM chỉ tự thoát khi không còn non-daemon thread. Một thread pool của thư viện, gateway hoặc thành phần khác còn sống có thể giữ Driver JVM chạy dù SparkContext đã dừng.

Kubernetes chỉ hoàn tất Driver pod khi process chính trong container thoát. Cần một guardrail để kết thúc process sau khi graceful cleanup hoàn tất.

## 4. Nguyên tắc thiết kế

- Giữ nguyên graceful shutdown hiện có của Kyuubi và Spark.
- Không gọi System.exit trong idle checker thread hoặc trong SparkSQLEngine.stop(); làm vậy có thể cắt ngang cleanup service, session hoặc SparkContext.
- Chỉ yêu cầu thoát process sau khi main thread được giải phóng bởi countDownLatch và spark.stop() hoàn tất.
- Chỉ áp dụng cho Kubernetes Spark Driver, không ảnh hưởng local mode, test JVM hay engine ngoài Kubernetes.
- Có feature flag mặc định false để rollout và rollback an toàn.
- Backend timeout vẫn là nguồn quyết định dọn dẹp; không dựa vào browser logout/beforeunload.

## 5. Flow hiện tại và flow sau thay đổi

### 5.1. Hiện tại

~~~text
Idle checker hoặc lifecycle checker
  → currentEngine.stop()
  → Serverable.stop() dừng service
  → SparkSQLEngine.stopServer() gọi countDownLatch.countDown()
  → main thread thoát countDownLatch.await()
  → finally gọi spark.stop()
  → non-daemon thread còn sống
  → JVM không thoát, Driver pod vẫn Running
~~~

### 5.2. Sau thay đổi

~~~text
Idle timeout / max lifetime / terminate chủ động
  → currentEngine.stop()
  → các service graceful shutdown
  → countDownLatch.countDown()
  → main thread tiếp tục
  → spark.stop() hoàn tất
  → Kubernetes Driver và flag bật: System.exit(0)
  → process chính kết thúc
  → Kubernetes hoàn tất Driver pod và dọn Executor pod
~~~

Nhu cầu ban đầu xuất phát từ idle timeout, nhưng force-exit nên áp dụng cho mọi graceful engine shutdown bình thường trên Kubernetes: idle timeout, max lifetime và terminate chủ động. Đây là cách tránh orphan pod ở mọi đường shutdown hợp lệ.

Startup failure hoặc exception bất thường không được đi vào nhánh exit code 0.

## 6. Thiết kế đề xuất

### 6.1. Feature flag

Thêm ConfigEntry[Boolean] trong KyuubiConf:

~~~properties
kyuubi.engine.force.exit.on.stop=false
~~~

Để Spark Driver nhận được cấu hình, deployment phải truyền Spark configuration:

~~~properties
spark.kyuubi.engine.force.exit.on.stop=true
~~~

SparkSQLEngine.setupConf() đã chuyển các key có prefix spark.kyuubi. thành kyuubi. trong KyuubiConf:

~~~text
spark.kyuubi.engine.force.exit.on.stop
  → kyuubi.engine.force.exit.on.stop
  → KyuubiConf của Spark engine
~~~

Default phải là false. Chỉ Helm/deployment notebook chạy Kubernetes mới bật true.

### 6.2. Shutdown path trong SparkSQLEngine.main

File chính:

~~~text
externals/kyuubi-spark-sql-engine/src/main/scala/
org/apache/kyuubi/engine/spark/SparkSQLEngine.scala
~~~

Trong main, khai báo biến cục bộ gracefulShutdownCompleted = false. Chỉ đặt biến này thành true ngay sau khi countDownLatch.await() trả về bình thường.

spark.stop() vẫn giữ trong finally. Sau khi finally hoàn tất thành công, đánh giá điều kiện:

~~~scala
if (gracefulShutdownCompleted &&
    kyuubiConf.get(ENGINE_FORCE_EXIT_ON_STOP) &&
    isOnK8sClusterMode) {
  info("Spark engine shutdown completed; exiting Kubernetes driver JVM.")
  System.exit(0)
}
~~~

Điểm bắt buộc:

- Không đặt System.exit(0) trong idle checker, stop(), catch hoặc ngay đầu finally.
- Không đặt exit trong finally vì finally cũng chạy khi startup thất bại.
- Nếu spark.stop() ném lỗi thì không đánh dấu graceful success bằng exit code 0.
- System.exit chạy shutdown hook hiện có; engine.stop() là idempotent nhờ AtomicBoolean shutdown nên có thể gọi lại an toàn.

### 6.3. Guard Kubernetes

Tái sử dụng isOnK8sClusterMode hiện có trong SparkSQLEngine. Hàm chỉ true khi:

1. Process đang chạy trong Kubernetes (Utils.isOnK8s).
2. Có biến môi trường SPARK_APPLICATION_ID, tức Spark Driver pod.

Điều này loại trừ local mode chạy trong pod Kyuubi và mọi môi trường không phải Kubernetes.

## 7. Kiểm thử

Không gọi trực tiếp System.exit trong unit test JVM. Tách predicate hoặc process terminator thành helper có thể thay thế, ví dụ shouldForceExitAfterGracefulStop(...) hoặc ProcessTerminator.

| Tình huống | Kết quả |
| --- | --- |
| Graceful shutdown + flag bật + Kubernetes Driver | Yêu cầu exit code 0 |
| Flag tắt | Không exit |
| Không phải Kubernetes Driver | Không exit |
| Lỗi createSpark() hoặc startEngine() | Không exit code 0 |
| spark.stop() thất bại | Không đánh dấu graceful success |

## 8. Phạm vi file

| File | Thay đổi |
| --- | --- |
| kyuubi-common/.../config/KyuubiConf.scala | Thêm ENGINE_FORCE_EXIT_ON_STOP, default false. |
| externals/kyuubi-spark-sql-engine/.../SparkSQLEngine.scala | Đánh dấu graceful shutdown và force-exit có điều kiện sau spark.stop(). |
| SparkSQLEngineSuite.scala hoặc suite phù hợp | Test predicate/process terminator mà không kết thúc JVM test. |
| charts/kyuubi/values-test-local.yaml | Truyền spark.kyuubi.engine.force.exit.on.stop=true vào Spark engine. |
| Tài liệu deployment/notebook phù hợp | Mô tả timeout, flag và pod lifecycle kỳ vọng. |

Không sửa REST layer, Notebook UI hoặc execute_python.py cho task này.

## 9. Các bước triển khai

- [x] Xác nhận vị trí Spark configuration trong chart/deployment để flag thực sự đến Driver pod.
- [x] Thêm ENGINE_FORCE_EXIT_ON_STOP vào KyuubiConf, default false, và test parsing/default.
- [x] Refactor SparkSQLEngine.main để phân biệt graceful shutdown với startup failure.
- [x] Thêm Kubernetes-only System.exit(0) sau spark.stop().
- [x] Thêm unit test cho predicate/process terminator.
- [x] Bật spark.kyuubi.engine.force.exit.on.stop=true trong values-test-local.yaml.
- [x] Build Spark SQL Engine JAR mới, rồi build Spark Driver image mới để image nhận JAR mới.
- [x] Import image mới vào K3s/containerd và tạo Driver mới để kiểm thử.

## 10. Checklist kiểm thử K3s

Dùng timeout ngắn:

~~~properties
kyuubi.notebook.runtime.idle.timeout=PT2M
kyuubi.notebook.runtime.idle.check.interval=PT30S
kyuubi.session.engine.idle.timeout=PT2M
kyuubi.session.engine.check.interval=PT30S
spark.kyuubi.engine.force.exit.on.stop=true
~~~

1. Tạo notebook, chạy một Python hoặc SQL cell để tạo Spark Driver.
2. Xác nhận Driver và Executor pod Running.
3. Không thao tác qua timeout cộng thêm ít nhất một chu kỳ check.
4. Xác nhận log có Reclaiming runtime, Idled ... terminating, Successfully stopped SparkContext và log force-exit mới.
5. Xác nhận Driver pod Completed hoặc biến mất theo chính sách Kubernetes; Executor pod cũng được dọn.
6. Chạy cell lại và xác nhận chỉ một Driver mới được tạo.
7. Tắt flag và xác nhận hệ thống quay về hành vi cũ để phục vụ rollback/debug.

## 11. Rollback và lưu ý vận hành

- Rollback nhanh nhất: đặt spark.kyuubi.engine.force.exit.on.stop=false, build/import image mới nếu cấu hình được bake vào chart/image, rồi tạo Driver mới.
- Sau idle timeout, Python session state, temporary file và package cài riêng theo session có thể mất; đây là hành vi mong đợi khi giải phóng resource.
- Force-exit là guardrail cho Kubernetes process lifecycle, không thay thế việc điều tra nguồn gốc pool-32-thread-*. Nếu xác định được chủ sở hữu thread, nên đóng thread pool tại nguồn.
