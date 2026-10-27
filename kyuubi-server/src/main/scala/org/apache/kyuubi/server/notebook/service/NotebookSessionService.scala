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

import java.time.Duration
import java.util.UUID

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.server.compute.{ComputeAllocation, ComputeAllocationService, ComputeAllocationState, ComputeMode, ComputeWorkloadType}
import org.apache.kyuubi.server.engineprofile.EngineProfilePrincipal
import org.apache.kyuubi.server.engineprofile.EngineProfileService
import org.apache.kyuubi.server.engineprofile.EngineProfileSnapshot
import org.apache.kyuubi.server.engineprofile.PythonEnvironmentService
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.routing.NotebookSessionRegistry
import org.apache.kyuubi.server.notebook.routing.NotebookSessionRegistry.{NoRegisteredOwner, OwnerRegistryUnavailable, RegisteredOwner}
import org.apache.kyuubi.server.notebook.runtime.RuntimeAdapterRegistry
import org.apache.kyuubi.server.notebook.store.NotebookStore

/**
 * Notebook sessions and the runtimes inside them.
 *
 * A session belongs to the user who opened it, not to the notebook: sharing a notebook grants
 * access to its saved content, never to somebody else's compute. Every method here therefore
 * checks session ownership separately from the notebook permission that got the caller this far.
 */
class NotebookSessionService(
    store: NotebookStore,
    documents: NotebookDocumentService,
    permissions: NotebookPermissionService,
    runtimes: NotebookRuntimeService,
    instanceUri: () => String,
    registry: () => Option[NotebookSessionRegistry] = () => None) extends Logging {

  // Persistence precedes ZooKeeper registration when a session opens. Do not let a peer that
  // happens to list sessions inside that small window declare the brand-new session orphaned.
  private val orphanRegistrationGraceMillis = 30000L

  // The execution service is constructed after sessions. Keep this narrow lifecycle callback
  // rather than making either service own the other or bypassing this service from REST.
  @volatile private var beforeRuntimeInvalidation
      : (NotebookPrincipal, NotebookSession, String) => Unit =
    (_, _, _) => ()

  def onBeforeRuntimeInvalidation(
      callback: (NotebookPrincipal, NotebookSession, String) => Unit): Unit = {
    beforeRuntimeInvalidation = callback
  }

  /** Lets runtime-scoped REST actions apply the same execution invalidation as session actions. */
  def invalidateExecutions(
      principal: NotebookPrincipal,
      session: NotebookSession,
      reason: String): Unit = {
    beforeRuntimeInvalidation(principal, session, reason)
  }

  def create(
      principal: NotebookPrincipal,
      notebookId: String,
      request: CreateSessionRequest): NotebookSession = {
    val notebook = documents.loadNotebook(notebookId)
    // Opening a session is an execute action, which a viewer may not perform.
    permissions.requireWrite(notebook, principal)
    val now = System.currentTimeMillis()
    val session = NotebookSession(
      id = UUID.randomUUID().toString,
      notebookId = notebookId,
      owner = principal.user,
      state = NotebookSessionState.IDLE,
      // A notebook's saved profile is the server-side source of truth. In particular, an old
      // browser session can still send `default` after its runtime has stopped; allowing that
      // request field to win would silently launch a different engine on the next execution.
      runtimeProfile = notebook.runtimeProfile.orElse(
        Option(request).flatMap(r => Option(r.getRuntimeProfile))
          .map(_.trim).filter(_.nonEmpty)),
      createdAt = now,
      lastActivityAt = now,
      stoppedAt = None,
      failureMessage = None,
      kyuubiInstance = Some(instanceUri()),
      version = 1L)
    store.createSession(session)
    // Announce ownership only once the session exists, so no other instance is ever sent here
    // for something that failed to open.
    registry().foreach(_.register(session.id, instanceUri()))
    session
  }

  /**
   * Creates the isolated session owned by one server-dispatched Notebook JOB attempt.
   *
   * ComputeAllocationService authorizes and snapshots the profile before this session starts a
   * runtime. This path deliberately does not read the mutable Notebook document or accept browser
   * runtime configuration.
   */
  def createManagedJob(
      principal: NotebookPrincipal,
      notebookId: String,
      runtimeProfile: String): NotebookSession = {
    val now = System.currentTimeMillis()
    val session = NotebookSession(
      id = UUID.randomUUID().toString,
      notebookId = notebookId,
      owner = principal.user,
      state = NotebookSessionState.IDLE,
      runtimeProfile = Some(runtimeProfile),
      createdAt = now,
      lastActivityAt = now,
      stoppedAt = None,
      failureMessage = None,
      kyuubiInstance = Some(instanceUri()),
      version = 1L)
    store.createSession(session)
    registry().foreach(_.register(session.id, instanceUri()))
    session
  }

  def list(principal: NotebookPrincipal, notebookId: String): Seq[NotebookSession] = {
    val notebook = documents.loadNotebook(notebookId)
    permissions.requireRead(notebook, principal)
    val stored = store.listSessions(notebookId)
    reconcileOrphanedSessions(stored)
    // Headless scheduled runs own private sessions. Exposing one as the newest usable session
    // makes a browser attach to a JOB allocation which is deliberately stopped after the run.
    val managedSessionIds = store.listRuns(notebookId, Int.MaxValue)
      .iterator.map(_.notebookSessionId).toSet
    // Only the caller's own sessions; another user's session is not theirs to see or touch.
    store.listSessions(notebookId).filterNot(session => managedSessionIds.contains(session.id))
      .filter(session => principal.admin || session.owner == principal.user)
  }

  def load(sessionId: String): NotebookSession =
    store.getSession(sessionId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_SESSION_NOT_FOUND,
        s"notebook session $sessionId was not found")
    }

  def require(principal: NotebookPrincipal, sessionId: String): NotebookSession = {
    val session = load(sessionId)
    if (!principal.admin && session.owner != principal.user) {
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_SESSION_NOT_FOUND,
        s"notebook session $sessionId was not found")
    }
    requireLocal(session)
    session
  }

  /** A terminal session has no Python namespace or Kyuubi session left to execute against. */
  def requireForExecution(principal: NotebookPrincipal, sessionId: String): NotebookSession = {
    val session = require(principal, sessionId)
    if (NotebookSessionState.terminal.contains(session.state)) {
      throw new NotebookException(
        NotebookErrorCode.KYUUBI_SESSION_LOST,
        "the notebook session has stopped; open a new session before running a cell",
        retryable = true,
        details = Map("action" -> "new-session"))
    }
    session
  }

  /**
   * A session's runtimes live in the instance that created them, and a request for one is
   * normally sent there by the routing filter before it reaches this layer.
   *
   * Reaching here for a session owned elsewhere means the routing filter could not - the owner
   * is no longer registered, or routing is switched off - and the runtime really is unreachable.
   * Saying so is better than acting on the wrong process.
   */
  private def requireLocal(session: NotebookSession): Unit = {
    session.kyuubiInstance.filter(_ != instanceUri()).foreach { owner =>
      throw new NotebookException(
        NotebookErrorCode.KYUUBI_SESSION_LOST,
        "the runtime for this notebook session is no longer reachable; restart the session",
        retryable = false,
        details = Map("ownedBy" -> owner, "action" -> "restart-session"))
    }
  }

  def restart(principal: NotebookPrincipal, sessionId: String): NotebookSession = {
    val session = require(principal, sessionId)
    invalidateExecutions(principal, session, "execution canceled because the session restarted")
    runtimes.listFor(session).foreach(runtime => runtimes.restart(principal, session, runtime))
    touch(session, NotebookSessionState.IDLE)
  }

  /** Reset is a restart plus discarding transient session state; content is never touched. */
  def reset(principal: NotebookPrincipal, sessionId: String): NotebookSession = {
    val session = require(principal, sessionId)
    val resetting = touch(session, NotebookSessionState.RESETTING)
    invalidateExecutions(principal, resetting, "execution canceled because the session reset")
    runtimes.listFor(resetting).foreach(runtime =>
      runtimes.restart(principal, resetting, runtime))
    touch(resetting, NotebookSessionState.IDLE)
  }

  /** Idempotent: stopping an already stopped session succeeds and changes nothing. */
  def stop(principal: NotebookPrincipal, sessionId: String): NotebookSession = {
    val session = require(principal, sessionId)
    if (NotebookSessionState.terminal.contains(session.state)) {
      session
    } else {
      val stopping = touch(session, NotebookSessionState.STOPPING)
      invalidateExecutions(principal, stopping, "execution canceled because the session stopped")
      runtimes.listFor(stopping).foreach(runtime => runtimes.stop(runtime))
      val now = System.currentTimeMillis()
      val stopped = stopping.copy(
        state = NotebookSessionState.STOPPED,
        stoppedAt = Some(now),
        lastActivityAt = now,
        version = stopping.version + 1)
      if (!store.updateSession(stopped, stopping.version)) {
        throw NotebookException.versionConflict("the session was modified concurrently")
      }
      // Drop the claim now rather than leaving it for the node to expire with this pod: another
      // instance asking about a deliberately stopped session should be told it is gone at once.
      registry().foreach(_.unregister(sessionId))
      stopped
    }
  }

  def touch(session: NotebookSession, state: NotebookSessionState.Value): NotebookSession = {
    val updated = session.copy(
      state = state,
      lastActivityAt = System.currentTimeMillis(),
      version = session.version + 1)
    if (!store.updateSession(updated, session.version)) {
      throw NotebookException.versionConflict("the session was modified concurrently")
    }
    updated
  }

  /**
   * Reclaims idle runtimes across every live session, and stops a session once nothing is left
   * running in it. Called on a timer, so an abandoned notebook does not hold an interpreter and
   * a Kyuubi session open indefinitely.
   */
  def reapIdle(idleTimeoutMillis: Long): Unit = {
    reconcileOrphanedSessions(store.listLiveSessions())
    store.listLiveSessions().filter(isLocal).foreach { session =>
      try {
        // Execution state is durable but its adapter handle is not. Mark it terminal before
        // closing a runtime; otherwise a row left RUNNING by a disconnected browser can block
        // every later cell in the session's single-flight queue forever.
        val reclaimable = runtimes.idleRuntimes(session, idleTimeoutMillis)
        if (reclaimable.nonEmpty) {
          invalidateExecutions(
            NotebookPrincipal(session.owner, admin = false),
            session,
            "execution lost because the notebook runtime was stopped after idle timeout")
        }
        val reclaimed = reclaimable.map(runtimes.stop)
        val remaining = runtimes.listFor(session)
        val inheritedSessionExpired = idleTimeoutMillis > 0 &&
          session.lastActivityAt < System.currentTimeMillis() - idleTimeoutMillis
        if (remaining.isEmpty && (reclaimed.nonEmpty || inheritedSessionExpired)) {
          // There may already have been no runtime row, for example after a prior failed
          // close. Do not leave a durable RUNNING execution behind in that case either.
          invalidateExecutions(
            NotebookPrincipal(session.owner, admin = false),
            session,
            "execution lost because the notebook session expired after idle timeout")
          val now = System.currentTimeMillis()
          val stopped = session.copy(
            state = NotebookSessionState.STOPPED,
            stoppedAt = Some(now),
            lastActivityAt = now,
            version = session.version + 1)
          store.updateSession(stopped, session.version)
          registry().foreach(_.unregister(session.id))
        }
      } catch {
        // Keep the runtime and its internal handle durable. The next reaper pass can retry the
        // backend close instead of forgetting a live session that would block engine idleness.
        case NonFatal(e) => warn(s"Failed to reap notebook session ${session.id}", e)
      }
    }
  }

  /**
   * A runtime is an in-process Kyuubi session. With a shared metadata store every replica sees
   * its row, but only the owning replica can close its session handle. Reaping a peer's row
   * would mark it stopped while leaving the Spark engine's session alive indefinitely.
   */
  private def isLocal(session: NotebookSession): Boolean = {
    val local = instanceUri()
    registry().map(_.lookupOwner(session.id)) match {
      case Some(RegisteredOwner(owner)) => owner == local
      case Some(NoRegisteredOwner) | Some(OwnerRegistryUnavailable) | None =>
        // Registry registration happens immediately after persistence, but keep the persisted
        // owner as a short-lived fallback if ZooKeeper is unavailable during that narrow window.
        session.kyuubiInstance.contains(local)
    }
  }

  private def isOrphaned(session: NotebookSession, now: Long): Boolean = {
    val registrationExpired = session.createdAt <= now - orphanRegistrationGraceMillis
    val ownedByAnotherFrontend = session.kyuubiInstance.exists(_ != instanceUri())
    registrationExpired && ownedByAnotherFrontend && registry().exists(
      _.lookupOwner(session.id) == NoRegisteredOwner)
  }

  private def reconcileOrphanedSessions(candidates: Seq[NotebookSession]): Unit = {
    val now = System.currentTimeMillis()
    candidates.filterNot(session => NotebookSessionState.terminal.contains(session.state))
      .filter(isOrphaned(_, now)).foreach { session =>
        markSessionLost(
          session,
          "the Kyuubi instance that owned this notebook session is no longer available")
      }
  }

  private def markSessionLost(session: NotebookSession, reason: String): Unit = {
    try {
      invalidateExecutions(NotebookPrincipal(session.owner, admin = false), session, reason)
      runtimes.listFor(session).foreach(runtimes.markLost)
      store.getSession(session.id)
        .filterNot(current => NotebookSessionState.terminal.contains(current.state)).foreach {
          current =>
            val now = System.currentTimeMillis()
            val lost = current.copy(
              state = NotebookSessionState.LOST,
              failureMessage = Some(reason),
              lastActivityAt = now,
              stoppedAt = Some(now),
              version = current.version + 1)
            store.updateSession(lost, current.version)
        }
      registry().foreach(_.unregister(session.id))
    } catch {
      case NonFatal(e) => warn(s"Failed to mark orphaned notebook session ${session.id} lost", e)
    }
  }

  /** Whether this frontend owns the in-process Kyuubi session for a durable session row. */
  def isLocalFrontend(session: NotebookSession): Boolean = isLocal(session)

  /**
   * Marks sessions whose runtimes cannot have survived. Called at startup: a session created by
   * this instance before a restart has no live Kyuubi session behind it any more, and pretending
   * otherwise would make the first execution fail in a confusing way.
   */
  def reconcileAfterRestart(): Unit = {
    // Only this frontend knows that its own in-process Kyuubi session handles disappeared.
    // Invalidating shared-store sessions owned by a peer disconnects otherwise healthy users.
    val live = store.listLiveSessions()
    live.filter(isLocal).foreach { session =>
      markSessionLost(session, "the server restarted while this session was open")
    }
    reconcileOrphanedSessions(live)
  }
}

/**
 * Runtime lifecycle. Everything that touches a backend goes through an adapter, so this class
 * stays the same whether the runtime is SQL or, later, CPython.
 */
class NotebookRuntimeService(
    store: NotebookStore,
    registry: RuntimeAdapterRegistry,
    instanceUri: () => String,
    engineProfiles: Option[EngineProfileService] = None,
    pythonEnvironments: Option[PythonEnvironmentService] = None,
    computeAllocations: Option[ComputeAllocationService] = None) extends Logging {

  def specs: Seq[RuntimeSpec] = registry.specs

  def spec(runtimeSpecId: String): RuntimeSpec = registry.spec(runtimeSpecId)

  def listFor(session: NotebookSession): Seq[NotebookRuntime] =
    store.listRuntimes(session.id).filterNot(runtime =>
      RuntimeState.terminal.contains(runtime.state))

  def list(session: NotebookSession): Seq[NotebookRuntime] = store.listRuntimes(session.id)

  def load(runtimeId: String): NotebookRuntime =
    store.getRuntime(runtimeId).getOrElse {
      throw NotebookException.notFound(
        NotebookErrorCode.RUNTIME_NOT_FOUND,
        s"runtime $runtimeId was not found")
    }

  def require(principal: NotebookPrincipal, runtimeId: String): NotebookRuntime = {
    val runtime = load(runtimeId)
    if (!principal.admin && runtime.owner != principal.user) {
      throw NotebookException.notFound(
        NotebookErrorCode.RUNTIME_NOT_FOUND,
        s"runtime $runtimeId was not found")
    }
    runtime
  }

  /** Reuses the session's one live Spark runtime, starting it only when there is none. */
  def ensureFor(
      principal: NotebookPrincipal,
      session: NotebookSession,
      requestedSpecId: Option[String],
      configuration: Map[String, String]): NotebookRuntime =
    ensureForAllocation(principal, session, requestedSpecId, configuration, None)

  /**
   * Starts or reuses a Notebook runtime on an explicit server-issued allocation. The overload is
   * the integration boundary for the headless Notebook runner; an allocation ID is never accepted
   * from the browser-facing session request.
   */
  def ensureForAllocation(
      principal: NotebookPrincipal,
      session: NotebookSession,
      requestedSpecId: Option[String],
      configuration: Map[String, String],
      allocationId: String): NotebookRuntime = {
    if (computeAllocations.isEmpty) {
      throw NotebookException.invalid("managed Notebook compute allocation is not configured")
    }
    ensureForAllocation(principal, session, requestedSpecId, configuration, Some(allocationId))
  }

  private def ensureForAllocation(
      principal: NotebookPrincipal,
      session: NotebookSession,
      requestedSpecId: Option[String],
      configuration: Map[String, String],
      requestedAllocationId: Option[String]): NotebookRuntime = {
    val specId = requestedSpecId.getOrElse(registry.defaultSpecFor(CellLanguage.SQL).id)
    computeAllocations.foreach(_.rejectReservedClientConfiguration(configuration))
    requestedAllocationId.foreach { allocationId =>
      val terminalRuntime = list(session).find(runtime =>
        runtime.computeAllocationId.contains(allocationId) &&
          RuntimeState.terminal.contains(runtime.state))
      terminalRuntime.foreach { _ =>
        val allocation = computeAllocations.get.getNotebookAllocation(
          profilePrincipal(principal),
          allocationId)
        if (allocation.mode == ComputeMode.JOB) {
          throw new NotebookException(
            NotebookErrorCode.KYUUBI_SESSION_LOST,
            "the managed Notebook job cannot replace a terminal runtime; retry it as a new " +
              "attempt",
            retryable = false)
        }
      }
    }
    val existing = listFor(session).headOption.map(refresh)
    existing match {
      case Some(runtime) if !RuntimeState.terminal.contains(runtime.state) =>
        requestedAllocationId.foreach { allocationId =>
          if (!runtime.computeAllocationId.contains(allocationId)) {
            throw NotebookException.invalid(
              "the Notebook session is already attached to a different compute allocation")
          }
        }
        // Editing, adding, moving or deleting a cell touches notebook.updatedAt for document
        // optimistic locking. Those changes must not recreate a Python worker: its global
        // namespace is the state shared by notebook cells. A selected Engine Profile is changed
        // through the explicit UI/session lifecycle, which stops the session before its next
        // runtime is created.
        warn(s"Reusing Spark runtime ${runtime.id} for session ${session.id}")
        runtime
      case Some(runtime) if isJobRuntime(principal, runtime) =>
        throw new NotebookException(
          NotebookErrorCode.KYUUBI_SESSION_LOST,
          "the managed Notebook job lost its Spark session; retry it as a new attempt",
          retryable = false)
      case _ =>
        val allocation = resolveAllocation(principal, session, requestedAllocationId)
        val profileSnapshot = if (allocation.isEmpty) {
          resolveProfileSnapshot(principal, session)
        } else {
          None
        }
        val serverConfig = allocation.map(notebookAllocationConfiguration).getOrElse(
          profileSessionConfig(profileSnapshot))
        val mergedConfig = configuration ++ serverConfig
        val runtimeIdleTimeoutMillis = allocation.flatMap(value =>
          value.launchSnapshot.notebookRuntimeIdleTimeout.map(timeout =>
            Duration.parse(timeout).toMillis)).orElse(
          profileSnapshot.flatMap(EngineProfileSnapshot.notebookRuntimeIdleTimeoutMillis))
        val environmentRevisionId = allocation.flatMap(
          _.launchSnapshot.pythonEnvironmentRevisionId).orElse(
          profileSnapshot.flatMap(snapshot =>
            pythonEnvironments.flatMap(_.environmentRevisionId(snapshot))))
        warn(s"No existing runtime found. Creating new runtime for session ${session.id}")
        create(
          session,
          specId,
          mergedConfig,
          runtimeIdleTimeoutMillis,
          environmentRevisionId,
          allocation.map(_.id))
    }
  }

  def create(
      session: NotebookSession,
      runtimeSpecId: String,
      configuration: Map[String, String],
      runtimeIdleTimeoutMillis: Option[Long] = None,
      environmentRevisionId: Option[String] = None,
      computeAllocationId: Option[String] = None): NotebookRuntime = {
    // A notebook session is deliberately a single Spark conversation. The explicit runtime API
    // is retained for compatibility, but cannot create a second live session behind a notebook.
    listFor(session).headOption.foreach(return _)
    val adapter = registry.get(runtimeSpecId)
    val spec = adapter.runtimeSpec
    if (!spec.enabled) {
      throw new NotebookException(
        NotebookErrorCode.RUNTIME_SPEC_NOT_FOUND,
        s"runtime spec $runtimeSpecId is disabled")
    }
    val now = System.currentTimeMillis()
    val creating = NotebookRuntime(
      id = UUID.randomUUID().toString,
      notebookSessionId = session.id,
      runtimeSpecId = spec.id,
      runtimeType = adapter.runtimeType,
      language = CellLanguage.withName(spec.language),
      owner = session.owner,
      state = RuntimeState.CREATING,
      generation = 1L,
      computeAllocationId = computeAllocationId,
      environmentRevisionId = environmentRevisionId,
      runtimeIdleTimeoutMillis = runtimeIdleTimeoutMillis,
      createdAt = now,
      lastActivityAt = now,
      stoppedAt = None,
      failureMessage = None,
      internalRuntimeHandle = None,
      internalRuntimeLocation = None,
      version = 1L)
    // Persisted before the backend call, so a crash mid-start leaves a row to reconcile rather
    // than an orphaned Kyuubi session nobody knows about.
    store.createRuntime(creating)
    try {
      val started = adapter.startRuntime(creating, configuration)
      computeAllocationId.foreach(id =>
        computeAllocations.foreach(
          _.recordEngineReference(id, started.handle)))
      val ready = creating.copy(
        state = RuntimeState.IDLE,
        internalRuntimeHandle = Some(started.handle),
        internalRuntimeLocation = started.location.orElse(Some(instanceUri())),
        lastActivityAt = System.currentTimeMillis(),
        version = creating.version + 1)
      store.updateRuntime(ready, creating.version)
      ready
    } catch {
      case NonFatal(e) =>
        val failed = creating.copy(
          state = RuntimeState.FAILED,
          failureMessage = Some("the runtime could not be started"),
          lastActivityAt = System.currentTimeMillis(),
          version = creating.version + 1)
        store.updateRuntime(failed, creating.version)
        warn(s"Failed to start runtime ${creating.id}", e)
        throw new NotebookException(
          NotebookErrorCode.KYUUBI_UNAVAILABLE,
          "the runtime could not be started",
          retryable = true,
          cause = e)
    }
  }

  def refresh(runtime: NotebookRuntime): NotebookRuntime = {
    if (RuntimeState.terminal.contains(runtime.state)) {
      runtime
    } else {
      val status = registry.get(runtime.runtimeSpecId).getRuntimeStatus(runtime)
      if (status.state == runtime.state) {
        runtime
      } else {
        val updated = runtime.copy(
          state = status.state,
          failureMessage = status.failureMessage.orElse(runtime.failureMessage),
          lastActivityAt = System.currentTimeMillis(),
          version = runtime.version + 1)
        store.updateRuntime(updated, runtime.version)
        updated
      }
    }
  }

  def interrupt(runtime: NotebookRuntime): NotebookRuntime = transition(
    runtime,
    RuntimeState.INTERRUPTING,
    () => (),
    RuntimeState.IDLE)

  /** A restart clears variables, so the generation is bumped and old executions stay attributed. */
  def restart(
      principal: NotebookPrincipal,
      session: NotebookSession,
      runtime: NotebookRuntime): NotebookRuntime = {
    val adapter = registry.get(runtime.runtimeSpecId)
    val restarting = store.getRuntime(runtime.id).getOrElse(runtime)
    val allocation = restarting.computeAllocationId.flatMap { id =>
      computeAllocations.map(_.getNotebookAllocation(profilePrincipal(principal), id))
    }
    if (allocation.exists(_.mode == ComputeMode.JOB)) {
      throw NotebookException.invalid(
        "a managed Notebook job cannot restart its runtime; submit a new attempt")
    }
    val usableAllocation = allocation.filter(current =>
      Set(
        ComputeAllocationState.RESERVED,
        ComputeAllocationState.STARTING,
        ComputeAllocationState.ACTIVE).contains(current.state))
      .orElse(resolveAllocation(principal, session, None))
    val profileSnapshot = if (usableAllocation.isEmpty) {
      resolveProfileSnapshot(principal, session)
    } else {
      None
    }
    val serverConfig = usableAllocation.map(notebookAllocationConfiguration).getOrElse(
      profileSessionConfig(profileSnapshot))
    try {
      val started = adapter.restartRuntime(restarting, serverConfig)
      val updated = restarting.copy(
        state = RuntimeState.IDLE,
        generation = restarting.generation + 1,
        computeAllocationId = usableAllocation.map(_.id),
        environmentRevisionId = usableAllocation.flatMap(
          _.launchSnapshot.pythonEnvironmentRevisionId).orElse(
          profileSnapshot.flatMap(snapshot =>
            pythonEnvironments.flatMap(_.environmentRevisionId(snapshot)))),
        runtimeIdleTimeoutMillis = usableAllocation.flatMap(value =>
          value.launchSnapshot.notebookRuntimeIdleTimeout.map(timeout =>
            Duration.parse(timeout).toMillis)).orElse(
          profileSnapshot.flatMap(EngineProfileSnapshot.notebookRuntimeIdleTimeoutMillis)),
        internalRuntimeHandle = Some(started.handle),
        internalRuntimeLocation = started.location.orElse(Some(instanceUri())),
        failureMessage = None,
        lastActivityAt = System.currentTimeMillis(),
        version = restarting.version + 1)
      store.updateRuntime(updated, restarting.version)
      updated
    } catch {
      case NonFatal(e) =>
        warn(s"Failed to restart runtime ${runtime.id}", e)
        markFailed(restarting, "the runtime could not be restarted")
    }
  }

  private def resolveProfileSnapshot(
      principal: NotebookPrincipal,
      session: NotebookSession): Option[EngineProfileSnapshot] = {
    // The session pins the selected profile. Editing the Notebook document later must not move
    // an existing interactive session, and a managed job must use its allocation snapshot.
    val profileId = session.runtimeProfile
    // A completed Python environment build is deliberately not applied to a live engine. The
    // service also prevents a replacement Driver from starting with env-N while env-N+1 is being
    // restored after the old Driver disappeared.
    profileId.foreach(id =>
      pythonEnvironments.foreach(_.prepareForEngineLaunch(id)))
    profileId.flatMap(id =>
      engineProfiles.map(_.snapshotForUse(
        id,
        EngineProfilePrincipal(principal.user, principal.admin))))
  }

  private def resolveAllocation(
      principal: NotebookPrincipal,
      session: NotebookSession,
      requestedAllocationId: Option[String]): Option[ComputeAllocation] =
    computeAllocations.map { allocations =>
      requestedAllocationId match {
        case Some(id) => allocations.requireNotebookAllocation(profilePrincipal(principal), id)
        case None =>
          allocations.acquireInteractive(
            profilePrincipal(principal),
            session.runtimeProfile.getOrElse(EngineProfileService.DefaultProfileId),
            ComputeWorkloadType.NOTEBOOK)
      }
    }

  private def notebookAllocationConfiguration(
      allocation: ComputeAllocation): Map[String, String] = {
    val notebookIdleTimeout = allocation.launchSnapshot.notebookRuntimeIdleTimeout.map { timeout =>
      "kyuubi.session.idle.timeout" -> Duration.parse(timeout).toMillis.toString
    }.toMap
    computeAllocations.get.routingConfiguration(allocation) ++ notebookIdleTimeout
  }

  private def isJobRuntime(
      principal: NotebookPrincipal,
      runtime: NotebookRuntime): Boolean =
    runtime.computeAllocationId.exists { allocationId =>
      computeAllocations.exists(_.getNotebookAllocation(
        profilePrincipal(principal),
        allocationId).mode == ComputeMode.JOB)
    }

  private def profilePrincipal(principal: NotebookPrincipal): EngineProfilePrincipal =
    EngineProfilePrincipal(principal.user, principal.admin)

  /** Returns only server-owned profile settings to use when opening a Kyuubi session. */
  private def profileSessionConfig(snapshot: Option[EngineProfileSnapshot]): Map[String, String] = {
    val profileSparkConfig = snapshot.map(_.sparkConfig).getOrElse(Map.empty)
    val engineTimeoutConfig = snapshot.map(EngineProfileSnapshot.engineSessionConfig)
      .getOrElse(Map.empty)
    val notebookSessionConfig = snapshot.map(EngineProfileSnapshot.notebookSessionConfig)
      .getOrElse(Map.empty)
    val pythonEnvironmentConfig = snapshot.map(value =>
      pythonEnvironments.map(_.launchConfig(value)).getOrElse(Map.empty)).getOrElse(Map.empty)
    val sessionOverlay = snapshot.map(value =>
      Map(
        "kyuubi.engine.type" -> "SPARK_SQL",
        "kyuubi.engine.share.level" -> "USER",
        "kyuubi.engine.share.level.subdomain" -> value.subdomain,
        "kyuubi.engine.share.level.sub.domain" -> value.subdomain)).getOrElse(Map.empty)
    profileSparkConfig ++ engineTimeoutConfig ++ notebookSessionConfig ++
      pythonEnvironmentConfig ++ sessionOverlay
  }

  /** Idempotent: stopping a stopped runtime is a no-op that still reports success. */
  def stop(runtime: NotebookRuntime): NotebookRuntime = {
    if (RuntimeState.terminal.contains(runtime.state)) {
      runtime
    } else {
      // Do not erase the backend handle when close fails. Keeping the row non-terminal allows the
      // idle reaper or an explicit Stop retry to close the session on a later pass.
      registry.get(runtime.runtimeSpecId).stopRuntime(runtime)
      val now = System.currentTimeMillis()
      val stopped = runtime.copy(
        state = RuntimeState.STOPPED,
        internalRuntimeHandle = None,
        stoppedAt = Some(now),
        lastActivityAt = now,
        version = runtime.version + 1)
      store.updateRuntime(stopped, runtime.version)
      stopped
    }
  }

  /**
   * Records that a runtime was used. Without this the idle reaper would measure time since the
   * runtime started rather than since it last did anything, and would stop a busy runtime.
   */
  def touchActivity(runtime: NotebookRuntime): NotebookRuntime = {
    val touched = runtime.copy(
      lastActivityAt = System.currentTimeMillis(),
      version = runtime.version + 1)
    if (store.updateRuntime(touched, runtime.version)) touched else runtime
  }

  /**
   * Stops runtimes that have been idle for longer than the timeout, releasing the interpreter
   * process and its scratch directory. Variables and anything installed from inside a cell go
   * with it; a managed environment is on disk and is untouched.
   */
  def idleRuntimes(
      session: NotebookSession,
      defaultIdleTimeoutMillis: Long): Seq[NotebookRuntime] =
    listFor(session)
      .filter(runtime => runtime.state != RuntimeState.BUSY)
      .flatMap { runtime =>
        val idleTimeoutMillis = runtime.runtimeIdleTimeoutMillis.getOrElse(defaultIdleTimeoutMillis)
        if (idleTimeoutMillis > 0 &&
          runtime.lastActivityAt < System.currentTimeMillis() - idleTimeoutMillis) {
          info(s"Reclaiming runtime ${runtime.id} after ${idleTimeoutMillis}ms idle")
          Some(runtime)
        } else {
          None
        }
      }

  def reapIdle(session: NotebookSession, defaultIdleTimeoutMillis: Long): Seq[NotebookRuntime] =
    idleRuntimes(session, defaultIdleTimeoutMillis).map(stop)

  def markLost(runtime: NotebookRuntime): NotebookRuntime = {
    if (RuntimeState.terminal.contains(runtime.state)) {
      runtime
    } else {
      val now = System.currentTimeMillis()
      val lost = runtime.copy(
        state = RuntimeState.LOST,
        internalRuntimeHandle = None,
        failureMessage = Some("the runtime did not survive a server restart"),
        stoppedAt = Some(now),
        lastActivityAt = now,
        version = runtime.version + 1)
      store.updateRuntime(lost, runtime.version)
      lost
    }
  }

  private def markFailed(runtime: NotebookRuntime, message: String): NotebookRuntime = {
    val failed = runtime.copy(
      state = RuntimeState.FAILED,
      failureMessage = Some(message),
      lastActivityAt = System.currentTimeMillis(),
      version = runtime.version + 1)
    store.updateRuntime(failed, runtime.version)
    failed
  }

  private def transition(
      runtime: NotebookRuntime,
      during: RuntimeState.Value,
      action: () => Unit,
      after: RuntimeState.Value): NotebookRuntime = {
    val marked = runtime.copy(
      state = during,
      lastActivityAt = System.currentTimeMillis(),
      version = runtime.version + 1)
    store.updateRuntime(marked, runtime.version)
    action()
    val settled = marked.copy(
      state = after,
      lastActivityAt = System.currentTimeMillis(),
      version = marked.version + 1)
    store.updateRuntime(settled, marked.version)
    settled
  }

  def configurationOf(request: CreateRuntimeRequest): Map[String, String] =
    Option(request).flatMap(r => Option(r.getConfiguration))
      .map(_.asScala.toMap)
      .getOrElse(Map.empty)
}
