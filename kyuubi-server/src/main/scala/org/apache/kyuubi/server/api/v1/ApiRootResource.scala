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

package org.apache.kyuubi.server.api.v1

import java.net.URI
import javax.ws.rs.{GET, Path, POST, Produces, QueryParam}
import javax.ws.rs.core.{MediaType, Response}

import com.google.common.annotations.VisibleForTesting
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.servlet.ServletContainer

import org.apache.kyuubi.KYUUBI_VERSION
import org.apache.kyuubi.client.api.v1.dto._
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.server.KyuubiRestFrontendService
import org.apache.kyuubi.server.api.{ApiRequestContext, EngineUIProxyServlet, FrontendServiceContext, OpenAPIConfig}
import org.apache.kyuubi.server.api.v1.ApiRootResource.{JWT_ISSUER, OIDC_UI_CLIENT_ID, OIDC_UI_SCOPE}
import org.apache.kyuubi.server.http.authentication.oidc.{OidcBffConf, OidcBffService, OidcBffUtils, OidcSession}
import org.apache.kyuubi.server.http.authentication.oidc.OidcBffConf.FLOW_PUBLIC
import org.apache.kyuubi.service.authentication.AuthTypes

@Path("/v1")
private[v1] class ApiRootResource extends ApiRequestContext {

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "Get the version of Kyuubi server.")
  @GET
  @Path("version")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def version(): VersionInfo = new VersionInfo(KYUUBI_VERSION)

  @GET
  @Path("ping")
  @Produces(Array(MediaType.TEXT_PLAIN))
  def ping(): String = "pong"

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "Get the authentication configuration the Web UI should use.")
  @GET
  @Path("authentication/config")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def authenticationConfig(): Map[String, Any] = {
    val conf = fe.getConf
    val authTypes = conf.get(AUTHENTICATION_METHOD)
    val oidcEnabled = authTypes.exists(_.equalsIgnoreCase(AuthTypes.OIDC.toString))
    val base = Map[String, Any](
      "authType" -> authTypes.headOption.getOrElse("NONE"),
      "oidcEnabled" -> oidcEnabled)
    if (!oidcEnabled) {
      base
    } else {
      // Only public discovery inputs are exposed here; never any secret.
      val bff = OidcBffService.get(conf)
      // Under the BFF flow the browser holds no token, so it has no way to tell whether it is
      // signed in other than asking. That is what "authenticated"/"username" answer.
      val session = bff.flatMap(currentSession)
      base ++ Map[String, Any](
        "issuer" -> conf.getOption(JWT_ISSUER).orNull,
        "clientId" -> conf.getOption(OIDC_UI_CLIENT_ID).orNull,
        "scope" -> conf.getOption(OIDC_UI_SCOPE).getOrElse("openid profile email"),
        "flow" -> bff.map(_.bffConf.flow).getOrElse(FLOW_PUBLIC),
        "authenticated" -> session.isDefined,
        "username" -> session.map(_.username).orNull)
    }
  }

  /**
   * Start the login: hand the browser a redirect to the provider, keeping the PKCE verifier and
   * the `state` server side. `redirect` is where to land afterwards, and is validated as a local
   * path so it cannot be used to bounce a signed-in user off-site.
   */
  @ApiResponse(responseCode = "302", description = "Redirect to the OIDC provider.")
  @GET
  @Path("authentication/login")
  def oidcLogin(@QueryParam("redirect") redirect: String): Response = {
    withBff { service =>
      service.buildAuthorizationRedirect(httpRequest, redirect) match {
        case Right(url) => noStore(Response.status(Response.Status.FOUND).location(URI.create(url)))
        case Left(message) => problem(Response.Status.INTERNAL_SERVER_ERROR, message)
      }
    }
  }

  /**
   * The provider's redirect target. Exchanges the code for tokens server side, stores them, and
   * gives the browser nothing but an opaque session cookie.
   */
  @ApiResponse(responseCode = "302", description = "Redirect back into the Web UI.")
  @GET
  @Path("authentication/callback")
  def oidcCallback(
      @QueryParam("code") code: String,
      @QueryParam("state") state: String,
      @QueryParam("error") error: String): Response = {
    withBff { service =>
      if (error != null && error.nonEmpty) {
        // The user declined consent, or the provider refused the request outright.
        problem(Response.Status.BAD_REQUEST, s"The OIDC provider returned an error: $error")
      } else {
        service.completeLogin(code, state) match {
          case Right((sessionId, redirectPath)) =>
            val cookie = OidcBffUtils.sessionCookie(
              service.cookieName,
              sessionId,
              service.bffConf.sessionTimeoutMs / 1000,
              service.bffConf.cookieSecure)
            noStore(
              Response.status(Response.Status.FOUND)
                .location(URI.create(redirectPath))
                .header("Set-Cookie", cookie))
          case Left(message) => problem(Response.Status.BAD_REQUEST, message)
        }
      }
    }
  }

  /**
   * Drop the server-side session and expire the cookie. The provider's own session outlives it,
   * so the caller is handed the URL that ends that too - never the raw id_token.
   */
  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "Sign out of the Web UI session.")
  @POST
  @Path("authentication/logout")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def oidcLogout(): Response = {
    withBff { service =>
      if (!OidcBffUtils.csrfAllowed(httpRequest)) {
        problem(Response.Status.FORBIDDEN, "Cross-origin logout rejected")
      } else {
        val session = service.sessionIdFrom(httpRequest).flatMap(service.invalidate)
        val postLogoutRedirect = s"${OidcBffUtils.requestOrigin(httpRequest)}/ui"
        val entity = Map[String, Any](
          "endSessionUrl" -> service.endSessionUrl(session, postLogoutRedirect).orNull)
        noStore(
          Response.ok(entity)
            .header(
              "Set-Cookie",
              OidcBffUtils.expiredSessionCookie(
                service.cookieName,
                service.bffConf.cookieSecure)))
      }
    }
  }

  /** The session the request's cookie names, if it is still live. */
  private def currentSession(service: OidcBffService): Option[OidcSession] =
    service.sessionIdFrom(httpRequest).flatMap(service.sessionFor)

  /** The BFF endpoints only exist when the UI is configured for that flow. */
  private def withBff(f: OidcBffService => Response): Response = {
    OidcBffService.get(fe.getConf) match {
      case Some(service) => f(service)
      case None =>
        problem(
          Response.Status.NOT_FOUND,
          "The OIDC backend-for-frontend flow is not enabled on this server")
    }
  }

  private def problem(status: Response.Status, message: String): Response =
    noStore(Response.status(status)
      .`type`(MediaType.APPLICATION_JSON)
      .entity(Map("message" -> message)))

  private def noStore(builder: Response.ResponseBuilder): Response =
    builder.header("Cache-Control", "no-store").header("Pragma", "no-cache").build()

  @Path("sessions")
  def sessions: Class[SessionsResource] = classOf[SessionsResource]

  @Path("operations")
  def operations: Class[OperationsResource] = classOf[OperationsResource]

  @Path("batches")
  def batches: Class[BatchesResource] = classOf[BatchesResource]

  @Path("admin")
  def admin: Class[AdminResource] = classOf[AdminResource]

  @Path("notebook-folders")
  def notebookFolders: Class[NotebookFoldersResource] = classOf[NotebookFoldersResource]

  @Path("notebooks")
  def notebooks: Class[NotebooksResource] = classOf[NotebooksResource]

  // Collection-level actions are siblings of "notebooks", not children, so they need their own
  // locators rather than a path inside NotebooksResource.
  @Path("notebooks:import")
  def notebooksImport: Class[NotebookImportResource] = classOf[NotebookImportResource]

  @Path("notebooks:search")
  def notebooksSearch: Class[NotebookSearchResource] = classOf[NotebookSearchResource]

  @Path("me")
  def currentUser: Class[CurrentUserResource] = classOf[CurrentUserResource]

  @Path("notebook-status")
  def notebookStatus: Class[NotebookStatusResource] = classOf[NotebookStatusResource]

  @Path("runtime-specs")
  def runtimeSpecs: Class[RuntimeSpecsResource] = classOf[RuntimeSpecsResource]

  @Path("notebook-sessions")
  def notebookSessions: Class[NotebookSessionsResource] = classOf[NotebookSessionsResource]

  @Path("notebook-runtimes")
  def notebookRuntimes: Class[NotebookRuntimesResource] = classOf[NotebookRuntimesResource]

  @Path("executions")
  def executions: Class[ExecutionsResource] = classOf[ExecutionsResource]

  @Path("engine-profiles")
  def engineProfiles: Class[NotebookEngineProfilesResource] =
    classOf[NotebookEngineProfilesResource]

  @GET
  @Path("exception")
  @Produces(Array(MediaType.TEXT_PLAIN))
  @VisibleForTesting
  def test(): Response = {
    1 / 0
    Response.ok().build()
  }
}

private[server] object ApiRootResource {

  // Config keys live with the BFF configuration so the two views of them cannot drift apart.
  private[v1] val JWT_ISSUER = OidcBffConf.JWT_ISSUER
  private[v1] val OIDC_UI_CLIENT_ID = OidcBffConf.OIDC_UI_CLIENT_ID
  private[v1] val OIDC_UI_SCOPE = OidcBffConf.OIDC_UI_SCOPE

  def getServletHandler(fe: KyuubiRestFrontendService): ServletContextHandler = {
    val openapiConf: ResourceConfig = new OpenAPIConfig
    val holder = new ServletHolder(new ServletContainer(openapiConf))
    val handler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    handler.setContextPath("/api")
    FrontendServiceContext.set(handler, fe)
    handler.addServlet(holder, "/*")
    handler
  }

  def getEngineUIProxyHandler(fe: KyuubiRestFrontendService): ServletContextHandler = {
    val proxyServlet = new EngineUIProxyServlet()
    val holder = new ServletHolder(proxyServlet)
    val conf = fe.getConf
    holder.setInitParameter(
      "idleTimeout",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_IDLE_TIMEOUT).toString)
    holder.setInitParameter(
      "maxConnections",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_MAX_CONNECTIONS).toString)
    holder.setInitParameter(
      "maxThreads",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_MAX_THREADS).toString)
    holder.setInitParameter(
      "requestBufferSize",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_REQUEST_BUFFER_SIZE).toString)
    holder.setInitParameter(
      "responseBufferSize",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_RESPONSE_BUFFER_SIZE).toString)
    holder.setInitParameter(
      "timeout",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_TIMEOUT).toString)
    val proxyHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    proxyHandler.setContextPath("/engine-ui")
    proxyHandler.addServlet(holder, "/*")
    proxyHandler
  }
}
