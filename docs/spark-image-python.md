<!--
- Licensed to the Apache Software Foundation (ASF) under one or more
- contributor license agreements.  See the NOTICE file distributed with
- this work for additional information regarding copyright ownership.
- The ASF licenses this file to You under the Apache License, Version 2.0
- (the "License"); you may not use this file except in compliance with
- the License.  You may obtain a copy of the License at
-
-   http://www.apache.org/licenses/LICENSE-2.0
-
- Unless required by applicable law or agreed to in writing, software
- distributed under the License is distributed on an "AS IS" BASIS,
- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
- See the License for the specific language governing permissions and
- limitations under the License.
-->

# Python in the Spark engine image

Notebook Python cells run **inside the Spark engine**, on the driver, through the worker Kyuubi
already ships (`kyuubi.operation.language=PYTHON`). The Kyuubi server itself no longer contains a
Python interpreter, so everything a notebook can import has to be present in the **Spark image**.

That image is not built from this repository. This file is the contract its builder has to meet.

## 1. Interpreter and libraries

The current image has `/usr/bin/python3` = **Python 3.8** from apt, with no scientific stack.
Either option below works; the second is preferred.

**Option A - keep 3.8.** The last releases that still support it must be pinned explicitly, or pip
will resolve to versions that refuse to install:

```bash
pip3 install --no-cache-dir "pandas==2.0.3" "numpy==1.24.4" "pyarrow==16.1.0" "matplotlib==3.7.5"
```

**Option B - move to Python 3.10 or newer** (recommended) and drop the pins:

```bash
pip3 install --no-cache-dir pandas numpy pyarrow matplotlib
```

**`python3 -m pip` must work in the image.** The apt `python3` package does not always bring
`python3-pip`, and without it the `%pip` magic can only report that pip is missing:

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends python3-pip
```

**Installing behind an internal mirror.** Pass the address at build time rather than writing it
into the Dockerfile, so the same file works in every environment:

```dockerfile
ARG PIP_INDEX_URL=
ARG PIP_TRUSTED_HOST=
RUN pip3 install --no-cache-dir \
      ${PIP_INDEX_URL:+--index-url $PIP_INDEX_URL} \
      ${PIP_TRUSTED_HOST:+--trusted-host $PIP_TRUSTED_HOST} \
      pandas numpy pyarrow matplotlib
```

Empty build args fall back to pip's defaults, which is what an environment that reaches the index
directly wants.

Option B is worth the rebuild: 3.8 is end-of-life, and every pin above is a version that will stop
receiving fixes before the others do.

`pyspark` itself must **not** be installed with pip. The engine puts `$SPARK_HOME/python` on the
path, and a pip-installed `pyspark` of a different version silently shadows it.

## 2. Environment variables

Driver and executors run the same image, so setting these once keeps both sides on one interpreter
— a mismatch fails the job at the first UDF rather than at build time:

```dockerfile
ENV PYSPARK_PYTHON=/usr/bin/python3
ENV PYSPARK_DRIVER_PYTHON=/usr/bin/python3
ENV MPLBACKEND=Agg
```

`MPLBACKEND=Agg` matters because the driver has no display; without it matplotlib picks an
interactive backend and the first plot call raises.

## 3. Notes for whoever owns the Helm chart

These are chart-side, deliberately not changed in this repository.

- **Enable Arrow** so `toPandas()` does not go row by row:
  `spark.sql.execution.arrow.pyspark.enabled=true`
- **Remove the Python plumbing aimed at the server.** It has no effect now that the server has no
  interpreter: the `prepare-python-packages` init container, the emptyDir mounted over
  `/usr/local`, the `pipConfig` block, and
  `kyuubi.notebook.python.venv.system.site.packages`.
- **Warn users about `.toPandas()`.** It pulls the whole result into the driver. Tell people to
  `.limit()` first, especially before plotting.

## 4. What a notebook user can rely on

Behaviour below follows from the engine's worker (`execute_python.py`), not from configuration:

- **Variables persist across cells** in one notebook session, because the worker is a process the
  engine keeps for the life of the session. Restarting the session is what clears them.
- **`spark` is already bound.** No `SparkSession.builder` needed.
- **`print()` output and the last expression's value both arrive as one text block.** The worker
  merges stdout and stderr into `text/plain` before the server sees them, so a notebook cannot
  colour stderr differently for a Python cell - that distinction is lost in the engine, not here.
- **HTML rendering works** for anything exposing `_repr_html_`, such as a pandas DataFrame.
- **`%pip install <packages>` installs for this session only.** The packages land in a directory
  under the driver's working directory, not in site-packages, so a session restart or a lost
  driver takes them with it. Install order matters only within the session that ran it.
- **`%pip` installs on the DRIVER only.** Executors never see those packages, so a UDF that
  imports one will fail at runtime even though the same import works in a cell. Anything a UDF
  needs has to be baked into this image.
- **Only `install` is accepted.** `%pip freeze`, `%pip uninstall` and friends return an
  unsupported-syntax message; `!pip ...` returns a pointer to `%pip`, because the worker runs
  `exec()` and has no shell.
- **The mirror comes from the deployment**, through the spark confs
  `spark.kyuubi.notebook.pip.indexUrl` and `spark.kyuubi.notebook.pip.trustedHost` (plus
  `spark.kyuubi.notebook.pip.timeout`, default 300s). When they are unset pip uses its own
  defaults. An `http://` index with no trusted host is refused by pip itself, and the cell shows
  pip's own message.
- **Plots need the magic.** The worker captures a figure when the cell ends with

  ```python
  %matplot plt
  ```

  A bare `plt.show()` produces nothing, because `Agg` draws to a buffer nobody reads. If automatic
  capture is wanted instead, it belongs in the engine's worker, not in the notebook server.

