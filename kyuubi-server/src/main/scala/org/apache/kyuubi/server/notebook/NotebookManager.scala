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

package org.apache.kyuubi.server.notebook

import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_ADDRESSES
import org.apache.kyuubi.ha.client.{DiscoveryClient, DiscoveryClientProvider}
import org.apache.kyuubi.server.notebook.NotebookConf._
import org.apache.kyuubi.server.notebook.api.{ExecutionState, NotebookErrorCode, NotebookException, NotebookSession, NotebookSessionState, NotebookStatusView}
import org.apache.kyuubi.server.notebook.routing.{NotebookSessionRegistry, RouteKey}
import org.apache.kyuubi.server.notebook.runtime.{KyuubiSqlRuntimeAdapter, PySparkRuntimeAdapter, RuntimeAdapterRegistry}
import org.apache.kyuubi.server.notebook.service._
import org.apache.kyuubi.server.notebook.store.{ExecutionFilter, NotebookStore}
import org.apache.kyuubi.service.{AbstractService, BackendService}
import org.apache.kyuubi.util.ThreadUtils
import org.apache.kyuubi.util.ThreadUtils.scheduleTolerableRunnableWithFixedDelay

/**
 * Composition root and lifecycle owner of the notebook subsystem. REST resources reach the
 * services only through here, so nothing in the API layer constructs a store or holds state.
 */
class NotebookManager(
    backendService: () => BackendService,
    instanceUri: () => String) extends AbstractService("NotebookManager") {
  import NotebookManager._

  @volatile private var _store: NotebookStore = _
  @volatile private var _permissions: NotebookPermissionService = _
  @volatile private var _revisions: NotebookRevisionService = _
  @volatile private var _documents: NotebookDocumentService = _
  @volatile private var _content: NotebookContentService = _
  @volatile private var _schedules: NotebookScheduleService = _
  @volatile private var _registry: RuntimeAdapterRegistry = _
  @volatile private var _runtimes: NotebookRuntimeService = _
  @volatile private var _sessions: NotebookSessionService = _
  @volatile private var _executions: NotebookExecutionService = _

  @volatile private var _sessionRegistry: NotebookSessionRegistry = _

  def store: NotebookStore = _store

  /** Where the routing filter looks up which instance owns a session. */
  def sessionRegistry: Option[NotebookSessionRegistry] = Option(_sessionRegistry)

  /**
   * Whether this process is holding the session, which is the authoritative answer.
   *
   * The registry can lag a moment behind an open; a live runtime here cannot.
   */
  def isSessionLocal(sessionId: String): Boolean =
    Option(_runtimes).exists(_ =>
      _store.getSession(sessionId).exists(_.kyuubiInstance
        .contains(instanceUri())))

  /**
   * The session a routed path concerns, when the path itself does not name one.
   *
   * An execution names its session; a notebook is resolved through whichever of its sessions is
   * still live. A miss is not an error - it simply means this instance cannot say, and the
   * registry answers instead.
   */
  def sessionIdOf(key: RouteKey): Option[String] = key match {
    case RouteKey.Session(id) => Some(id)
    case RouteKey.Execution(id) => _store.getExecution(id).map(_.notebookSessionId)
    case RouteKey.Notebook(id) =>
      _store.listSessions(id).find(_.state != NotebookSessionState.STOPPED.toString).map(_.id)
  }
  def permissions: NotebookPermissionService = _permissions
  def revisions: NotebookRevisionService = _revisions
  def documents: NotebookDocumentService = _documents
  def content: NotebookContentService = _content
  def schedules: NotebookScheduleService = _schedules
  def registry: RuntimeAdapterRegistry = _registry
  def runtimes: NotebookRuntimeService = _runtimes
  def sessions: NotebookSessionService = _sessions
  def executions: NotebookExecutionService = _executions

  override def initialize(conf: KyuubiConf): Unit = {
    _store = NotebookManager.loadStore(conf)
    if (conf.get(NOTEBOOK_SCHEMA_INIT)) {
      _store.initSchema()
    }
    _permissions = new NotebookPermissionService(_store)
    _revisions = new NotebookRevisionService(conf, _store)
    _documents = new NotebookDocumentService(conf, _store, _permissions, _revisions)
    _content = new NotebookContentService(conf, _store, _documents, _revisions, _permissions)
    _schedules = new NotebookScheduleService(_store, _permissions)
    // Python runs in the Spark engine, never on this server: one notebook session is one engine,
    // and the engine's python worker is what makes a name bound in one cell outlive it.
    _registry = new RuntimeAdapterRegistry(Seq(
      new KyuubiSqlRuntimeAdapter(backendService, instanceUri, conf),
      new PySparkRuntimeAdapter(backendService, instanceUri, conf)))
    _sessionRegistry = new NotebookSessionRegistry(conf, () => discoveryClient)
    _runtimes = new NotebookRuntimeService(_store, _registry, instanceUri)
    _sessions =
      new NotebookSessionService(
        _store,
        _documents,
        _permissions,
        _runtimes,
        instanceUri,
        () => sessionRegistry)
    _executions = new NotebookExecutionService(
      conf,
      _store,
      _documents,
      _permissions,
      _sessions,
      _runtimes,
      _registry)
    super.initialize(conf)
  }

  /**
   * The ZooKeeper client the owner registry uses.
   *
   * Created lazily and only when a quorum is configured, so a single-node development server
   * neither needs ZooKeeper nor pays for it: with no client there is no registry, every request
   * resolves locally, and nothing is ever forwarded.
   */
  private lazy val discoveryClient: Option[DiscoveryClient] =
    if (conf.get(HA_ADDRESSES).isEmpty) None
    else {
      try {
        val client = DiscoveryClientProvider.createDiscoveryClient(conf)
        client.createClient()
        Some(client)
      } catch {
        case NonFatal(e) =>
          warn("Notebook session routing is disabled: no discovery client", e)
          None
      }
    }

  private lazy val idleReaper =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("notebook-idle-reaper")

  override def start(): Unit = {
    // Nothing that was running can have survived; saying so up front beats letting the first
    // poll of a stale execution report a confusing failure.
    _executions.reconcileAfterRestart()
    _sessions.reconcileAfterRestart()
    val idleTimeout = conf.get(NOTEBOOK_RUNTIME_IDLE_TIMEOUT)
    if (idleTimeout > 0) {
      val interval = conf.get(NOTEBOOK_RUNTIME_IDLE_CHECK_INTERVAL)
      scheduleTolerableRunnableWithFixedDelay(
        idleReaper,
        () => _sessions.reapIdle(idleTimeout),
        interval,
        interval,
        TimeUnit.MILLISECONDS)
    }
    super.start()
  }

  override def stop(): Unit = {
    ThreadUtils.shutdown(idleReaper)
    if (_store != null) {
      try _store.close()
      catch { case NonFatal(e) => warn("Failed to close the notebook store", e) }
    }
    super.stop()
  }

  /**
   * Sanitized health. A subsystem that does not exist yet reports `NOT_IMPLEMENTED` rather than
   * a zero count, so a monitor cannot read "no runtimes" as "healthy and idle" while that part
   * is simply absent.
   */
  def status(): NotebookStatusView = {
    val liveSessions =
      try _store.listLiveSessions()
      catch { case NonFatal(_) => Seq.empty[NotebookSession] }
    val persistence =
      try {
        _store.healthCheck()
        "HEALTHY"
      } catch {
        case NonFatal(e) =>
          warn("Notebook store health check failed", e)
          "UNAVAILABLE"
      }
    NotebookStatusView(
      notebookService = if (persistence == "HEALTHY") "HEALTHY" else "DEGRADED",
      persistence = persistence,
      kyuubiSql = if (_registry.specs.exists(_.enabled)) "HEALTHY" else "UNAVAILABLE",
      // Still reported under its old name so the status contract does not change, but there is
      // no environment manager behind it any more: Python is available when the engine's
      // runtime is registered, and its packages come from the Spark image.
      pythonRuntimeManager = {
        val pythonReady = _registry.specs.exists { spec =>
          spec.id == PySparkRuntimeAdapter.SPEC_ID && spec.enabled
        }
        if (pythonReady) "HEALTHY" else "UNAVAILABLE"
      },
      activeSessions = liveSessions.size,
      activeRuntimes = liveSessions.flatMap(_runtimes.listFor).size,
      queuedExecutions = _store.listExecutions(ExecutionFilter(
        states = Set(ExecutionState.QUEUED.toString, ExecutionState.STARTING.toString),
        limit = STATUS_COUNT_LIMIT)).size)
  }
}

object NotebookManager {

  /**
   * Builds the configured store.
   *
   * The class is named in configuration rather than chosen by a flag so that a deployment can
   * keep the JDBC store it already has while another moves its documents onto shared storage,
   * without either needing a different build.
   */
  def loadStore(conf: KyuubiConf): NotebookStore = {
    val className = conf.get(NOTEBOOK_STORE_CLASS)
    try {
      Class.forName(className)
        .getConstructor(classOf[KyuubiConf])
        .newInstance(conf)
        .asInstanceOf[NotebookStore]
    } catch {
      case NonFatal(e) =>
        throw new KyuubiException(
          s"Could not create the notebook store $className configured by " +
            s"${NOTEBOOK_STORE_CLASS.key}; it must have a constructor taking a KyuubiConf",
          e)
    }
  }

  /** Bound on the status count query; a status page never needs an exact number of a huge queue. */
  private val STATUS_COUNT_LIMIT = 1000

  /** Raised when a notebook endpoint is reached while the subsystem is switched off. */
  def disabled(): NotebookException = new NotebookException(
    NotebookErrorCode.NOTEBOOK_DISABLED,
    s"the notebook subsystem is disabled; set ${NOTEBOOK_ENABLED.key} to true to enable it")
}
