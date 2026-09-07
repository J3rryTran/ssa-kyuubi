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

import java.util.concurrent.TimeUnit
import javax.servlet.{Filter, FilterChain, FilterConfig, ServletRequest, ServletResponse}
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.util.{InputStreamContentProvider, InputStreamResponseListener}

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.server.http.authentication.AuthenticationFilter
import org.apache.kyuubi.server.notebook.NotebookConf.NOTEBOOK_PROXY_INTERNAL_SECRET

/**
 * Sends a notebook runtime request to the instance that can actually serve it.
 *
 * Only the live half of the notebook is routed. A session is a Kyuubi session handle, a Spark
 * engine connection and a python worker held inside one process, so a request for it is useless
 * anywhere else; notebook content, by contrast, comes from the shared store and is served
 * identically everywhere, and routing it would buy a network hop and nothing else.
 *
 * The forward is a plain streamed relay rather than a buffered call. `/logs` and `/outputs` are
 * long-polled and can stay open, so reading the whole response before answering would turn a live
 * log tail into a stall.
 */
class NotebookRoutingFilter(
    conf: KyuubiConf,
    localInstance: () => String,
    sessionIdOf: RouteKey => Option[String],
    isLocallyKnown: String => Boolean,
    registry: () => Option[NotebookSessionRegistry]) extends Filter with Logging {

  import NotebookRoutingFilter._
  import RoutingDecision._

  private val router = new NotebookRouter(
    localInstance,
    id => registry().flatMap(_.ownerOf(id)),
    isLocallyKnown)

  private val secret = conf.get(NOTEBOOK_PROXY_INTERNAL_SECRET)

  private lazy val client: HttpClient = {
    val http = new HttpClient()
    // The same knobs the engine-UI proxy already runs on; a second set would only drift.
    http.setIdleTimeout(conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_IDLE_TIMEOUT))
    http.setMaxConnectionsPerDestination(conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_MAX_CONNECTIONS))
    http.setRequestBufferSize(conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_REQUEST_BUFFER_SIZE))
    http.setResponseBufferSize(conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_RESPONSE_BUFFER_SIZE))
    http.start()
    http
  }

  override def init(filterConfig: FilterConfig): Unit = {}

  override def destroy(): Unit =
    try client.stop()
    catch { case NonFatal(e) => warn("Could not stop the notebook proxy client", e) }

  override def doFilter(
      request: ServletRequest,
      response: ServletResponse,
      chain: FilterChain): Unit = {
    val httpRequest = request.asInstanceOf[HttpServletRequest]
    val httpResponse = response.asInstanceOf[HttpServletResponse]

    val alreadyProxied = Option(httpRequest.getHeader(NotebookProxyHeaders.PROXIED))
      .exists(_.equalsIgnoreCase(NotebookProxyHeaders.PROXIED_VALUE))
    val sessionId = NotebookRoutePaths.sessionKeyOf(pathOf(httpRequest)).flatMap(resolve)

    router.decide(sessionId, alreadyProxied) match {
      case Local => chain.doFilter(request, response)
      case Forward(instance) => forward(httpRequest, httpResponse, instance)
      case Lost => sendJson(
          httpResponse,
          HttpServletResponse.SC_CONFLICT,
          """{"error":"runtime lost","action":"restart-session"}""")
      case Looped =>
        // The registry and reality disagree. Forwarding again would bounce between instances,
        // so the caller is told the truth instead.
        warn(s"Notebook request ${pathOf(httpRequest)} arrived proxied but this is not the owner")
        sendJson(
          httpResponse,
          HttpServletResponse.SC_BAD_GATEWAY,
          """{"error":"proxy loop","action":"restart-session"}""")
    }
  }

  /** The path below the `/api` context, which is what the route patterns are written against. */
  private def pathOf(request: HttpServletRequest): String =
    Option(request.getPathInfo).getOrElse(
      request.getRequestURI.stripPrefix(Option(request.getContextPath).getOrElse("")))

  /**
   * An execution or a notebook does not name its session in the path.
   *
   * Looking those up in the store is safe even though the store's runtime tables are per pod:
   * a miss simply means this instance cannot resolve ownership, which the router then treats as
   * "not local" and answers from ZooKeeper.
   */
  private def resolve(key: RouteKey): Option[String] = key match {
    case RouteKey.Session(id) => Some(id)
    case other => sessionIdOf(other)
  }

  private def forward(
      request: HttpServletRequest,
      response: HttpServletResponse,
      instance: String): Unit = {
    val query = Option(request.getQueryString).map("?" + _).getOrElse("")
    val target = s"http://$instance${request.getRequestURI}$query"
    try {
      val proxied = client.newRequest(target)
        .method(request.getMethod)
        .timeout(conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_TIMEOUT), TimeUnit.MILLISECONDS)

      request.getHeaderNames.asScala
        .filterNot(name => HOP_BY_HOP.contains(name.toLowerCase))
        .foreach(name => proxied.header(name, request.getHeader(name)))

      proxied.header(NotebookProxyHeaders.PROXIED, NotebookProxyHeaders.PROXIED_VALUE)
      // The caller was authenticated here; the far end cannot repeat that for a cookie it never
      // issued, so this instance vouches for the identity and proves it is a peer.
      secret.foreach { value =>
        proxied.header(NotebookProxyHeaders.INTERNAL_TOKEN, value)
        Option(AuthenticationFilter.getUserName).filter(_.nonEmpty)
          .foreach(proxied.header(NotebookProxyHeaders.REAL_USER, _))
      }

      if (request.getContentLength != 0) {
        proxied.content(new InputStreamContentProvider(request.getInputStream))
      }

      val listener = new InputStreamResponseListener()
      proxied.send(listener)
      val upstream = listener.get(
        conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_TIMEOUT),
        TimeUnit.MILLISECONDS)

      response.setStatus(upstream.getStatus)
      upstream.getHeaders.asScala
        .filterNot(field => HOP_BY_HOP.contains(field.getName.toLowerCase))
        .foreach(field => response.addHeader(field.getName, field.getValue))

      val in = listener.getInputStream
      try copyStream(in, response)
      finally in.close()
    } catch {
      case NonFatal(e) =>
        error(s"Could not forward a notebook request to $instance", e)
        sendJson(
          response,
          HttpServletResponse.SC_BAD_GATEWAY,
          """{"error":"proxy failed","action":"restart-session"}""")
    }
  }

  /** Relays in chunks and flushes as it goes, so a long poll reaches the client while it runs. */
  private def copyStream(in: java.io.InputStream, response: HttpServletResponse): Unit = {
    val out = response.getOutputStream
    val buffer = new Array[Byte](8192)
    var read = in.read(buffer)
    while (read > 0) {
      out.write(buffer, 0, read)
      out.flush()
      read = in.read(buffer)
    }
  }

  private def sendJson(response: HttpServletResponse, status: Int, body: String): Unit = {
    response.setStatus(status)
    response.setContentType("application/json")
    response.getWriter.write(body)
    response.getWriter.flush()
  }
}

object NotebookRoutingFilter {

  /** Headers that describe one hop and must not be copied onto the next one. */
  val HOP_BY_HOP: Set[String] = Set(
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "host",
    "content-length")
}
