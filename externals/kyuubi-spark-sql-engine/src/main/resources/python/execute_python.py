#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import ast
import datetime
import decimal
import importlib
import io
import json

import os
import re
import subprocess
import sys
import traceback
import base64
from glob import glob

if sys.version_info >= (3, 8):
    from ast import Module
else:
    from ast import Module as OriginalModule
    Module = lambda nodelist, type_ignores: OriginalModule(nodelist)

try:
    import resource
except ImportError:
    resource = None

if sys.version_info[0] < 3:
    sys.exit("Python < 3 is unsupported.")

os.environ["PYSPARK_PYTHON"] = os.environ.get("PYSPARK_PYTHON", sys.executable)

def _refresh_session_pip_path():
    """Expose packages installed by any notebook session on this shared engine.

    A worker can have started before another notebook runs ``%pip install``. Refreshing before
    each request makes the driver's shared, session-scoped package directory visible without a
    Spark-engine restart. The directory remains ephemeral; durable packages are promoted by the
    server only for a later PVC-backed engine generation.
    """
    session_pip_dir = os.path.join(os.getcwd(), "kyuubi-session-pip")
    if os.path.exists(session_pip_dir):
        if session_pip_dir in sys.path:
            sys.path.remove(session_pip_dir)
        sys.path.insert(0, session_pip_dir)
        importlib.invalidate_caches()


_refresh_session_pip_path()

# add pyspark to sys.path

if "pyspark" not in sys.modules:
    # try to get PY4J_PATH and use it directly if not none
    py4j_path = os.environ.get("PY4J_PATH")
    if py4j_path is not None:
        sys.path[:0] = sys_path = [py4j_path]
    else:
        spark_home = os.environ.get("SPARK_HOME", "")
        spark_python = os.path.join(spark_home, "python")
        try:
            py4j = glob(os.path.join(spark_python, "lib", "py4j-*.zip"))[0]
        except IndexError:
            raise Exception(
                "Unable to find py4j in {}, your SPARK_HOME may not be configured correctly".format(
                    spark_python
                )
            )
        sys.path[:0] = sys_path = [spark_python, py4j]
else:
    sys_path = None

import kyuubi_util


TOP_FRAME_REGEX = re.compile(r'\s*File "<stdin>".*in <module>')

global_dict = {}

MAGIC_ENABLED = os.environ.get("MAGIC_ENABLED") == "true"


class NormalNode(object):
    def __init__(self, code):
        self.code = compile(code, "<stdin>", "exec", ast.PyCF_ONLY_AST, 1)

    def execute(self):
        to_run_exec, to_run_single = self.code.body[:-1], self.code.body[-1:]

        try:
            for node in to_run_exec:
                mod = Module([node], [])
                code = compile(mod, "<stdin>", "exec")
                exec(code, global_dict)

            for node in to_run_single:
                mod = ast.Interactive([node])
                code = compile(mod, "<stdin>", "single")
                exec(code, global_dict)
        except Exception:
            raise ExecutionError(sys.exc_info())


class UnknownMagic(Exception):
    pass


class PipError(Exception):
    """Raised for anything %pip refuses or pip itself reports, so the cell names the real cause."""
    pass


MEMORY_LIMIT = os.environ.get("KYUUBI_NOTEBOOK_PY_MEMORY_LIMIT", "").strip()
CPU_TIME_LIMIT = os.environ.get("KYUUBI_NOTEBOOK_PY_CPU_TIME_LIMIT", "").strip()


def _parse_memory(value):
    text = value.lower().rstrip("b")
    units = {"k": 1024, "m": 1024**2, "g": 1024**3, "t": 1024**4}
    multiplier = units.get(text[-1:], 1)
    if multiplier != 1:
        text = text[:-1]
    return int(float(text) * multiplier)


def _apply_resource_limits():
    """Caps this worker so one cell cannot take the whole driver down with it.

    All notebooks of a user share one engine, so an unbounded pandas frame here would OOM the
    driver and kill that user's SQL as well. A limit that cannot be applied is reported and
    ignored rather than fatal: refusing to start would be a worse outcome than running uncapped.
    """
    if resource is None:
        return
    if MEMORY_LIMIT:
        try:
            limit = _parse_memory(MEMORY_LIMIT)
            resource.setrlimit(resource.RLIMIT_AS, (limit, limit))
        except (ValueError, OSError) as e:
            print(
                "WARN: could not apply memory limit %r: %s" % (MEMORY_LIMIT, e),
                file=sys.stderr,
            )
    if CPU_TIME_LIMIT:
        try:
            seconds = int(float(CPU_TIME_LIMIT))
            resource.setrlimit(resource.RLIMIT_CPU, (seconds, seconds))
        except (ValueError, OSError) as e:
            print(
                "WARN: could not apply cpu time limit %r: %s" % (CPU_TIME_LIMIT, e),
                file=sys.stderr,
            )


_apply_resource_limits()


class MagicNode(object):
    def __init__(self, line):
        parts = line[1:].split(" ", 1)
        if len(parts) == 1:
            self.magic, self.rest = parts[0], ()
        else:
            self.magic, self.rest = parts[0], (parts[1],)

    def execute(self):
        if not self.magic:
            raise UnknownMagic("magic command not specified")

        try:
            handler = magic_router[self.magic]
        except KeyError:
            raise UnknownMagic("unknown magic command '%s'" % self.magic)

        try:
            return handler(*self.rest)
        except ExecutionError as e:
            raise e
        except Exception:
            exc_type, exc_value, tb = sys.exc_info()
            raise ExecutionError((exc_type, exc_value, None))


class ExecutionError(Exception):
    def __init__(self, exc_info):
        self.exc_info = exc_info


class UnicodeDecodingStringIO(io.StringIO):
    def write(self, s):
        if isinstance(s, bytes):
            s = s.decode("utf-8")
        super(UnicodeDecodingStringIO, self).write(s)


def clearOutputs():
    sys.stdout.close()
    sys.stderr.close()
    sys.stdout = UnicodeDecodingStringIO()
    sys.stderr = UnicodeDecodingStringIO()


def reject_shell_commands(code):
    """`!pip install x` is a notebook habit this worker cannot serve.

    The worker runs plain `exec()`, never a shell, so such a line is only ever a SyntaxError
    pointing at the `!`. Saying what to use instead is far more useful than the parser's message.
    """
    for line in code.split("\n"):
        if line.lstrip().startswith("!"):
            raise SyntaxError(
                "Shell commands are not supported. "
                "Use %pip install <packages> to install libraries."
            )


def parse_code_into_nodes(code):
    reject_shell_commands(code)
    nodes = []
    try:
        nodes.append(NormalNode(code))
    except SyntaxError:
        # It's possible we hit a syntax error because of a magic command. Split the code groups
        # of 'normal code', and code that starts with a '%'. possibly magic code lines, and see
        # if any of the lines. Remove lines until we find a node that parses, then check if the
        # next line is a magic line.

        # Split the code into chunks of normal code, and possibly magic code, which starts with
        # a '%'.

        normal = []
        chunks = []
        for i, line in enumerate(code.rstrip().split("\n")):
            if line.startswith("%"):
                if normal:
                    chunks.append("\n".join(normal))
                    normal = []

                chunks.append(line)
            else:
                normal.append(line)

        if normal:
            chunks.append("\n".join(normal))

        # Convert the chunks into AST nodes. Let exceptions propagate.
        for chunk in chunks:
            if MAGIC_ENABLED and chunk.startswith("%"):
                nodes.append(MagicNode(chunk))
            else:
                nodes.append(NormalNode(chunk))

    return nodes


def execute_reply(status, content):
    return {
        "msg_type": "execute_reply",
        "content": dict(
            content,
            status=status,
        ),
    }


def execute_reply_ok(data):
    return execute_reply(
        "ok",
        {
            "data": data,
        },
    )


def execute_reply_error(exc_type, exc_value, tb):
    formatted_tb = traceback.format_exception(exc_type, exc_value, tb, chain=False)
    for i in range(len(formatted_tb)):
        if TOP_FRAME_REGEX.match(formatted_tb[i]):
            formatted_tb = formatted_tb[:1] + formatted_tb[i + 1 :]
            break

    evalue = str(exc_value)
    if exc_type is MemoryError and MEMORY_LIMIT:
        # A bare MemoryError leaves the user guessing whether the driver is out of memory or
        # their own cell hit a ceiling somebody configured. Name the ceiling.
        evalue = "python worker exceeded memory limit (%s)" % MEMORY_LIMIT

    return execute_reply(
        "error",
        {
            "ename": str(exc_type.__name__),
            "evalue": evalue,
            "traceback": formatted_tb,
        },
    )


def execute_reply_internal_error(message, exc_info=None):
    return execute_reply(
        "error",
        {
            "ename": "InternalError",
            "evalue": message,
            "traceback": [],
        },
    )


def execute_request(content):
    _refresh_session_pip_path()
    try:
        code = content["code"]
    except KeyError:
        return execute_reply_internal_error(
            'Malformed message: content object missing "code"', sys.exc_info()
        )

    try:
        nodes = parse_code_into_nodes(code)
    except SyntaxError:
        exc_type, exc_value, tb = sys.exc_info()
        return execute_reply_error(exc_type, exc_value, None)

    result = None

    try:
        for node in nodes:
            result = node.execute()
    except UnknownMagic:
        exc_type, exc_value, tb = sys.exc_info()
        return execute_reply_error(exc_type, exc_value, None)
    except ExecutionError as e:
        return execute_reply_error(*e.exc_info)

    if result is None:
        result = {}

    # A normal notebook cell commonly ends with ``plt.show()``. Kyuubi's Python protocol is a
    # MIME bundle rather than an interactive display transport, so capture the latest open figure
    # as a PNG before returning the bundle. This is deliberately best-effort: matplotlib is an
    # optional package and a non-plotting cell must keep exactly its existing behaviour.
    if "image/png" not in result:
        result.update(_capture_matplotlib_figure())

    stdout = sys.stdout.getvalue()
    stderr = sys.stderr.getvalue()

    clearOutputs()

    output = result.pop("text/plain", "")

    if stdout:
        output += stdout

    if stderr:
        output += stderr

    output = output.rstrip()

    # Only add the output if it exists, or if there are no other mimetypes in the result.
    if output or not result:
        result["text/plain"] = output.rstrip()

    return execute_reply_ok(result)


def _capture_matplotlib_figure():
    """Return the latest matplotlib figure as a notebook image, if one was created.

    The response protocol holds one value per MIME type, so a cell with several figures exposes
    the latest figure. Closing figures after capture prevents stale plots from appearing again in
    later cells and releases Driver memory.
    """
    try:
        import matplotlib

        # A Spark Driver is normally headless. Set a non-interactive backend before pyplot is
        # imported; if the user already imported pyplot, retain their selected backend instead.
        if "matplotlib.pyplot" not in sys.modules:
            matplotlib.use("Agg", force=True)
        import matplotlib.pyplot as plt

        figure_numbers = plt.get_fignums()
        if not figure_numbers:
            return {}
        figure = plt.figure(figure_numbers[-1])
        image = io.BytesIO()
        figure.savefig(image, format="png", bbox_inches="tight")
        encoded = base64.b64encode(image.getvalue())
        if sys.version_info[0] >= 3:
            encoded = encoded.decode("ascii")
        plt.close("all")
        return {"image/png": encoded}
    except ImportError:
        return {}
    except Exception:
        # Rendering must not turn an otherwise successful Python cell into a failure. The user
        # still receives stdout/stderr and can inspect a plotting-library error explicitly.
        return {}


def magic_table_convert(value):
    try:
        converter = magic_table_types[type(value)]
    except KeyError:
        converter = magic_table_types[str]

    return converter(value)


def magic_table_convert_seq(items):
    last_item_type = None
    converted_items = []

    for item in items:
        item_type, item = magic_table_convert(item)

        if last_item_type is None:
            last_item_type = item_type
        elif last_item_type != item_type:
            raise ValueError("value has inconsistent types")

        converted_items.append(item)

    return "ARRAY_TYPE", converted_items


def magic_table_convert_map(m):
    last_key_type = None
    last_value_type = None
    converted_items = {}

    for key, value in m.items():
        key_type, key = magic_table_convert(key)
        value_type, value = magic_table_convert(value)

        if last_key_type is None:
            last_key_type = key_type
        elif last_value_type != value_type:
            raise ValueError("value has inconsistent types")

        if last_value_type is None:
            last_value_type = value_type
        elif last_value_type != value_type:
            raise ValueError("value has inconsistent types")

        converted_items[key] = value

    return "MAP_TYPE", converted_items


magic_table_types = {
    type(None): lambda x: ("NULL_TYPE", x),
    bool: lambda x: ("BOOLEAN_TYPE", x),
    int: lambda x: ("INT_TYPE", x),
    float: lambda x: ("DOUBLE_TYPE", x),
    str: lambda x: ("STRING_TYPE", str(x)),
    datetime.date: lambda x: ("DATE_TYPE", str(x)),
    datetime.datetime: lambda x: ("TIMESTAMP_TYPE", str(x)),
    decimal.Decimal: lambda x: ("DECIMAL_TYPE", str(x)),
    tuple: magic_table_convert_seq,
    list: magic_table_convert_seq,
    dict: magic_table_convert_map,
}


def magic_table(name):
    try:
        value = global_dict[name]
    except KeyError:
        exc_type, exc_value, tb = sys.exc_info()
        raise ExecutionError((exc_type, exc_value, None))

    if not isinstance(value, (list, tuple)):
        value = [value]

    headers = {}
    data = []

    for row in value:
        cols = []
        data.append(cols)

        if "Row" == row.__class__.__name__:
            row = row.asDict()

        if not isinstance(row, (list, tuple, dict)):
            row = [row]

        if isinstance(row, (list, tuple)):
            iterator = enumerate(row)
        else:
            iterator = sorted(row.items())

        for name, col in iterator:
            col_type, col = magic_table_convert(col)

            try:
                header = headers[name]
            except KeyError:
                header = {
                    "name": str(name),
                    "type": col_type,
                }
                headers[name] = header
            else:
                # Reject columns that have a different type. (allow none value)
                if col_type != "NULL_TYPE" and header["type"] != col_type:
                    if header["type"] == "NULL_TYPE":
                        header["type"] = col_type
                    else:
                        exc_type = Exception
                        exc_value = Exception("table rows have different types")
                        raise ExecutionError((exc_type, exc_value, None))

            cols.append(col)

    headers = [v for k, v in sorted(headers.items())]

    return {
        "application/vnd.livy.table.v1+json": {
            "headers": headers,
            "data": data,
        }
    }


def magic_json(name):
    try:
        value = global_dict[name]
    except KeyError:
        exc_type, exc_value, tb = sys.exc_info()
        raise ExecutionError((exc_type, exc_value, None))

    return {
        "application/json": value,
    }


def magic_matplot(name):
    try:
        value = global_dict[name]
        fig = value.gcf()
        imgdata = io.BytesIO()
        fig.savefig(imgdata, format="png")
        imgdata.seek(0)
        encode = base64.b64encode(imgdata.getvalue())
        if sys.version >= "3":
            encode = encode.decode()

    except:
        exc_type, exc_value, tb = sys.exc_info()
        raise ExecutionError((exc_type, exc_value, None))

    return {
        "image/png": encode,
    }


def _pip_target_dir():
    """Where %pip installs land.

    Kept under the driver's working directory on purpose: it dies with the driver pod. A
    notebook-session restart can reuse the same driver, so its new Python worker must discover
    this directory too. Nothing reaches the interpreter's own site-packages, preventing package
    leakage to a different Engine Profile or user.
    """
    target = os.path.join(os.getcwd(), "kyuubi-session-pip")
    os.makedirs(target, exist_ok=True)
    return target


def _pip_timeout():
    raw = os.environ.get("KYUUBI_NOTEBOOK_PIP_TIMEOUT", "").strip()
    try:
        return int(float(raw)) if raw else 300
    except ValueError:
        return 300


def magic_pip(rest=""):
    """`%pip install`, `%pip uninstall` or `%pip list` for the current session."""
    args = rest.split()
    if not args:
        raise PipError(
            "%pip requires a subcommand. Use: %pip install <packages>, %pip uninstall <packages> or %pip list")
    subcommand = args[0]
    if subcommand not in ("install", "uninstall", "list"):
        raise PipError(
            "Only '%%pip install', '%%pip uninstall' and '%%pip list' are supported, not '%%pip %s'. "
            "Use: %%pip install <packages>, %%pip uninstall <packages> or %%pip list" % subcommand)

    target = _pip_target_dir()

    if subcommand == "list":
        command = [
            sys.executable,
            "-m",
            "pip",
            "list",
            "--disable-pip-version-check",
        ] + args[1:]

        env = os.environ.copy()
        if os.path.exists(target):
            env["PYTHONPATH"] = target + os.pathsep + env.get("PYTHONPATH", "")

        timeout = _pip_timeout()
        try:
            completed = subprocess.run(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout,
                env=env,
            )
        except subprocess.TimeoutExpired:
            raise PipError("pip list timed out after %d seconds." % timeout)
        except FileNotFoundError:
            raise PipError(
                "pip is not available in this image: '%s -m pip' was not found." % sys.executable)

        log = completed.stdout.decode("utf-8", "replace") if completed.stdout else ""
        if completed.returncode != 0:
            raise PipError("pip list failed with exit code %d:\n%s" % (completed.returncode, log))
        # `PYTHONPATH` lets imports resolve from --target, but pip's distribution discovery does
        # not consistently enumerate that directory. Query it explicitly so %pip list accurately
        # reports packages shared by Python workers in the still-live Spark driver.
        target_command = command + ["--path", target]
        try:
            target_completed = subprocess.run(
                target_command,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout,
                env=env,
            )
            target_log = (
                target_completed.stdout.decode("utf-8", "replace")
                if target_completed.stdout
                else ""
            )
            if target_completed.returncode == 0 and target_log and target_log != log:
                log += "\n\nSession-scoped packages:\n" + target_log
        except (subprocess.TimeoutExpired, FileNotFoundError):
            # The regular list above already succeeded. Older pip versions may not support
            # --path, so do not turn a diagnostic enhancement into a failed notebook cell.
            pass
        return {"text/plain": log}

    packages = args[1:]
    if not packages:
        raise PipError("No packages given. Use: %pip %s <packages>" % subcommand)

    if subcommand == "uninstall":
        # `pip uninstall` cannot safely target an arbitrary directory.  A persistent profile
        # environment rebuild has already been requested by the notebook backend; pretending to
        # remove a distribution from the shared interpreter here would risk deleting image-wide
        # packages.  The new revision applies when the runtime is restarted.
        return {"text/plain": (
            "Package removal was queued for the persistent Engine Profile environment. "
            "Restart the runtime after its environment build is READY.\n")}

    command = [
        sys.executable,
        "-m",
        "pip",
        "install",
        "--disable-pip-version-check",
        "--target",
        target,
    ]
    index_url = os.environ.get("KYUUBI_NOTEBOOK_PIP_INDEX_URL", "").strip()
    if index_url:
        command += ["--index-url", index_url]
    trusted_host = os.environ.get("KYUUBI_NOTEBOOK_PIP_TRUSTED_HOST", "").strip()
    if trusted_host:
        command += ["--trusted-host", trusted_host]
    command += packages

    timeout = _pip_timeout()
    try:
        completed = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        raise PipError(
            "pip install timed out after %d seconds. Raise "
            "spark.kyuubi.notebook.pip.timeout if the mirror is slow." % timeout)
    except FileNotFoundError:
        raise PipError(
            "pip is not available in this image: '%s -m pip' was not found. "
            "The Spark image needs python3-pip." % sys.executable)

    log = completed.stdout.decode("utf-8", "replace") if completed.stdout else ""
    if completed.returncode != 0:
        raise PipError(
            "pip install failed with exit code %d:\n%s" % (completed.returncode, log))

    if target not in sys.path:
        sys.path.insert(0, target)
    importlib.invalidate_caches()
    return {"text/plain": log}


magic_router = {
    "table": magic_table,
    "json": magic_json,
    "matplot": magic_matplot,
    "pip": magic_pip,
}


# get or create spark session
spark_session = kyuubi_util.get_spark_session(
    os.environ.get("KYUUBI_SPARK_SESSION_UUID")
)
global_dict["spark"] = spark_session


def main():
    sys_stdin = sys.stdin
    sys_stdout = sys.stdout
    sys_stderr = sys.stderr

    sys.stdin = io.StringIO()
    sys.stdout = UnicodeDecodingStringIO()
    sys.stderr = UnicodeDecodingStringIO()

    stderr = sys.stderr.getvalue()
    print(stderr, file=sys_stderr)
    clearOutputs()

    try:

        while True:
            try:
                line = sys_stdin.readline()

                if line == "":
                    break
                elif line == "\n":
                    continue

                try:
                    content = json.loads(line)
                except ValueError:
                    continue

                if content["cmd"] == "exit_worker":
                    break

                result = execute_request(content)

                try:
                    result = json.dumps(result)
                except ValueError:
                    result = json.dumps(
                        {
                            "msg_type": "inspect_reply",
                            "content": {
                                "status": "error",
                                "ename": "ValueError",
                                "evalue": "cannot json-ify %s" % result,
                                "traceback": [],
                            },
                        }
                    )
                except Exception:
                    exc_type, exc_value, tb = sys.exc_info()
                    result = json.dumps(
                        {
                            "msg_type": "inspect_reply",
                            "content": {
                                "status": "error",
                                "ename": str(exc_type.__name__),
                                "evalue": "cannot json-ify %s: %s"
                                % (result, str(exc_value)),
                                "traceback": [],
                            },
                        }
                    )

                print(result, file=sys_stdout)
            except KeyboardInterrupt:
                result = json.dumps(
                    {
                        "msg_type": "inspect_reply",
                        "content": {
                            "status": "canceled",
                            "ename": "KeyboardInterrupt",
                            "evalue": "execution interrupted by user",
                            "traceback": [],
                        },
                    }
                )
                print(result, file=sys_stdout)
                print("execution interrupted by user: " + line, file=sys_stderr)
            sys_stdout.flush()
            clearOutputs()
    finally:
        print("python worker exit", file=sys_stderr)
        sys.stdin = sys_stdin
        sys.stdout = sys_stdout
        sys.stderr = sys_stderr


if __name__ == "__main__":
    sys.exit(main())
