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

package org.apache.kyuubi.server.http.authentication.oidc

import java.util.concurrent.atomic.AtomicInteger
import javax.servlet.http.HttpServletRequest

import scala.collection.mutable.ArrayBuffer

import org.mockito.Mockito.lenient
import org.scalatestplus.mockito.MockitoSugar.mock

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.http.authentication.oidc.OidcBffConf._

class OidcBffServiceSuite extends KyuubiFunSuite {

  /**
   * Stands in for the provider's token endpoint. Every test here runs entirely offline -
   * the real client is only exercised against a live provider.
   */
  private class FakeTokenEndpointClient(
      responses: Seq[Either[OidcTokenException, TokenResponse]])
    extends TokenEndpointClient {
    val calls = new ArrayBuffer[(String, Seq[(String, String)])]()
    private val index = new AtomicInteger(0)

    override def post(endpoint: String, form: Seq[(String, String)]): TokenResponse = {
      calls.append((endpoint, form))
      if (responses.isEmpty) {
        throw new IllegalStateException("the token endpoint was not expected to be called")
      }
      responses(math.min(index.getAndIncrement(), responses.size - 1)) match {
        case Right(response) => response
        case Left(failure) => throw failure
      }
    }
  }

  /** `Either.right`/`Either.left` are deprecated on 2.13, so unwrap by matching instead. */
  private def rightOf[A, B](either: Either[A, B]): B = either match {
    case Right(value) => value
    case Left(error) => fail(s"expected a success but got: $error")
  }

  private def leftOf[A, B](either: Either[A, B]): A = either match {
    case Left(error) => error
    case Right(value) => fail(s"expected a failure but got: $value")
  }

  private def conf(): KyuubiConf = {
    KyuubiConf(loadSysDefault = false)
      .set(OIDC_UI_CLIENT_ID, "kyuubi")
      .set(OIDC_UI_CLIENT_SECRET, "s3cret")
      .set(JWT_ISSUER, "https://provider.example.com/realms/lakehouse")
      .set(OIDC_AUTHORIZATION_ENDPOINT, "https://provider.example.com/auth")
      .set(OIDC_TOKEN_ENDPOINT, "http://provider.internal:8080/token")
      .set(OIDC_END_SESSION_ENDPOINT, "https://provider.example.com/logout")
  }

  private def service(
      client: TokenEndpointClient,
      kyuubiConf: KyuubiConf = conf()): (OidcBffService, OidcSessionStore[OidcSession]) = {
    // An empty discovery map guarantees nothing reaches out to the network.
    val bffConf = new OidcBffConf(kyuubiConf, _ => Map.empty)
    val sessions = new InMemoryOidcSessionStore[OidcSession](60000, "test-sessions", 600000)
    val pending = new InMemoryOidcSessionStore[PendingLogin](60000, "test-pending", 600000)
    (new OidcBffService(kyuubiConf, bffConf, client, sessions, pending), sessions)
  }

  private def request(): HttpServletRequest = {
    val req = mock[HttpServletRequest]
    lenient.when(req.getScheme).thenReturn("http")
    lenient.when(req.getServerName).thenReturn("kyuubi.example.com")
    lenient.when(req.getServerPort).thenReturn(32379)
    lenient.when(req.getHeader("Host")).thenReturn("kyuubi.example.com:32379")
    lenient.when(req.getHeader("X-Forwarded-Proto")).thenReturn(null)
    lenient.when(req.getHeader("X-Forwarded-Host")).thenReturn(null)
    req
  }

  private def session(expiresInMs: Long, refreshToken: Option[String] = Some("rt1")) =
    OidcSession(
      accessToken = "at1",
      refreshToken = refreshToken,
      idToken = Some("it1"),
      expiresAt = System.currentTimeMillis() + expiresInMs,
      username = "trungtm8",
      createdAt = System.currentTimeMillis())

  test("the flow is only enabled once a client secret is configured") {
    assert(new OidcBffConf(conf(), _ => Map.empty).enabled)
    assert(new OidcBffConf(conf(), _ => Map.empty).flow === FLOW_BFF)

    val withoutSecret = conf().unset(OIDC_UI_CLIENT_SECRET)
    assert(!new OidcBffConf(withoutSecret, _ => Map.empty).enabled)
    assert(new OidcBffConf(withoutSecret, _ => Map.empty).flow === FLOW_PUBLIC)
  }

  test("configured endpoints are preferred over discovery") {
    val discovered = Map(
      "authorization_endpoint" -> "https://discovered/auth",
      "token_endpoint" -> "https://discovered/token",
      "end_session_endpoint" -> "https://discovered/logout")
    val pinned = new OidcBffConf(conf(), _ => discovered)
    assert(pinned.tokenEndpoint.contains("http://provider.internal:8080/token"))
    assert(pinned.authorizationEndpoint.contains("https://provider.example.com/auth"))

    // Only what was left unset falls through to the discovery document.
    val partial = new OidcBffConf(conf().unset(OIDC_TOKEN_ENDPOINT), _ => discovered)
    assert(partial.tokenEndpoint.contains("https://discovered/token"))
    assert(partial.authorizationEndpoint.contains("https://provider.example.com/auth"))
  }

  test("session timeout accepts a duration or milliseconds") {
    assert(new OidcBffConf(conf(), _ => Map.empty).sessionTimeoutMs === 4 * 60 * 60 * 1000L)
    assert(new OidcBffConf(
      conf().set(OIDC_SESSION_TIMEOUT, "PT30M"),
      _ => Map.empty).sessionTimeoutMs === 30 * 60 * 1000L)
    assert(new OidcBffConf(
      conf().set(OIDC_SESSION_TIMEOUT, "60000"),
      _ => Map.empty).sessionTimeoutMs === 60000L)
  }

  private def stateOf(url: String): String = {
    val encoded = "state=([^&]+)".r.findFirstMatchIn(url).map(_.group(1)).getOrElse(
      fail(s"no state in $url"))
    java.net.URLDecoder.decode(encoded, "UTF-8")
  }

  test("the authorization redirect carries pkce and a one-time state") {
    val (svc, _) = service(new FakeTokenEndpointClient(Seq(
      Left(new OidcTokenException("invalid_grant", "nope")))))
    val url = rightOf(svc.buildAuthorizationRedirect(request(), "/ui/session"))

    assert(url.startsWith("https://provider.example.com/auth?"))
    assert(url.contains("response_type=code"))
    assert(url.contains("client_id=kyuubi"))
    assert(url.contains("code_challenge_method=S256"))
    assert(url.contains("scope=openid+profile+email"))
    val expectedRedirect =
      "http%3A%2F%2Fkyuubi.example.com%3A32379%2Fapi%2Fv1%2Fauthentication%2Fcallback"
    assert(url.contains(s"redirect_uri=$expectedRedirect"))
    // The secret must never appear in something the browser sees.
    assert(!url.contains("s3cret"))
    assert(!url.contains("code_verifier"))

    // The state is what the callback looks the pending login up by.
    assert(svc.completeLogin("code", stateOf(url)).isLeft)
  }

  test("an off-site redirect target is replaced by the default") {
    val (svc, _) = service(new FakeTokenEndpointClient(Seq(
      Left(new OidcTokenException("invalid_grant", "nope")))))
    val state = stateOf(rightOf(svc.buildAuthorizationRedirect(request(), "//evil.com")))

    // The exchange fails, but only after the sanitized path was stored against the state.
    assert(leftOf(svc.completeLogin("code", state)).contains("invalid_grant"))
    // Replaying the same state now finds nothing: it was consumed on first use.
    assert(leftOf(svc.completeLogin("code", state)) === "Unknown or expired login state")
  }

  test("callback rejects an unknown state without calling the provider") {
    val client = new FakeTokenEndpointClient(Seq.empty)
    val (svc, _) = service(client)
    assert(leftOf(svc.completeLogin("code", "never-issued")) === "Unknown or expired login state")
    assert(leftOf(svc.completeLogin("code", null)) === "Missing state")
    assert(leftOf(svc.completeLogin("", "state")) === "Missing authorization code")
    assert(client.calls.isEmpty)
  }

  test("a session with time left is used as is") {
    val client = new FakeTokenEndpointClient(Seq.empty)
    val (svc, sessions) = service(client)
    sessions.put("sid", session(expiresInMs = 300000))

    assert(rightOf(svc.authenticate("sid")).accessToken === "at1")
    assert(client.calls.isEmpty, "no refresh should have been attempted")
  }

  test("an expiring session is refreshed and the rotated refresh token is kept") {
    val client = new FakeTokenEndpointClient(Seq(
      Right(TokenResponse("at2", Some("rt2"), Some("it2"), 300))))
    val (svc, sessions) = service(client)
    // Inside the 60s skew, so it renews before the request goes through.
    sessions.put("sid", session(expiresInMs = 5000))

    val renewed = rightOf(svc.authenticate("sid"))
    assert(renewed.accessToken === "at2")
    assert(renewed.refreshToken.contains("rt2"))
    assert(renewed.expiresAt > System.currentTimeMillis() + 200000)
    // Keycloak invalidates the old refresh token, so the stored session must carry the new one.
    assert(sessions.get("sid").flatMap(_.refreshToken).contains("rt2"))
    assert(sessions.get("sid").map(_.accessToken).contains("at2"))

    val (endpoint, form) = client.calls.head
    assert(endpoint === "http://provider.internal:8080/token")
    assert(form.contains("grant_type" -> "refresh_token"))
    assert(form.contains("refresh_token" -> "rt1"))
    assert(form.contains("client_secret" -> "s3cret"))
  }

  test("a session keeps its refresh token when the provider does not rotate it") {
    val client = new FakeTokenEndpointClient(Seq(Right(TokenResponse("at2", None, None, 300))))
    val (svc, sessions) = service(client)
    sessions.put("sid", session(expiresInMs = 5000))

    assert(rightOf(svc.authenticate("sid")).accessToken === "at2")
    assert(sessions.get("sid").flatMap(_.refreshToken).contains("rt1"))
    // The previous id_token is still the one to use as a logout hint.
    assert(sessions.get("sid").flatMap(_.idToken).contains("it1"))
  }

  test("a rejected refresh grant destroys the session") {
    val client = new FakeTokenEndpointClient(Seq(
      Left(new OidcTokenException("invalid_grant", "refresh token expired"))))
    val (svc, sessions) = service(client)
    sessions.put("sid", session(expiresInMs = 5000))

    assert(leftOf(svc.authenticate("sid")) === OidcAuthFailure.RefreshFailed("invalid_grant"))
    assert(sessions.get("sid").isEmpty, "the session cannot recover, so it must be dropped")
  }

  test("a transient refresh failure does not destroy a still-valid session") {
    val client = new FakeTokenEndpointClient(Seq(
      Left(new OidcTokenException("request_failed", "connection refused"))))
    val (svc, sessions) = service(client)
    sessions.put("sid", session(expiresInMs = 30000))

    // The access token has 30s left, so the request carries on with it.
    assert(rightOf(svc.authenticate("sid")).accessToken === "at1")
    assert(sessions.get("sid").isDefined)
  }

  test("an unknown session id is not authenticated") {
    val (svc, _) = service(new FakeTokenEndpointClient(Seq.empty))
    assert(leftOf(svc.authenticate("nope")) === OidcAuthFailure.UnknownSession)
    assert(svc.sessionFor("nope").isEmpty)
  }

  test("concurrent requests on one session refresh it only once") {
    val client = new FakeTokenEndpointClient(Seq(
      Right(TokenResponse("at2", Some("rt2"), None, 300))))
    val (svc, sessions) = service(client)
    sessions.put("sid", session(expiresInMs = 1000))

    val threads = (1 to 8).map { _ =>
      new Thread(new Runnable {
        override def run(): Unit = svc.authenticate("sid")
      })
    }
    threads.foreach(_.start())
    threads.foreach(_.join())

    // A second refresh would be sent with the rotated token already invalidated.
    assert(client.calls.size === 1, s"expected one refresh, got ${client.calls.size}")
  }

  test("logout builds an end-session url without leaking the id token to the browser") {
    val (svc, sessions) = service(new FakeTokenEndpointClient(Seq.empty))
    sessions.put("sid", session(expiresInMs = 300000))

    val url = svc.endSessionUrl(svc.sessionFor("sid"), "http://kyuubi.example.com:32379/ui").get
    assert(url.startsWith("https://provider.example.com/logout?"))
    assert(url.contains("id_token_hint=it1"))
    assert(url.contains("client_id=kyuubi"))
    assert(url.contains("post_logout_redirect_uri=http%3A%2F%2Fkyuubi.example.com%3A32379%2Fui"))

    // Signing out drops the session, so the cookie that survives it is worthless.
    assert(svc.invalidate("sid").isDefined)
    assert(svc.sessionFor("sid").isEmpty)
  }

  test("no end-session url when the provider does not publish one") {
    val (svc, _) = service(
      new FakeTokenEndpointClient(Seq.empty),
      conf().unset(OIDC_END_SESSION_ENDPOINT))
    assert(svc.endSessionUrl(None, "http://kyuubi.example.com:32379/ui").isEmpty)
  }
}
