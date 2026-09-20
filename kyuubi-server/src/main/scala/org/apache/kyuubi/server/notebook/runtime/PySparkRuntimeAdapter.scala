/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.notebook.runtime

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.{ENGINE_SPARK_OUTPUT_MODE, OPERATION_LANGUAGE}
import org.apache.kyuubi.operation.{FetchOrientation, OperationHandle, OperationState}
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.service.BackendService
import org.apache.kyuubi.session.SessionHandle
import org.apache.kyuubi.shaded.hive.service.rpc.thrift.TProtocolVersion

/**
 * Runs Python cells inside the Spark engine, not on this server.
 *
 * Kyuubi's Spark engine already knows how to execute Python: an operation submitted with
 * `kyuubi.operation.language=PYTHON` is handed to a python worker the engine keeps alive for the
 * whole session, with `spark` already bound. That worker is what gives a notebook its semantics
 * for free - a name bound in one cell is still there in the next, and restarting the session is
 * what clears them - so no interpreter of our own is started or supervised here.
 *
 * The engine is asked for `NOTEBOOK` output mode, which makes it hand back the python response as
 * a JSON mime bundle instead of collapsing it to plain text. That is what carries a pandas
 * `_repr_html_` or a PNG through, and it makes the shape uniform: exactly one row, whose first
 * column is always a bundle, whatever the cell produced.
 */
class PySparkRuntimeAdapter(
    backendService: () => BackendService,
    instanceUri: () => String,
    conf: KyuubiConf)
  extends NotebookRuntimeAdapter with Logging {

  import PySparkRuntimeAdapter._

  override val runtimeType: String = RUNTIME_TYPE

  override val runtimeSpec: RuntimeSpec = RuntimeSpec(
    id = SPEC_ID,
    displayName = "PySpark",
    language = CellLanguage.PYTHON.toString,
    version = org.apache.kyuubi.KYUUBI_VERSION,
    enabled = true,
    configurableKeys = Seq("spark.sql.shuffle.partitions", "spark.executor.memory"),
    // Nothing here is fetched a page at a time: a python cell answers with outputs, not rows.
    limits = Map("packagesSource" -> "spark-image"))

  override def startRuntime(
      runtime: NotebookRuntime,
      configuration: Map[String, String]): AdapterRuntime = {
    val subdomain = configuration.get("kyuubi.engine.share.level.subdomain")
      .orElse(configuration.get("kyuubi.engine.share.level.sub.domain"))
      .getOrElse("default")
    // ===== DEBUG ENGINE SUBDOMAIN TRACING =====
    warn(s"[NOTEBOOK-ENGINE-DEBUG] PySparkAdapter.startRuntime: runtimeId=${runtime.id}" +
      s" owner=${runtime.owner}" +
      s" configKeys=${configuration.keys.mkString(",")}" +
      s" subdomain_from_config=${configuration.get("kyuubi.engine.share.level.subdomain")}" +
      s" subdomain_final=$subdomain")
    // ==========================================
    val handle = backendService().openSession(
      TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11,
      runtime.owner,
      "",
      LOCAL_IP,
      configuration ++ Map(
        KYUUBI_SESSION_TAG -> s"notebook-runtime-${runtime.id}",
        "kyuubi.engine.share.level.subdomain" -> subdomain,
        "kyuubi.engine.share.level.sub.domain" -> subdomain,
        ENGINE_SPARK_OUTPUT_MODE.key -> NOTEBOOK_OUTPUT_MODE))
    AdapterRuntime(handle.identifier.toString, Some(instanceUri()))
  }

  override def getRuntimeStatus(runtime: NotebookRuntime): AdapterRuntimeStatus = {
    runtime.internalRuntimeHandle match {
      case None => AdapterRuntimeStatus(RuntimeState.STOPPED, None)
      case Some(handle) =>
        try {
          backendService().sessionManager.getSession(SessionHandle.fromUUID(handle))
          AdapterRuntimeStatus(RuntimeState.IDLE, None)
        } catch {
          case NonFatal(_) =>
            AdapterRuntimeStatus(RuntimeState.LOST, Some("the Spark session is gone"))
        }
    }
  }

  override def execute(
      runtime: NotebookRuntime,
      execution: CellExecution,
      configuration: Map[String, String]): AdapterExecution = {
    val sessionHandle = runtime.internalRuntimeHandle.map(SessionHandle.fromUUID).getOrElse {
      throw new NotebookException(
        NotebookErrorCode.RUNTIME_LOST,
        "the runtime has no live Spark session; restart it",
        retryable = true)
    }
    // The language is ours to decide, so it is applied after the caller's configuration rather
    // than before it: a cell cannot ask to be run as SQL. This is the one key the engine does
    // read from an operation's confOverlay; the output mode is set on the session instead.
    val overlay = configuration + (OPERATION_LANGUAGE.key -> "PYTHON")
    val operationHandle =
      try {
        backendService().executeStatement(
          sessionHandle,
          execution.sourceSnapshot,
          overlay,
          runAsync = true,
          queryTimeout = 0L)
      } catch {
        case NonFatal(e) =>
          throw new NotebookException(
            NotebookErrorCode.KYUUBI_SESSION_LOST,
            "the Spark session is no longer usable; restart the notebook session",
            retryable = true,
            cause = e)
      }
    AdapterExecution(operationHandle.identifier.toString)
  }

  override def getExecutionStatus(execution: CellExecution): AdapterExecutionStatus = {
    val handle = execution.internalOperationHandle.getOrElse {
      return AdapterExecutionStatus(ExecutionState.QUEUED, None, None, None, None, false)
    }
    try {
      val status = backendService().getOperationStatus(OperationHandle(handle), None)
      AdapterExecutionStatus(
        state = normalize(status.state),
        startedAt = Option(status.start).filter(_ > 0),
        finishedAt = Option(status.completed).filter(_ > 0),
        errorCode = status.exception.map(_ => PYTHON_EXECUTION_FAILED),
        // A python failure is a traceback, and the useful line is the exception itself rather
        // than the engine's wrapper. The whole traceback still reaches the cell as an output.
        errorMessage = status.exception.map(e => PySparkResponseCodec.summarize(e.getMessage)),
        // A python cell has no result set in the SQL sense; its answer arrives through outputs.
        hasResultSet = false)
    } catch {
      case NonFatal(e) =>
        debug(s"Operation for execution ${execution.id} is no longer known", e)
        AdapterExecutionStatus(
          ExecutionState.LOST,
          None,
          None,
          Some(NotebookErrorCode.KYUUBI_OPERATION_LOST.toString),
          Some("the Spark operation is no longer known to the server"),
          hasResultSet = false)
    }
  }

  /**
   * The cell's outputs, derived from the one row the engine returns.
   *
   * The row is read again on every call rather than cached: the operation answers `FETCH_FIRST`
   * identically each time, so sequence numbers stay stable and a client polling with
   * `afterSequence` sees each output exactly once.
   */
  override def fetchOutputs(
      execution: CellExecution,
      afterSequence: Long,
      limit: Int): Seq[AdapterOutput] = {
    val handle = execution.internalOperationHandle.getOrElse(return Seq.empty)
    val produced =
      try {
        val status = backendService().getOperationStatus(OperationHandle(handle), None)
        status.exception match {
          // A failed cell never produced a bundle; the traceback lives in the engine's error.
          case Some(error) => PySparkResponseCodec.errorOutputs(error.getMessage)
          case None if status.state == OperationState.FINISHED =>
            PySparkResponseCodec.bundleOutputs(firstColumnOfFirstRow(handle))
          case None => Seq.empty
        }
      } catch {
        case NonFatal(e) =>
          debug(s"Outputs for execution ${execution.id} are unavailable", e)
          Seq.empty
      }
    produced.filter(_.sequence > afterSequence).take(math.max(limit, 0))
  }

  /** A python operation answers with exactly one row whose first column is the bundle. */
  private def firstColumnOfFirstRow(handle: String): Option[String] = {
    val response = backendService().fetchResults(
      OperationHandle(handle),
      FetchOrientation.FETCH_FIRST,
      1,
      fetchLog = false)
    ThriftRowSetConverter.toRows(response.getResults).headOption.flatMap(_.headOption)
      .flatMap(Option(_))
  }

  override def interruptExecution(execution: CellExecution): Unit = {
    execution.internalOperationHandle.foreach { handle =>
      try backendService().cancelOperation(OperationHandle(handle))
      catch { case NonFatal(e) => warn(s"Failed to cancel execution ${execution.id}", e) }
    }
  }

  override def closeExecution(execution: CellExecution): Unit = {
    execution.internalOperationHandle.foreach { handle =>
      try backendService().closeOperation(OperationHandle(handle))
      catch { case NonFatal(e) => debug(s"Failed to close execution ${execution.id}", e) }
    }
  }

  override def restartRuntime(runtime: NotebookRuntime): AdapterRuntime = {
    stopRuntime(runtime)
    startRuntime(runtime, Map.empty)
  }

  override def stopRuntime(runtime: NotebookRuntime): Unit = {
    runtime.internalRuntimeHandle.foreach { handle =>
      try backendService().closeSession(SessionHandle.fromUUID(handle))
      catch {
        case NonFatal(e) => debug(s"Failed to close Spark session of runtime ${runtime.id}", e)
      }
    }
  }

  override def fetchLogs(execution: CellExecution, offset: Long, maxLines: Int): AdapterLogPage = {
    val handle = execution.internalOperationHandle.getOrElse {
      return AdapterLogPage(Seq.empty, offset, hasMore = false)
    }
    try {
      val response = backendService().sessionManager.operationManager.getOperationLogRowSet(
        OperationHandle(handle),
        FetchOrientation.FETCH_FIRST,
        (offset + maxLines).toInt.max(maxLines))
      val all = ThriftRowSetConverter.toRows(response.getResults).flatMap(_.headOption)
        .flatMap(Option(_))
      val page = all.drop(offset.toInt).take(maxLines)
      AdapterLogPage(page, offset + page.size, hasMore = all.size > offset + page.size)
    } catch {
      case NonFatal(e) =>
        debug(s"Log for execution ${execution.id} is unavailable", e)
        AdapterLogPage(Seq.empty, offset, hasMore = false)
    }
  }

  private def normalize(state: OperationState.OperationState): ExecutionState.ExecutionState =
    state match {
      case OperationState.INITIALIZED | OperationState.PENDING => ExecutionState.QUEUED
      case OperationState.RUNNING | OperationState.COMPILED => ExecutionState.RUNNING
      case OperationState.FINISHED => ExecutionState.SUCCEEDED
      case OperationState.CANCELED => ExecutionState.CANCELED
      case OperationState.CLOSED => ExecutionState.CLOSED
      case OperationState.TIMEOUT | OperationState.ERROR => ExecutionState.FAILED
      case OperationState.UNKNOWN => ExecutionState.LOST
    }
}

object PySparkRuntimeAdapter {
  val SPEC_ID = "pyspark"
  val RUNTIME_TYPE = "PYTHON"

  val PYTHON_EXECUTION_FAILED = "PYTHON_EXECUTION_FAILED"

  /** Asks the engine for the raw python response instead of the extracted `text/plain`. */
  private val NOTEBOOK_OUTPUT_MODE = "NOTEBOOK"

  private val LOCAL_IP = "127.0.0.1"
  private val KYUUBI_SESSION_TAG = "kyuubi.session.name"
}

/**
 * Turns what the Spark engine says about a python cell into notebook outputs.
 *
 * Kept apart from the adapter because this is the part with real behaviour to pin down, and it
 * can then be tested against recorded engine responses without a backend.
 */
private[notebook] object PySparkResponseCodec {

  private val mapper = new ObjectMapper()

  private val PLAIN = "text/plain"

  /**
   * Rendered outputs of a successful cell, in the order a reader expects them.
   *
   * The worker folds the last expression's repr together with everything the cell printed into
   * `text/plain`, so that one entry is the console and is emitted as a STREAM - stdout and stderr
   * are already merged by then and cannot be told apart here. Anything else in the bundle is a
   * rich rendering and follows it.
   */
  def bundleOutputs(output: Option[String]): Seq[AdapterOutput] = {
    val bundle = output.map(asBundle).getOrElse(Map.empty[String, String])
    val console = bundle.get(PLAIN).filter(_.nonEmpty).map { text =>
      AdapterOutput(1L, OutputType.STREAM.toString, Some("stdout"), PLAIN, text)
    }
    val rich = bundle.filterKeys(_ != PLAIN).toSeq.sortBy(_._1)
    val richOutputs = rich.zipWithIndex.map { case ((mimeType, data), index) =>
      AdapterOutput(index + 2L, outputTypeOf(mimeType), None, mimeType, data)
    }
    console.toSeq ++ richOutputs
  }

  /**
   * The traceback of a failed cell.
   *
   * The engine does not return a row for a failure: it raises, wrapping the python response in a
   * message that still contains `ename`, `evalue` and the frames. Reading them back out is what
   * puts a usable traceback in the cell instead of one truncated line.
   */
  def errorOutputs(message: String): Seq[AdapterOutput] = {
    val content = errorContent(message)
    val traceback = content.map(_._3).getOrElse(Seq.empty)
    val text =
      if (traceback.nonEmpty) traceback.mkString
      else content.map { case (name, value, _) => s"$name: $value" }
        .getOrElse(Option(message).getOrElse(""))
    if (text.isEmpty) Seq.empty
    else Seq(AdapterOutput(1L, OutputType.STREAM.toString, Some("stderr"), PLAIN, text))
  }

  /** A single line naming the python exception, for the execution's error message. */
  def summarize(message: String): String =
    errorContent(message).map { case (name, value, _) => s"$name: $value" }
      .getOrElse(Option(message).map(_.split("\n").head.take(1024)).getOrElse("the cell failed"))

  /** `(ename, evalue, traceback)` dug out of the engine's wrapper, when it is there. */
  private def errorContent(message: String): Option[(String, String, Seq[String])] =
    for {
      raw <- Option(message)
      start <- Some(raw.indexOf('{')).filter(_ >= 0)
      tree <- parseTree(raw.substring(start))
      content <- Option(tree.path("response").path("content")).filterNot(_.isMissingNode)
      name <- Option(content.path("ename").asText(null)).filter(_ != null)
    } yield (
      name,
      content.path("evalue").asText(""),
      Option(content.path("traceback")).filter(_.isArray)
        .map(_.elements().asScala.map(_.asText("")).toSeq).getOrElse(Seq.empty))

  /**
   * Reads the engine's `output` column as a mime bundle.
   *
   * Two shapes arrive here. With `kyuubi.engine.spark.output.mode=NOTEBOOK` the engine writes the
   * whole data map as JSON. Under AUTO - the engine's default, and what is still seen if the
   * session conf did not take - a cell whose only entry is `text/plain` yields the bare text
   * instead, so `print("hello")` arrives as `hello` and `1+1` as `2`. Both have to read back the
   * same way or an ordinary cell shows nothing at all.
   *
   * A parsed object is only accepted as a bundle when every key looks like a mime type. Without
   * that check a cell printing JSON of its own - `print('{"a":1}')` - would be mistaken for one
   * and its text would vanish into made-up output types.
   */
  private def asBundle(output: String): Map[String, String] =
    parseTree(output).filter(isMimeBundle).map { tree =>
      tree.fields().asScala.map { entry =>
        val value = entry.getValue
        // Only text arrives as a JSON string; a bundle may also carry structured values such as
        // the `application/json` a magic produces, and those keep their own encoding.
        entry.getKey -> (if (value.isTextual) value.asText() else value.toString)
      }.toMap
    }.getOrElse(Map(PLAIN -> output))

  /** An empty object counts: a cell that produced nothing must stay silent, not print `{}`. */
  private def isMimeBundle(tree: JsonNode): Boolean =
    tree.isObject && tree.fieldNames().asScala.forall(_.contains("/"))

  private def parseTree(value: String): Option[JsonNode] =
    try Option(mapper.readTree(value))
    catch { case NonFatal(_) => None }

  private def outputTypeOf(mimeType: String): String =
    if (mimeType.startsWith("image/")) OutputType.IMAGE.toString
    else if (mimeType == "text/html") OutputType.HTML.toString
    else if (mimeType == "application/json") OutputType.JSON.toString
    else OutputType.TEXT.toString
}
