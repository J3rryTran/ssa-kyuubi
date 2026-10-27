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

package org.apache.kyuubi.server.notebook.service

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.compute.{ComputeAllocationService, ComputeTriggerType, ComputeWorkloadType, ManagedComputeStatus}
import org.apache.kyuubi.server.engineprofile.{EngineProfilePrincipal, EngineProfileService}
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.store.NotebookStore
import org.apache.kyuubi.util.ThreadUtils

/** Executes the immutable document pinned by a Notebook schedule on dedicated JOB compute. */
class NotebookRunService(
    conf: KyuubiConf,
    store: NotebookStore,
    documents: NotebookDocumentService,
    permissions: NotebookPermissionService,
    schedules: NotebookScheduleService,
    sessions: NotebookSessionService,
    runtimes: NotebookRuntimeService,
    executions: NotebookExecutionService,
    computeAllocations: ComputeAllocationService) extends AutoCloseable with Logging {

  private val enabled = conf.get(NOTEBOOK_JOB_RUNNER_ENABLED)
  private val pollInterval = conf.get(NOTEBOOK_JOB_POLL_INTERVAL)
  private val runTimeout = conf.get(NOTEBOOK_JOB_RUN_TIMEOUT)
  private val heartbeatInterval = math.max(
    1000L,
    math.min(conf.get(COMPUTE_JOB_HEARTBEAT_TIMEOUT) / 3L, TimeUnit.SECONDS.toMillis(30)))
  private val executor = ThreadUtils.newDaemonFixedThreadPool(
    conf.get(NOTEBOOK_JOB_RUNNER_THREADS),
    "notebook-job-runner")

  def dispatch(
      principal: NotebookPrincipal,
      notebookId: String,
      request: DispatchScheduledNotebookRequest): NotebookRun = {
    requireEnabled()
    val notebook = documents.loadNotebook(notebookId)
    permissions.requireOwner(notebook, principal)
    val requestId = Option(request).flatMap(value => Option(value.getClientRequestId))
      .map(_.trim).filter(_.nonEmpty)
    if (requestId.isEmpty) {
      throw NotebookException.invalid(
        "clientRequestId is required for a scheduled Notebook submission")
    }
    requestId.foreach { value =>
      if (value.length > 255) {
        throw NotebookException.invalid("clientRequestId must not exceed 255 characters")
      }
    }
    requestId.flatMap(value => store.findRunByRequestId(principal.user, value)).foreach { run =>
      if (run.notebookId != notebookId) {
        throw NotebookException.versionConflict(
          "clientRequestId was already used for a different Notebook")
      }
      return run
    }

    val schedule = schedules.requireModel(notebookId)
    if (!schedule.enabled) {
      throw NotebookException.invalid("the notebook schedule is disabled")
    }
    val running = store.listRuns(notebookId, Int.MaxValue).exists { run =>
      run.scheduleId.contains(schedule.id) && !RunState.terminal.contains(run.state)
    }
    if (running && schedule.overlapPolicy == OverlapPolicy.SKIP_IF_RUNNING) {
      throw NotebookException.versionConflict(
        "the previous occurrence of this notebook schedule is still running")
    }
    val (revision, document) = schedules.pinnedDocument(schedule)
    val profileId = schedule.runtimeProfile.orElse(document.runtimeProfile)
      .getOrElse(EngineProfileService.DefaultProfileId)
    val runId = UUID.randomUUID().toString
    val attemptId = UUID.randomUUID().toString
    var session: Option[NotebookSession] = None
    var allocationId: Option[String] = None
    var runCreated = false
    try {
      session = Some(sessions.createManagedJob(principal, notebook.id, profileId))
      val executable = executableCells(document)
      val now = System.currentTimeMillis()
      val claimed = NotebookRun(
        id = runId,
        notebookId = notebook.id,
        notebookSessionId = session.get.id,
        state = RunState.QUEUED,
        submittedAt = now,
        startedAt = None,
        finishedAt = None,
        submittedBy = principal.user,
        requestedCellIds = executable.map(cell => s"position:${cell.position}"),
        currentCellId = None,
        failurePolicy = schedule.failurePolicy,
        version = 1L,
        scheduleId = Some(schedule.id),
        sourceRevisionId = Some(revision.id),
        sourceRevisionNumber = Some(revision.revisionNumber),
        sourceChecksum = schedule.sourceChecksum,
        sourceSnapshot = Some(revision.documentSnapshot),
        attemptId = Some(attemptId),
        computeAllocationId = None,
        clientRequestId = requestId,
        errorMessage = None)
      try store.createRun(claimed)
      catch {
        case NonFatal(error) =>
          requestId.flatMap(value => store.findRunByRequestId(principal.user, value)) match {
            case Some(existing) if existing.notebookId == notebookId =>
              stopSessionQuietly(principal, session.get)
              return existing
            case Some(_) =>
              throw NotebookException.versionConflict(
                "clientRequestId was already used for a different Notebook")
            case None => throw error
          }
      }
      runCreated = true
      val allocation = computeAllocations.reserveJob(
        EngineProfilePrincipal(principal.user, principal.admin),
        profileId,
        ComputeWorkloadType.NOTEBOOK,
        ComputeTriggerType.SCHEDULE,
        runId,
        attemptId)
      allocationId = Some(allocation.id)
      var notebookRun = updateActive(claimed.copy(
        computeAllocationId = Some(allocation.id),
        version = claimed.version + 1))
      if (notebookRun.state == RunState.CANCELING) {
        notebookRun = updateActive(notebookRun.copy(
          computeAllocationId = Some(allocation.id),
          version = notebookRun.version + 1))
        val canceled = finish(runId, RunState.CANCELED, None)
        stopSessionQuietly(principal, session.get)
        requestCleanup(notebookRun, "scheduled Notebook run was canceled")
        return canceled
      }
      executor.submit(new Runnable {
        override def run(): Unit = execute(notebookRun.id, document)
      })
      notebookRun
    } catch {
      case NonFatal(error) =>
        session.foreach(stopSessionQuietly(principal, _))
        allocationId.foreach { id =>
          try {
            computeAllocations.requestRelease(
              id,
              "scheduled Notebook dispatch failed before execution")
          } catch {
            case NonFatal(cleanupError) =>
              warn(s"Failed to request cleanup for allocation $id", cleanupError)
          }
        }
        if (runCreated) {
          try finish(runId, RunState.FAILED, Some(safeMessage(error)))
          catch {
            case NonFatal(storeError) =>
              warn(s"Failed to mark Notebook run $runId failed", storeError)
          }
        }
        throw error
    }
  }

  def get(principal: NotebookPrincipal, runId: String): NotebookRun = {
    val run = load(runId)
    authorize(principal, run)
  }

  def status(principal: NotebookPrincipal, runId: String): ManagedComputeStatus = {
    val run = get(principal, runId)
    computeAllocations.managedStatus(
      EngineProfilePrincipal(principal.user, principal.admin),
      ComputeWorkloadType.NOTEBOOK,
      run.id,
      run.attemptId,
      run.computeAllocationId,
      run.state.toString,
      run.errorMessage)
  }

  def retryCleanup(principal: NotebookPrincipal, runId: String): ManagedComputeStatus = {
    val run = get(principal, runId)
    val allocationId = run.computeAllocationId.getOrElse {
      throw NotebookException.invalid(s"Notebook run '$runId' has no compute allocation")
    }
    computeAllocations.retryCleanup(
      EngineProfilePrincipal(principal.user, principal.admin),
      allocationId,
      ComputeWorkloadType.NOTEBOOK)
    status(principal, runId)
  }

  def list(
      principal: NotebookPrincipal,
      notebookId: String,
      limit: Int): Seq[NotebookRun] = {
    val notebook = documents.loadNotebook(notebookId)
    permissions.requireRead(notebook, principal)
    store.listRuns(notebookId, math.max(1, math.min(limit, 200))).filter(run =>
      principal.admin || run.submittedBy == principal.user)
  }

  def cancel(principal: NotebookPrincipal, runId: String): NotebookRun = {
    val current = authorize(principal, load(runId))
    if (RunState.terminal.contains(current.state)) return current
    val canceling = current.copy(
      state = RunState.CANCELING,
      version = current.version + 1)
    val stored = if (store.updateRun(canceling, current.version)) canceling else load(runId)
    if (RunState.terminal.contains(stored.state)) return stored
    stored.currentCellId.foreach { executionId =>
      try executions.cancel(principal, executionId)
      catch { case NonFatal(error) => warn(s"Failed to cancel execution $executionId", error) }
    }
    requestCleanup(stored, "scheduled Notebook run cancellation requested")
    stored
  }

  /** Runs belonging to this restarted frontend cannot retain their in-process Spark session. */
  def reconcileAfterRestart(): Unit = store.listActiveRuns().foreach { run =>
    val session = store.getSession(run.notebookSessionId)
    if (session.isEmpty || session.exists(sessions.isLocalFrontend)) {
      try {
        val reason = if (session.isEmpty) {
          "the Notebook run session disappeared"
        } else {
          "the server restarted during the Notebook run"
        }
        finish(run.id, RunState.FAILED, Some(reason))
        requestCleanup(run, "scheduled Notebook session could not be recovered")
      } catch {
        case NonFatal(error) => warn(s"Failed to reconcile Notebook run ${run.id}", error)
      }
    }
  }

  override def close(): Unit = ThreadUtils.shutdown(executor)

  private def execute(runId: String, document: NotebookDocument): Unit = {
    var run = load(runId)
    val principal = NotebookPrincipal(run.submittedBy, admin = false)
    val session = sessions.load(run.notebookSessionId)
    try {
      if (run.state == RunState.CANCELING) {
        finish(runId, RunState.CANCELED, None)
        return
      }
      run = updateActive(run.copy(
        state = RunState.RUNNING,
        startedAt = Some(System.currentTimeMillis()),
        version = run.version + 1))
      val cells = executableCells(document)
      var stop = false
      var firstFailure: Option[String] = None
      cells.foreach { cell =>
        if (!stop) {
          val latest = load(runId)
          if (latest.state == RunState.CANCELING) {
            stop = true
          } else {
            val execution = executions.submitManaged(
              principal,
              session,
              latest.computeAllocationId.get,
              latest.id,
              cell.position,
              cell)
            run = updateActive(latest.copy(
              currentCellId = Some(execution.id),
              version = latest.version + 1))
            val terminal = awaitExecution(principal, run, execution)
            executions.captureAudit(principal, terminal)
            if (terminal.state != ExecutionState.SUCCEEDED) {
              val message = terminal.errorMessage.orElse(
                Some(s"cell at position ${cell.position} became ${terminal.state}"))
              if (firstFailure.isEmpty) firstFailure = message
              if (load(runId).state == RunState.CANCELING) {
                stop = true
              } else if (run.failurePolicy == FailurePolicy.STOP_ON_ERROR) {
                throw NotebookRunFailed(message.get)
              }
            }
          }
        }
      }
      val latest = load(runId)
      if (latest.state == RunState.CANCELING) {
        finish(runId, RunState.CANCELED, None)
      } else if (firstFailure.nonEmpty) {
        finish(runId, RunState.FAILED, firstFailure)
      } else {
        finish(runId, RunState.SUCCEEDED, None)
      }
    } catch {
      case NotebookRunFailed(message) => finish(runId, RunState.FAILED, Some(message))
      case NonFatal(error) =>
        warn(s"Scheduled Notebook run $runId failed", error)
        val current = load(runId)
        if (current.state == RunState.CANCELING) finish(runId, RunState.CANCELED, None)
        else finish(runId, RunState.FAILED, Some(safeMessage(error)))
    } finally {
      stopSessionQuietly(principal, session)
      requestCleanup(load(runId), "scheduled Notebook run became terminal")
    }
  }

  private def awaitExecution(
      principal: NotebookPrincipal,
      run: NotebookRun,
      initial: CellExecution): CellExecution = {
    var current = initial
    var nextHeartbeat = System.currentTimeMillis() + heartbeatInterval
    while (!ExecutionState.terminal.contains(current.state)) {
      if (System.currentTimeMillis() - run.submittedAt >= runTimeout) {
        try executions.cancel(principal, current.id)
        catch {
          case NonFatal(error) => warn(s"Failed to cancel timed out execution ${current.id}", error)
        }
        throw NotebookRunFailed(s"Notebook run exceeded its ${runTimeout}ms timeout")
      } else if (load(run.id).state == RunState.CANCELING) {
        current = executions.cancel(principal, current.id)
      } else {
        Thread.sleep(pollInterval)
        current = executions.refresh(store.getExecution(current.id).getOrElse(current))
      }
      if (System.currentTimeMillis() >= nextHeartbeat &&
        !ExecutionState.terminal.contains(current.state)) {
        computeAllocations.heartbeatJob(
          EngineProfilePrincipal(run.submittedBy, admin = false),
          run.computeAllocationId.get,
          run.id,
          run.attemptId.get)
        nextHeartbeat = System.currentTimeMillis() + heartbeatInterval
      }
    }
    current
  }

  private def finish(
      runId: String,
      state: RunState.Value,
      errorMessage: Option[String]): NotebookRun = {
    val current = load(runId)
    if (RunState.terminal.contains(current.state)) return current
    val terminal = current.copy(
      state = state,
      currentCellId = None,
      finishedAt = Some(System.currentTimeMillis()),
      errorMessage = errorMessage.map(_.take(4096)),
      version = current.version + 1)
    if (store.updateRun(terminal, current.version)) terminal else load(runId)
  }

  private def updateActive(updated: NotebookRun): NotebookRun = {
    if (store.updateRun(updated, updated.version - 1)) updated
    else {
      val current = load(updated.id)
      if (current.state == RunState.CANCELING) current
      else throw NotebookException.versionConflict(s"Notebook run '${updated.id}' changed")
    }
  }

  private def requestCleanup(run: NotebookRun, reason: String): Unit =
    run.computeAllocationId.foreach { allocationId =>
      try computeAllocations.requestRelease(allocationId, reason)
      catch {
        case NonFatal(error) => warn(s"Failed to request cleanup for $allocationId", error)
      }
    }

  private def stopSessionQuietly(
      principal: NotebookPrincipal,
      session: NotebookSession): Unit = {
    try sessions.stop(principal, session.id)
    catch { case NonFatal(error) => warn(s"Failed to stop session ${session.id}", error) }
  }

  private def authorize(principal: NotebookPrincipal, run: NotebookRun): NotebookRun = {
    if (!principal.admin && run.submittedBy != principal.user) {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"Notebook run '${run.id}' was not found")
    }
    run
  }

  private def load(runId: String): NotebookRun = store.getRun(runId).getOrElse {
    throw NotebookException.notFound(
      NotebookErrorCode.NOTEBOOK_NOT_FOUND,
      s"Notebook run '$runId' was not found")
  }

  private def requireEnabled(): Unit = {
    if (!enabled) {
      throw NotebookException.invalid(
        s"scheduled Notebook dispatch is disabled; enable ${NOTEBOOK_JOB_RUNNER_ENABLED.key}")
    }
  }

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage).map(_.trim).filter(_.nonEmpty)
      .getOrElse(error.getClass.getSimpleName).take(4096)

  private def executableCells(document: NotebookDocument): Seq[NotebookDocumentCell] =
    document.cells.sortBy(_.position).filter { cell =>
      cell.cellType.equalsIgnoreCase(CellType.CODE.toString) &&
      CellLanguage.values.exists(value =>
        value.toString.equalsIgnoreCase(cell.language) && CellLanguage.executable.contains(value))
    }

  private case class NotebookRunFailed(message: String) extends RuntimeException(message)
}
