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

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

import scala.util.Try
import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.ObjectMapper

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.compute.{ComputeAllocation, ComputeAllocationService, ComputeBindingService, ComputeMode, ComputeTriggerType, ComputeWorkloadType, ManagedComputeStatus}
import org.apache.kyuubi.server.engineprofile.{EngineProfilePrincipal, EngineProfileService}
import org.apache.kyuubi.server.notebook.NotebookConf.{COMPUTE_JOB_HEARTBEAT_TIMEOUT, DBT_MANAGED_CLI_ENABLED, DBT_MANAGED_CLI_HEARTBEAT_INTERVAL, DBT_RUNNER_ARTIFACT_MAX_BYTES, DBT_RUNNER_KYUUBI_USER, DBT_RUNNER_LOG_MAX_BYTES, DBT_RUNNER_LOG_PAGE_MAX_BYTES, DBT_RUNNER_THREADS, DBT_RUNNER_THREADS_MAX}
import org.apache.kyuubi.server.notebook.api.{NotebookErrorCode, NotebookException}
import org.apache.kyuubi.server.notebook.service.NotebookPrincipal

/** Authorization and state transitions for DBT metadata; it never runs a dbt executable itself. */
class DbtWorkspaceService(
    conf: KyuubiConf,
    store: DbtStore,
    engineProfiles: EngineProfileService,
    computeAllocations: ComputeAllocationService,
    computeBindings: ComputeBindingService,
    runner: DbtRunner = DisabledDbtRunner) extends Logging {
  import DbtAction._

  private val artifactMapper = new ObjectMapper()

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

  /** Creates and dispatches only when a runner implementation has been installed. */
  def submitJob(principal: NotebookPrincipal, id: String): DbtJobRun = {
    submitJob(principal, id, None)
  }

  def submitJob(
      principal: NotebookPrincipal,
      id: String,
      clientRequestId: Option[String]): DbtJobRun = {
    val job = authorizeJob(loadJob(id), principal)
    val workspace = authorizeWorkspace(loadWorkspace(job.workspaceId), principal)
    submit(
      principal,
      workspace,
      Some(job),
      job.action,
      job.selector,
      clientRequestId,
      ComputeTriggerType.MANUAL)
  }

  /** Headless scheduler entry point; the trigger is server-owned and cannot come from a body. */
  def submitScheduledJob(
      principal: NotebookPrincipal,
      id: String,
      clientRequestId: Option[String]): DbtJobRun = {
    submitScheduledJob(principal, id, clientRequestId, runner)
  }

  private[server] def submitScheduledJob(
      principal: NotebookPrincipal,
      id: String,
      clientRequestId: Option[String],
      runnerOverride: DbtRunner): DbtJobRun = {
    val requestId = requireScheduledRequestId(clientRequestId)
    val job = authorizeJob(loadJob(id), principal)
    val workspace = authorizeWorkspace(loadWorkspace(job.workspaceId), principal)
    submit(
      principal,
      workspace,
      Some(job),
      job.action,
      job.selector,
      Some(requestId),
      ComputeTriggerType.SCHEDULE,
      runnerOverride)
  }

  /** Test seam; production requests always use the runner installed in the service. */
  def submitJob(
      principal: NotebookPrincipal,
      id: String,
      runnerOverride: DbtRunner): DbtJobRun = {
    submitJob(principal, id, None, runnerOverride)
  }

  /** Test seam for cross-replica idempotency; production selects the installed runner. */
  private[server] def submitJob(
      principal: NotebookPrincipal,
      id: String,
      clientRequestId: Option[String],
      runnerOverride: DbtRunner): DbtJobRun = {
    val job = authorizeJob(loadJob(id), principal)
    val workspace = authorizeWorkspace(loadWorkspace(job.workspaceId), principal)
    submit(
      principal,
      workspace,
      Some(job),
      job.action,
      job.selector,
      clientRequestId,
      ComputeTriggerType.MANUAL,
      runnerOverride)
  }

  /**
   * Reserves managed compute for a DBT process that the authenticated caller starts locally.
   *
   * The returned bearer token is intentionally one-shot: it is never stored in DBT metadata and
   * cannot be recovered through get/status APIs.
   */
  def prepareManagedCli(
      principal: NotebookPrincipal,
      id: String): DbtManagedCliLaunch = {
    requireManagedCliEnabled()
    val job = authorizeJob(loadJob(id), principal)
    val workspace = authorizeWorkspace(loadWorkspace(job.workspaceId), principal)
    val profileId = job.engineProfileIdOverride.getOrElse(workspace.selectedEngineProfileId)
    requireProfile(profileId, principal)
    val runId = UUID.randomUUID().toString
    val attemptId = UUID.randomUUID().toString
    val allocation = computeAllocations.reserveJob(
      EngineProfilePrincipal(principal.user, principal.admin),
      profileId,
      ComputeWorkloadType.DBT,
      ComputeTriggerType.SCHEDULE,
      runId,
      attemptId)
    val runnerUser = conf.get(DBT_RUNNER_KYUUBI_USER).trim
    val binding =
      try computeBindings.issue(allocation.id, runnerUser)
      catch {
        case error: Exception =>
          computeAllocations.requestRelease(
            allocation.id,
            "DBT managed CLI binding issuance failed")
          throw error
      }
    val now = System.currentTimeMillis()
    val run = DbtJobRun(
      runId,
      workspace.id,
      Some(job.id),
      principal.user,
      principal.user,
      job.action,
      job.selector,
      workspace.projectRef,
      snapshotOf(allocation),
      attemptId,
      ComputeMode.JOB,
      DbtExecutionTransport.LOCAL_CLI,
      Some(allocation.id),
      Some(binding.bindingId),
      None,
      DbtRunState.RUNNING,
      None,
      None,
      None,
      now,
      Some(now),
      None,
      None,
      now,
      0L,
      logTruncated = false,
      runnerResourcesCleaned = true,
      runnerCleanupError = None,
      version = 1L)
    try store.createRun(run)
    catch {
      case error: Exception =>
        computeBindings.revoke(binding.bindingId)
        computeAllocations.requestRelease(allocation.id, "DBT managed CLI metadata creation failed")
        throw error
    }
    managedCliLaunch(
      run,
      job,
      binding)
  }

  private def managedCliLaunch(
      run: DbtJobRun,
      job: DbtJob,
      binding: org.apache.kyuubi.server.compute.IssuedComputeBinding): DbtManagedCliLaunch =
    DbtManagedCliLaunch(
      run.id,
      run.attemptId,
      run.computeAllocationId.get,
      run.profile.profileId,
      run.effectiveKyuubiUser,
      conf.get(DBT_RUNNER_KYUUBI_USER).trim,
      binding.bindingId,
      binding.token,
      binding.expiresAt,
      conf.get(DBT_MANAGED_CLI_HEARTBEAT_INTERVAL),
      configuredThreads,
      commandArguments(job.action, job.selector))

  def heartbeatManagedCli(principal: NotebookPrincipal, id: String): DbtJobRun = {
    requireManagedCliEnabled()
    val run = requireManagedCliRun(getRun(principal, id))
    if (run.state != DbtRunState.RUNNING) {
      throw NotebookException.versionConflict(
        s"managed DBT CLI run '${run.id}' no longer accepts heartbeats")
    }
    computeAllocations.heartbeatJob(
      EngineProfilePrincipal(run.effectiveKyuubiUser, admin = false),
      run.computeAllocationId.get,
      run.id,
      run.attemptId)
    store.getRun(run.id).getOrElse(run)
  }

  def completeManagedCli(
      principal: NotebookPrincipal,
      id: String,
      state: String,
      exitCode: Int,
      errorSummary: Option[String],
      dbtInvocationId: Option[String]): DbtJobRun = {
    requireManagedCliEnabled()
    val run = requireManagedCliRun(getRun(principal, id))
    if (DbtRunState.terminal.contains(run.state)) return run
    val requested = parseManagedCliTerminalState(state)
    val terminal = if (run.state == DbtRunState.CANCELLING) DbtRunState.CANCELLED else requested
    if (terminal == DbtRunState.SUCCEEDED && exitCode != 0) {
      throw NotebookException.invalid("a successful managed DBT CLI run requires exit code 0")
    }
    if (terminal == DbtRunState.FAILED && exitCode == 0) {
      throw NotebookException.invalid("a failed managed DBT CLI run requires a non-zero exit code")
    }
    val invocationId = dbtInvocationId.map(_.trim).filter(_.nonEmpty)
    if (invocationId.exists(_.length > 64)) {
      throw NotebookException.invalid("dbt invocation ID must not exceed 64 characters")
    }
    requestComputeCleanup(run, s"managed DBT CLI run became $terminal")
    val now = System.currentTimeMillis()
    val updated = run.copy(
      dbtInvocationId = invocationId.orElse(run.dbtInvocationId),
      state = terminal,
      finishedAt = Some(now),
      errorSummary = if (terminal == DbtRunState.FAILED) {
        errorSummary.map(_.trim).filter(_.nonEmpty).map(_.take(2048))
          .orElse(Some(s"dbt exited with code $exitCode"))
      } else {
        None
      },
      stateUpdatedAt = now,
      runnerResourcesCleaned = true,
      runnerCleanupError = None,
      version = run.version + 1)
    if (store.updateRun(updated, DbtRunState.active)) updated
    else store.getRun(run.id).getOrElse(updated)
  }

  def preview(
      principal: NotebookPrincipal,
      workspaceId: String,
      selector: String): DbtJobRun = {
    val workspace = authorizeWorkspace(loadWorkspace(workspaceId), principal)
    validateSelector(PREVIEW, Option(selector))
    submit(
      principal,
      workspace,
      None,
      PREVIEW,
      Option(selector).map(_.trim),
      None,
      ComputeTriggerType.MANUAL)
  }

  def status(principal: NotebookPrincipal, id: String): ManagedComputeStatus = {
    val run = getRun(principal, id)
    computeAllocations.managedStatus(
      EngineProfilePrincipal(principal.user, principal.admin),
      ComputeWorkloadType.DBT,
      run.id,
      Some(run.attemptId),
      run.computeAllocationId,
      run.state.toString,
      run.errorSummary.orElse(run.runnerCleanupError))
  }

  def retryCleanup(principal: NotebookPrincipal, id: String): ManagedComputeStatus = {
    val run = getRun(principal, id)
    val allocationId = run.computeAllocationId.getOrElse {
      throw NotebookException.invalid(s"DBT run '$id' has no compute allocation")
    }
    computeAllocations.retryCleanup(
      EngineProfilePrincipal(principal.user, principal.admin),
      allocationId,
      ComputeWorkloadType.DBT)
    status(principal, id)
  }

  def artifact(
      principal: NotebookPrincipal,
      id: String,
      name: String): DbtRunArtifact = {
    getRun(principal, id)
    if (!DbtWorkspaceService.SupportedArtifacts.contains(name)) {
      throw NotebookException.invalid("unsupported DBT artifact name")
    }
    store.getArtifact(id, name).getOrElse(throw dbtNotFound("DBT artifact was not found"))
  }

  def reconcileActiveRuns(): Unit = store.listRunsForReconciliation().foreach { run =>
    try reconcile(run)
    catch {
      case NonFatal(error) => warn(s"Failed to reconcile DBT run ${run.id}", error)
    }
  }

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
        requestComputeCleanup(cancelling, "DBT run cancellation requested")
        if (run.executionTransport == DbtExecutionTransport.LOCAL_CLI) {
          completeCancellation(cancelling)
        } else {
          run.runnerRunId match {
            case Some(runnerRunId) => runner.cancel(runnerRunId)
            case None =>
              runner.findSubmission(run.id) match {
                case Some(submission) => runner.cancel(submission.runnerRunId)
                case None => completeCancellation(cancelling)
              }
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
      clientRequestId: Option[String],
      triggerType: ComputeTriggerType.Value,
      selectedRunner: DbtRunner = runner): DbtJobRun = {
    val profileId =
      job.flatMap(_.engineProfileIdOverride).getOrElse(workspace.selectedEngineProfileId)
    requireProfile(profileId, principal)
    val requestId = validateClientRequestId(clientRequestId)
    requestId.flatMap(store.findRunByRequestId(principal.user, _)).foreach { existing =>
      validateIdempotentRun(
        existing,
        workspace,
        job,
        action,
        selector,
        profileId,
        DbtExecutionTransport.KUBERNETES,
        triggerType)
      return existing
    }
    if (selectedRunner eq DisabledDbtRunner) throw new DbtRunnerNotConfiguredException
    val runId = requestId.map(stableRequestId(principal.user, _, "run"))
      .getOrElse(UUID.randomUUID().toString)
    val attemptId = requestId.map(stableRequestId(principal.user, _, "attempt"))
      .getOrElse(UUID.randomUUID().toString)
    val allocation = computeAllocations.reserveJob(
      EngineProfilePrincipal(principal.user, principal.admin),
      profileId,
      ComputeWorkloadType.DBT,
      triggerType,
      runId,
      attemptId)
    val snapshot = snapshotOf(allocation)
    val now = System.currentTimeMillis()
    val queued = DbtJobRun(
      runId,
      workspace.id,
      job.map(_.id),
      principal.user,
      principal.user,
      action,
      selector,
      workspace.projectRef,
      snapshot,
      attemptId,
      ComputeMode.JOB,
      DbtExecutionTransport.KUBERNETES,
      Some(allocation.id),
      None,
      None,
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
      runnerResourcesCleaned = false,
      runnerCleanupError = None,
      version = 1L,
      clientRequestId = requestId)
    try store.createRun(queued)
    catch {
      case error: Exception =>
        requestId.flatMap(store.findRunByRequestId(principal.user, _)) match {
          case Some(existing) =>
            validateIdempotentRun(
              existing,
              workspace,
              job,
              action,
              selector,
              profileId,
              DbtExecutionTransport.KUBERNETES,
              triggerType)
            return existing
          case None =>
            computeAllocations.requestRelease(allocation.id, "DBT run metadata creation failed")
            throw error
        }
    }
    val token = UUID.randomUUID().toString
    if (!store.claimDispatch(queued.id, queued.version, token, now)) {
      requestComputeCleanup(queued, "DBT dispatch claim failed")
      throw new IllegalStateException(s"Unable to claim DBT run ${queued.id} for dispatch")
    }
    val dispatching = store.getRun(queued.id).getOrElse(
      throw new IllegalStateException(s"DBT run ${queued.id} disappeared during dispatch"))
    dispatch(dispatching, selectedRunner)
  }

  private def dispatch(run: DbtJobRun, selectedRunner: DbtRunner): DbtJobRun = {
    val (boundRun, binding) =
      try prepareDispatchBinding(run)
      catch {
        case error: Exception =>
          failDispatch(run, "DBT compute binding issuance failed")
          throw error
      }
    try {
      val submission = selectedRunner.submit(DbtRunRequest(boundRun, binding))
      val submitted = boundRun.copy(
        state = DbtRunState.SUBMITTED,
        runnerRunId = Some(submission.runnerRunId),
        runnerJobUid = submission.runnerJobUid,
        stateUpdatedAt = System.currentTimeMillis(),
        version = boundRun.version + 1)
      if (store.updateRun(submitted, Set(DbtRunState.DISPATCHING))) {
        submitted
      } else {
        selectedRunner.cancel(submission.runnerRunId)
        requestComputeCleanup(boundRun, "DBT dispatch ownership changed")
        store.getRun(boundRun.id).getOrElse(submitted)
      }
    } catch {
      case e: DbtRunnerNotConfiguredException =>
        failDispatch(boundRun, "DBT runner is not configured")
        throw e
      case e: Exception =>
        try selectedRunner.cleanup(boundRun.id)
        catch {
          case cleanupError: Exception =>
            warn(s"Failed to clean DBT dispatch resources for run ${boundRun.id}", cleanupError)
        }
        failDispatch(boundRun, "DBT runner submission failed")
        throw e
    }
  }

  private def prepareDispatchBinding(run: DbtJobRun) = {
    val binding = computeBindings.issue(
      run.computeAllocationId.getOrElse {
        throw new IllegalStateException(s"DBT run ${run.id} has no managed compute allocation")
      },
      conf.get(DBT_RUNNER_KYUUBI_USER))
    val bound = run.copy(
      computeBindingId = Some(binding.bindingId),
      stateUpdatedAt = System.currentTimeMillis(),
      version = run.version + 1)
    if (!store.updateRun(bound, Set(DbtRunState.DISPATCHING))) {
      computeBindings.revoke(binding.bindingId)
      throw new IllegalStateException(s"DBT run ${run.id} changed while issuing its binding")
    }
    bound -> binding
  }

  private def failDispatch(run: DbtJobRun, message: String): Unit = {
    requestComputeCleanup(run, message)
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
    if (DbtRunState.terminal.contains(run.state)) {
      cleanupRunnerResources(run)
      return
    }
    if (run.executionTransport == DbtExecutionTransport.LOCAL_CLI) {
      reconcileManagedCli(run)
      return
    } else {
      try heartbeatCompute(run)
      catch {
        case NonFatal(error) =>
          warn(
            s"Failed to heartbeat compute for DBT run ${run.id}; status will still reconcile",
            error)
      }
    }
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
        val invocationId = persistArtifacts(run.id, page.content)
        val maxBytes = conf.get(DBT_RUNNER_LOG_MAX_BYTES)
        val available = math.max(0L, maxBytes - run.logNextOffset).toInt
        val bytes = page.content.getBytes("UTF-8")
        val content = new String(bytes.take(available), "UTF-8")
        if (content.nonEmpty && store.appendLog(run, content, System.currentTimeMillis())) {
          val logged = store.getRun(run.id).getOrElse(run)
          persistInvocationId(logged, invocationId)
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

  private def persistArtifacts(runId: String, output: String): Option[String] = {
    val maximum = conf.get(DBT_RUNNER_ARTIFACT_MAX_BYTES)
    val chunks = output.split("\\r?\\n").iterator.flatMap {
      case DbtWorkspaceService.ArtifactRecord(name, index, total, content) if
            DbtWorkspaceService.SupportedArtifacts.contains(name) =>
        Try(name -> (index.toInt, total.toInt, content)).toOption
      case _ => None
    }.toSeq.groupBy(_._1)
    chunks.iterator.flatMap { case (name, records) =>
      val values = records.map(_._2).sortBy(_._1)
      val expected = values.headOption.map(_._2).getOrElse(0)
      val complete = expected > 0 && values.size == expected &&
        values.map(_._1) == (0 until expected)
      if (!complete || values.exists(_._2 != expected)) {
        warn(s"Ignoring incomplete DBT artifact $name for run $runId")
        None
      } else {
        try {
          val bytes = Base64.getDecoder.decode(values.map(_._3).mkString)
          if (bytes.length > maximum) {
            warn(s"Ignoring oversized DBT artifact $name for run $runId")
            None
          } else {
            val content = new String(bytes, StandardCharsets.UTF_8)
            store.putArtifact(
              runId,
              DbtRunArtifact(name, content, System.currentTimeMillis()))
            invocationIdOf(content)
          }
        } catch {
          case NonFatal(error) =>
            warn(s"Could not persist DBT artifact $name for run $runId", error)
            None
        }
      }
    }.toSeq.headOption
  }

  private def invocationIdOf(artifact: String): Option[String] = {
    val value = artifactMapper.readTree(artifact).path("metadata").path("invocation_id").asText("")
    Option(value).map(_.trim).filter(_.nonEmpty)
  }

  private def persistInvocationId(run: DbtJobRun, invocationId: Option[String]): DbtJobRun = {
    invocationId.filter(_ => run.dbtInvocationId.isEmpty).map { value =>
      val updated = run.copy(
        dbtInvocationId = Some(value),
        stateUpdatedAt = System.currentTimeMillis(),
        version = run.version + 1)
      if (store.updateRun(updated, DbtRunState.active)) updated
      else store.getRun(run.id).getOrElse(run)
    }.getOrElse(run)
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
      if (DbtRunState.terminal.contains(nextState)) {
        requestComputeCleanup(run, s"DBT run became $nextState")
      }
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
        cleanupRunnerResources(updated)
      }
    }
  }

  private def completeCancellation(run: DbtJobRun): Unit = {
    requestComputeCleanup(run, "DBT run was cancelled")
    val cancelled = run.copy(
      state = DbtRunState.CANCELLED,
      finishedAt = Some(System.currentTimeMillis()),
      stateUpdatedAt = System.currentTimeMillis(),
      version = run.version + 1)
    if (store.updateRun(cancelled, Set(DbtRunState.CANCELLING))) {
      cleanupRunnerResources(cancelled)
    }
  }

  private def cleanupRunnerResources(run: DbtJobRun): Unit = {
    if (run.runnerResourcesCleaned) return
    try {
      runner.cleanup(run.id)
      val cleaned = run.copy(
        runnerResourcesCleaned = true,
        runnerCleanupError = None,
        stateUpdatedAt = System.currentTimeMillis(),
        version = run.version + 1)
      store.updateRun(cleaned, DbtRunState.terminal)
    } catch {
      case NonFatal(error) =>
        warn(s"Failed to clean DBT runner resources for run ${run.id}", error)
        val failed = run.copy(
          runnerCleanupError = Some(Option(error.getMessage).getOrElse(error.getClass.getName)),
          stateUpdatedAt = System.currentTimeMillis(),
          version = run.version + 1)
        store.updateRun(failed, DbtRunState.terminal)
    }
  }

  private def reconcileManagedCli(run: DbtJobRun): Unit = {
    if (run.state == DbtRunState.CANCELLING) {
      completeCancellation(run)
    } else if (run.computeAllocationId.forall(id => !computeAllocations.isAttachable(id))) {
      val now = System.currentTimeMillis()
      val failed = run.copy(
        state = DbtRunState.FAILED,
        finishedAt = Some(now),
        errorSummary = Some("managed DBT CLI heartbeat expired or its compute was released"),
        stateUpdatedAt = now,
        runnerResourcesCleaned = true,
        runnerCleanupError = None,
        version = run.version + 1)
      requestComputeCleanup(run, "managed DBT CLI process was lost")
      store.updateRun(failed, DbtRunState.active)
    }
  }

  private def heartbeatCompute(run: DbtJobRun): Unit = {
    if (DbtRunState.active.contains(run.state) && run.state != DbtRunState.CANCELLING) {
      run.computeAllocationId.foreach { allocationId =>
        computeAllocations.heartbeatJob(
          EngineProfilePrincipal(run.effectiveKyuubiUser, admin = false),
          allocationId,
          run.id,
          run.attemptId)
      }
    }
  }

  private def requestComputeCleanup(run: DbtJobRun, reason: String): Unit = {
    run.computeAllocationId.foreach { allocationId =>
      computeBindings.revokeAllocation(allocationId)
      computeAllocations.requestRelease(allocationId, reason)
    }
  }

  private def validateClientRequestId(value: Option[String]): Option[String] = {
    val normalized = value.map(_.trim).filter(_.nonEmpty)
    if (normalized.exists(_.length > 255)) {
      throw NotebookException.invalid("clientRequestId must not exceed 255 characters")
    }
    normalized
  }

  private def requireScheduledRequestId(value: Option[String]): String =
    validateClientRequestId(value).getOrElse {
      throw NotebookException.invalid(
        "Idempotency-Key is required for a scheduled DBT submission")
    }

  private def stableRequestId(owner: String, requestId: String, kind: String): String =
    UUID.nameUUIDFromBytes(
      s"kyuubi-dbt-$kind:${owner.length}:$owner:${requestId.length}:$requestId"
        .getBytes(StandardCharsets.UTF_8)).toString

  private def validateIdempotentRun(
      existing: DbtJobRun,
      workspace: DbtWorkspace,
      job: Option[DbtJob],
      action: DbtAction.Value,
      selector: Option[String],
      profileId: String,
      transport: DbtExecutionTransport.Value,
      triggerType: ComputeTriggerType.Value): Unit = {
    val actualTrigger = existing.computeAllocationId.map { allocationId =>
      computeAllocations.getOwnedAllocation(
        EngineProfilePrincipal(existing.effectiveKyuubiUser, admin = false),
        allocationId,
        ComputeWorkloadType.DBT).triggerType
    }
    val samePayload = existing.workspaceId == workspace.id &&
      existing.jobId == job.map(_.id) &&
      existing.action == action &&
      existing.selector.map(_.trim) == selector.map(_.trim) &&
      existing.projectRef == workspace.projectRef &&
      existing.profile.profileId == profileId &&
      existing.executionTransport == transport &&
      actualTrigger.contains(triggerType)
    if (!samePayload) {
      throw NotebookException.versionConflict(
        "clientRequestId was already used with a different DBT request")
    }
  }

  private def requireManagedCliEnabled(): Unit = {
    if (!conf.get(DBT_MANAGED_CLI_ENABLED)) {
      throw new NotebookException(
        NotebookErrorCode.DBT_RUNNER_NOT_CONFIGURED,
        "DBT managed CLI dispatch is not enabled on this server")
    }
    val interval = conf.get(DBT_MANAGED_CLI_HEARTBEAT_INTERVAL)
    if (interval >= conf.get(COMPUTE_JOB_HEARTBEAT_TIMEOUT)) {
      throw NotebookException.invalid(
        s"${DBT_MANAGED_CLI_HEARTBEAT_INTERVAL.key} must be shorter than " +
          COMPUTE_JOB_HEARTBEAT_TIMEOUT.key)
    }
    configuredThreads
  }

  private def requireManagedCliRun(run: DbtJobRun): DbtJobRun = {
    if (run.executionTransport != DbtExecutionTransport.LOCAL_CLI ||
      run.computeAllocationId.isEmpty || run.computeBindingId.isEmpty) {
      throw NotebookException.invalid(s"DBT run '${run.id}' is not a managed CLI run")
    }
    run
  }

  private def parseManagedCliTerminalState(value: String): DbtRunState.Value = {
    val parsed =
      try DbtRunState.withName(Option(value).map(_.trim.toUpperCase).getOrElse(""))
      catch {
        case _: NoSuchElementException =>
          throw NotebookException.invalid("managed DBT CLI terminal state is invalid")
      }
    if (!Set(DbtRunState.SUCCEEDED, DbtRunState.FAILED, DbtRunState.CANCELLED).contains(parsed)) {
      throw NotebookException.invalid("managed DBT CLI state must be terminal")
    }
    parsed
  }

  private def configuredThreads: Int = {
    val threads = conf.get(DBT_RUNNER_THREADS)
    val maximum = conf.get(DBT_RUNNER_THREADS_MAX)
    if (threads > maximum) {
      throw NotebookException.invalid(
        s"${DBT_RUNNER_THREADS.key} must not exceed ${DBT_RUNNER_THREADS_MAX.key}")
    }
    threads
  }

  private def commandArguments(
      action: DbtAction.Value,
      selector: Option[String]): Seq[String] = action match {
    case DbtAction.PREVIEW => Seq("show", "--select", selector.get)
    case DbtAction.RUN_MODEL => Seq("run", "--select", selector.get)
    case DbtAction.RUN_PROJECT => Seq("run")
  }

  private def snapshotOf(allocation: ComputeAllocation) = {
    val launch = allocation.launchSnapshot
    org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot(
      launch.profileId,
      launch.profileOwner,
      allocation.subdomain,
      launch.sparkConfig,
      launch.capturedAt,
      launch.notebookRuntimeIdleTimeout,
      launch.engineIdleTimeout,
      launch.pythonEnvironmentRevisionId,
      launch.profileVersion)
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

object DbtWorkspaceService {
  private[dbt] val ArtifactRecord =
    "^KYUUBI_DBT_ARTIFACT_V1 ([^ ]+) ([0-9]+) ([0-9]+) ([A-Za-z0-9+/=]+)$".r
  private[dbt] val SupportedArtifacts: Set[String] =
    Set("manifest.json", "run_results.json")
}
