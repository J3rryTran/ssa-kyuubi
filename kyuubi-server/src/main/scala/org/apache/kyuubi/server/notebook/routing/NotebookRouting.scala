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

/**
 * Headers carried on a forwarded notebook request.
 *
 * They only ever mean anything between server instances. A request arriving from outside must
 * never be able to set them, which is what the shared secret is for: without it the receiving
 * instance ignores the claimed identity entirely and falls back to ordinary authentication.
 */
object NotebookProxyHeaders {

  /** Marks a request that has already been forwarded once, so it is never forwarded again. */
  val PROXIED = "X-Kyuubi-Proxied"

  /** The identity the forwarding instance already authenticated. */
  val REAL_USER = "X-Kyuubi-Real-User"

  /** Proof that the forwarding instance is a peer and not a client that guessed the header. */
  val INTERNAL_TOKEN = "X-Kyuubi-Internal-Token"

  val PROXIED_VALUE = "true"
}

/** What should happen to a request for a notebook runtime resource. */
sealed trait RoutingDecision

object RoutingDecision {

  /** This instance owns the session, or ownership does not apply to this path. */
  case object Local extends RoutingDecision

  /** Another live instance owns it; the request is forwarded there unchanged. */
  case class Forward(instance: String) extends RoutingDecision

  /**
   * No live instance owns it. The session died with its pod, and no amount of retrying will
   * bring it back, so the caller is told to start a new one rather than left to time out.
   */
  case object Lost extends RoutingDecision

  /**
   * A request that was already forwarded arrived somewhere that still does not own it, which
   * means the registry and reality disagree. Forwarding again would loop between instances.
   */
  case object Looped extends RoutingDecision
}

/**
 * Decides where a notebook runtime request belongs.
 *
 * Kept free of servlet types so the rules can be read and tested on their own: what is being
 * decided is ownership, and that has nothing to do with HTTP.
 */
class NotebookRouter(
    localInstance: () => String,
    ownerOf: String => Option[String],
    isLocallyKnown: String => Boolean) {

  import RoutingDecision._

  /**
   * @param sessionId  the session the request concerns, if the path names one
   * @param alreadyProxied whether the request arrived carrying the proxy marker
   */
  def decide(sessionId: Option[String], alreadyProxied: Boolean): RoutingDecision =
    sessionId match {
      // A path that names no session - listing notebooks, say - is served by any instance.
      case None => Local
      // The fast path, and the authoritative one: if this process is holding the session then
      // it is the owner, whatever ZooKeeper has caught up to.
      case Some(id) if isLocallyKnown(id) => Local
      case Some(id) =>
        ownerOf(id) match {
          case Some(instance) if instance == localInstance() => Local
          case Some(_) if alreadyProxied => Looped
          case Some(instance) => Forward(instance)
          case None => Lost
        }
    }
}

/**
 * Pulls the session a request concerns out of its path.
 *
 * Only the runtime paths carry ownership. Notebook and folder content is read from the shared
 * store and is served identically by every instance, so it is deliberately absent here - routing
 * it would add a network hop for nothing.
 */
object NotebookRoutePaths {

  private val Session = """/v1/notebook-sessions/([^/:]+).*""".r
  private val Execution = """/v1/executions/([^/:]+).*""".r
  private val NotebookExecutions = """/v1/notebooks/([^/:]+)/executions.*""".r

  /**
   * The session id a path concerns, and how to find it.
   *
   * An execution and a notebook do not name their session in the path, so the caller resolves
   * those through the store - which is safe because an execution row and a notebook row are
   * shared state, unlike the live session they point at.
   */
  def sessionKeyOf(path: String): Option[RouteKey] = path match {
    case Session(id) => Some(RouteKey.Session(id))
    case Execution(id) => Some(RouteKey.Execution(id))
    case NotebookExecutions(id) => Some(RouteKey.Notebook(id))
    case _ => None
  }
}

sealed trait RouteKey

object RouteKey {
  case class Session(id: String) extends RouteKey
  case class Execution(id: String) extends RouteKey
  case class Notebook(id: String) extends RouteKey
}
