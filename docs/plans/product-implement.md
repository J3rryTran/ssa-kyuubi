# Kyuubi Helm Chart

Helm chart triển khai **Apache Kyuubi 1.10.3** (image custom `kyuubi-custom`) lên Kubernetes, đóng vai trò cổng SQL/Notebook đa người dùng cho lakehouse.

Một chart, hai engine phía sau:

- **Spark on K8s** (Iceberg + Ranger authz) cho lakehouse trên HDFS và SeaweedFS.
- **StarRocks** qua JDBC engine.

Người dùng đăng nhập bằng **Keycloak OIDC**, làm việc trên Web UI/Notebook (REST, NodePort 32379) hoặc kết nối Thrift HTTP (NodePort 32377).

## Cấu trúc thư mục

```
kyuubi-helm/
├── Chart.yaml              chart `kyuubi` 0.1.0, appVersion 1.10.0
├── values.yaml             values mặc định của chart
├── values_dev.yaml         môi trường dev (image trungtmba11093/kyuubi-custom:0.0.3, OrderedReady)
├── values-oidc.yaml        môi trường đang chạy (OIDC, 2 replica Parallel)
├── dashboards/
│   └── kyuubi-spark.json   Grafana dashboard, được nhúng vào ConfigMap
└── templates/              xem mục "Tài nguyên chart sinh ra"
```

## Kiến trúc triển khai

```
Keycloak (OIDC) ──▶ Kyuubi StatefulSet (2 replica, Parallel)
                        │  pod-0  ◀── Service rest (NodePort 32379, pin về pod-0)
                        │  pod-1
                        │  Service thrift-http (NodePort 32377, sessionAffinity ClientIP)
                        │  Service headless (DNS từng pod + port prometheus)
                        │
                        ├─▶ Spark driver/executor pods (cùng namespace, SA <release>-engine)
                        │      ├─ Iceberg catalog `iceberg` ──▶ Hive Metastore + HDFS (Kerberos)
                        │      ├─ Iceberg REST catalog `sqllab_imports` ──▶ SeaweedFS (sigv4)
                        │      └─ Ranger plugin (service type starrocks) ──▶ Ranger Admin + Solr audit
                        │
                        ├─▶ StarRocks FE (JDBC engine, MySQL protocol, SSL)
                        │
                        ├─▶ Python env builder pod + PVC Longhorn 8Gi (notebook Python env)
                        │
                        └─▶ SeaweedFS S3 (s3a://kyuubi-notebook/kyuubi-test)
                               ├─ notebook store
                               └─ engine-profile store
```

### Pod Kyuubi

Mỗi pod chạy 2 initContainer rồi mới tới container `kyuubi-server`:

1. `prepare-spark-home` (image `kyuubi-spark-engine:v2`): copy `/opt/spark` sang emptyDir `spark-home`, mount vào `/opt/spark` của server. Server và engine dùng cùng Spark binary.
2. `render-kyuubi-conf` (image `kyuubi-custom`): copy `kyuubi-defaults.conf` từ ConfigMap sang emptyDir `kyuubi-conf-rendered`, thay các placeholder bằng giá trị đọc từ Secret `kyuubi-secrets-config`:
   - `__STARROCKS_PWD__` ← `starrocks-password`
   - `__OIDC_CLIENT_SECRET__` ← `oidc-client-secret`
   - `__NOTEBOOK_PROXY_SECRET__` ← `notebook-proxy-secret`
   - `__S3_ACCESS_KEY__` / `__S3_SECRET_KEY__` ← `s3-access-key` / `s3-secret-key` (hiện conf không dùng, để sẵn)

   Nhờ vậy ConfigMap trong git không chứa mật khẩu. `KYUUBI_CONF_DIR` của server trỏ vào `/opt/kyuubi/conf` (đã render), còn ConfigMap gốc mount ở `/opt/kyuubi/conf-tpl`.

Volume/mount khác:

- `/etc/krb5.conf` từ ConfigMap `<release>-krb5` (subPath).
- `/opt/kyuubi/keytabs/kyuubi.keytab` từ Secret `kyuubi-keytab` (readOnly).
- `/opt/hadoop/conf` (projected: ConfigMap `<release>-hadoop` + `<release>-krb5`), `/opt/spark/conf` (ConfigMap `<release>-spark`).

Container `kyuubi-server`:

- Args mặc định của chart: `./bin/kyuubi run --conf ...` với namespace K8s, bind port cho từng protocol, `kyuubi.frontend.protocols` (tự ghép từ các frontend bật), metrics Prometheus.
- Env: `JAVA_TOOL_OPTIONS` mở module JGSS cho Kerberos + trỏ krb5; `STARROCKS_PWD`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, `AWS_ENDPOINT_URL*` để server tự đọc/ghi S3 (notebook store, engine-profile store) qua `EnvironmentVariableCredentialsProvider`.
- Liveness/readiness: `bin/kyuubi status`, delay 30s, 10 lần fail.
- Resources: request 2 CPU/4Gi, limit 4 CPU/10Gi.
- Annotation `checksum/conf`, `checksum/conf-hadoop`: đổi ConfigMap là pod tự rolling restart.

## Các khối cấu hình chính (values-oidc.yaml)

| Khối | Giá trị | Ghi chú |
|---|---|---|
| Xác thực | OIDC, JWT RS256, claim `preferred_username`, audience `kyuubi` | Issuer/authorize/logout đi qua `https://10.29.19.56:32004` (trình duyệt), JWKS/token đi nội bộ qua `keycloak-service.keycloak-prod:8080`. Redirect `http://10.29.19.56:32379/api/v1/authentication/callback`. Session 4h. |
| Kerberos | realm `KNAB.COM`, KDC `krb5-kdc.hdfs`, keytab `kyuubi-keytab` | kinit mỗi 1h. Principal cấu hình trong `kyuubi.kinit.principal` / `spark.kerberos.principal`, thay đổi theo môi trường. Spark driver/executor mount cùng keytab + krb5 ConfigMap. |
| Engine Spark | share level `USER`, idle 5m, lifetime 4h (graceful 5m), init timeout 10m | `doAs` tắt, `credentials.hadoopfs` tắt, result format thrift, incremental collect bật. Main resource `kyuubi-spark-sql-engine_2.12-1.10.3.jar` có sẵn trong image engine. |
| Spark resources | executor 4 core / 8G + 10G overhead, dynamic 0–20 (initial 1); driver 2 core / 18G + 6G, maxResultSize 4G | Overhead executor lớn hơn heap là có chủ đích (off-heap). `autoBroadcastJoinThreshold` và `shuffle.partitions` tính từ values. AQE, speculation, bloom filter, DPP bật. |
| Catalog | `iceberg` (Hive type, HMS `hive-postgres-hdfs-metastore`, warehouse `hdfs://hdfs-cluster/stackable/warehouse`), `sqllab_imports` (REST + S3FileIO, sigv4, bucket `sqllabimports`) | `kyuubi.engine.session.initialize.sql` chạy `SHOW NAMESPACES IN <catalog>` cho từng table bucket để warm catalog. Hive metastore jars lấy từ `/opt/spark/jars/*` (version 3.1.3). |
| Authz | `RangerSparkExtension`, service type `starrocks`, Ranger service `starrocks` tại `ranger-admin-svc.ranger:6080`, poll 30s | Audit ghi Solr `solr-ingress.ranger/solr/ranger_audits`. Cấu hình trong `ranger-starrocks-security.xml` / `ranger-starrocks-audit.xml` nằm trong `/opt/spark/conf`. |
| HDFS | HA nameservice `hdfs-cluster`, 2 namenode Stackable, RPC/data transfer `privacy`, encrypt AES/CTR | `auth_to_local` strip `@KNAB.COM`. |
| StarRocks | JDBC engine, `kube-starrocks-fe-service.starrocks:9030`, SSL required, user `starrocks` | Clear-password plugin. |
| Notebook | store `FileSystemNotebookStore`, root `s3a://kyuubi-notebook/kyuubi-test`, `migrateFromJdbc=true` | Python env: builder image `kyuubi-python-environment-builder:v1`, SA `kyuubi`, PVC Longhorn 8Gi, mount `/python-env`. pip index Nexus `10.60.129.132/repository/PyPI`. Proxy nội bộ notebook ký bằng `notebook-proxy-secret`. |
| Engine profile | `S3EngineProfileStore`, root `.../kyuubi-test/engine-profile`, `migrateFromJdbc`, probe bật | Khóa ghi (`EngineProfileOwnerLock`) qua Curator trên ZooKeeper, path `/<ha.namespace>/engine-profile-store/...`. |
| HA / discovery | `ha.addresses` trống, namespace `<release>` | Xem mục lưu ý. |
| Metrics | Prometheus port 10019, PodMonitor (label `release: kube-prometheus-stack`, scrape 15s), Grafana dashboard ConfigMap | ServiceMonitor và PrometheusRule tắt. |

## Tài nguyên chart sinh ra (templates/)

| Template | Tài nguyên | Ghi chú |
|---|---|---|
| `kyuubi-statefulset.yaml` | StatefulSet `<release>` | Mô tả ở trên. |
| `kyuubi-headless-service.yaml` | Service `<release>-headless` | `clusterIP: None`, serviceName của StatefulSet; expose port các frontend bật + `prometheus`. |
| `kyuubi-service.yaml` | Service `<release>-<frontend>` cho mỗi frontend bật | NodePort, sessionAffinity ClientIP 3h. `rest` có `pinToPodOrdinal: 0` → selector thêm `statefulset.kubernetes.io/pod-name: <release>-0`. |
| `kyuubi-frontend-extra-nodeports.yaml` | Service NodePort phụ | Theo `server.<frontend>.extraNodePorts`. Hiện không dùng. |
| `kyuubi-configmap.yaml` | ConfigMap `<release>` | `kyuubi-defaults.conf` (template, còn placeholder). |
| `kyuubi-hadoop-configmap.yaml` | ConfigMap `<release>-hadoop` | `core-site.xml`, `hdfs-site.xml`, `hive-site.xml`. |
| `kyuubi-spark-configmap.yaml` | ConfigMap `<release>-spark` | `spark-defaults.conf`, `ranger-starrocks-*.xml`. |
| `kyuubi-krb5-configmap.yaml` | ConfigMap `<release>-krb5` | `krb5.conf` sinh từ `krb5.*`; mount vào server và Spark driver/executor (`spark.kubernetes.kerberos.krb5.configMapName`). |
| `kyuubi-serviceaccount.yaml`, `kyuubi-role.yaml`, `kyuubi-rolebinding.yaml` | SA `kyuubi` + Role | Server tạo/xoá pod, svc, cm, secret, pvc trong namespace. |
| `kyuubi-engine-serviceaccount.yaml`, `kyuubi-engine-role.yaml`, `kyuubi-engine-rolebinding.yaml` | SA `<release>-engine` + Role | Spark driver tạo executor pod, cm, pvc. |
| `kyuubi-priorityclass.yaml` | PriorityClass | Tắt (`priorityClass.create: false`). |
| `kyuubi-spark-driver-cleaner.yaml` | CronJob `<release>-spark-driver-cleaner` + SA/Role | Mỗi giờ xoá driver pod (`spark-role=driver`) ở Succeeded/Failed. Image `alpine/kubectl`. |
| `kyuubi-spark-driver-terminator.yaml` | CronJob `<release>-spark-driver-terminator` + SA/Role | Kill driver Running quá `maxAgeSeconds` (14400s). Hiện tắt, có `dryRun`. |
| `kyuubi-podmonitor.yaml` | PodMonitor `<release>` | Scrape port `prometheus`. |
| `kyuubi-servicemonitor.yaml` | ServiceMonitor | Tắt. |
| `kyuubi-alert.yaml` | PrometheusRule | Tắt. |
| `grafana-dashboard-configmap.yaml` | ConfigMap `<release>-grafana-dashboards` | Label `grafana_dashboard: "1"` để sidecar Grafana tự nạp `dashboards/*.json`. |
| `NOTES.txt` | Thông báo sau install | In URL service nội bộ và cách lấy NodePort. |

## Tài nguyên phải tạo tay (ngoài chart)

Chart không sinh các tài nguyên sau. Phải có sẵn trong namespace trước khi sync, nếu thiếu pod sẽ kẹt ở initContainer `render-kyuubi-conf` hoặc không mount được volume.

### Secret `kyuubi-secrets-config`

| Key | Dùng cho |
|---|---|
| `starrocks-password` | mật khẩu user `starrocks` cho JDBC engine |
| `oidc-client-secret` | client secret của client `kyuubi` trên Keycloak realm `lakehouse` |
| `notebook-proxy-secret` | khoá ký proxy nội bộ notebook (chuỗi ngẫu nhiên, tự sinh) |
| `s3-access-key` | access key SeaweedFS S3 (server + Spark driver/executor) |
| `s3-secret-key` | secret key SeaweedFS S3 |

### Secret `kyuubi-keytab`

| Key | Dùng cho |
|---|---|
| `kyuubi.keytab` | keytab của principal cấu hình trong `kyuubi.kinit.principal`; mount vào server và Spark driver/executor tại `/opt/kyuubi/keytabs` |

### Khác

- Bucket `kyuubi-notebook` trên SeaweedFS (notebook + engine profile). Bucket `sqllabimports` cho catalog `sqllab_imports`.
- StorageClass `longhorn` cho PVC Python env.
- Keycloak: client `kyuubi` (confidential) trong realm `lakehouse`, redirect URI `http://10.29.19.56:32379/api/v1/authentication/callback`.
- Ranger: service tên `starrocks`.
- Prometheus Operator với label selector `release: kube-prometheus-stack`; Grafana sidecar nhặt label `grafana_dashboard`.

## Lưu ý / việc tiếp theo

1. **ZooKeeper nhúng với 2 replica.** Kyuubi có ZooKeeper nhúng, nhưng là **mỗi pod một instance riêng**, không chia sẻ dữ liệu với nhau. Vì vậy service discovery và khoá engine-profile chỉ có tác dụng trong từng pod: hai pod không thấy engine của nhau, không khoá chéo khi ghi profile lên S3. Service `rest` đang pin về pod-0 chính là để tránh trạng thái lệch. Muốn HA thật, điền `ha.addresses` trỏ sang một ZooKeeper dùng chung; template đã có sẵn khối cấu hình `ZookeeperDiscoveryClient`.
2. **REST pin pod-0.** Nếu pod-0 chết, UI mất cho tới khi pod-0 lên lại. Trade-off tạm thời do mục 1.
3. **Kerberos principal.** Giá trị `kyuubi.kinit.principal` / `spark.kerberos.principal` phải khớp principal trong `kyuubi-keytab`; khi đổi môi trường cần cập nhật cả hai cùng lúc với secret.
