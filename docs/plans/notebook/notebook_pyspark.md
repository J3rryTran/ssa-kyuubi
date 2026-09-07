# Spec: Python notebook chay tren SPARK ENGINE (PySpark) + GO PYTHON khoi Kyuubi server + store HDFS

## 0. Boi canh & rang buoc

- Repo: `https://github.com/J3rryTran/kyuubi-custom.git`, branch `notebook`. Base Kyuubi 1.10.3.
- He thong chay `replicaCount: 2` — moi thu phai dung voi 2 pod. KHONG dung DB ngoai (Postgres/MySQL).
- KHONG dung: BFF OIDC (da xong), arrow-converter /rowset (task rieng), Helm chart (nguoi khac lo).
- Agent khong truy cap duoc cluster — sua code + unit test; e2e do nguoi deploy.
- Quyet dinh kien truc DA CHOT:
  1. Python cell chay trong SPARK ENGINE (worker tren driver, co bien `spark`).
  2. **GO HAN Python khoi Kyuubi server**: xoa code CPython path va xoa Python khoi image
     kyuubi-custom. KHONG giu fallback cpython.

## 1. Hien trang da audit (dich nguoc image that — ten file tu map trong repo)

- Adapter hien co: `CpythonRuntimeAdapter` (Python = subprocess CPython tren pod server, kernel
  nhung `python/kyuubi_notebook_kernel.py`, venv per environment) va `KyuubiSqlRuntimeAdapter`
  (SQL -> Kyuubi session -> Spark engine). Co san trait `NotebookRuntimeAdapter` +
  `RuntimeAdapterRegistry` + DTO `AdapterOutput/AdapterResultPage/AdapterExecution...`.
- Python env: `PythonEnvironmentsResource`, `PythonPackageOperationsResource`,
  `PythonEnvironmentBuilder`, schema SQL `notebook-python-schema-1.0.0.*`, config
  `kyuubi.notebook.python.*` (executable, venv, limit.*, package.*, work.dir).
- Notebook store: trait `NotebookStore`, impl duy nhat `JDBCNotebookStore` driver `sqlite.JDBC`
  (file tren dia tung pod). Co `IpynbCodec`, `Tombstone`, `PathUpdate`, khai niem `kyuubiInstance`.
- Config proxy REST co san (upstream dung cho batch): `kyuubi.frontend.rest.proxy.jetty.client.*`.
- HA ZooKeeper dang chay: `kyuubi.ha.zookeeper.quorum`, `kyuubi.ha.namespace`, `kyuubi.ha.addresses`.
- UI da render text/html, image/*, image/svg+xml; text/plain STREAM (fix rieng dang lam).
- Image Spark engine (`spark_img`): CO `/usr/bin/python3` = Python 3.8 (apt), KHONG co
  pandas/numpy/pyarrow/matplotlib. pyspark import duoc qua $SPARK_HOME/python (khong can pip pyspark).

## 2. TASK A — PySparkRuntimeAdapter (thay the hoan toan CPython)

### Co che nen tang: TAI DUNG Python mode co san cua Kyuubi engine
Kyuubi Spark engine 1.10 da ho tro chay Python: operation voi
`kyuubi.operation.language=PYTHON` -> engine spawn python worker TREN DRIVER, co san bien `spark`
(SparkSession). Worker la resource python trong kyuubi-spark-sql-engine (execute_python).
KHONG phat minh engine type moi — dung SPARK_SQL engine + doi operation language.

### Yeu cau
1. Them `PySparkRuntimeAdapter` implement `NotebookRuntimeAdapter`, dang ky vao
   `RuntimeAdapterRegistry` cho language PYTHON. Day la runtime DUY NHAT cho Python
   (khong co config chon runtime — cpython bi xoa o Task B).
2. Session: notebook Python DUNG CHUNG notebook-session/Kyuubi session voi SQL adapter
   (1 notebook mo 1 engine; SQL cell va Python cell cung mot SparkSession).
   - Python worker cua engine giu process ben vung theo session -> BIEN PYTHON PERSIST giua cac
     cell trong cung session (dung ngu nghia notebook). Restart session = xoa sach bien.
3. Execution: submit statement voi conf operation `kyuubi.operation.language=PYTHON`,
   payload = source cua cell. Parse ket qua tra ve cua python mode (moi dong JSON kieu jupyter:
   status ok/error, content text/plain, ename/evalue/traceback, mime bundle neu co) va map:
   - stdout/stderr  -> `AdapterOutput` mimeType=text/plain, outputType=STREAM (khop fix UI).
   - text/html      -> AdapterOutput text/html (vd `df._repr_html_()` cua pandas).
   - image/png      -> AdapterOutput image/png (base64).
   - error          -> execution FAILED, traceback day du vao outputs (stderr, mau do).
   - KHONG co rows kieu SQL -> AdapterResultPage rong (message "no rows" khong ap dung cho Python
     theo fix UI da giao).
4. Doc ky implementation python worker cua Kyuubi 1.10.3 trong source (module externals/engine
   spark, file execute_python / kyuubi python magic) de map DUNG format that — neu format thuc te
   khac mo ta tren thi theo SOURCE, khong theo spec nay.
5. Timeout/cancel: map cancel cua notebook execution -> cancel operation Kyuubi tuong ung.

### Matplotlib tren engine
6. Python worker cua engine hien KHONG chac bat figure matplotlib. Kiem tra source; neu chua co,
   patch worker (hoac wrap code cell) theo mau:

       import matplotlib; matplotlib.use("Agg")
       # sau khi exec cell:
       for num in plt.get_fignums():
           buf = io.BytesIO(); plt.figure(num).savefig(buf, format="png", bbox_inches="tight")
           emit image/png base64
       plt.close("all")

   Chi kich hoat khi import duoc matplotlib (ImportError -> bo qua im lang).

## 3. TASK B — XOA Python khoi Kyuubi server (code + image)

### 3.1 Xoa code CPython path
1. Xoa `CpythonRuntimeAdapter` (+ KernelProcess, ExecutionOutcome, PythonRuntimeContext neu chi
   phuc vu cpython) va resource kernel `python/kyuubi_notebook_kernel.py`.
2. Xoa feature python-environments phia server:
   - `PythonEnvironmentsResource`, `PythonPackageOperationsResource`, `PythonEnvironmentBuilder`,
     cac model/API class PythonEnvironment*, InstalledPackage, PythonPackageListView...
   - Schema SQL `notebook-python-schema-1.0.0.*` (migration: neu bang da ton tai trong SQLite cu
     thi BO QUA, khong drop — chi khong tao moi).
   - Cac config key `kyuubi.notebook.python.*` KHONG con dung (executable, venv.*, limit.*,
     package.*, work.dir, execution.timeout...) — xoa khoi NotebookConf. GIU LAI duy nhat cac key
     con nghia voi pyspark neu can (vd execution.timeout ap cho operation Kyuubi) — neu giu phai
     doi mo ta cho dung.
3. Xoa UI lien quan: nut/man "Python env", API client goi python-environments,
   `python-package-operations`. `runtime-specs` van tra PYTHON enabled khi engine kha dung,
   them truong `packagesSource: "spark-image"`.
4. Route/endpoint da xoa tra 404 mac dinh — dam bao KHONG con cho nao trong UI goi toi.

### 3.2 Xoa Python khoi image kyuubi-custom (Dockerfile trong repo)
1. Bo cac buoc lien quan Python trong Dockerfile: multi-stage COPY tu image python
   (/usr/local), pip install libs, ENV python. Image chi con JDK + Kyuubi + web-ui + jars.
2. Ket qua: image nho lai, khong con python3/pip trong container server. Them buoc kiem tra
   trong CI/build script (neu co):

       docker run --rm <image> sh -c "! command -v python3 && ! command -v pip3"

   (exit 0 = da sach python).
3. LUU Y cho nguoi van hanh (ghi vao docs, agent khong sua chart): chart dang co init container
   `prepare-python-packages` + mount emptyDir de `/usr/local` + block `pipConfig` + config
   `kyuubi.notebook.python.venv.system.site.packages=true` — sau khi image moi len, cac phan nay
   se duoc phia chart GO BO (nguoi khac lam, dong bo cung dot sync).

## 4. TASK C — Notebook store tren HDFS (giu nguyen tu spec truoc, tom tat)

Notebook/folder/cell/revision van dang SQLite per-pod -> phai chuyen shared. Chi tiet day du theo
spec `notebook_multi_replica.md` TASK A da giao; diem chinh:
1. Impl `FileSystemNotebookStore` sau trait `NotebookStore` (Hadoop FileSystem API, fs.defaultFS
   tu core-site da mount; UGI/kinit co san cua server).
2. Config: `kyuubi.notebook.store.class` (default JDBCNotebookStore — giu nguyen) va
   `kyuubi.notebook.store.fs.root` (vd hdfs:///user/kyuubi/notebooks).
3. Layout ipynb + meta.json + revisions + .trash (tai dung IpynbCodec); ghi atomic (tmp + rename);
   chong ghi dong thoi bang version compare-and-set -> 409; cache list TTL ~5s.
4. Migration mot lan tu SQLite (idempotent, khong xoa SQLite).

## 5. TASK D — Route notebook-session ve dung pod so huu

Kyuubi session (ca SQL lan Python) van do MOT pod server giu handle -> voi 2 replica van phai routing.
1. Ghi `kyuubiInstance` (host:port REST cua pod mo session) vao ban ghi notebook-session trong store.
2. Endpoint runtime (`/api/v1/notebook-sessions/*`, `/api/v1/executions/*`,
   `/api/v1/notebooks/{id}/executions`): khong phai owner -> proxy sang owner bang Jetty client
   voi config `kyuubi.frontend.rest.proxy.jetty.client.*` co san. Proxy phai:
   - Giu method/body/header/status; stream duoc endpoint /logs /outputs (khong buffer het).
   - Giu danh tinh nguoi dung (forward Authorization/cookie) — khong thanh duong vong xac thuc.
   - Header chong lap `X-Kyuubi-Proxied: true`; nhan header nay ma van khong phai owner -> 502.
3. Owner khong con trong ZooKeeper (pod chet) -> tra 409 + message "runtime da mat, hay Restart
   session"; UI hien nut Restart (mo session moi tren pod dang phuc vu).

## 6. Thay doi NGOAI repo (giao cho nguoi van hanh — agent chi xuat file huong dan)

Xuat file `docs/spark-image-python.md` trong repo voi noi dung chinh xac sau (nguoi build image
Spark ap dung — Dockerfile image Spark KHONG nam trong repo nay):

1. Python: giu python3.8 co san thi PIN version libs tuong thich 3.8:

       pip3 install --no-cache-dir "pandas==2.0.3" "numpy==1.24.4" \
           "pyarrow==16.1.0" "matplotlib==3.7.5"

   (Khuyen nghi hon: nang python3.10+ trong image roi dung ban libs moi — ghi ca 2 phuong an.)
2. ENV bat buoc trong image (driver va executor DUNG CHUNG image nen tu dong nhat):

       ENV PYSPARK_PYTHON=/usr/bin/python3
       ENV PYSPARK_DRIVER_PYTHON=/usr/bin/python3
       ENV MPLBACKEND=Agg

3. Ghi chu cho chart (nguoi khac ap dung, agent KHONG sua chart):
   - spark.sql.execution.arrow.pyspark.enabled=true
   - go init container prepare-python-packages + mount /usr/local + pipConfig +
     kyuubi.notebook.python.venv.system.site.packages (het tac dung khi server khong con python).
   - canh bao .toPandas() keo het du lieu ve driver — dung .limit() truoc khi ve bieu do.

## 7. Nghiem thu (2 replica; tat/ignore sessionAffinity khi test routing)

Python (pyspark):
1. Cell `print("hello")` -> thay hello (STREAM).
2. Cell `a = 1` roi cell sau `print(a)` -> in 1 (bien persist trong session).
3. Cell `spark.sql("select 1 as x").toPandas()` -> bang HTML pandas hien trong cell.
4. Cell matplotlib (plt.plot([1,2,3]); plt.show()) -> anh PNG hien trong cell.
5. Cell loi (1/0) -> execution FAILED, traceback do, khong treo.
6. Restart session -> bien mat sach; chay lai tu dau OK.

Go Python khoi server:
7. Image moi: `docker run --rm <image> sh -c "command -v python3"` -> khong tim thay.
8. Jar moi khong con CpythonRuntimeAdapter/PythonEnvironmentsResource:

       unzip -p kyuubi-server_*.jar 'org/apache/kyuubi/server/notebook/**' | strings \
         | grep -iE 'CpythonRuntimeAdapter|PythonEnvironmentsResource' -> RONG
       unzip -l kyuubi-server_*.jar | grep kyuubi_notebook_kernel.py -> RONG

9. UI khong con nut "Python env"; khong co request nao toi /python-environments (check Network tab).

Store + routing:
10. Tao notebook o pod A -> thay ngay tu pod B (20 lan lien tiep khong lech).
11. Ghi dong thoi 2 pod -> mot ben 409, khong mat du lieu.
12. Delete pod dang giu session -> API tra 409 "restart session", UI cho restart, notebook con nguyen.
13. `kyuubi.notebook.store.class` default (SQLite) -> hanh vi cu khong doi.

Kiem tra jar sau build:
    unzip -p kyuubi-server_*.jar 'org/apache/kyuubi/server/notebook/**' | strings | grep -iE 'PySparkRuntimeAdapter|FileSystemNotebookStore'

## 8. Giao hang

- Push image kyuubi-custom tag moi len registry (de phia deploy audit tu xa truoc khi sync).
- Kem file `docs/spark-image-python.md` (muc 6) trong commit.
- Thu tu lam khuyen nghi: TASK A (adapter) -> B (go python) -> C (store) -> D (routing).

--- HET FILE (Giao hang la muc cuoi) ---
