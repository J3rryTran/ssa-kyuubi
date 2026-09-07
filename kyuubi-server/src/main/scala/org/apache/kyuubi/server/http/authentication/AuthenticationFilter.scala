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

package org.apache.kyuubi.server.http.authentication

import java.io.IOException
import javax.security.sasl.AuthenticationException
import javax.servlet.{Filter, FilterChain, FilterConfig, ServletException, ServletRequest, ServletResponse}
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import scala.collection.mutable

import org.apache.commons.lang3.StringUtils

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.{AUTHENTICATION_METHOD, FRONTEND_PROXY_HTTP_CLIENT_IP_HEADER}
import org.apache.kyuubi.server.http.authentication.oidc.{BearerSessionRequest, OidcAuthFailure, OidcBffService, OidcBffUtils}
import org.apache.kyuubi.server.http.util.HttpAuthUtils.AUTHORIZATION_HEADER
import org.apache.kyuubi.server.notebook.NotebookConf.NOTEBOOK_PROXY_INTERNAL_SECRET
import org.apache.kyuubi.server.notebook.routing.{NotebookProxyHeaders, NotebookProxyIdentity}
import org.apache.kyuubi.service.authentication.{AuthTypes, InternalSecurityAccessor}
import org.apache.kyuubi.service.authentication.AuthenticationProviderFactory
import org.apache.kyuubi.service.authentication.AuthTypes.{CUSTOM, KERBEROS, NOSASL, OIDC}

class AuthenticationFilter(conf: KyuubiConf) extends Filter with Logging {
  import AuthenticationFilter._
  import AuthSchemes._

  private[authentication] val authSchemeHandlers =
    new mutable.HashMap[AuthScheme, AuthenticationHandler]()

  private[authentication] def addAuthHandler(authHandler: AuthenticationHandler): Unit = {
    authHandler.init(conf)
    if (authHandler.authenticationSupported) {
      if (authSchemeHandlers.contains(authHandler.authScheme)) {
        warn(s"Authentication handler has been defined for scheme ${authHandler.authScheme}")
      } else {
        info(s"Add authentication handler ${authHandler.getClass.getSimpleName}" +
          s" for scheme ${authHandler.authScheme}")
        authSchemeHandlers.put(authHandler.authScheme, authHandler)
      }
    } else {
      warn(s"The authentication handler ${authHandler.getClass.getSimpleName}" +
        s" for scheme ${authHandler.authScheme} is not supported")
    }
  }

  private[kyuubi] def initAuthHandlers(): Unit = {
    val authTypes = conf.get(AUTHENTICATION_METHOD).map(AuthTypes.withName)
    val spnegoKerberosEnabled = authTypes.contains(KERBEROS)
    val basicAuthTypeOpt = {
      if (authTypes.toSet == Set(NOSASL)) {
        authTypes.headOption
      } else {
        authTypes.filterNot(_.equals(KERBEROS)).filterNot(_.equals(NOSASL)).headOption
      }
    }
    if (spnegoKerberosEnabled) {
      val kerberosHandler = new KerberosAuthenticationHandler
      addAuthHandler(kerberosHandler)
    }
    basicAuthTypeOpt.foreach { basicAuthType =>
      if (basicAuthType.equals(CUSTOM) || basicAuthType.equals(OIDC)) {
        val basicClass = conf.get(KyuubiConf.AUTHENTICATION_CUSTOM_BASIC_CLASS).orElse {
          if (basicAuthType.equals(OIDC)) {
            Some(AuthenticationProviderFactory.OIDC_PASSWD_PROVIDER_CLASS)
          } else None
        }
        basicClass.foreach { _ =>
          val basicHandler = new BasicAuthenticationHandler(basicAuthType)
          addAuthHandler(basicHandler)
        }
        val bearerClass = conf.get(KyuubiConf.AUTHENTICATION_CUSTOM_BEARER_CLASS).orElse {
          if (basicAuthType.equals(OIDC)) {
            Some(AuthenticationProviderFactory.OIDC_BEARER_PROVIDER_CLASS)
          } else None
        }
        bearerClass.foreach { bearerClassName =>
          val bearerHandler = new BearerAuthenticationHandler(bearerClassName)
          addAuthHandler(bearerHandler)
        }
      } else {
        val basicHandler = new BasicAuthenticationHandler(basicAuthType)
        addAuthHandler(basicHandler)
      }
    }
    if (InternalSecurityAccessor.get() != null) {
      val internalHandler = new KyuubiInternalAuthenticationHandler
      addAuthHandler(internalHandler)
    }
  }

  override def init(filterConfig: FilterConfig): Unit = {
    initAuthHandlers()
  }

  private[kyuubi] def getMatchedHandler(authorization: String): Option[AuthenticationHandler] = {
    authSchemeHandlers.values.find(_.matchAuthScheme(authorization))
  }

  /** `None` unless the Web UI is configured for the backend-for-frontend OIDC flow. */
  private[authentication] lazy val oidcBff: Option[OidcBffService] = OidcBffService.get(conf)

  /**
   * Resolve the Web UI's session cookie into a Bearer credential.
   *
   * Returns the request to carry on with, or `None` when the response has already been
   * completed with an error and the chain must stop.
   *
   * An `Authorization` header always wins: JDBC and REST clients authenticate that way and must
   * behave exactly as before, cookie or no cookie.
   */
  private[authentication] def applyCookieSession(
      request: HttpServletRequest,
      response: HttpServletResponse): Option[HttpServletRequest] =
    applyCookieSession(request, response, oidcBff)

  private[authentication] def applyCookieSession(
      request: HttpServletRequest,
      response: HttpServletResponse,
      bff: Option[OidcBffService]): Option[HttpServletRequest] = {
    val service = bff.orNull
    if (service == null || StringUtils.isNotBlank(request.getHeader(AUTHORIZATION_HEADER))) {
      return Some(request)
    }
    service.sessionIdFrom(request) match {
      case None => Some(request)
      case Some(sessionId) if service.sessionFor(sessionId).isEmpty =>
        // Unknown session: expired here, signed out, or the request reached another replica.
        // Clearing the cookie stops the browser replaying it on every following request.
        response.addHeader(
          "Set-Cookie",
          OidcBffUtils.expiredSessionCookie(service.cookieName, service.bffConf.cookieSecure))
        unauthorized(response, "Session is unknown or has expired")
        None
      case Some(_) if !OidcBffUtils.csrfAllowed(request) =>
        warn(s"Rejected cookie-authenticated ${request.getMethod} to ${request.getRequestURI}: " +
          "the request did not come from this origin")
        response.sendError(
          HttpServletResponse.SC_FORBIDDEN,
          "Cross-origin request rejected for a cookie-authenticated session")
        None
      case Some(sessionId) =>
        service.authenticate(sessionId) match {
          case Right(session) => Some(new BearerSessionRequest(request, session.accessToken))
          case Left(failure) =>
            val reason = failure match {
              case OidcAuthFailure.UnknownSession => "session is unknown or has expired"
              case OidcAuthFailure.RefreshFailed(error) => s"session could not be renewed ($error)"
            }
            response.addHeader(
              "Set-Cookie",
              OidcBffUtils.expiredSessionCookie(service.cookieName, service.bffConf.cookieSecure))
            unauthorized(response, s"OIDC $reason")
            None
        }
    }
  }

  private def unauthorized(response: HttpServletResponse, message: String): Unit = {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, message)
  }

  /**
   * If the request has a valid authentication token it allows the request to continue to the
   * target resource, otherwise it triggers an authentication sequence using the configured
   * [[AuthenticationHandler]].
   *
   * @param request     the request object.
   * @param response    the response object.
   * @param filterChain the filter chain object.
   * @throws IOException      thrown if an IO error occurred.
   * @throws ServletException thrown if a processing error occurred.
   */
  override def doFilter(
      request: ServletRequest,
      response: ServletResponse,
      filterChain: FilterChain): Unit = {
    val incomingRequest = request.asInstanceOf[HttpServletRequest]
    val httpResponse = response.asInstanceOf[HttpServletResponse]

    // The Web UI must be able to learn how to authenticate before it has any
    // credential, so these endpoints are served without authentication. They only
    // return public OIDC discovery inputs, or drive the login redirect itself.
    if (UNAUTHENTICATED_PATHS.contains(incomingRequest.getRequestURI)) {
      doFilter(filterChain, incomingRequest, httpResponse)
      return
    }

    HTTP_CLIENT_IP_ADDRESS.set(incomingRequest.getRemoteAddr)
    HTTP_PROXY_HEADER_CLIENT_IP_ADDRESS.set(
      incomingRequest.getHeader(conf.get(FRONTEND_PROXY_HTTP_CLIENT_IP_HEADER)))

    // A notebook request forwarded by a peer instance carries an identity that instance already
    // authenticated. The far end cannot re-check a Web UI cookie it never issued, so the claim is
    // honoured here - but only against the shared secret, so a client that merely copies the
    // header names gains nothing and falls through to normal authentication below.
    NotebookProxyIdentity.acceptedUser(
      Option(incomingRequest.getHeader(NotebookProxyHeaders.PROXIED)),
      Option(incomingRequest.getHeader(NotebookProxyHeaders.REAL_USER)),
      Option(incomingRequest.getHeader(NotebookProxyHeaders.INTERNAL_TOKEN)),
      conf.get(NOTEBOOK_PROXY_INTERNAL_SECRET)) match {
      case Some(vouchedUser) =>
        try {
          HTTP_AUTH_TYPE.set("INTERNAL_NOTEBOOK_PROXY")
          HTTP_CLIENT_USER_NAME.set(vouchedUser)
          doFilter(filterChain, incomingRequest, httpResponse)
        } finally {
          HTTP_CLIENT_USER_NAME.remove()
          HTTP_AUTH_TYPE.remove()
          HTTP_CLIENT_IP_ADDRESS.remove()
          HTTP_PROXY_HEADER_CLIENT_IP_ADDRESS.remove()
        }
        return
      case None => // Not a vouched request; authenticate it in the usual way.
    }

    // A Web UI session cookie stands in for a Bearer token from here on. Rejections are audited
    // here rather than in the `finally` below, which this path never reaches.
    val httpRequest = applyCookieSession(incomingRequest, httpResponse) match {
      case Some(req) => req
      case None =>
        AuthenticationAuditLogger.audit(incomingRequest, httpResponse)
        return
    }

    val authorization = httpRequest.getHeader(AUTHORIZATION_HEADER)
    val matchedHandler = getMatchedHandler(authorization).orNull

    try {
      if (matchedHandler == null) {
        debug(s"No auth scheme matched for url: ${httpRequest.getRequestURL}")
        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
        httpResponse.sendError(
          HttpServletResponse.SC_UNAUTHORIZED,
          s"No auth scheme matched for $authorization")
      } else {
        HTTP_AUTH_TYPE.set(matchedHandler.authScheme.toString)
        val authUser = matchedHandler.authenticate(httpRequest, httpResponse)
        if (authUser != null) {
          HTTP_CLIENT_USER_NAME.set(authUser)
          doFilter(filterChain, httpRequest, httpResponse)
        }
      }
    } catch {
      case e: AuthenticationException =>
        httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN)
        HTTP_CLIENT_USER_NAME.remove()
        HTTP_CLIENT_IP_ADDRESS.remove()
        HTTP_PROXY_HEADER_CLIENT_IP_ADDRESS.remove()
        HTTP_AUTH_TYPE.remove()
        HTTP_CLIENT_PROXY_USER_NAME.remove()
        HTTP_FORWARDED_ADDRESSES.remove()
        httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage)
    } finally {
      AuthenticationAuditLogger.audit(httpRequest, httpResponse)
    }
  }

  /**
   * Delegates call to the servlet filter chain. Sub-classes my override this
   * method to perform pre and post tasks.
   *
   * @param filterChain the filter chain object.
   * @param request     the request object.
   * @param response    the response object.
   * @throws IOException      thrown if an IO error occurred.
   * @throws ServletException thrown if a processing error occurred.
   */
  @throws[IOException]
  @throws[ServletException]
  protected def doFilter(
      filterChain: FilterChain,
      request: HttpServletRequest,
      response: HttpServletResponse): Unit = {
    filterChain.doFilter(request, response)
  }

  override def destroy(): Unit = {
    if (authSchemeHandlers.nonEmpty) {
      authSchemeHandlers.values.foreach(_.destroy())
      authSchemeHandlers.clear()
    }
  }
}

object AuthenticationFilter {

  /**
   * Request URIs served without authentication; they must never expose secrets.
   *
   * The three BFF endpoints belong here because they are the way *in*: login and callback run
   * before any session exists, and logout has to work for a session that has already expired.
   * They are not unprotected - the callback is bound to a one-time `state`, and logout applies
   * the same origin check the filter does.
   */
  final val UNAUTHENTICATED_PATHS: Set[String] = Set(
    "/api/v1/authentication/config",
    "/api/v1/authentication/login",
    "/api/v1/authentication/callback",
    "/api/v1/authentication/logout")

  final val HTTP_CLIENT_IP_ADDRESS = new ThreadLocal[String]() {
    override protected def initialValue: String = null
  }
  final val HTTP_PROXY_HEADER_CLIENT_IP_ADDRESS = new ThreadLocal[String]() {
    override protected def initialValue: String = null
  }
  final val HTTP_CLIENT_USER_NAME = new ThreadLocal[String]() {
    override protected def initialValue: String = null
  }
  final val HTTP_AUTH_TYPE = new ThreadLocal[String]() {
    override protected def initialValue(): String = null
  }
  final val HTTP_CLIENT_PROXY_USER_NAME = new ThreadLocal[String]() {
    override protected def initialValue(): String = null
  }
  final val HTTP_FORWARDED_ADDRESSES = new ThreadLocal[List[String]] {
    override protected def initialValue: List[String] = List.empty
  }

  def getUserIpAddress: String = HTTP_CLIENT_IP_ADDRESS.get

  def getUserProxyHeaderIpAddress: String = HTTP_PROXY_HEADER_CLIENT_IP_ADDRESS.get()

  def getForwardedAddresses: List[String] = HTTP_FORWARDED_ADDRESSES.get

  def getUserName: String = HTTP_CLIENT_USER_NAME.get

  def getProxyUserName: String = HTTP_CLIENT_PROXY_USER_NAME.get

  def getAuthType: String = HTTP_AUTH_TYPE.get()
}
