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

import javax.servlet.http.{Cookie, HttpServletRequest, HttpServletResponse}

import org.mockito.ArgumentMatchers.{anyInt, anyString, contains, eq => meq}
import org.mockito.Mockito.{lenient, never, verify}
import org.scalatestplus.mockito.MockitoSugar.mock

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.http.authentication.oidc._
import org.apache.kyuubi.server.http.authentication.oidc.OidcBffConf._
import org.apache.kyuubi.server.http.util.HttpAuthUtils.AUTHORIZATION_HEADER

/**
 * The cookie branch of [[AuthenticationFilter]]: how a Web UI session cookie turns into the
 * Bearer credential the rest of the pipeline already knows how to check, and - just as
 * importantly - when it must not.
 */
class OidcCookieAuthenticationSuite extends KyuubiFunSuite {

  private val conf = KyuubiConf(loadSysDefault = false)
    .set(OIDC_UI_CLIENT_ID, "kyuubi")
    .set(OIDC_UI_CLIENT_SECRET, "s3cret")
    .set(JWT_ISSUER, "https://provider.example.com/realms/lakehouse")
    .set(OIDC_AUTHORIZATION_ENDPOINT, "https://provider.example.com/auth")
    .set(OIDC_TOKEN_ENDPOINT, "http://provider.internal:8080/token")

  /** A token endpoint that fails loudly: none of these tests should need to reach one. */
  private object UnreachableTokenClient extends TokenEndpointClient {
    override def post(endpoint: String, form: Seq[(String, String)]): TokenResponse =
      throw new IllegalStateException("the token endpoint must not be called")
  }

  private def newService(): (OidcBffService, OidcSessionStore[OidcSession]) = {
    val sessions = new InMemoryOidcSessionStore[OidcSession](60000, "test-sessions", 600000)
    val pending = new InMemoryOidcSessionStore[PendingLogin](60000, "test-pending", 600000)
    val service = new OidcBffService(
      conf,
      new OidcBffConf(conf, _ => Map.empty),
      UnreachableTokenClient,
      sessions,
      pending)
    (service, sessions)
  }

  private def liveSession = OidcSession(
    accessToken = "the-access-token",
    refreshToken = Some("rt1"),
    idToken = Some("it1"),
    // Well outside the refresh window, so resolving it never calls the provider.
    expiresAt = System.currentTimeMillis() + 3600000,
    username = "trungtm8",
    createdAt = System.currentTimeMillis())

  private def request(
      method: String = "GET",
      authorization: Option[String] = None,
      cookie: Option[String] = Some("sid"),
      origin: Option[String] = None): HttpServletRequest = {
    val req = mock[HttpServletRequest]
    lenient.when(req.getMethod).thenReturn(method)
    lenient.when(req.getRequestURI).thenReturn("/api/v1/sessions")
    lenient.when(req.getScheme).thenReturn("http")
    lenient.when(req.getServerName).thenReturn("kyuubi.example.com")
    lenient.when(req.getServerPort).thenReturn(32379)
    lenient.when(req.getHeader("Host")).thenReturn("kyuubi.example.com:32379")
    lenient.when(req.getHeader("Origin")).thenReturn(origin.orNull)
    lenient.when(req.getHeader("Referer")).thenReturn(null)
    lenient.when(req.getHeader("X-Forwarded-Proto")).thenReturn(null)
    lenient.when(req.getHeader("X-Forwarded-Host")).thenReturn(null)
    lenient.when(req.getHeader(AUTHORIZATION_HEADER)).thenReturn(authorization.orNull)
    lenient.when(req.getCookies)
      .thenReturn(cookie.map(c => Array(new Cookie(DEFAULT_COOKIE_NAME, c))).orNull)
    req
  }

  test("an Authorization header takes precedence over the session cookie") {
    val (service, sessions) = newService()
    sessions.put("sid", liveSession)
    val filter = new AuthenticationFilter(conf)
    val response = mock[HttpServletResponse]
    // A JDBC client that happens to carry a browser cookie must behave exactly as before.
    val req = request(authorization = Some("Bearer jdbc-client-token"))

    val result = filter.applyCookieSession(req, response, Some(service))
    assert(result.contains(req), "the request must be passed through untouched")
    assert(result.map(_.getHeader(AUTHORIZATION_HEADER)).contains("Bearer jdbc-client-token"))
    verify(response, never()).sendError(anyInt(), anyString())
  }

  test("a valid session cookie is presented as a Bearer credential") {
    val (service, sessions) = newService()
    sessions.put("sid", liveSession)
    val filter = new AuthenticationFilter(conf)
    val response = mock[HttpServletResponse]

    val result = filter.applyCookieSession(request(), response, Some(service))
    assert(result.isDefined)
    assert(result.get.getHeader(AUTHORIZATION_HEADER) === "Bearer the-access-token")
    // Case-insensitively, as a servlet container would.
    assert(result.get.getHeader("authorization") === "Bearer the-access-token")
  }

  test("an unknown session is rejected and the stale cookie cleared") {
    val (service, _) = newService()
    val filter = new AuthenticationFilter(conf)
    val response = mock[HttpServletResponse]

    assert(filter.applyCookieSession(request(), response, Some(service)).isEmpty)
    verify(response).sendError(meq(HttpServletResponse.SC_UNAUTHORIZED), anyString())
    verify(response).addHeader(meq("Set-Cookie"), contains("Max-Age=0"))
  }

  test("a cookie-authenticated write from another origin is refused") {
    val (service, sessions) = newService()
    sessions.put("sid", liveSession)
    val filter = new AuthenticationFilter(conf)
    val response = mock[HttpServletResponse]

    val req = request(method = "POST", origin = Some("http://evil.example.com"))
    assert(filter.applyCookieSession(req, response, Some(service)).isEmpty)
    verify(response).sendError(meq(HttpServletResponse.SC_FORBIDDEN), anyString())
  }

  test("a cookie-authenticated write from this origin goes through") {
    val (service, sessions) = newService()
    sessions.put("sid", liveSession)
    val filter = new AuthenticationFilter(conf)
    val response = mock[HttpServletResponse]

    val req = request(method = "POST", origin = Some("http://kyuubi.example.com:32379"))
    val result = filter.applyCookieSession(req, response, Some(service))
    assert(result.map(_.getHeader(AUTHORIZATION_HEADER)).contains("Bearer the-access-token"))
  }

  test("requests without a cookie are left to the existing handlers") {
    val (service, _) = newService()
    val filter = new AuthenticationFilter(conf)
    val response = mock[HttpServletResponse]
    val req = request(cookie = None)

    assert(filter.applyCookieSession(req, response, Some(service)).contains(req))
    verify(response, never()).sendError(anyInt(), anyString())
  }

  test("nothing changes when the backend-for-frontend flow is off") {
    val filter = new AuthenticationFilter(conf)
    val response = mock[HttpServletResponse]
    val req = request()

    assert(filter.applyCookieSession(req, response, None).contains(req))
    verify(response, never()).sendError(anyInt(), anyString())
  }

  test("the login endpoints are reachable without authentication") {
    // Otherwise there would be no way to obtain a session in the first place.
    assert(AuthenticationFilter.UNAUTHENTICATED_PATHS.contains(LOGIN_PATH))
    assert(AuthenticationFilter.UNAUTHENTICATED_PATHS.contains(CALLBACK_PATH))
    assert(AuthenticationFilter.UNAUTHENTICATED_PATHS.contains(LOGOUT_PATH))
    assert(AuthenticationFilter.UNAUTHENTICATED_PATHS.contains("/api/v1/authentication/config"))
    assert(!AuthenticationFilter.UNAUTHENTICATED_PATHS.contains("/api/v1/sessions"))
  }

  test("the wrapper reports the Bearer header among the request's headers") {
    val req = request(cookie = None)
    lenient.when(req.getHeaderNames)
      .thenReturn(java.util.Collections.enumeration(java.util.Arrays.asList("Host")))
    val wrapped = new BearerSessionRequest(req, "abc")

    val names = new scala.collection.mutable.ArrayBuffer[String]()
    val enumeration = wrapped.getHeaderNames
    while (enumeration.hasMoreElements) names += enumeration.nextElement()
    assert(names.contains(AUTHORIZATION_HEADER))
    assert(wrapped.getHeaders(AUTHORIZATION_HEADER).nextElement() === "Bearer abc")
    assert(wrapped.getHeader("Host") === "kyuubi.example.com:32379")
  }
}
