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

package org.apache.kyuubi.server.engineprofile

import java.util.concurrent.ConcurrentHashMap

import scala.util.control.NonFatal

import org.apache.kyuubi.KYUUBI_VERSION
import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiReservedKeys
import org.apache.kyuubi.engine.ApplicationManagerInfo
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_NAMESPACE
import org.apache.kyuubi.ha.client.DiscoveryClientProvider.withDiscoveryClient
import org.apache.kyuubi.ha.client.DiscoveryPaths
import org.apache.kyuubi.server.notebook.api.EngineProfileEngineStatusView
import org.apache.kyuubi.service.BackendService
import org.apache.kyuubi.session.KyuubiSessionManager
import org.apache.kyuubi.shaded.hive.service.rpc.thrift.TProtocolVersion
import org.apache.kyuubi.util.ThreadUtils

/** Starts an authorized profile through a short-lived Kyuubi bootstrap session. */
class EngineProfileRuntimeService(
    conf: KyuubiConf,
    backendService: () => BackendService,
    profiles: EngineProfileService,
    pythonEnvironments: Option[PythonEnvironmentService]) extends Logging {

  import EngineProfileRuntimeService._

  private val transitions = new ConcurrentHashMap[String, Transition]()
  private val lifecycleExecutor =
    ThreadUtils.newDaemonFixedThreadPool(1, "engine-profile-lifecycle")

  def status(
      principal: EngineProfilePrincipal,
      profileId: String): EngineProfileEngineStatusView = {
    val snapshot = profiles.snapshotForUse(profileId, principal)
    status(snapshot)
  }

  /** Returns discovery-backed status for one immutable profile revision. */
  def status(
      principal: EngineProfilePrincipal,
      profileId: String,
      revision: Long): EngineProfileEngineStatusView = {
    val snapshot = profiles.snapshotForRevision(profileId, revision, principal)
    status(snapshot)
  }

  private def status(snapshot: EngineProfileSnapshot): EngineProfileEngineStatusView = {
    try {
      val nodes = discoveryNodes(snapshot.owner, snapshot.subdomain)
      if (nodes.nonEmpty) {
        transitions.remove(transitionKey(snapshot))
        view(snapshot, RUNNING, nodes.size)
      } else {
        Option(transitions.get(transitionKey(snapshot))) match {
          case Some(transition) => view(snapshot, transition.state, 0, transition.errorSummary)
          case None => view(snapshot, STOPPED, 0)
        }
      }
    } catch {
      case NonFatal(error) =>
        warn(s"Could not read engine status for profile ${snapshot.profileId}", error)
        view(snapshot, UNKNOWN, 0, Some(error.getMessage))
    }
  }

  def start(principal: EngineProfilePrincipal, profileId: String): EngineProfileEngineStatusView = {
    // A READY Python environment becomes part of the immutable launch configuration only after
    // the old Driver has disappeared. Do this before taking the snapshot so an explicit Start
    // cannot accidentally recreate the previous profile revision without its environment.
    pythonEnvironments.foreach(_.prepareForEngineLaunch(profileId))
    val snapshot = profiles.snapshotForUse(profileId, principal)
    if (discoveryNodes(principal.user, snapshot.subdomain).nonEmpty) {
      return view(snapshot, RUNNING, 1)
    }
    transitions.put(transitionKey(snapshot), Transition(STARTING, None))
    lifecycleExecutor.execute(() => bootstrap(principal, snapshot))
    view(snapshot, STARTING, 0)
  }

  def stop(principal: EngineProfilePrincipal, profileId: String): EngineProfileEngineStatusView = {
    val snapshot = profiles.snapshotForUse(profileId, principal)
    if (discoveryNodes(principal.user, snapshot.subdomain).isEmpty) {
      transitions.remove(transitionKey(snapshot))
      return view(snapshot, STOPPED, 0)
    }
    transitions.put(transitionKey(snapshot), Transition(STOPPING, None))
    lifecycleExecutor.execute(() => terminate(principal, snapshot))
    view(snapshot, STOPPING, 0)
  }

  def close(): Unit = ThreadUtils.shutdown(lifecycleExecutor)

  private def bootstrap(
      principal: EngineProfilePrincipal,
      snapshot: EngineProfileSnapshot): Unit = {
    try {
      val configuration = snapshot.sparkConfig ++
        EngineProfileSnapshot.engineSessionConfig(snapshot) ++
        pythonEnvironments.map(_.launchConfig(snapshot)).getOrElse(Map.empty) ++
        Map(
          "kyuubi.engine.share.level.subdomain" -> snapshot.subdomain,
          "kyuubi.engine.share.level.sub.domain" -> snapshot.subdomain,
          "kyuubi.session.tag" -> s"engine-profile-warmup-${snapshot.profileId}")
      val handle = backendService().openSession(
        TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11,
        principal.user,
        "",
        "127.0.0.1",
        configuration)
      try {
        // Opening a session submits the engine launch asynchronously. Closing it before the
        // driver has registered cancels the pending LaunchEngine operation, so keep this
        // short-lived bootstrap session until discovery confirms the engine is ready.
        if (awaitRegistration(principal.user, snapshot.subdomain)) {
          transitions.remove(transitionKey(snapshot))
        } else {
          transitions.put(
            transitionKey(snapshot),
            Transition(FAILED, Some("engine did not register before the startup timeout")))
        }
      } finally {
        try backendService().closeSession(handle)
        catch {
          case NonFatal(error) =>
            warn(s"Failed to close bootstrap session for ${snapshot.profileId}", error)
        }
      }
    } catch {
      case NonFatal(error) =>
        warn(s"Failed to start engine profile ${snapshot.profileId}", error)
        transitions.put(transitionKey(snapshot), Transition(FAILED, Some(error.getMessage)))
    }
  }

  private def terminate(
      principal: EngineProfilePrincipal,
      snapshot: EngineProfileSnapshot): Unit = {
    try {
      discoveryNodes(principal.user, snapshot.subdomain).foreach { node =>
        withDiscoveryClient(conf) { client =>
          client.delete(s"${engineSpace(principal.user, snapshot.subdomain)}/${node.nodeName}")
        }
        Option(node.engineRefId.orNull).foreach { engineRefId =>
          val managerInfo = node.attributes.get(KyuubiReservedKeys.KYUUBI_ENGINE_APP_MGR_INFO_KEY)
            .map(ApplicationManagerInfo.deserialize).getOrElse(ApplicationManagerInfo(None))
          backendService().sessionManager.asInstanceOf[KyuubiSessionManager].applicationManager
            .killApplication(managerInfo, engineRefId)
        }
      }
      transitions.remove(transitionKey(snapshot))
    } catch {
      case NonFatal(error) =>
        warn(s"Failed to stop engine profile ${snapshot.profileId}", error)
        transitions.put(transitionKey(snapshot), Transition(FAILED, Some(error.getMessage)))
    }
  }

  private def discoveryNodes(user: String, subdomain: String) =
    withDiscoveryClient(conf) { client =>
      client.getServiceNodesInfo(engineSpace(user, subdomain), silent = true)
    }

  /**
   * Engine launch is asynchronous: OpenSession returns before the Spark driver has registered its
   * discovery node. Keep STARTING visible during that interval instead of reporting a false
   * FAILED state after a single immediate lookup.
   */
  private def awaitRegistration(user: String, subdomain: String): Boolean = {
    val deadline = System.nanoTime() + EngineRegistrationTimeoutNanos
    while (System.nanoTime() < deadline) {
      if (discoveryNodes(user, subdomain).nonEmpty) {
        return true
      }
      Thread.sleep(EngineRegistrationPollIntervalMillis)
    }
    discoveryNodes(user, subdomain).nonEmpty
  }

  private def view(
      snapshot: EngineProfileSnapshot,
      state: String,
      engineCount: Int,
      errorSummary: Option[String] = None): EngineProfileEngineStatusView =
    EngineProfileEngineStatusView(
      snapshot.profileId,
      snapshot.revision,
      state,
      engineCount,
      errorSummary)

  private def engineSpace(user: String, subdomain: String): String =
    DiscoveryPaths.makePath(
      s"${conf.get(HA_NAMESPACE)}_${KYUUBI_VERSION}_USER_SPARK_SQL",
      user,
      subdomain)

  private def transitionKey(snapshot: EngineProfileSnapshot): String =
    s"${snapshot.profileId}:${snapshot.revision}"
}

object EngineProfileRuntimeService {
  val STARTING = "STARTING"
  val RUNNING = "RUNNING"
  val STOPPING = "STOPPING"
  val STOPPED = "STOPPED"
  val FAILED = "FAILED"
  val UNKNOWN = "UNKNOWN"

  private val EngineRegistrationTimeoutNanos = 60L * 1000L * 1000L * 1000L
  private val EngineRegistrationPollIntervalMillis = 1000L

  private case class Transition(state: String, errorSummary: Option[String])
}
