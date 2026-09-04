# Plan: Ket thuc JVM Spark Driver sau Idle Shutdown

## 1. Muc tieu

Dam bao Spark Driver pod tren Kubernetes thuc su ket thuc sau khi Kyuubi engine da idle
timeout va SparkContext da duoc stop. Muc tieu la giai phong CPU/RAM va tranh cac Driver pod
orphan o trang thai `Running` sau khi nguoi dung bo notebook, dong trinh duyet, logout, hoac
khong thuc thi cell nua.

## 2. Su co da xac nhan

Hai co che timeout hien co deu da hoat dong:

1. `kyuubi.notebook.runtime.idle.timeout` mac dinh 1 gio: Notebook idle reaper dong runtime
   va Kyuubi session.
2. `kyuubi.session.engine.idle.timeout` mac dinh 30 phut: Spark engine tu dong shutdown khi
   khong con user session.

Log Driver da xac nhan day du chuoi graceful shutdown:

```text
Reclaiming runtime ... after 3600000ms idle
Idled for more than 1800000 ms, terminating
SparkContext: Successfully stopped SparkContext
```

Tuy nhien Kubernetes Driver pod van `Running`. Thread dump cho thay JVM main thread da vao
`DestroyJavaVM`, nhung cac non-daemon thread `pool-32-thread-*` van con song trong
`ScheduledThreadPoolExecutor`. JVM vi the khong thoat, PID `SparkSubmit` con ton tai, va pod
khong ket thuc.

Day khong phai la loi Notebook idle reaper; day la loi process lifecycle sau khi engine da
shutdown thanh cong.

## 3. Nguyen tac thiet ke

- Giu nguyen graceful shutdown hien tai cua Kyuubi va Spark.
- Khong goi `System.exit` truc tiep trong idle-reaper thread; cach nay co the cat ngang
  `engine.stop()` hoac `SparkContext.stop()`.
- Chi force-exit sau khi main thread da duoc giai phong va `SparkContext.stop()` da hoan tat.
- Chi ap dung cho Spark Driver Kubernetes, khong anh huong local/test/non-Kubernetes engine.
- Co feature flag de rollback ma khong can doi code.

## 4. Thiet ke de xuat

File chinh: `externals/kyuubi-spark-sql-engine/src/main/scala/org/apache/kyuubi/engine/spark/SparkSQLEngine.scala`

Luong hien tai:

```text
idle checker -> currentEngine.stop()
             -> stopServer() -> countDownLatch.countDown()
main thread  -> countDownLatch.await() returns
             -> finally { spark.stop() }
             -> non-daemon threads keep JVM alive
```

Luong sau khi sua:

```text
idle checker -> currentEngine.stop()
             -> stopServer() -> countDownLatch.countDown()
main thread  -> countDownLatch.await() returns
             -> spark.stop() completes
             -> if Kubernetes Driver and flag enabled: System.exit(0)
             -> PID 1 exits -> Kubernetes completes/removes Driver pod
```

### 4.1. Config feature flag

Them config server/engine-side moi, duoc truyen bang Spark conf:

```properties
spark.kyuubi.engine.force.exit.on.stop=true
```

Config nen duoc dinh nghia trong `KyuubiConf` voi default `false` de han che rui ro cho cac
deployment khac. Helm/deployment Kubernetes notebook dat gia tri `true`.

Chi force-exit khi dong thoi thoa ca ba dieu kien:

1. engine shutdown binh thuong (main thread da duoc count down),
2. flag enabled,
3. `isOnK8sClusterMode` la `true`.

### 4.2. Thay doi `SparkSQLEngine.main`

Trong `main`, track mot bien `gracefulShutdown` chi duoc dat `true` sau khi
`countDownLatch.await()` return binh thuong. Sau `spark.stop()`, goi mot helper rieng de thoat:

```scala
if (gracefulShutdown && forceExitOnStop && isOnK8sClusterMode) {
  info("Spark engine shutdown completed; exiting Kubernetes driver JVM.")
  System.exit(0)
}
```

`System.exit(0)` phai nam tren main thread, sau cleanup. Catch/exception trong khoi tao engine
khong duoc di vao nhanh nay; cac loi do phai giu exit code/hanh vi hien tai.

### 4.3. Kha nang test

Khong goi `System.exit` truc tiep trong unit test JVM. Tach action nay qua mot helper/package-
visible process terminator de test co the thay bang fake, hoac chi unit-test predicate:

```text
graceful shutdown + flag true + K8s -> request exit(0)
flag false                         -> no exit
non-K8s                            -> no exit
exception while startup             -> no exit(0)
```

## 5. Pham vi file du kien

| File | Thay doi |
| --- | --- |
| `kyuubi-common/.../config/KyuubiConf.scala` | Them feature flag config va default an toan. |
| `externals/kyuubi-spark-sql-engine/.../SparkSQLEngine.scala` | Track graceful shutdown va force-exit co dieu kien sau `spark.stop()`. |
| `externals/kyuubi-spark-sql-engine/.../SparkSQLEngineSuite.scala` hoac suite phu hop | Test predicate/process terminator, khong ket thuc JVM test. |
| `charts/kyuubi/values-test-local.yaml` | Bat flag cho local K3s notebook deployment. |
| Tai lieu deployment/notebook phu hop | Mo ta timeout, flag, va expected pod lifecycle. |

Khong sua REST layer, Notebook UI, hoac `execute_python.py` cho task nay.

## 6. Ke hoach trien khai

- [ ] Task 1: Xac nhan ten config va noi truyen `spark.kyuubi.*` vao KyuubiConf cua engine.
- [ ] Task 2: Them config entry, default `false`, va test parsing/default.
- [ ] Task 3: Refactor `SparkSQLEngine.main` de phan biet normal shutdown voi startup failure.
- [ ] Task 4: Them Kubernetes-only force exit sau `spark.stop()`.
- [ ] Task 5: Them unit tests voi process terminator fake/predicate test.
- [ ] Task 6: Bat flag trong `values-test-local.yaml`.
- [ ] Task 7: Build Spark SQL Engine JAR va Spark Driver image moi.
- [ ] Task 8: Integration test idle timeout tren K3s.

## 7. Integration test checklist

Dung timeout ngan de test nhanh:

```properties
kyuubi.notebook.runtime.idle.timeout=2m
kyuubi.notebook.runtime.idle.check.interval=30s
kyuubi.session.engine.idle.timeout=2m
kyuubi.session.engine.check.interval=30s
spark.kyuubi.engine.force.exit.on.stop=true
```

1. Tao notebook va run mot Python cell de tao Spark Driver.
2. Xac nhan Driver pod `Running`.
3. Khong thao tac qua timeout + mot chu ky check.
4. Xac nhan log co `Reclaiming runtime`, `Idled ... terminating`, va log force-exit moi.
5. Xac nhan Driver pod chuyen `Completed` hoac bien mat; executor pods cung bien mat.
6. Run cell lai va xac nhan chi co mot Driver moi duoc tao.
7. Tat feature flag va xac nhan shutdown quay lai hanh vi cu, phuc vu rollback/debug.

## 8. Rollback va luu y van hanh

- Rollback nhanh nhat: dat `spark.kyuubi.engine.force.exit.on.stop=false`, rebuild/import Spark
  image neu config nam trong image/chart, sau do tao Driver moi.
- Force-exit lam mat state Python session, temp files, va packages cai bang `%pip --target` sau
  idle timeout; day la hanh vi da duoc mong doi khi release resources.
- Khong dua cleanup bang `beforeunload` cua browser vao thiet ke. Browser crash/mat mang khong
  dam bao request logout duoc gui; backend timeout phai la authority.
- Co the dieu tra rieng chu so huu `pool-32-thread-*` de dong thread dung cach. Tuy nhien
  Kubernetes-only force-exit la guardrail can thiet de bao dam resource duoc release trong khi
  dieu tra do chua hoan tat.
