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

import java.util.concurrent.ConcurrentHashMap
import javax.servlet.http.HttpServletRequest

import com.nimbusds.jwt.JWTParser

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.service.authentication.{AuthenticationProviderFactory, DefaultTokenCredential, TokenAuthenticationProvider}

/**
 * Why a session cannot be used. Both map to 401 - the browser is told to sign in again - but
 * they are distinguished so the log line says which happened without leaking the session id.
 */
sealed trait OidcAuthFailure
object OidcAuthFailure {

  /** No such session on this server: expired, logged out, or landed on another replica. */
  case object UnknownSession extends OidcAuthFailure

  /** The session exists but its tokens could not be renewed. */
  case class RefreshFailed(error: String) extends OidcAuthFailure
}

/**
 * Backend-for-frontend for the Web UI's OIDC login.
 *
 * The browser never sees a token. It gets an opaque, random session id in an `HttpOnly` cookie;
 * the access/refresh/id tokens live only in [[OidcSessionStore]] here, and the code-for-token
 * exchange happens server side so the OIDC client can stay confidential.
 *
 * Token *validation* is not reimplemented: the configured
 * [[TokenAuthenticationProvider]] - normally `JwtTokenAuthenticationProvider` from the
 * kyuubi-oidc-auth extension - is what checks signatures and claims, both here and, for every
 * subsequent request, in `AuthenticationFilter`.
 */
class OidcBffService(
    val conf: KyuubiConf,
    val bffConf: OidcBffConf,
    tokenClient: TokenEndpointClient,
    sessions: OidcSessionStore[OidcSession],
    pendingLogins: OidcSessionStore[PendingLogin]) extends Logging {

  import OidcBffConf._

  /** Serialises concurrent refreshes of the same session; see [[refreshIfNeeded]]. */
  private val refreshLocks = new ConcurrentHashMap[String, AnyRef]()

  private val bearerProviderClass: String =
    conf.get(KyuubiConf.AUTHENTICATION_CUSTOM_BEARER_CLASS)
      .getOrElse(AuthenticationProviderFactory.OIDC_BEARER_PROVIDER_CLASS)

  /** Validates access tokens exactly as the Bearer request path does. */
  private lazy val accessTokenVerifier: TokenAuthenticationProvider =
    AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(bearerProviderClass, conf)

  /**
   * Validates id_tokens. Same issuer, JWKS, audience and algorithms - only the expected JOSE
   * `typ` is dropped, because that setting exists precisely to reject id_tokens on the Bearer
   * path, and here an id_token is what we are meant to be looking at.
   */
  private lazy val idTokenVerifier: TokenAuthenticationProvider = {
    val idTokenConf = conf.clone
    idTokenConf.unset(JWT_EXPECTED_TYP)
    AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(
      bearerProviderClass,
      idTokenConf)
  }

  def enabled: Boolean = bffConf.enabled

  def cookieName: String = bffConf.cookieName

  /** The session id the browser presented, if any. */
  def sessionIdFrom(request: HttpServletRequest): Option[String] =
    OidcBffUtils.readCookie(request, bffConf.cookieName)

  def sessionFor(sessionId: String): Option[OidcSession] = sessions.get(sessionId)

  /**
   * Step 1 of the flow: remember the PKCE verifier and nonce under a fresh `state`, and return
   * the provider URL to send the browser to.
   */
  def buildAuthorizationRedirect(
      request: HttpServletRequest,
      rawRedirect: String): Either[String, String] = {
    for {
      authorizeUrl <- bffConf.authorizationEndpoint.toRight(
        s"$OIDC_AUTHORIZATION_ENDPOINT is not configured and could not be discovered")
      clientId <- bffConf.clientId.toRight(s"$OIDC_UI_CLIENT_ID is not configured")
    } yield {
      val state = OidcBffUtils.randomToken()
      val nonce = OidcBffUtils.randomToken()
      val verifier = OidcBffUtils.generateCodeVerifier()
      val redirectUri = bffConf.redirectUri(request)
      pendingLogins.put(
        state,
        PendingLogin(
          codeVerifier = verifier,
          nonce = nonce,
          redirectPath = OidcBffUtils.sanitizeRedirectPath(rawRedirect),
          redirectUri = redirectUri,
          createdAt = System.currentTimeMillis()))
      val query = OidcBffUtils.formEncode(Seq(
        "response_type" -> "code",
        "client_id" -> clientId,
        "redirect_uri" -> redirectUri,
        "scope" -> bffConf.scope,
        "state" -> state,
        "nonce" -> nonce,
        "code_challenge" -> OidcBffUtils.codeChallengeS256(verifier),
        "code_challenge_method" -> "S256"))
      val separator = if (authorizeUrl.contains("?")) "&" else "?"
      s"$authorizeUrl$separator$query"
    }
  }

  /**
   * Step 2: swap the authorization code for tokens and open a browser session.
   * Returns the new session id and where to send the browser next.
   */
  def completeLogin(code: String, state: String): Either[String, (String, String)] = {
    if (code == null || code.isEmpty) return Left("Missing authorization code")
    if (state == null || state.isEmpty) return Left("Missing state")

    // One-time use: a replayed callback must not find the verifier still sitting here.
    val pending = pendingLogins.remove(state) match {
      case Some(p) => p
      case None => return Left("Unknown or expired login state")
    }

    for {
      tokenUrl <- bffConf.tokenEndpoint.toRight(
        s"$OIDC_TOKEN_ENDPOINT is not configured and could not be discovered")
      clientId <- bffConf.clientId.toRight(s"$OIDC_UI_CLIENT_ID is not configured")
      clientSecret <- bffConf.clientSecret.toRight(s"$OIDC_UI_CLIENT_SECRET is not configured")
      tokens <- exchange(
        tokenUrl,
        Seq(
          "grant_type" -> "authorization_code",
          "code" -> code,
          "redirect_uri" -> pending.redirectUri,
          "code_verifier" -> pending.codeVerifier,
          "client_id" -> clientId,
          "client_secret" -> clientSecret))
      idToken <- tokens.idToken.toRight(
        "Token response carried no id_token; the 'openid' scope must be granted to this client")
      _ <- verifyIdToken(idToken, pending.nonce)
      username <- verifyAccessToken(tokens.accessToken)
    } yield {
      val sessionId = OidcBffUtils.randomToken()
      val now = System.currentTimeMillis()
      sessions.put(
        sessionId,
        OidcSession(
          accessToken = tokens.accessToken,
          refreshToken = tokens.refreshToken,
          idToken = tokens.idToken,
          expiresAt = now + tokens.expiresInSeconds * 1000L,
          username = username,
          createdAt = now))
      info(s"Established OIDC browser session ${OidcBffUtils.shortId(sessionId)} for '$username'")
      (sessionId, pending.redirectPath)
    }
  }

  /**
   * Resolve a session id into a usable session, renewing the access token first if it is about
   * to expire. Callers treat any [[OidcAuthFailure]] as 401.
   */
  def authenticate(sessionId: String): Either[OidcAuthFailure, OidcSession] = {
    sessions.get(sessionId) match {
      case None => Left(OidcAuthFailure.UnknownSession)
      case Some(session) => refreshIfNeeded(sessionId, session)
    }
  }

  /**
   * Renew when the access token has less than [[OidcBffConf.REFRESH_SKEW_MS]] left.
   *
   * Parallel requests on one session must not each fire a refresh: the provider may rotate the
   * refresh token, in which case the losers would be left holding an already-invalidated one.
   * So the work happens under a per-session lock, and whoever gets in re-reads the session first
   * in case it was renewed while they waited.
   */
  private def refreshIfNeeded(
      sessionId: String,
      session: OidcSession): Either[OidcAuthFailure, OidcSession] = {
    if (!needsRefresh(session)) {
      Right(session)
    } else {
      val lock = refreshLocks.computeIfAbsent(sessionId, _ => new Object)
      lock.synchronized {
        sessions.get(sessionId) match {
          case None => Left(OidcAuthFailure.UnknownSession)
          case Some(current) if !needsRefresh(current) => Right(current)
          case Some(current) => doRefresh(sessionId, current)
        }
      }
    }
  }

  private def doRefresh(
      sessionId: String,
      current: OidcSession): Either[OidcAuthFailure, OidcSession] = {
    val attempt = for {
      token <- current.refreshToken.toRight("no_refresh_token")
      tokenUrl <- bffConf.tokenEndpoint.toRight("no_token_endpoint")
      clientId <- bffConf.clientId.toRight("no_client_id")
      clientSecret <- bffConf.clientSecret.toRight("no_client_secret")
      tokens <- exchangeForRefresh(
        tokenUrl,
        Seq(
          "grant_type" -> "refresh_token",
          "refresh_token" -> token,
          "client_id" -> clientId,
          "client_secret" -> clientSecret))
    } yield tokens

    attempt match {
      case Right(tokens) =>
        val renewed = current.copy(
          accessToken = tokens.accessToken,
          // Keycloak rotates refresh tokens; the old one is dead the moment this returns.
          refreshToken = tokens.refreshToken.orElse(current.refreshToken),
          idToken = tokens.idToken.orElse(current.idToken),
          expiresAt = System.currentTimeMillis() + tokens.expiresInSeconds * 1000L)
        sessions.put(sessionId, renewed)
        debug(s"Renewed OIDC session ${OidcBffUtils.shortId(sessionId)}")
        Right(renewed)

      case Left(error) =>
        // A rejected grant is terminal - the session can never recover, so drop it. Anything
        // else may be transient, so an access token that is still valid keeps working.
        if (error == "invalid_grant" || error == "no_refresh_token") {
          invalidate(sessionId)
          warn(s"Dropped OIDC session ${OidcBffUtils.shortId(sessionId)}: refresh $error")
          Left(OidcAuthFailure.RefreshFailed(error))
        } else if (current.expiresAt > System.currentTimeMillis()) {
          warn(s"Could not renew OIDC session ${OidcBffUtils.shortId(sessionId)} ($error); " +
            "continuing with the current access token")
          Right(current)
        } else {
          Left(OidcAuthFailure.RefreshFailed(error))
        }
    }
  }

  private def needsRefresh(session: OidcSession): Boolean =
    session.expiresAt - System.currentTimeMillis() < REFRESH_SKEW_MS

  /** Forget a session; the caller is responsible for expiring the cookie too. */
  def invalidate(sessionId: String): Option[OidcSession] = {
    refreshLocks.remove(sessionId)
    sessions.remove(sessionId)
  }

  /**
   * The provider URL that ends the SSO session, when the provider publishes one. The id_token is
   * used as `id_token_hint` here and nowhere else - it is never handed to the browser.
   */
  def endSessionUrl(session: Option[OidcSession], postLogoutRedirect: String): Option[String] = {
    bffConf.endSessionEndpoint.map { endpoint =>
      val params = Seq("post_logout_redirect_uri" -> postLogoutRedirect) ++
        bffConf.clientId.map("client_id" -> _) ++
        session.flatMap(_.idToken).map("id_token_hint" -> _)
      val separator = if (endpoint.contains("?")) "&" else "?"
      s"$endpoint$separator${OidcBffUtils.formEncode(params)}"
    }
  }

  private def exchange(
      tokenUrl: String,
      form: Seq[(String, String)]): Either[String, TokenResponse] = {
    try {
      Right(tokenClient.post(tokenUrl, form))
    } catch {
      case e: OidcTokenException =>
        // The message is safe to surface: it carries the OAuth2 error code, not the payload.
        Left(s"Token exchange failed (${e.error})")
    }
  }

  /** Same call, but the caller needs the bare error code to decide whether to drop the session. */
  private def exchangeForRefresh(
      tokenUrl: String,
      form: Seq[(String, String)]): Either[String, TokenResponse] = {
    try {
      Right(tokenClient.post(tokenUrl, form))
    } catch {
      case e: OidcTokenException => Left(e.error)
    }
  }

  private def verifyIdToken(idToken: String, expectedNonce: String): Either[String, Unit] = {
    try {
      idTokenVerifier.authenticate(DefaultTokenCredential(idToken))
      val nonce = JWTParser.parse(idToken).getJWTClaimsSet.getStringClaim("nonce")
      if (!OidcBffUtils.secureEquals(nonce, expectedNonce)) {
        Left("id_token nonce does not match the login request")
      } else {
        Right(())
      }
    } catch {
      case e: Exception => Left(s"id_token validation failed: ${e.getMessage}")
    }
  }

  /** Returns the session username, as decided by the configured provider's username claim. */
  private def verifyAccessToken(accessToken: String): Either[String, String] = {
    try {
      Right(accessTokenVerifier.authenticate(DefaultTokenCredential(accessToken)).getName)
    } catch {
      case e: Exception => Left(s"access token validation failed: ${e.getMessage}")
    }
  }

  def close(): Unit = {
    refreshLocks.clear()
    sessions match {
      case s: InMemoryOidcSessionStore[_] => s.shutdown()
      case _ =>
    }
    pendingLogins match {
      case s: InMemoryOidcSessionStore[_] => s.shutdown()
      case _ =>
    }
  }
}

object OidcBffService extends Logging {

  @volatile private var instance: Option[OidcBffService] = None
  // Tracked separately from `instance`, which stays None when the flow is simply not configured.
  @volatile private var initialized: Boolean = false

  /**
   * The REST resource and the authentication filter are built independently but must share one
   * set of session stores, so the service is a per-JVM singleton keyed off nothing but the
   * server's configuration.
   */
  def get(conf: KyuubiConf): Option[OidcBffService] = {
    if (!initialized) {
      synchronized {
        if (!initialized) {
          val bffConf = new OidcBffConf(conf)
          instance =
            if (!bffConf.enabled) {
              None
            } else {
              info("OIDC backend-for-frontend enabled for the Web UI; " +
                "the browser will receive a session cookie instead of tokens")
              Some(new OidcBffService(
                conf,
                bffConf,
                new HttpUrlTokenEndpointClient(bffConf.connectTimeoutMs, bffConf.readTimeoutMs),
                new InMemoryOidcSessionStore[OidcSession](bffConf.sessionTimeoutMs, "sessions"),
                new InMemoryOidcSessionStore[PendingLogin](
                  OidcBffConf.PENDING_LOGIN_TTL_MS,
                  "pending-logins")))
            }
          initialized = true
        }
      }
    }
    instance
  }

  /** Drop the singleton; only meaningful between tests. */
  private[kyuubi] def reset(): Unit = synchronized {
    instance.foreach(_.close())
    instance = None
    initialized = false
  }
}
