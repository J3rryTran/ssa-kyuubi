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

import javax.servlet.http.{Cookie, HttpServletRequest}

import org.mockito.Mockito.lenient
import org.scalatestplus.mockito.MockitoSugar.mock

import org.apache.kyuubi.KyuubiFunSuite

class OidcBffUtilsSuite extends KyuubiFunSuite {

  private def request(
      method: String = "GET",
      origin: Option[String] = None,
      referer: Option[String] = None,
      host: String = "kyuubi.example.com:32379",
      scheme: String = "http",
      cookies: Seq[Cookie] = Seq.empty): HttpServletRequest = {
    val req = mock[HttpServletRequest]
    lenient.when(req.getMethod).thenReturn(method)
    lenient.when(req.getScheme).thenReturn(scheme)
    lenient.when(req.getServerName).thenReturn("kyuubi.example.com")
    lenient.when(req.getServerPort).thenReturn(32379)
    lenient.when(req.getHeader("Host")).thenReturn(host)
    lenient.when(req.getHeader("Origin")).thenReturn(origin.orNull)
    lenient.when(req.getHeader("Referer")).thenReturn(referer.orNull)
    lenient.when(req.getHeader("X-Forwarded-Proto")).thenReturn(null)
    lenient.when(req.getHeader("X-Forwarded-Host")).thenReturn(null)
    lenient.when(req.getCookies).thenReturn(if (cookies.isEmpty) null else cookies.toArray)
    req
  }

  test("redirect path only accepts a local path") {
    assert(OidcBffUtils.sanitizeRedirectPath("/ui/overview") === "/ui/overview")
    assert(OidcBffUtils.sanitizeRedirectPath("/ui/session?id=1") === "/ui/session?id=1")

    val fallback = OidcBffUtils.DEFAULT_REDIRECT_PATH
    // protocol-relative URLs would leave the site while still starting with '/'
    assert(OidcBffUtils.sanitizeRedirectPath("//evil.com") === fallback)
    assert(OidcBffUtils.sanitizeRedirectPath("/\\evil.com") === fallback)
    assert(OidcBffUtils.sanitizeRedirectPath("http://evil.com") === fallback)
    assert(OidcBffUtils.sanitizeRedirectPath("/redirect?to=http://evil.com") === fallback)
    assert(OidcBffUtils.sanitizeRedirectPath("ui/overview") === fallback)
    assert(OidcBffUtils.sanitizeRedirectPath("") === fallback)
    assert(OidcBffUtils.sanitizeRedirectPath(null) === fallback)
    // header injection into the Location response header
    assert(OidcBffUtils.sanitizeRedirectPath("/ui\r\nSet-Cookie: a=b") === fallback)
  }

  test("session cookie carries the hardening flags") {
    val cookie = OidcBffUtils.sessionCookie("KYUUBI_SESSION", "abc123", 14400, secure = false)
    assert(cookie.startsWith("KYUUBI_SESSION=abc123"))
    assert(cookie.contains("; HttpOnly"))
    assert(cookie.contains("; SameSite=Lax"))
    assert(cookie.contains("; Path=/"))
    assert(cookie.contains("; Max-Age=14400"))
    assert(!cookie.contains("Secure"))

    val secureCookie = OidcBffUtils.sessionCookie("KYUUBI_SESSION", "abc123", 60, secure = true)
    assert(secureCookie.contains("; Secure"))

    val expired = OidcBffUtils.expiredSessionCookie("KYUUBI_SESSION", secure = false)
    assert(expired.contains("Max-Age=0"))
    assert(expired.contains("; HttpOnly"))
  }

  test("cookie is read back by name") {
    val req = request(cookies = Seq(new Cookie("other", "x"), new Cookie("KYUUBI_SESSION", "sid")))
    assert(OidcBffUtils.readCookie(req, "KYUUBI_SESSION").contains("sid"))
    assert(OidcBffUtils.readCookie(req, "missing").isEmpty)
    assert(OidcBffUtils.readCookie(request(), "KYUUBI_SESSION").isEmpty)
  }

  test("csrf check only constrains state-changing requests") {
    // Reads are always allowed, whatever the origin says.
    assert(OidcBffUtils.csrfAllowed(request(method = "GET", origin = Some("http://evil.com"))))
    assert(OidcBffUtils.csrfAllowed(request(method = "HEAD")))

    // Writes must come from this very origin.
    assert(OidcBffUtils.csrfAllowed(
      request(method = "POST", origin = Some("http://kyuubi.example.com:32379"))))
    assert(!OidcBffUtils.csrfAllowed(
      request(method = "POST", origin = Some("http://evil.com"))))
    assert(!OidcBffUtils.csrfAllowed(
      request(method = "DELETE", origin = Some("https://kyuubi.example.com:32379"))))

    // No Origin at all: fall back to Referer, and refuse when neither is present.
    assert(OidcBffUtils.csrfAllowed(
      request(method = "PUT", referer = Some("http://kyuubi.example.com:32379/ui/overview"))))
    assert(!OidcBffUtils.csrfAllowed(
      request(method = "PUT", referer = Some("http://evil.com/ui/overview"))))
    assert(!OidcBffUtils.csrfAllowed(request(method = "POST")))
  }

  test("csrf check follows the proxy headers") {
    val req = mock[HttpServletRequest]
    lenient.when(req.getMethod).thenReturn("POST")
    lenient.when(req.getScheme).thenReturn("http")
    lenient.when(req.getServerName).thenReturn("10.0.0.1")
    lenient.when(req.getServerPort).thenReturn(10099)
    lenient.when(req.getHeader("X-Forwarded-Proto")).thenReturn("https")
    lenient.when(req.getHeader("X-Forwarded-Host")).thenReturn("kyuubi.example.com")
    lenient.when(req.getHeader("Host")).thenReturn("10.0.0.1:10099")
    lenient.when(req.getHeader("Origin")).thenReturn("https://kyuubi.example.com")
    assert(OidcBffUtils.csrfAllowed(req))
  }

  test("default ports are equivalent to no port") {
    assert(OidcBffUtils.originOf("http://host:80/x").contains("http://host"))
    assert(OidcBffUtils.originOf("https://host:443/x").contains("https://host"))
    assert(OidcBffUtils.originOf("https://host/x").contains("https://host"))
    assert(OidcBffUtils.originOf("not a url").isEmpty)
  }

  test("pkce challenge matches the RFC 7636 example") {
    val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    assert(OidcBffUtils.codeChallengeS256(verifier) ===
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  }

  test("generated secrets are url safe and long enough") {
    val verifier = OidcBffUtils.generateCodeVerifier()
    // RFC 7636 requires 43-128 characters from the unreserved set.
    assert(verifier.length >= 43 && verifier.length <= 128)
    assert(verifier.forall(c => c.isLetterOrDigit || c == '-' || c == '_'))
    assert(OidcBffUtils.randomToken() !== OidcBffUtils.randomToken())
  }

  test("secure comparison behaves like equality") {
    assert(OidcBffUtils.secureEquals("abc", "abc"))
    assert(!OidcBffUtils.secureEquals("abc", "abd"))
    assert(!OidcBffUtils.secureEquals("abc", "ab"))
    assert(!OidcBffUtils.secureEquals(null, "abc"))
    assert(!OidcBffUtils.secureEquals("abc", null))
  }

  test("session ids are truncated before they reach a log") {
    assert(OidcBffUtils.shortId("0123456789abcdef") === "01234567")
    assert(OidcBffUtils.shortId("short") === "short")
    assert(OidcBffUtils.shortId(null) === "-")
  }

  test("openid scope is always requested") {
    assert(OidcBffUtils.withOpenIdScope("profile email") === "openid profile email")
    assert(OidcBffUtils.withOpenIdScope("openid profile") === "openid profile")
    assert(OidcBffUtils.withOpenIdScope("") === "openid")
  }
}
