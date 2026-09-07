# Round 2: notebook bugs + new tasks (repo kyuubi-custom, branch notebook)

## 0. Context & MANDATORY packaging facts

- Currently running: kyuubi-custom `1.10.3-notebook-v1.3` + spark_img `3.5.5_v0`.
- DONE (verified on the image): PySparkRuntimeAdapter replaced CpythonRuntimeAdapter (removed),
  SQL cells render rows correctly, FAILED cells used to show tracebacks.
- **CRITICAL — packaging**: `python/execute_python.py` (the Python worker) lives inside
  `kyuubi-spark-sql-engine_2.12-1.10.3.jar`, which ships in the SPARK IMAGE (spark_img),
  NOT in the kyuubi-custom image. Any worker change (TASK 2, TASK 3, the engine part of BUG 1)
  only takes effect after building a new engine jar and packing it into a NEW SPARK IMAGE TAG.
  Rebuilding only kyuubi-custom (as in previous rounds) leaves %pip as UnknownMagic.
- **PyPI source**: the mirror address is SUPPLIED BY THE HELM CHART via spark confs
  `spark.kyuubi.notebook.pip.indexUrl` / `spark.kyuubi.notebook.pip.trustedHost`
  (operators fill these per environment; some environments use an internal mirror,
  others reach pypi.org directly and set NOTHING).
  **Hardcoding ANY mirror URL/IP in code or sample Dockerfiles is FORBIDDEN** — code may
  only read the confs; when a conf is absent, pip runs with its defaults.
  For image builds on internal machines, pass the mirror via build args, e.g.:
      ARG PIP_INDEX_URL=
      ARG PIP_TRUSTED_HOST=
      RUN pip3 install ${PIP_INDEX_URL:+--index-url $PIP_INDEX_URL} \
                       ${PIP_TRUSTED_HOST:+--trusted-host $PIP_TRUSTED_HOST} <packages>
  (empty build args -> pip defaults; concrete values are supplied at `docker build` time).
- The agent has NO cluster access. Do NOT touch the Helm chart (already done — see section 4).
- Task C (HDFS store) + Task D (session routing) from `notebook_pyspark.md` remain in force
  if not finished yet — do not duplicate them here.

## 1. BUG 1 — Python cell SUCCEEDED shows no stdout/output (FAILED does show)

Symptoms (screenshots from the live system):
- `print("hello")` -> SUCCEEDED, "Execution completed.", NO Output tab (0 outputs).
- `import numpy` (error) -> FAILED with traceback; in the latest build the Output tab
  also DISAPPEARED on FAILED cells -> check the error branch for regressions too.

Facts ALREADY VERIFIED (do not re-verify):
1. Worker `execute_python.py` (engine jar 1.10.3, spark image unmodified), lines 264-283:
   stdout + stderr + repr are MERGED into `content.data["text/plain"]` and returned via
   execute_reply_ok. print("hello") -> data["text/plain"] = "hello".
2. Engine `ExecutePython.scala`: resultSchema has 2 columns `output`, `status`;
   the server receives them through the rowset.
3. Server `PySparkResponseCodec` has bundleOutputs/errorOutputs and knows the keys
   content/data/text-plain/image/... yet the SUCCESS branch emits nothing.

Requirements:
1. Read ExecutePython.scala in the source to determine EXACTLY what the `output` column
   serializes (JSON of the data map vs. the full message) — fix PySparkResponseCodec
   according to the SOURCE.
2. On status=ok: fetch the operation rowset, parse the output column, emit:
   - data["text/plain"]        -> AdapterOutput text/plain (STREAM/TEXT, matching the UI filter)
   - data["image/png"]         -> image/png (the upstream worker already has the %matplot
                                  savefig magic)
   - data["application/json"]  -> application/json
   Note: the engine MERGES repr + stdout + stderr into one text/plain string (upstream
   limitation) -> rendering them as one text block is CORRECT.
3. Empty text/plain -> emit no empty output (keep "Execution completed.").
4. Regression guard: the FAILED branch must still show the traceback in the Output tab
   as in the first v1.3 build.

Acceptance: print("hello") -> "hello" is visible; `1+1` -> "2"; print + error in the same
cell -> both visible; failing import numpy -> traceback still visible.

## 2. TASK 2 — `%pip install` magic (session-scoped library install)

Context: the worker only has %json/%table/%matplot -> `%pip` raises UnknownMagic and
`!pip` raises SyntaxError (the worker runs plain exec(), not a shell) — matches the
current behavior exactly.

Requirements (patch `python/execute_python.py`):
1. Syntax: `%pip install pkg1 pkg2==1.2.3 ...` (install only; other subcommands -> clear error).
2. Execution: subprocess `[sys.executable, "-m", "pip", "install",
   "--disable-pip-version-check", "--target", <target_dir>] + packages`; return pip
   stdout+stderr as regular output (visible in the cell once BUG 1 is fixed).
3. SESSION SCOPE:
   - target_dir is private to the driver app (e.g. under cwd/spark.local.dir:
     `kyuubi-session-pip/`).
   - After a successful install: sys.path.insert(0, target_dir) once +
     importlib.invalidate_caches().
   - Engine death / session Restart -> gone with the driver pod. Do NOT use system
     site-packages.
4. Mirror: read spark confs `spark.kyuubi.notebook.pip.indexUrl` and
   `spark.kyuubi.notebook.pip.trustedHost` (passed down to the worker via env or argv by
   ExecutePython.scala — pick the least invasive way). When present -> append
   `--index-url` and `--trusted-host` to the pip command. When absent -> pip runs with
   defaults (must not crash).
   NOTE: an HTTP mirror without trusted-host will be rejected by pip — if only indexUrl
   is set, still run and let pip report the error; do NOT invent values.
5. NO proxy handling needed — the chart sets no proxy env; pip reaches the mirror directly.
6. pip timeout (default 300s, read spark conf `spark.kyuubi.notebook.pip.timeout` if set)
   -> on expiry kill the process + FAIL with a clear message.
7. pip errors -> execution FAILED with the full pip stdout/stderr (never swallow).
8. Cells starting with `!` -> error with the hint: "Shell commands are not supported.
   Use %pip install <packages> to install libraries."

Add to docs/spark-image-python.md:
- %pip installs on the DRIVER — executors (UDFs) will NOT see these libs; bake libs into
  the spark image if UDFs need them.
- The spark image needs a working `python3 -m pip` (apt python3.8 may lack python3-pip —
  add the install step).
- Baking libs at image build time can use the mirror via the build args shown in section 0.

Acceptance: %pip install <pkg> -> SUCCEEDED, pip log visible in the cell, next cell can
import it; session Restart -> the import fails again (correct session scope);
%pip install nonexistent-package -> FAILED with the full error; !pip -> hint to use %pip;
%pip freeze -> clear unsupported-syntax error.

## 3. TASK 3 — python worker resource limits (keep share.level=USER)

Context: DECISION — keep kyuubi.engine.share.level=USER, so all notebooks of one user
share ONE engine/driver. Multiple workers live on the same driver; one pandas cell can
eat all driver RAM -> OOM kills the whole engine -> the user's SQL dies too.
Each worker needs its own ceiling.

Requirements (patch the worker + the conf-passing part of ExecutePython.scala):
1. Read from the session's spark conf:
   - `spark.kyuubi.notebook.python.memory.limit`   (e.g. "4g"; unset = unlimited)
   - `spark.kyuubi.notebook.python.cpu.time.limit` (cumulative CPU seconds; unset = unlimited)
2. Apply via the `resource` module: RLIMIT_AS (memory), RLIMIT_CPU (cpu). Apply only when
   set; if setrlimit fails -> log a warning, do not crash.
3. On hitting the ceiling:
   - MemoryError inside a cell -> FAILED with message "MemoryError: python worker exceeded
     memory limit (<value>)"; the worker stays alive, subsequent cells keep working.
   - Worker killed -> the adapter detects the dead process, FAILS with "python worker was
     killed (resource limit exceeded?)" and AUTO-RESPAWNS the worker for the next
     execution (do not force a full session restart).
4. The %pip subprocess inherits the rlimits (fork default) — no extra handling.

Acceptance: limit=512m + `bytearray(10**9)` -> FAILED with MemoryError, next cell
`print("ok")` works; no limit set -> behaves as today; SQL cells in the same session
are unaffected.

## 4. Helm chart contract (ALREADY DONE — use these exact CONF NAMES, do not invent)

The chart renders the following spark confs for the engine. VALUES are filled by operators
per environment — code reads by conf NAME only, never hardcodes values, and must tolerate
MISSING confs (missing = skip the corresponding flag, never crash):

    spark.kyuubi.notebook.pip.indexUrl             (optional — pip index URL)
    spark.kyuubi.notebook.pip.trustedHost          (optional — goes with indexUrl for HTTP mirrors)
    spark.kyuubi.notebook.pip.timeout              (optional — seconds, default 300)
    spark.kyuubi.notebook.python.memory.limit      (optional — operators add at deploy time)
    spark.kyuubi.notebook.python.cpu.time.limit    (optional — operators add at deploy time)

There is NO proxy env (HTTP_PROXY/HTTPS_PROXY) — do not expect one. The server-pod
pip.conf, the separate python image, and the server venv have been REMOVED from the chart
(the server no longer runs python) — ignore them entirely, do not recreate them.

## 5. Delivery — 2 IMAGES, mandatory order

1. Build the kyuubi source -> NEW `kyuubi-spark-sql-engine_2.12-1.10.3.jar` (TASK 2+3 and
   the engine part of BUG 1 live in the engine jar; the codec part of BUG 1 lives in the
   kyuubi-server jar — both come from the same source tree).
2. NEW SPARK IMAGE tag (e.g. 3.5.5_v1): COPY the new engine jar into /opt/spark/jars/
   (replace the old one) + bake libs (numpy/pandas/pyarrow/matplotlib pinned for py3.8,
   via the build args from section 0) + ensure python3-pip + ENV PYSPARK_PYTHON,
   PYSPARK_DRIVER_PYTHON, MPLBACKEND=Agg.
3. NEW kyuubi-custom image tag (e.g. v1.4): contains the server-side BUG 1 fix (codec) +
   any other server/UI changes from this round.
4. PUSH BOTH tags to the registry (10.60.129.132:8890) — so the deploy side can audit
   remotely before syncing.
5. Report both tags. The deploy side updates the values (spark container.image + kyuubi
   image + section-4 confs) and syncs once.

--- END OF FILE (Delivery is the last section) ---
