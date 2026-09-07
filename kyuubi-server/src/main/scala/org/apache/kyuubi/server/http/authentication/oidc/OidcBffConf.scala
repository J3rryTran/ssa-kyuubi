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

import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf

/**
 * Configuration of the Web UI's backend-for-frontend OIDC flow.
 *
 * Like the rest of the OIDC work in this fork, every key is read raw through
 * [[KyuubiConf.getOption]] instead of being registered as a `ConfigEntry`, so Kyuubi core stays
 * untouched and the settings can be dropped into `kyuubi-defaults.conf` as-is.
 *
 * Supplying `oidc.ui.client.secret` is what switches the UI from the in-browser PKCE flow to the
 * BFF flow; without it everything here stays dormant and the existing deployment is unaffected.
 */
class OidcBffConf(
    conf: KyuubiConf,
    discover: String => Map[String, String] = OidcDiscovery.fetch) extends Logging {

  import OidcBffConf._

  val clientId: Option[String] = trimmed(OIDC_UI_CLIENT_ID)
  val clientSecret: Option[String] = trimmed(OIDC_UI_CLIENT_SECRET)
  val issuer: Option[String] = trimmed(JWT_ISSUER)
  val scope: String = OidcBffUtils.withOpenIdScope(
    trimmed(OIDC_UI_SCOPE).getOrElse(DEFAULT_SCOPE))

  val cookieName: String = trimmed(OIDC_COOKIE_NAME).getOrElse(DEFAULT_COOKIE_NAME)
  val cookieSecure: Boolean = trimmed(OIDC_COOKIE_SECURE).exists(_.toBoolean)

  val sessionTimeoutMs: Long =
    trimmed(OIDC_SESSION_TIMEOUT).map(parseDuration).getOrElse(DEFAULT_SESSION_TIMEOUT_MS)

  val connectTimeoutMs: Int = trimmed(JWT_CONNECT_TIMEOUT_MS).map(_.toInt).getOrElse(5000)
  val readTimeoutMs: Int = trimmed(JWT_READ_TIMEOUT_MS).map(_.toInt).getOrElse(5000)

  /** The BFF is on exactly when a client secret is available to authenticate the token call. */
  val enabled: Boolean = clientSecret.exists(_.nonEmpty) && clientId.exists(_.nonEmpty)

  /** What `GET /api/v1/authentication/config` reports, so the UI picks the matching flow. */
  def flow: String = if (enabled) FLOW_BFF else FLOW_PUBLIC

  private val configuredAuthorizationEndpoint = trimmed(OIDC_AUTHORIZATION_ENDPOINT)
  private val configuredTokenEndpoint = trimmed(OIDC_TOKEN_ENDPOINT)
  private val configuredEndSessionEndpoint = trimmed(OIDC_END_SESSION_ENDPOINT)

  /**
   * Discovery is only consulted for endpoints that were not configured, and only on first use.
   * That matters in the target deployment: the issuer is published over HTTPS with a self-signed
   * certificate, so an operator who pins all three endpoints never makes the server touch it.
   */
  private lazy val discovered: Map[String, String] = {
    issuer match {
      case Some(iss) =>
        try {
          discover(iss)
        } catch {
          case e: Exception =>
            warn(s"OIDC discovery against the issuer failed: ${e.getMessage}. Configure " +
              s"$OIDC_AUTHORIZATION_ENDPOINT / $OIDC_TOKEN_ENDPOINT / $OIDC_END_SESSION_ENDPOINT " +
              "explicitly to avoid discovery.")
            Map.empty
        }
      case None => Map.empty
    }
  }

  /** Must be reachable by the *browser*, so it is the externally published issuer URL. */
  def authorizationEndpoint: Option[String] =
    configuredAuthorizationEndpoint.orElse(discovered.get("authorization_endpoint"))

  /** Called *server to server*, so it can - and in the target deployment must - be internal. */
  def tokenEndpoint: Option[String] =
    configuredTokenEndpoint.orElse(discovered.get("token_endpoint"))

  def endSessionEndpoint: Option[String] =
    configuredEndSessionEndpoint.orElse(discovered.get("end_session_endpoint"))

  /**
   * The `redirect_uri` registered with the provider. Defaults to this server's own callback,
   * derived from the request so a deployment behind a proxy still gets the browser-facing URL.
   * It must be byte-identical between the authorize and token calls, hence the shared helper.
   */
  def redirectUri(request: HttpServletRequest): String = {
    trimmed(OIDC_REDIRECT_URI).getOrElse {
      s"${OidcBffUtils.requestOrigin(request)}$CALLBACK_PATH"
    }
  }

  private def trimmed(key: String): Option[String] =
    conf.getOption(key).map(_.trim).filter(_.nonEmpty)
}

object OidcBffConf {

  /** Set by the OIDC auth extension; re-read here to tell the Web UI which issuer to use. */
  val JWT_ISSUER = "kyuubi.authentication.jwt.issuer"
  val JWT_EXPECTED_TYP = "kyuubi.authentication.jwt.expected.typ"
  val JWT_CONNECT_TIMEOUT_MS = "kyuubi.authentication.jwt.connect.timeout.ms"
  val JWT_READ_TIMEOUT_MS = "kyuubi.authentication.jwt.read.timeout.ms"

  /**
   * The OIDC client the Web UI authenticates as. Without a secret it is treated as a public
   * client and the browser runs PKCE itself; with one, the code exchange moves to the server.
   */
  val OIDC_UI_CLIENT_ID = "kyuubi.authentication.oidc.ui.client.id"
  val OIDC_UI_CLIENT_SECRET = "kyuubi.authentication.oidc.ui.client.secret"
  val OIDC_UI_SCOPE = "kyuubi.authentication.oidc.ui.scope"

  val OIDC_AUTHORIZATION_ENDPOINT = "kyuubi.authentication.oidc.authorization.endpoint"
  val OIDC_TOKEN_ENDPOINT = "kyuubi.authentication.oidc.token.endpoint"
  val OIDC_END_SESSION_ENDPOINT = "kyuubi.authentication.oidc.end.session.endpoint"
  val OIDC_REDIRECT_URI = "kyuubi.authentication.oidc.redirect.uri"
  val OIDC_SESSION_TIMEOUT = "kyuubi.authentication.oidc.session.timeout"
  val OIDC_COOKIE_NAME = "kyuubi.authentication.oidc.cookie.name"
  val OIDC_COOKIE_SECURE = "kyuubi.authentication.oidc.cookie.secure"

  val DEFAULT_COOKIE_NAME = "KYUUBI_SESSION"
  val DEFAULT_SCOPE = "openid profile email"
  val DEFAULT_SESSION_TIMEOUT_MS: Long = TimeUnit.HOURS.toMillis(4)

  /** How long a started-but-uncompleted login is remembered. */
  val PENDING_LOGIN_TTL_MS: Long = TimeUnit.MINUTES.toMillis(10)

  /** Renew the access token this long before it actually expires. */
  val REFRESH_SKEW_MS: Long = TimeUnit.SECONDS.toMillis(60)

  val FLOW_BFF = "bff"
  val FLOW_PUBLIC = "public"

  val LOGIN_PATH = "/api/v1/authentication/login"
  val CALLBACK_PATH = "/api/v1/authentication/callback"
  val LOGOUT_PATH = "/api/v1/authentication/logout"

  /** ISO-8601 (`PT4H`) as documented, or a plain number of milliseconds. */
  private[oidc] def parseDuration(value: String): Long = {
    val trimmed = value.trim
    if (trimmed.forall(_.isDigit)) {
      trimmed.toLong
    } else {
      Duration.parse(trimmed).toMillis
    }
  }
}
