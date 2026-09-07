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

package org.apache.kyuubi.server.notebook.routing

import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.ha.HighAvailabilityConf.HA_NAMESPACE
import org.apache.kyuubi.ha.client.DiscoveryClient

/**
 * Which server instance owns a live notebook session.
 *
 * A session is a Kyuubi session handle, a Spark engine connection and a python worker, all of
 * them living inside one process. Nothing about that can be shared or copied, so the only way a
 * second replica can serve a request for it is to forward the request to the process that holds
 * it - and for that it has to be able to find out which one that is.
 *
 * The answer is kept in ZooKeeper rather than in the notebook store on purpose. The store's
 * runtime tables are per pod today, so a row written on one replica is not visible on another;
 * asking the store would mean asking the wrong process. ZooKeeper is already running for engine
 * discovery and every replica sees the same view of it.
 *
 * The node is EPHEMERAL, which is what makes a dead owner detectable for free: when the pod goes,
 * its session ZooKeeper node goes with it, and a lookup that finds nothing means the session is
 * genuinely gone rather than merely busy. There is nothing to expire and nothing to clean up.
 */
class NotebookSessionRegistry(
    conf: KyuubiConf,
    discovery: () => Option[DiscoveryClient]) extends Logging {

  import NotebookSessionRegistry._

  private val root = s"${conf.get(HA_NAMESPACE)}$SESSIONS_PATH"

  private def pathOf(sessionId: String): String = s"$root/$sessionId"

  /**
   * Records this instance as the owner of a session.
   *
   * A failure here is logged and swallowed: the session is already open and usable on this pod,
   * and refusing to return it because ZooKeeper hiccupped would turn a routing problem into an
   * outage. The cost is that another replica cannot route to it until it is registered again.
   */
  def register(sessionId: String, instance: String): Unit = withDiscovery("register") { client =>
    val path = pathOf(sessionId)
    if (client.pathExists(path)) {
      client.delete(path)
    }
    client.create(path, "EPHEMERAL", createParent = true)
    client.setData(path, instance.getBytes(StandardCharsets.UTF_8))
  }

  /** Drops the record when a session is closed on purpose, rather than waiting for the node. */
  def unregister(sessionId: String): Unit = withDiscovery("unregister") { client =>
    val path = pathOf(sessionId)
    if (client.pathExists(path)) {
      client.delete(path)
    }
  }

  /**
   * The instance that owns the session, or nothing when no live instance does.
   *
   * Nothing means gone, not "ask again": the node only exists while its owner's session with
   * ZooKeeper is alive.
   */
  def ownerOf(sessionId: String): Option[String] =
    discovery().flatMap { client =>
      try {
        val path = pathOf(sessionId)
        if (!client.pathExists(path)) None
        else {
          Option(client.getData(path))
            .map(new String(_, StandardCharsets.UTF_8))
            .map(_.trim)
            .filter(_.nonEmpty)
        }
      } catch {
        case NonFatal(e) =>
          warn(s"Could not read the owner of notebook session $sessionId", e)
          None
      }
    }

  private def withDiscovery(action: String)(f: DiscoveryClient => Unit): Unit =
    discovery().foreach { client =>
      try f(client)
      catch {
        case NonFatal(e) => warn(s"Notebook session registry could not $action", e)
      }
    }
}

object NotebookSessionRegistry {

  /** Kept under the HA namespace so it is discarded with the rest of a namespace's state. */
  val SESSIONS_PATH = "/notebook-sessions"
}
