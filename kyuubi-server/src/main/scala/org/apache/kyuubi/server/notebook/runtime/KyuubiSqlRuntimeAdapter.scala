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

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.{ARROW_BASED_ROWSET_TIMESTAMP_AS_STRING, ENGINE_SPARK_OUTPUT_MODE, OPERATION_LANGUAGE, OPERATION_RESULT_FORMAT}
import org.apache.kyuubi.operation.{FetchOrientation, OperationHandle, OperationState}
import org.apache.kyuubi.server.api.v1.ArrowRowSetConverter
import org.apache.kyuubi.server.notebook.NotebookConf.NOTEBOOK_MAX_PAGE_SIZE
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.service.BackendService
import org.apache.kyuubi.session.SessionHandle
import org.apache.kyuubi.shaded.hive.service.rpc.thrift.{TProtocolVersion, TRowSet}

/**
 * Runs SQL and Python cells through one Kyuubi Spark session.
 *
 * The adapter talks to the same [[BackendService]] the REST session and operation resources use,
 * which is what "use the existing Kyuubi primitives" means in practice: one Kyuubi session per
 * notebook runtime, one asynchronous operation per execution. Python is selected only in the
 * operation overlay, so Spark SQL state and the engine's session-scoped Python worker are shared.
 *
 * Cleanup is deliberately done with `cancelOperation`/`closeOperation`/`closeSession` rather than
 * the admin endpoints, so an ordinary notebook user needs no administrator rights.
 */
class SparkNotebookRuntimeAdapter(
    backendService: () => BackendService,
    instanceUri: () => String,
    conf: KyuubiConf)
  extends TabularNotebookRuntimeAdapter with Logging {

  import SparkNotebookRuntimeAdapter._

  private val maxResultRows = conf.get(NOTEBOOK_MAX_PAGE_SIZE)

  override val runtimeType: String = RUNTIME_TYPE

  override val legacyRuntimeSpecIds: Seq[String] = Seq("kyuubi-sql", "pyspark")

  override val runtimeSpec: RuntimeSpec = RuntimeSpec(
    id = SPEC_ID,
    displayName = "Spark Notebook",
    // This remains the legacy/default display field. `supportedLanguages` is the capability.
    language = CellLanguage.SQL.toString,
    version = org.apache.kyuubi.KYUUBI_VERSION,
    enabled = true,
    configurableKeys = Seq("kyuubi.engine.share.level", "spark.sql.shuffle.partitions"),
    limits = Map("maxResultRows" -> maxResultRows.toString, "packagesSource" -> "spark-image"),
    supportedLanguages = Seq(CellLanguage.SQL.toString, CellLanguage.PYTHON.toString))

  override def startRuntime(
      runtime: NotebookRuntime,
      configuration: Map[String, String]): AdapterRuntime = {
    val subdomain = configuration.get("kyuubi.engine.share.level.subdomain")
      .orElse(configuration.get("kyuubi.engine.share.level.sub.domain"))
      .getOrElse("default")
    val handle = backendService().openSession(
      TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11,
      runtime.owner,
      "",
      LOCAL_IP,
      configuration ++ Map(
        KYUUBI_SESSION_TAG -> s"notebook-runtime-${runtime.id}",
        "kyuubi.engine.share.level.subdomain" -> subdomain,
        "kyuubi.engine.share.level.sub.domain" -> subdomain,
        // The mode affects Python response serialization only; SQL still returns a TRowSet.
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
          // The session manager forgets a session that was closed or lost with its server, and
          // there is no way to tell those apart from here, so it is reported as LOST rather
          // than guessed to be healthy.
          case NonFatal(_) =>
            AdapterRuntimeStatus(RuntimeState.LOST, Some("the Spark session is gone"))
        }
    }
  }

  override def execute(
      runtime: NotebookRuntime,
      execution: CellExecution,
      configuration: Map[String, String]): AdapterExecution = {
    val sessionHandle = requireSession(runtime)
    val overlay = execution.language match {
      case CellLanguage.PYTHON => configuration + (OPERATION_LANGUAGE.key -> "PYTHON")
      case CellLanguage.SQL => configuration - OPERATION_LANGUAGE.key
      case language =>
        throw new NotebookException(
          NotebookErrorCode.UNSUPPORTED_LANGUAGE,
          s"$language cannot be executed by a Spark notebook runtime")
    }
    val operationHandle =
      try {
        backendService().executeStatement(
          sessionHandle,
          execution.sourceSnapshot,
          overlay,
          // Always asynchronous: a synchronous call would tie the statement to the request
          // thread and make a browser refresh lose the work.
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
        errorCode = status.exception.map(_ => failureCode(execution.language)),
        errorMessage = status.exception.map(e => failureMessage(execution.language, e.getMessage)),
        hasResultSet = execution.language == CellLanguage.SQL && status.hasResultSet)
    } catch {
      case NonFatal(e) =>
        debug(s"Operation for execution ${execution.id} is no longer known", e)
        AdapterExecutionStatus(
          ExecutionState.LOST,
          None,
          None,
          Some(NotebookErrorCode.KYUUBI_OPERATION_LOST.toString),
          Some("the SQL operation is no longer known to the server"),
          hasResultSet = false)
    }
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

  override def fetchOutputs(
      execution: CellExecution,
      afterSequence: Long,
      limit: Int): Seq[AdapterOutput] = {
    if (execution.language != CellLanguage.PYTHON) {
      return Seq.empty
    }
    val handle = execution.internalOperationHandle.getOrElse(return Seq.empty)
    val produced =
      try {
        val status = backendService().getOperationStatus(OperationHandle(handle), None)
        status.exception match {
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

  override def restartRuntime(
      runtime: NotebookRuntime,
      configuration: Map[String, String]): AdapterRuntime = {
    stopRuntime(runtime)
    startRuntime(runtime, configuration)
  }

  override def stopRuntime(runtime: NotebookRuntime): Unit = {
    runtime.internalRuntimeHandle.foreach { handle =>
      try backendService().closeSession(SessionHandle.fromUUID(handle))
      catch {
        case NonFatal(e) => debug(s"Failed to close Spark session of runtime ${runtime.id}", e)
      }
    }
  }

  /**
   * Reads the operation log from the start and slices it, because Kyuubi's log cursor is
   * stateful and would otherwise make two clients steal each other's lines.
   */
  override def fetchLogs(execution: CellExecution, offset: Long, maxLines: Int): AdapterLogPage = {
    val handle = execution.internalOperationHandle.getOrElse {
      return AdapterLogPage(Seq.empty, offset, hasMore = false)
    }
    try {
      val response = backendService().sessionManager.operationManager.getOperationLogRowSet(
        OperationHandle(handle),
        FetchOrientation.FETCH_FIRST,
        (offset + maxLines).toInt.max(maxLines))
      val all = Option(response.getResults).map(stringColumn).getOrElse(Seq.empty)
      val page = all.drop(offset.toInt).take(maxLines)
      AdapterLogPage(page, offset + page.size, hasMore = all.size > offset + page.size)
    } catch {
      case NonFatal(e) =>
        debug(s"Log for execution ${execution.id} is unavailable", e)
        AdapterLogPage(Seq.empty, offset, hasMore = false)
    }
  }

  override def fetchSchema(execution: CellExecution): ExecutionSchema = {
    val handle = execution.internalOperationHandle.getOrElse {
      throw new NotebookException(
        NotebookErrorCode.NO_TABULAR_RESULT,
        "the execution produced no result set")
    }
    try {
      val columns = backendService().getResultSetMetadata(OperationHandle(handle))
        .getSchema.getColumns.asScala
      ExecutionSchema(columns.map { column =>
        val primitive = column.getTypeDesc.getTypes.get(0).getPrimitiveEntry
        ColumnSchema(
          column.getColumnName,
          primitive.getType.toString,
          column.getPosition,
          Option(column.getComment).filter(_.nonEmpty))
      })
    } catch {
      case e: NotebookException => throw e
      case NonFatal(e) =>
        throw new NotebookException(
          NotebookErrorCode.RESULT_EXPIRED,
          "the result of this execution is no longer available",
          cause = e)
    }
  }

  override def fetchResults(
      execution: CellExecution,
      offset: Long,
      maxRows: Int): AdapterResultPage = {
    val handle = execution.internalOperationHandle.getOrElse {
      throw new NotebookException(
        NotebookErrorCode.NO_TABULAR_RESULT,
        "the execution produced no result set")
    }
    // Cursor policy lives in the execution service, which applies it to every runtime; here the
    // read is simply forward, restarting the Kyuubi cursor only for the first page.
    val capped = math.min(maxRows, maxResultRows)
    try {
      val orientation =
        if (offset == 0) FetchOrientation.FETCH_FIRST else FetchOrientation.FETCH_NEXT
      val response = backendService().fetchResults(
        OperationHandle(handle),
        orientation,
        capped,
        fetchLog = false)
      val rowSet = response.getResults
      val rows = if (resultFormatOf(handle).equalsIgnoreCase("arrow")) {
        val schema = backendService().getResultSetMetadata(OperationHandle(handle)).getSchema
        ArrowRowSetConverter.toRows(
          rowSet,
          schema,
          timestampAsStringOf(handle),
          capped).map(_.getFields.asScala.map(field => renderObject(field.getValue)))
      } else {
        ThriftRowSetConverter.toRows(rowSet)
      }
      AdapterResultPage(rows, offset + rows.size, rows.size == capped)
    } catch {
      case e: NotebookException => throw e
      case NonFatal(e) =>
        throw new NotebookException(
          NotebookErrorCode.RESULT_EXPIRED,
          "the result of this execution is no longer available",
          cause = e)
    }
  }

  private def requireSession(runtime: NotebookRuntime): SessionHandle = {
    val handle = runtime.internalRuntimeHandle.getOrElse {
      throw new NotebookException(
        NotebookErrorCode.RUNTIME_LOST,
        "the runtime has no live Spark session; restart it",
        retryable = true)
    }
    SessionHandle.fromUUID(handle)
  }

  private def stringColumn(rowSet: TRowSet): Seq[String] = {
    if (rowSet.getColumns == null || rowSet.getColumns.isEmpty) {
      Seq.empty
    } else {
      rowSet.getColumns.get(0).getStringVal.getValues.asScala
    }
  }

  private def firstColumnOfFirstRow(handle: String): Option[String] = {
    val response = backendService().fetchResults(
      OperationHandle(handle),
      FetchOrientation.FETCH_FIRST,
      1,
      fetchLog = false)
    ThriftRowSetConverter.toRows(response.getResults).headOption
      .flatMap(_.headOption).flatMap(Option(_))
  }

  private def resultFormatOf(handle: String): String =
    operationConf(handle, OPERATION_RESULT_FORMAT.key).getOrElse(conf.get(OPERATION_RESULT_FORMAT))

  private def timestampAsStringOf(handle: String): Boolean =
    operationConf(handle, ARROW_BASED_ROWSET_TIMESTAMP_AS_STRING.key)
      .map(_.trim.toBoolean)
      .getOrElse(conf.get(ARROW_BASED_ROWSET_TIMESTAMP_AS_STRING))

  private def operationConf(handle: String, key: String): Option[String] =
    try {
      backendService().sessionManager.operationManager.getOperation(OperationHandle(handle))
        .getSession.conf.get(key)
    } catch {
      case NonFatal(e) =>
        debug(s"Could not read $key for notebook operation $handle", e)
        None
    }

  private def renderObject(value: Any): String = if (value == null) null else value.toString

  /** Normalizes Kyuubi operation states onto the notebook execution lifecycle. */
  private def normalize(state: OperationState.OperationState): ExecutionState.ExecutionState =
    state match {
      case OperationState.INITIALIZED | OperationState.PENDING => ExecutionState.QUEUED
      case OperationState.RUNNING | OperationState.COMPILED => ExecutionState.RUNNING
      case OperationState.FINISHED => ExecutionState.SUCCEEDED
      case OperationState.CANCELED => ExecutionState.CANCELED
      case OperationState.CLOSED => ExecutionState.CLOSED
      case OperationState.TIMEOUT | OperationState.ERROR => ExecutionState.FAILED
      // An unknown state is never optimistically reported as success.
      case OperationState.UNKNOWN => ExecutionState.LOST
    }

  /** Engine errors can be long and carry stack traces; only the first line is safe to surface. */
  private def safeMessage(message: String): String =
    Option(message).map(_.split("\n").head.take(1024)).getOrElse("the statement failed")

  private def failureCode(language: CellLanguage.Value): String =
    if (language == CellLanguage.PYTHON) PYTHON_EXECUTION_FAILED else SQL_EXECUTION_FAILED

  private def failureMessage(language: CellLanguage.Value, message: String): String =
    if (language == CellLanguage.PYTHON) PySparkResponseCodec.summarize(message)
    else safeMessage(message)
}

object SparkNotebookRuntimeAdapter {
  val SPEC_ID = "spark-notebook"
  val RUNTIME_TYPE = "SPARK_NOTEBOOK"

  /** Error code carried on a failed SQL execution; the message itself comes from the engine. */
  val SQL_EXECUTION_FAILED = "SQL_EXECUTION_FAILED"
  val PYTHON_EXECUTION_FAILED = "PYTHON_EXECUTION_FAILED"

  private val NOTEBOOK_OUTPUT_MODE = "NOTEBOOK"
  private val LOCAL_IP = "127.0.0.1"
  private val KYUUBI_SESSION_TAG = "kyuubi.session.name"
}

/**
 * Compatibility shim for direct adapter tests and old extensions. It is intentionally not
 * registered by [[NotebookManager]], so new notebook sessions always use the unified runtime.
 */
@deprecated("Use SparkNotebookRuntimeAdapter", "1.10.3")
class KyuubiSqlRuntimeAdapter(
    backendService: () => BackendService,
    instanceUri: () => String,
    conf: KyuubiConf)
  extends SparkNotebookRuntimeAdapter(backendService, instanceUri, conf)
