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

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.servlet.http.HttpServletRequest

/**
 * Stateless helpers for the OIDC BFF flow. Kept free of any I/O or configuration so the
 * security-critical decisions here - where to redirect, whether a cookie-authenticated write is
 * allowed, how a cookie is spelled - can be unit tested without a provider or a servlet container.
 */
object OidcBffUtils {

  /** Where the browser lands when no usable `redirect` was supplied. */
  final val DEFAULT_REDIRECT_PATH = "/ui/overview"

  final private val METHODS_REQUIRING_ORIGIN = Set("POST", "PUT", "PATCH", "DELETE")

  private val secureRandom = new SecureRandom()

  private val base64Url = Base64.getUrlEncoder.withoutPadding()

  /** `bytes` bytes of [[SecureRandom]], base64url encoded without padding. */
  def randomToken(bytes: Int = 32): String = {
    val buf = new Array[Byte](bytes)
    secureRandom.nextBytes(buf)
    base64Url.encodeToString(buf)
  }

  /** A PKCE `code_verifier`: 32 random bytes encode to 43 characters, within the legal 43-128. */
  def generateCodeVerifier(): String = randomToken(32)

  /** `code_challenge` for `code_challenge_method=S256`. */
  def codeChallengeS256(verifier: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(verifier.getBytes(StandardCharsets.US_ASCII))
    base64Url.encodeToString(digest)
  }

  /** Constant-time comparison, for values an attacker could otherwise probe byte by byte. */
  def secureEquals(a: String, b: String): Boolean = {
    if (a == null || b == null) {
      false
    } else {
      MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8))
    }
  }

  /**
   * Reduce a session id to something safe to log. Never log the id itself: it is the whole
   * credential, so a log line carrying it is as good as the cookie.
   */
  def shortId(sessionId: String): String = {
    if (sessionId == null || sessionId.isEmpty) "-"
    else if (sessionId.length <= 8) sessionId
    else sessionId.substring(0, 8)
  }

  /**
   * Accept only a same-site absolute path, so `?redirect=` cannot bounce the user off-site after
   * a successful login. Anything suspicious silently falls back to [[DEFAULT_REDIRECT_PATH]]
   * rather than failing the login.
   */
  def sanitizeRedirectPath(raw: String): String = {
    if (raw == null) return DEFAULT_REDIRECT_PATH
    val candidate = raw.trim
    val rejected =
      candidate.isEmpty ||
        !candidate.startsWith("/") ||
        // "//host" and "/\host" are protocol-relative URLs, not local paths
        candidate.startsWith("//") ||
        candidate.startsWith("/\\") ||
        candidate.contains("://") ||
        // header injection into the Location response header
        candidate.exists(c => c == '\r' || c == '\n' || c.isControl)
    if (rejected) DEFAULT_REDIRECT_PATH else candidate
  }

  /**
   * CSRF defence for cookie-authenticated requests: `SameSite=Lax` already blocks cross-site
   * writes on modern browsers, and this rejects the rest. Requests carrying an `Authorization`
   * header are exempt - they are not cookie-authenticated, and JDBC clients send no `Origin`.
   */
  def csrfAllowed(request: HttpServletRequest): Boolean = {
    val method = Option(request.getMethod).map(_.toUpperCase).getOrElse("")
    if (!METHODS_REQUIRING_ORIGIN.contains(method)) {
      true
    } else {
      val expected = requestOrigin(request)
      Option(request.getHeader("Origin")).filter(_.nonEmpty) match {
        case Some(origin) => originOf(origin).contains(expected)
        // No Origin (older browsers, some proxies): fall back to Referer, then refuse.
        case None =>
          Option(request.getHeader("Referer")).filter(_.nonEmpty)
            .flatMap(originOf).contains(expected)
      }
    }
  }

  /** The origin this request was addressed to, honouring the usual reverse-proxy headers. */
  def requestOrigin(request: HttpServletRequest): String = {
    val scheme = Option(request.getHeader("X-Forwarded-Proto"))
      .map(_.split(",")(0).trim)
      .filter(_.nonEmpty)
      .getOrElse(request.getScheme)
    val hostHeader = Option(request.getHeader("X-Forwarded-Host"))
      .map(_.split(",")(0).trim)
      .filter(_.nonEmpty)
      .orElse(Option(request.getHeader("Host")).filter(_.nonEmpty))
      .getOrElse {
        val port = request.getServerPort
        s"${request.getServerName}:$port"
      }
    normalizeOrigin(scheme, hostHeader)
  }

  /** Parse an absolute URL down to `scheme://host[:port]`, dropping the default port. */
  private[oidc] def originOf(url: String): Option[String] = {
    try {
      val uri = new URI(url.trim)
      val scheme = Option(uri.getScheme).map(_.toLowerCase)
      val host = Option(uri.getHost)
      (scheme, host) match {
        case (Some(s), Some(h)) =>
          val hostPort = if (uri.getPort > 0) s"$h:${uri.getPort}" else h
          Some(normalizeOrigin(s, hostPort))
        case _ => None
      }
    } catch {
      case _: Exception => None
    }
  }

  private def normalizeOrigin(scheme: String, hostPort: String): String = {
    val s = scheme.toLowerCase
    val hp = hostPort.toLowerCase
    val normalized = (s, hp) match {
      case ("http", h) if h.endsWith(":80") => h.dropRight(3)
      case ("https", h) if h.endsWith(":443") => h.dropRight(4)
      case (_, h) => h
    }
    s"$s://$normalized"
  }

  /**
   * The `Set-Cookie` value for the session cookie. Written by hand because JAX-RS 2.0's
   * `NewCookie` cannot express `SameSite`.
   */
  def sessionCookie(
      name: String,
      value: String,
      maxAgeSeconds: Long,
      secure: Boolean): String = {
    val sb = new StringBuilder
    sb.append(name).append('=').append(value)
    sb.append("; Path=/")
    sb.append("; HttpOnly")
    sb.append("; SameSite=Lax")
    sb.append("; Max-Age=").append(maxAgeSeconds)
    if (secure) sb.append("; Secure")
    sb.toString
  }

  /** The same cookie, expired - sent on logout so the browser drops it immediately. */
  def expiredSessionCookie(name: String, secure: Boolean): String =
    sessionCookie(name, "", 0, secure)

  /** Read the session id out of the request cookies, if the browser sent one. */
  def readCookie(request: HttpServletRequest, name: String): Option[String] = {
    Option(request.getCookies).toSeq.flatten
      .find(c => c.getName == name)
      .map(_.getValue)
      .filter(v => v != null && v.nonEmpty)
  }

  /** `application/x-www-form-urlencoded` body or query string from parameter pairs. */
  def formEncode(params: Seq[(String, String)]): String = {
    params.map { case (k, v) =>
      s"${urlEncode(k)}=${urlEncode(v)}"
    }.mkString("&")
  }

  def urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

  /** OIDC requires the `openid` scope for an id_token to be issued; make sure it is asked for. */
  def withOpenIdScope(scope: String): String = {
    val scopes = Option(scope).getOrElse("").split("\\s+").filter(_.nonEmpty)
    if (scopes.exists(_.equalsIgnoreCase("openid"))) scopes.mkString(" ")
    else ("openid" +: scopes).mkString(" ")
  }
}
