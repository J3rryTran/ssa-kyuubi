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

package org.apache.kyuubi.server.dbt

import java.util.UUID

import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.engineprofile.{EngineProfilePrincipal, EngineProfileService}
import org.apache.kyuubi.server.notebook.NotebookConf.{DBT_RUNNER_LOG_MAX_BYTES, DBT_RUNNER_LOG_PAGE_MAX_BYTES}
import org.apache.kyuubi.server.notebook.api.{NotebookErrorCode, NotebookException}
import org.apache.kyuubi.server.notebook.service.NotebookPrincipal

/** Authorization and state transitions for DBT metadata; it never runs a dbt executable itself. */
class DbtWorkspaceService(
    conf: KyuubiConf,
    store: DbtStore,
    engineProfiles: EngineProfileService,
    runner: DbtRunner = DisabledDbtRunner) extends Logging {
  import DbtAction._

  def createWorkspace(
      principal: NotebookPrincipal,
      name: String,
      projectRef: String,
      engineProfileId: String): DbtWorkspace = {
    validateName(name, "workspace")
    validateProjectRef(projectRef)
    requireProfile(engineProfileId, principal)
    val now = System.currentTimeMillis()
    val workspace = DbtWorkspace(
      UUID.randomUUID().toString,
      principal.user,
      name.trim,
      projectRef.trim,
      engineProfileId.trim,
      now,
      now,
      1L)
    store.createWorkspace(workspace)
    workspace
  }

  def listWorkspaces(principal: NotebookPrincipal): Seq[DbtWorkspace] =
    if (principal.admin) store.listWorkspaces(principal.user)
    else store.listWorkspaces(principal.user)

  def getWorkspace(principal: NotebookPrincipal, id: String): DbtWorkspace =
    authorizeWorkspace(loadWorkspace(id), principal)

  def updateWorkspace(
      principal: NotebookPrincipal,
      id: String,
      name: Option[String],
      projectRef: Option[String],
      engineProfileId: Option[String],
      expectedVersion: Long): DbtWorkspace = {
    val current = authorizeWorkspace(loadWorkspace(id), principal)
    if (expectedVersion != current.version) {
      throw NotebookException.versionConflict("workspace changed")
    }
    name.foreach(validateName(_, "workspace"))
    projectRef.foreach(validateProjectRef)
    engineProfileId.foreach(requireProfile(_, principal))
    val updated = current.copy(
      name = name.map(_.trim).getOrElse(current.name),
      projectRef = projectRef.map(_.trim).getOrElse(current.projectRef),
      selectedEngineProfileId =
        engineProfileId.map(_.trim).getOrElse(current.selectedEngineProfileId),
      updatedAt = System.currentTimeMillis(),
      version = current.version + 1)
    if (!store.updateWorkspace(updated, current.version)) {
      throw NotebookException.versionConflict("workspace changed")
    }
    updated
  }

  def deleteWorkspace(principal: NotebookPrincipal, id: String, expectedVersion: Long): Unit = {
    val workspace = authorizeWorkspace(loadWorkspace(id), principal)
    if (expectedVersion != workspace.version) {
      throw NotebookException.versionConflict("workspace changed")
    }
    if (store.listActiveRunsForWorkspace(id).nonEmpty) {
      throw NotebookException.invalid("cannot delete a workspace with an active DBT run")
    }
    if (store.listJobs(id).nonEmpty) {
      throw NotebookException.invalid("delete DBT jobs before deleting their workspace")
    }
    if (!store.deleteWorkspace(id, workspace.owner, expectedVersion)) {
      throw NotebookException.versionConflict("workspace changed")
    }
  }

  def createJob(
      principal: NotebookPrincipal,
      workspaceId: String,
      name: String,
      action: String,
      selector: Option[String],
      profileOverride: Option[String]): DbtJob = {
    val workspace = authorizeWorkspace(loadWorkspace(workspaceId), principal)
    validateName(name, "job")
    val parsedAction = parseAction(action)
    validateSelector(parsedAction, selector)
    profileOverride.foreach(requireProfile(_, principal))
    val now = System.currentTimeMillis()
    val job = DbtJob(
      UUID.randomUUID().toString,
      workspace.id,
      workspace.owner,
      name.trim,
      parsedAction,
      selector.map(_.trim),
      profileOverride.map(_.trim),
      now,
      now,
      1L)
    store.createJob(job)
    job
  }

  def listJobs(principal: NotebookPrincipal, workspaceId: String): Seq[DbtJob] = {
    authorizeWorkspace(loadWorkspace(workspaceId), principal)
    store.listJobs(workspaceId)
  }

  def getJob(principal: NotebookPrincipal, id: String): DbtJob =
    authorizeJob(loadJob(id), principal)

  def updateJob(
      principal: NotebookPrincipal,
      id: String,
      name: Option[String],
      action: Option[String],
      selector: Option[String],
      profileOverride: Option[String],
      clearProfileOverride: Boolean,
      expectedVersion: Long): DbtJob = {
    val current = authorizeJob(loadJob(id), principal)
    if (expectedVersion != current.version) throw NotebookException.versionConflict("job changed")
    name.foreach(validateName(_, "job"))
    val newAction = action.map(parseAction).getOrElse(current.action)
    val newSelector = selector.orElse(current.selector)
    validateSelector(newAction, newSelector)
    profileOverride.foreach(requireProfile(_, principal))
    val updated = current.copy(
      name = name.map(_.trim).getOrElse(current.name),
      action = newAction,
      selector = newSelector,
      engineProfileIdOverride = if (clearProfileOverride) None
      else {
        profileOverride.map(_.trim).orElse(current.engineProfileIdOverride)
      },
      updatedAt = System.currentTimeMillis(),
      version = current.version + 1)
    if (!store.updateJob(updated, current.version)) {
      throw NotebookException.versionConflict("job changed")
    }
    updated
  }

  def deleteJob(principal: NotebookPrincipal, id: String, expectedVersion: Long): Unit = {
    val job = authorizeJob(loadJob(id), principal)
    if (expectedVersion != job.version) {
      throw NotebookException.versionConflict("job changed")
    }
    if (!store.deleteJob(id, job.owner, expectedVersion)) {
      throw NotebookException.versionConflict("job changed")
    }
  }

  def getRun(principal: NotebookPrincipal, id: String): DbtJobRun = {
    val run = store.getRun(id).getOrElse(throw dbtNotFound("run was not found"))
    authorizeOwner(run.submittedBy, principal)
    run
  }

  def listRunsForWorkspace(
      principal: NotebookPrincipal,
      workspaceId: String,
      limit: Int): Seq[DbtJobRun] = {
    authorizeWorkspace(loadWorkspace(workspaceId), principal)
    store.listRunsForWorkspace(workspaceId, validatedRunLimit(limit))
  }

  def listRunsForJob(
      principal: NotebookPrincipal,
      jobId: String,
      limit: Int): Seq[DbtJobRun] = {
    authorizeJob(loadJob(jobId), principal)
    store.listRunsForJob(jobId, validatedRunLimit(limit))
  }

  def hasActiveRunsForProfile(profileId: String): Boolean =
    store.listActiveRunsForProfile(profileId).nonEmpty

  def hasActiveRunsForProfileRevision(profileId: String, revision: Long): Boolean =
    store.listActiveRunsForProfile(profileId).exists(_.profile.revision == revision)

  /** Creates and dispatches only when a runner implementation has been installed. */
  def submitJob(principal: NotebookPrincipal, id: String): DbtJobRun = {
    val job = authorizeJob(loadJob(id), principal)
    val workspace = authorizeWorkspace(loadWorkspace(job.workspaceId), principal)
    submit(principal, workspace, Some(job), job.action, job.selector)
  }

  /** Test seam; production requests always use the runner installed in the service. */
  def submitJob(
      principal: NotebookPrincipal,
      id: String,
      runnerOverride: DbtRunner): DbtJobRun = {
    val job = authorizeJob(loadJob(id), principal)
    val workspace = authorizeWorkspace(loadWorkspace(job.workspaceId), principal)
    submit(principal, workspace, Some(job), job.action, job.selector, runnerOverride)
  }

  def preview(
      principal: NotebookPrincipal,
      workspaceId: String,
      selector: String): DbtJobRun = {
    val workspace = authorizeWorkspace(loadWorkspace(workspaceId), principal)
    validateSelector(PREVIEW, Option(selector))
    submit(principal, workspace, None, PREVIEW, Option(selector).map(_.trim))
  }

  def reconcileActiveRuns(): Unit = store.listActiveRuns().foreach(reconcile)

  def logs(
      principal: NotebookPrincipal,
      id: String,
      offset: Long,
      requestedLimit: Option[Int]): DbtRunnerLogPage = {
    getRun(principal, id)
    if (offset < 0) {
      throw NotebookException.invalid("DBT log offset must not be negative")
    }
    val limit = requestedLimit.getOrElse(conf.get(DBT_RUNNER_LOG_PAGE_MAX_BYTES))
    if (limit <= 0) {
      throw NotebookException.invalid("DBT log limit must be positive")
    }
    store.readLogs(id, offset, math.min(limit, conf.get(DBT_RUNNER_LOG_PAGE_MAX_BYTES)))
  }

  def cancel(principal: NotebookPrincipal, id: String): DbtJobRun = {
    val run = getRun(principal, id)
    run.state match {
      case DbtRunState.SUCCEEDED | DbtRunState.FAILED | DbtRunState.CANCELLED => run
      case DbtRunState.CANCELLING => run
      case _ =>
        val now = System.currentTimeMillis()
        val cancelling = run.copy(
          state = DbtRunState.CANCELLING,
          stateUpdatedAt = now,
          version = run.version + 1)
        if (!store.updateRun(cancelling, DbtRunState.active - DbtRunState.CANCELLING)) {
          return getRun(principal, id)
        }
        run.runnerRunId match {
          case Some(runnerRunId) => runner.cancel(runnerRunId)
          case None =>
            runner.findSubmission(run.id) match {
              case Some(submission) => runner.cancel(submission.runnerRunId)
              case None => completeCancellation(cancelling)
            }
        }
        store.getRun(id).getOrElse(cancelling)
    }
  }

  private def submit(
      principal: NotebookPrincipal,
      workspace: DbtWorkspace,
      job: Option[DbtJob],
      action: DbtAction.Value,
      selector: Option[String],
      selectedRunner: DbtRunner = runner): DbtJobRun = {
    if (selectedRunner eq DisabledDbtRunner) throw new DbtRunnerNotConfiguredException
    val profileId =
      job.flatMap(_.engineProfileIdOverride).getOrElse(workspace.selectedEngineProfileId)
    val snapshot = requireProfile(profileId, principal)
    val now = System.currentTimeMillis()
    val queued = DbtJobRun(
      UUID.randomUUID().toString,
      workspace.id,
      job.map(_.id),
      principal.user,
      principal.user,
      action,
      selector,
      workspace.projectRef,
      snapshot,
      DbtRunState.QUEUED,
      None,
      None,
      None,
      now,
      None,
      None,
      None,
      now,
      0L,
      logTruncated = false,
      version = 1L)
    store.createRun(queued)
    val token = UUID.randomUUID().toString
    if (!store.claimDispatch(queued.id, queued.version, token, now)) {
      throw new IllegalStateException(s"Unable to claim DBT run ${queued.id} for dispatch")
    }
    val dispatching = store.getRun(queued.id).getOrElse(
      throw new IllegalStateException(s"DBT run ${queued.id} disappeared during dispatch"))
    dispatch(dispatching, selectedRunner)
  }

  private def dispatch(run: DbtJobRun, selectedRunner: DbtRunner): DbtJobRun = {
    try {
      val submission = selectedRunner.submit(DbtRunRequest(run))
      val submitted = run.copy(
        state = DbtRunState.SUBMITTED,
        runnerRunId = Some(submission.runnerRunId),
        runnerJobUid = submission.runnerJobUid,
        stateUpdatedAt = System.currentTimeMillis(),
        version = run.version + 1)
      if (store.updateRun(submitted, Set(DbtRunState.DISPATCHING))) {
        submitted
      } else {
        selectedRunner.cancel(submission.runnerRunId)
        store.getRun(run.id).getOrElse(submitted)
      }
    } catch {
      case e: DbtRunnerNotConfiguredException =>
        failDispatch(run, "DBT runner is not configured")
        throw e
      case e: Exception =>
        failDispatch(run, "DBT runner submission failed")
        throw e
    }
  }

  private def failDispatch(run: DbtJobRun, message: String): Unit = {
    store.updateRun(
      run.copy(
        state = DbtRunState.FAILED,
        finishedAt = Some(System.currentTimeMillis()),
        errorSummary = Some(message),
        stateUpdatedAt = System.currentTimeMillis(),
        version = run.version + 1),
      Set(DbtRunState.DISPATCHING))
  }

  private def reconcile(run: DbtJobRun): Unit = {
    val current = if (run.runnerRunId.isEmpty && run.state == DbtRunState.DISPATCHING) {
      runner.findSubmission(run.id) match {
        case Some(submission) =>
          val submitted = run.copy(
            state = DbtRunState.SUBMITTED,
            runnerRunId = Some(submission.runnerRunId),
            runnerJobUid = submission.runnerJobUid,
            stateUpdatedAt = System.currentTimeMillis(),
            version = run.version + 1)
          if (store.updateRun(submitted, Set(DbtRunState.DISPATCHING))) submitted
          else store.getRun(run.id).getOrElse(run)
        case None => dispatch(run, runner)
      }
    } else {
      run
    }
    val afterLogs =
      try {
        collectLogs(current)
      } catch {
        case NonFatal(error) =>
          warn(s"Failed to collect DBT runner log for run ${current.id}", error)
          current
      }
    afterLogs.runnerRunId.foreach { runnerRunId =>
      updateFromStatus(afterLogs, runner.status(runnerRunId))
    }
    if (afterLogs.state == DbtRunState.CANCELLING && afterLogs.runnerRunId.isEmpty) {
      completeCancellation(afterLogs)
    }
  }

  private def collectLogs(run: DbtJobRun): DbtJobRun = run.runnerRunId match {
    case None => run
    case Some(runnerRunId) =>
      val page = runner.logs(runnerRunId, run.logNextOffset)
      if (page.content.isEmpty) run
      else {
        val maxBytes = conf.get(DBT_RUNNER_LOG_MAX_BYTES)
        val available = math.max(0L, maxBytes - run.logNextOffset).toInt
        val bytes = page.content.getBytes("UTF-8")
        val content = new String(bytes.take(available), "UTF-8")
        if (content.nonEmpty && store.appendLog(run, content, System.currentTimeMillis())) {
          store.getRun(run.id).getOrElse(run)
        } else if (bytes.nonEmpty && available < bytes.length) {
          val truncated = run.copy(logTruncated = true, version = run.version + 1)
          if (store.updateRun(truncated, DbtRunState.active)) {
            store.getRun(run.id).getOrElse(truncated)
          } else {
            run
          }
        } else {
          run
        }
      }
  }

  private def updateFromStatus(run: DbtJobRun, status: DbtRunnerStatus): Unit = {
    if (DbtRunState.terminal.contains(run.state)) return
    val nextState =
      if (run.state == DbtRunState.CANCELLING &&
        status.state == DbtRunState.FAILED) {
        DbtRunState.CANCELLED
      } else {
        status.state
      }
    if (run.state != nextState || status.startedAt.nonEmpty || status.finishedAt.nonEmpty) {
      val isCancelled = nextState == DbtRunState.CANCELLED
      val updated = run.copy(
        state = nextState,
        startedAt = run.startedAt.orElse(status.startedAt),
        finishedAt =
          if (isCancelled) {
            run.finishedAt.orElse(status.finishedAt).orElse(Some(System.currentTimeMillis()))
          } else {
            run.finishedAt.orElse(status.finishedAt)
          },
        errorSummary = if (isCancelled) None else status.errorSummary.orElse(run.errorSummary),
        stateUpdatedAt = System.currentTimeMillis(),
        version = run.version + 1)
      if (store.updateRun(updated, DbtRunState.active) && DbtRunState.terminal.contains(
          nextState)) {
        try runner.cleanup(updated.id)
        catch { case e: Exception => warn(s"Failed to clean DBT run ${updated.id}", e) }
      }
    }
  }

  private def completeCancellation(run: DbtJobRun): Unit = {
    val cancelled = run.copy(
      state = DbtRunState.CANCELLED,
      finishedAt = Some(System.currentTimeMillis()),
      stateUpdatedAt = System.currentTimeMillis(),
      version = run.version + 1)
    store.updateRun(cancelled, Set(DbtRunState.CANCELLING))
  }

  private def requireProfile(id: String, principal: NotebookPrincipal) = {
    if (id == null || id.trim.isEmpty) {
      throw NotebookException.invalid("engineProfileId is required")
    }
    engineProfiles.snapshotForUse(id.trim, EngineProfilePrincipal(principal.user, principal.admin))
  }

  private def loadWorkspace(id: String): DbtWorkspace =
    store.getWorkspace(id).getOrElse(throw dbtNotFound("workspace was not found"))

  private def loadJob(id: String): DbtJob =
    store.getJob(id).getOrElse(throw dbtNotFound("job was not found"))

  private def authorizeWorkspace(
      workspace: DbtWorkspace,
      principal: NotebookPrincipal): DbtWorkspace = {
    authorizeOwner(workspace.owner, principal)
    workspace
  }

  private def authorizeJob(job: DbtJob, principal: NotebookPrincipal): DbtJob = {
    authorizeOwner(job.owner, principal)
    job
  }

  private def authorizeOwner(owner: String, principal: NotebookPrincipal): Unit =
    if (!principal.admin && owner != principal.user) throw dbtNotFound("DBT resource was not found")

  private def dbtNotFound(message: String): NotebookException =
    new NotebookException(NotebookErrorCode.DBT_RESOURCE_NOT_FOUND, message)

  private def validateName(value: String, kind: String): Unit =
    if (value == null || value.trim.isEmpty || value.trim.length > 255) {
      throw NotebookException.invalid(s"$kind name must be between 1 and 255 characters")
    }

  private def validateProjectRef(value: String): Unit =
    if (value == null || !value.trim.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) {
      throw NotebookException.invalid("projectRef must be a server-controlled project identifier")
    }

  private def validatedRunLimit(limit: Int): Int = {
    if (limit <= 0 || limit > 100) {
      throw NotebookException.invalid("DBT run limit must be between 1 and 100")
    }
    limit
  }

  private def parseAction(value: String): DbtAction.Value =
    try DbtAction.withName(Option(value).map(_.trim.toUpperCase).getOrElse(""))
    catch {
      case _: NoSuchElementException => throw NotebookException.invalid("unsupported DBT action")
    }

  private def validateSelector(action: DbtAction.Value, selector: Option[String]): Unit = {
    val hasSelector = selector.exists(_.trim.nonEmpty)
    if ((action == PREVIEW || action == RUN_MODEL) && !hasSelector) {
      throw NotebookException.invalid(s"selector is required for $action")
    }
    if (action == RUN_PROJECT && hasSelector) {
      throw NotebookException.invalid("selector is not allowed for RUN_PROJECT")
    }
  }
}
