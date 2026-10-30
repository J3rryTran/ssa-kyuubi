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

package org.apache.kyuubi.auth.oidc

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.security.sasl.AuthenticationException

import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf

class JwtPasswordAuthenticationProviderSuite extends KyuubiFunSuite {

  private val keyId = "test-key"
  private var rsaKey: RSAKey = _
  private var otherKey: RSAKey = _
  private var server: HttpServer = _
  private var issuer: String = _
  private val audience = "kyuubi-test"

  private val discoveryRequestCount = new AtomicInteger(0)
  private val jwksRequestCount = new AtomicInteger(0)

  override def beforeAll(): Unit = {
    super.beforeAll()
    rsaKey = new RSAKeyGenerator(2048).keyID(keyId).generate()
    otherKey = new RSAKeyGenerator(2048).keyID(keyId).generate()

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val port = server.getAddress.getPort
    issuer = s"http://127.0.0.1:$port"
    val jwksJson = new JWKSet(rsaKey.toPublicJWK).toString

    server.createContext(
      "/jwks",
      countingJsonHandler(jwksJson, jwksRequestCount))
    server.createContext(
      "/.well-known/openid-configuration",
      countingJsonHandler(
        s"""{"issuer":"$issuer","jwks_uri":"$issuer/jwks"}""",
        discoveryRequestCount))
    server.setExecutor(null)
    server.start()

    JwtPasswordAuthenticationProvider.resetVerifier()
  }

  override def afterAll(): Unit = {
    if (server != null) server.stop(0)
    JwtPasswordAuthenticationProvider.resetVerifier()
    super.afterAll()
  }

  private def countingJsonHandler(
      body: String,
      counter: AtomicInteger): HttpHandler = new HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
      counter.incrementAndGet()
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.length)
      val os = exchange.getResponseBody
      os.write(bytes)
      os.close()
    }
  }

  private def baseConf(extra: Map[String, String] = Map.empty): KyuubiConf = {
    val conf = new KyuubiConf(false)
    conf.set(JwtTokenAuthenticationProvider.ISSUER, issuer)
    conf.set(JwtTokenAuthenticationProvider.AUDIENCE, audience)
    conf.set(JwtTokenAuthenticationProvider.CONNECT_TIMEOUT_MS, "5000")
    conf.set(JwtTokenAuthenticationProvider.READ_TIMEOUT_MS, "5000")
    extra.foreach { case (k, v) => conf.set(k, v) }
    conf
  }

  private def signedToken(
      signer: RSAKey = rsaKey,
      iss: String = null,
      aud: String = audience,
      username: Option[String] = Some("alice"),
      expMillisFromNow: Long = 600000L,
      iatMillisFromNow: Long = 0L,
      typ: String = null): String = {
    val now = System.currentTimeMillis()
    val builder = new JWTClaimsSet.Builder()
      .subject("alice-subject")
      .issuer(if (iss == null) issuer else iss)
      .audience(aud)
      .expirationTime(new Date(now + expMillisFromNow))
      .issueTime(new Date(now + iatMillisFromNow))
    username.foreach(u => builder.claim("preferred_username", u))
    val headerBuilder = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId)
    if (typ != null) headerBuilder.`type`(new com.nimbusds.jose.JOSEObjectType(typ))
    val jwt = new SignedJWT(headerBuilder.build(), builder.build())
    jwt.sign(new RSASSASigner(signer))
    jwt.serialize()
  }

  test("1. valid access token authenticates and matches user") {
    val provider = new JwtPasswordAuthenticationProvider(baseConf())
    provider.authenticate("alice", signedToken(typ = "at+jwt"))
    assert(provider.authenticateAndResolve("alice", signedToken(typ = "at+jwt")) === "alice")
  }

  test("1b. user placeholder aliases (token, empty, null) resolve to token user") {
    val provider = new JwtPasswordAuthenticationProvider(baseConf())
    val token = signedToken(typ = "at+jwt")
    // User alias 'token'
    assert(provider.authenticateAndResolve("token", token) === "alice")
    // Empty user
    assert(provider.authenticateAndResolve("", token) === "alice")
    assert(provider.authenticateAndResolve("  ", token) === "alice")
    // Null user
    assert(provider.authenticateAndResolve(null, token) === "alice")
  }

  test("1c. custom user aliases are honored") {
    val conf = baseConf(Map(
      JwtPasswordAuthenticationProvider.PASSWORD_USER_ALIASES -> "token, custom-placeholder"
    ))
    val provider = new JwtPasswordAuthenticationProvider(conf)
    val token = signedToken(typ = "at+jwt")
    assert(provider.authenticateAndResolve("custom-placeholder", token) === "alice")
  }

  test("1d. Bearer prefix in password field is stripped and accepted") {
    val provider = new JwtPasswordAuthenticationProvider(baseConf())
    val tokenWithPrefix = s"Bearer ${signedToken(typ = "at+jwt")}"
    assert(provider.authenticateAndResolve("token", tokenWithPrefix) === "alice")
    val lowerPrefix = s"bearer ${signedToken(typ = "at+jwt")}"
    assert(provider.authenticateAndResolve("alice", lowerPrefix) === "alice")
  }

  test("1e. password.enabled=false rejects any password with Deny message") {
    val conf = baseConf(Map(
      JwtPasswordAuthenticationProvider.PASSWORD_ENABLED -> "false"
    ))
    val provider = new JwtPasswordAuthenticationProvider(conf)
    val e = intercept[AuthenticationException] {
      provider.authenticate("alice", signedToken(typ = "at+jwt"))
    }
    assert(e.getMessage.contains("Password authentication is disabled; use OIDC/JWT bearer"))
  }

  test("2. valid access token with mismatching user is rejected") {
    val provider = new JwtPasswordAuthenticationProvider(baseConf())
    val e = intercept[AuthenticationException] {
      provider.authenticate("bob", signedToken(typ = "at+jwt"))
    }
    assert(e.getMessage.contains("does not match"))
  }

  test("3. plain password or non-jwt is rejected with informative message") {
    val provider = new JwtPasswordAuthenticationProvider(baseConf())
    Seq(
      "plainpassword",
      "only.two.parts.extra.parts",
      "onepart",
      "",
      "   ",
      null).foreach { pw =>
      val e = intercept[AuthenticationException] {
        provider.authenticate("alice", pw)
      }
      assert(e.getMessage.contains("pass an OIDC access token"))
    }
  }

  test("4. invalid tokens (expired, wrong iss, wrong aud, bad signature, alg=none) are rejected") {
    val provider = new JwtPasswordAuthenticationProvider(baseConf())

    // Expired
    intercept[AuthenticationException] {
      provider.authenticate("alice", signedToken(expMillisFromNow = -60000L, typ = "at+jwt"))
    }

    // Wrong issuer
    intercept[AuthenticationException] {
      provider.authenticate("alice", signedToken(iss = "https://evil.example.com", typ = "at+jwt"))
    }

    // Wrong audience
    intercept[AuthenticationException] {
      provider.authenticate("alice", signedToken(aud = "some-other-audience", typ = "at+jwt"))
    }

    // Bad signature
    intercept[AuthenticationException] {
      provider.authenticate("alice", signedToken(signer = otherKey, typ = "at+jwt"))
    }

    // alg=none
    val now = System.currentTimeMillis()
    val claims = new JWTClaimsSet.Builder()
      .subject("alice-subject")
      .issuer(issuer)
      .audience(audience)
      .expirationTime(new Date(now + 600000L))
      .issueTime(new Date(now))
      .claim("preferred_username", "alice")
      .build()
    val plain = new PlainJWT(claims).serialize()
    intercept[AuthenticationException] {
      provider.authenticate("alice", plain)
    }
  }

  test("default typ rejects an id_token (typ=JWT) and a token without typ") {
    val provider = new JwtPasswordAuthenticationProvider(baseConf())
    // ID token (typ=JWT)
    intercept[AuthenticationException] {
      provider.authenticate("alice", signedToken(typ = "JWT"))
    }
    // Token without typ
    intercept[AuthenticationException] {
      provider.authenticate("alice", signedToken(typ = null))
    }
  }

  test("empty expected.typ disables the check") {
    // The verifier is a JVM-wide singleton built from the first conf it sees; rebuild it so
    // this conf takes effect, and reset afterwards so later tests get the default typ check.
    JwtPasswordAuthenticationProvider.resetVerifier()
    try {
      val provider = new JwtPasswordAuthenticationProvider(
        baseConf(Map(JwtTokenAuthenticationProvider.EXPECTED_TYP -> "")))
      assert(provider.authenticateAndResolve("alice", signedToken(typ = null)) === "alice")
    } finally {
      JwtPasswordAuthenticationProvider.resetVerifier()
    }
  }

  test("5. authenticating 1000 times only fetches discovery and JWKS once") {
    val token = signedToken(typ = "at+jwt")
    val beforeDiscovery = discoveryRequestCount.get()
    val beforeJwks = jwksRequestCount.get()

    for (_ <- 1 to 1000) {
      val provider = new JwtPasswordAuthenticationProvider(baseConf())
      provider.authenticate("alice", token)
    }

    val afterDiscovery = discoveryRequestCount.get()
    val afterJwks = jwksRequestCount.get()

    // Since the verifier singleton is reused, no additional discovery or JWKS fetch occurs
    assert(afterDiscovery - beforeDiscovery <= 1)
    assert(afterJwks - beforeJwks <= 1)
  }

  test("null conf throws IllegalStateException") {
    val provider = new JwtPasswordAuthenticationProvider()
    intercept[IllegalStateException] {
      provider.authenticate("alice", signedToken(typ = "at+jwt"))
    }
  }
}
