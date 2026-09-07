# Task: build & ship the Spark engine image — %pip magic + Python 3.11 + baked libraries

## 0. Context & resolved facts (read first — these are VERIFIED, do not re-check)

- Repo: `https://github.com/J3rryTran/kyuubi-custom.git`, branch `notebook`. Base Kyuubi 1.10.3.
- **The engine/worker code is ALREADY COMMITTED on this branch** (commit `5217cb3`, verified:
  `magic_pip`, `_apply_resource_limits`, `reject_shell_commands` all present in
  `externals/kyuubi-spark-sql-engine/src/main/resources/python/execute_python.py`, and
  `ExecutePython.scala` passes the `spark.kyuubi.notebook.pip.*` /
  `spark.kyuubi.notebook.python.*` confs to the worker via env).
  **DO NOT re-implement anything from `notebook_round2.md`.** This task is:
  build the jar → build the image → push. Plus the Python upgrade below.
- The registry `10.60.129.132:8890` still has only the ORIGINAL `spark_img:3.5.5_v0`
  (apt python3.8, no pip, no libs, old engine jar). That is why `%pip` raises
  `UnknownMagic` on the live system today. Rebuilding kyuubi-custom cannot fix it.
- The agent has NO cluster access. Do NOT touch the Helm chart. Deploy side audits the
  pushed image, then wires `spark.kubernetes.container.image` and syncs.
- Start from a committed green state. Commit in logical chunks. An unpushed local image
  counts as NOT delivered (this exact failure happened last round).

## 1. TASK 1 — build the engine jar (no code changes)

```bash
./build/mvn clean install -pl externals/kyuubi-spark-sql-engine -DskipTests -Dspark.version=3.5.5
```

- `clean` is MANDATORY — incremental builds have shipped stale classes in this repo before.
- Verify the jar BEFORE it goes anywhere near an image:

```bash
unzip -p externals/kyuubi-spark-sql-engine/target/kyuubi-spark-sql-engine_2.12-1.10.3.jar \
  python/execute_python.py | grep -c -e magic_pip -e _apply_resource_limits -e reject_shell_commands
# expected: 6
```

If the count is not 6, STOP and report — do not build an image from a wrong jar.

## 2. TASK 2 — the Spark image: Python 3.11 + baked libraries + new engine jar

### 2.1 Python version — pinned in exactly ONE place

```dockerfile
ARG PYTHON_VERSION=3.11
```

Install that CPython (deadsnakes on ubuntu focal, or official tarball — whatever works on
the base image) **plus a working `python -m pip` for it**. Target 3.11.x latest micro;
do NOT go to 3.12/3.13 (PySpark 3.5 ceiling).

```dockerfile
ENV PYSPARK_PYTHON=/usr/local/bin/python3.11
ENV PYSPARK_DRIVER_PYTHON=/usr/local/bin/python3.11
ENV MPLBACKEND=Agg
```

Driver and executors share this image, so the version-match law of PySpark is satisfied
by construction.

### 2.2 Package indexes — build args ONLY, nothing environment-specific in the file

```dockerfile
ARG PIP_INDEX_URL=
ARG PIP_TRUSTED_HOST=
ARG PIP_EXTRA_INDEX_URLS=
```

- `PIP_INDEX_URL` / `PIP_TRUSTED_HOST`: the operator's mirror (e.g. internal Nexus).
  Empty = pip defaults. **Hardcoding any internal mirror URL/IP is FORBIDDEN.**
- `PIP_EXTRA_INDEX_URLS`: space-separated extra indexes, expanded to repeated
  `--extra-index-url` flags. Exists because the GPU variant (section 3) needs vendor
  indexes; the CPU build leaves it empty. Extra indexes live HERE, never inside the
  requirements file — a requirements file must not decide where packages come from.

```dockerfile
COPY python-requirements.txt /tmp/python-requirements.txt
RUN EXTRA=""; for u in ${PIP_EXTRA_INDEX_URLS}; do EXTRA="$EXTRA --extra-index-url $u"; done; \
    python3.11 -m pip install --no-cache-dir \
      ${PIP_INDEX_URL:+--index-url $PIP_INDEX_URL} \
      ${PIP_TRUSTED_HOST:+--trusted-host $PIP_TRUSTED_HOST} \
      $EXTRA \
      -r /tmp/python-requirements.txt
```

### 2.3 `python-requirements.txt` — CPU baseline (single source of truth, commit to repo)

```
# Python runtime target: CPython 3.11.x — the notebook baseline every session sees.
# Indexes are NOT configured here; they come from docker build args.

# Core scientific/data stack
numpy==2.5.1
pandas==3.0.5
scipy==1.17.1
pyarrow==25.0.0
polars==1.42.1

# Distributed / big data (CPU)
dask==2026.7.1
ray==2.56.1

# Visualization
matplotlib==3.11.1
seaborn==0.13.2
plotly==6.9.0
plotnine==0.15.7
hvplot==0.12.2
altair==6.2.2
vega-datasets==0.9.0

# Data loading / SQL
pandasql==0.7.3
intake==2.0.9
intake-parquet==0.3.0
intake-xarray==2.0.0

# HTTP / API
requests==2.34.2
grpcio==1.82.1
```

**Removed from the draft list, deliberately — do NOT add back:**

- `pyspark==3.5.5` — FORBIDDEN. The engine puts `$SPARK_HOME/python` on the path; a
  pip-installed pyspark of even a slightly different build silently shadows it. This rule
  is already documented in `docs/spark-image-python.md`.
- `jupyter==1.1.1` — the notebook worker is not Jupyter; this metapackage drags in an
  entire notebook server stack for zero function on the driver. Add individual libs
  (e.g. `ipython`) later only with a stated reason.
- The GPU block (`cupy/cudf/cuml/cugraph/torch`) — moved to section 3; it multiplies
  image size several times and is useless without cluster GPUs.

**Version-resolution policy**: these pins are the requester's. If pip resolution fails on
any pin (version does not exist on the mirror), STOP, report the failing package with the
versions the mirror actually offers, and wait — do NOT silently substitute a different
version. The pins file is a contract; the build failing loudly is it working.

### 2.4 Engine jar — exactly one, the new one

```dockerfile
# Remove the stock engine jar first, then place ours: two engine jars on the classpath
# is a coin-flip at runtime.
RUN rm -f /opt/spark/jars/kyuubi-spark-sql-engine_*.jar
COPY kyuubi-spark-sql-engine_2.12-1.10.3.jar /opt/spark/jars/
```

### 2.5 Build-time smoke test — the build MUST fail if the env is broken

```dockerfile
RUN python3.11 -c "import sys; assert sys.version_info[:2]==(3,11), sys.version; \
    import numpy, pandas, scipy, pyarrow, polars, matplotlib, seaborn, plotly, requests; \
    print('py-env ok', sys.version)"
RUN python3.11 -m pip --version
RUN sh -c 'ls /opt/spark/jars/kyuubi-spark-sql-engine_*.jar | wc -l | grep -qx 1'
RUN unzip -p /opt/spark/jars/kyuubi-spark-sql-engine_*.jar python/execute_python.py \
    | grep -q magic_pip
```

Only CPU-safe imports here. Never import GPU packages in a docker build — there is no
CUDA driver on any build machine, and e.g. cudf fails at import without one.

## 3. TASK 3 (GATED — skip unless explicitly told D1=yes) — GPU variant

**Decision D1 (owner: deploy side): does the cluster schedule GPUs for Spark pods?**
Until answered yes, do NOT build this. The GPU wheels are multi-GB, need vendor indexes
(`https://pypi.nvidia.com`, `https://download.pytorch.org/whl/cu126` — passed via
`PIP_EXTRA_INDEX_URLS`, or Nexus proxies of them), need a CUDA-enabled base image, and at
runtime need `nvidia.com/gpu` resources on driver/executor pods. Without all of that they
are dead weight that only slows every image pull.

If D1=yes: separate file `python-requirements-gpu.txt`
(`cupy-cuda12x==14.1.1`, `cudf-cu12==26.6.0`, `cuml-cu12==26.6.0`,
`cugraph-cu12==26.6.0`, `torch==2.13.0`), separate tag `3.5.5-py311-gpu-r1`, smoke test still CPU-safe imports
only (torch import is safe without a GPU; cudf import is NOT — leave it to a runtime
canary cell).

## 4. Tag policy (mandatory)

- CPU tag: **`3.5.5-py311-r1`**. Any rebuild bumps `-rN`. NEVER overwrite a pushed tag.
- `3.5.5_v0` stays on the registry untouched — it is the rollback target.

## 5. Acceptance (local, no cluster)

1. `docker run --rm <tag> python3.11 -c "import sys; print(sys.version)"` → 3.11.x
2. `docker run --rm <tag> python3.11 -c "import pandas,numpy,pyarrow; print(pandas.__version__, numpy.__version__, pyarrow.__version__)"` → the pinned versions.
3. Jar check inside the image:
   ```bash
   docker run --rm <tag> sh -c \
     "unzip -p /opt/spark/jars/kyuubi-spark-sql-engine_*.jar python/execute_python.py \
      | grep -c -e magic_pip -e _apply_resource_limits -e reject_shell_commands"
   ```
   → 6, and exactly one `kyuubi-spark-sql-engine_*.jar` under `/opt/spark/jars/`.
4. Worker syntax: `docker run --rm <tag> python3.11 -m py_compile` on the extracted
   `execute_python.py` → exit 0. (Behavioural checks — %pip install/session scope/timeout/
   `!pip` hint — are the deploy side's live-cluster checklist; there is no python unit-test
   harness in the maven build, do not invent one for this task.)

## 6. Delivery

1. Commit: `python-requirements.txt` (+ the Dockerfile if it lives in this repo — if the
   spark image Dockerfile is external, commit the requirements file and update
   `docs/spark-image-python.md` with the full Dockerfile fragment above so the contract
   is versioned here).
2. Update `docs/spark-image-python.md`: Python 3.11 replaces the old 3.8 guidance
   (drop the 3.8 pin table); %pip remains driver-only; version changes = edit
   `ARG PYTHON_VERSION` + pins, cut a new immutable tag; document `PIP_EXTRA_INDEX_URLS`.
3. Build jar (section 1) → build image → **PUSH `3.5.5-py311-r1` to
   `10.60.129.132:8890`**. No kyuubi-custom rebuild is needed — nothing server-side
   changes in this task.
4. Report: pushed tag + output of acceptance checks 1–4. Deploy side then updates
   `spark.kubernetes.container.image` and syncs; engines pick it up after idle restart.

## 7. Known risks the deploy side accepts by approving these pins

- `pandas==3.0.5` with PySpark 3.5: `toPandas()` / pandas-UDF paths were built against
  pandas 2.x. First canary cell after deploy must exercise
  `spark.sql("select 1").toPandas()`; if it breaks, the fallback is repinning pandas to
  `2.2.*` and cutting `-r2`.
- `intake==2.0.9` vs `intake-parquet==0.3.0`: the intake 2.x API split older plugins;
  resolution may conflict. Same policy as 2.3: report, don't substitute.

--- END OF FILE (Delivery is the last section) ---
