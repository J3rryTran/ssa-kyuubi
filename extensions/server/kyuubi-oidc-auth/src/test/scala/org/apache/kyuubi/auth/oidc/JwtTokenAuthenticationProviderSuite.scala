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
import javax.security.sasl.AuthenticationException

import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.service.authentication.DefaultTokenCredential

class JwtTokenAuthenticationProviderSuite extends KyuubiFunSuite {

  private val keyId = "test-key"
  private var rsaKey: RSAKey = _
  private var otherKey: RSAKey = _
  private var server: HttpServer = _
  private var issuer: String = _
  private val audience = "kyuubi-test"

  override def beforeAll(): Unit = {
    super.beforeAll()
    rsaKey = new RSAKeyGenerator(2048).keyID(keyId).generate()
    otherKey = new RSAKeyGenerator(2048).keyID(keyId).generate()

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val port = server.getAddress.getPort
    issuer = s"http://127.0.0.1:$port"
    val jwksJson = new JWKSet(rsaKey.toPublicJWK).toString

    server.createContext("/jwks", jsonHandler(jwksJson))
    server.createContext(
      "/.well-known/openid-configuration",
      jsonHandler(s"""{"issuer":"$issuer","jwks_uri":"$issuer/jwks"}"""))
    server.setExecutor(null)
    server.start()
  }

  override def afterAll(): Unit = {
    if (server != null) server.stop(0)
    super.afterAll()
  }

  private def jsonHandler(body: String): HttpHandler = new HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
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
      typ: String = null,
      azp: String = null): String = {
    val now = System.currentTimeMillis()
    val builder = new JWTClaimsSet.Builder()
      .subject("alice-subject")
      .issuer(if (iss == null) issuer else iss)
      .audience(aud)
      .expirationTime(new Date(now + expMillisFromNow))
      .issueTime(new Date(now + iatMillisFromNow))
    username.foreach(u => builder.claim("preferred_username", u))
    if (azp != null) builder.claim("azp", azp)
    val headerBuilder = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId)
    if (typ != null) headerBuilder.`type`(new com.nimbusds.jose.JOSEObjectType(typ))
    val jwt = new SignedJWT(headerBuilder.build(), builder.build())
    jwt.sign(new RSASSASigner(signer))
    jwt.serialize()
  }

  private def credential(token: String) = DefaultTokenCredential(token)

  test("valid access token authenticates and maps the username claim") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    val principal = provider.authenticate(credential(signedToken(typ = "at+jwt")))
    assert(principal.getName === "alice")
  }

  test("expired token is rejected") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(expMillisFromNow = -60000L, typ = "at+jwt")))
    }
  }

  test("wrong issuer is rejected") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(iss = "https://evil.example.com", typ = "at+jwt")))
    }
  }

  test("wrong audience is rejected") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(aud = "some-other-audience", typ = "at+jwt")))
    }
  }

  test("missing username claim is rejected") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(username = None, typ = "at+jwt")))
    }
  }

  test("bad signature is rejected") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(signer = otherKey, typ = "at+jwt")))
    }
  }

  test("unsigned (alg=none) token is rejected") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
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
      provider.authenticate(credential(plain))
    }
  }

  test("token issued in the future is rejected") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(iatMillisFromNow = 120000L, typ = "at+jwt")))
    }
  }

  test("configured expected typ rejects a mismatching (ID) token and accepts a matching one") {
    val provider = new JwtTokenAuthenticationProvider(
      baseConf(Map(JwtTokenAuthenticationProvider.EXPECTED_TYP -> "at+jwt")))
    // ID-token-like: typ=JWT -> rejected
    intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(typ = "JWT")))
    }
    // access token with typ=at+jwt -> accepted
    val principal = provider.authenticate(credential(signedToken(typ = "at+jwt")))
    assert(principal.getName === "alice")
  }

  test("default typ rejects an id_token (typ=JWT) and a token without typ") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    // ID token (typ=JWT)
    intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(typ = "JWT")))
    }
    // Token without typ
    intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(typ = null)))
    }
  }

  test("empty expected.typ disables the check") {
    val provider = new JwtTokenAuthenticationProvider(
      baseConf(Map(JwtTokenAuthenticationProvider.EXPECTED_TYP -> "")))
    val principal = provider.authenticate(credential(signedToken(typ = null)))
    assert(principal.getName === "alice")
  }

  test("custom username claim is honored") {
    val provider = new JwtTokenAuthenticationProvider(
      baseConf(Map(JwtTokenAuthenticationProvider.USERNAME_CLAIM -> "sub")))
    val principal = provider.authenticate(credential(signedToken(typ = "at+jwt")))
    assert(principal.getName === "alice-subject")
  }

  test("multiple issuers: accepts tokens from IP, DNS, and in-cluster issuers") {
    val ipIssuer = issuer
    val dnsIssuer = "https://keycloak.prod.example.com/realms/lakehouse"
    val inClusterIssuer =
      "http://keycloak-service.keycloak-prod.svc.cluster.local:8080/realms/lakehouse"
    val multiIssuerConf = baseConf(Map(
      JwtTokenAuthenticationProvider.ISSUER -> s"$ipIssuer, $dnsIssuer/, , $inClusterIssuer"
    ))
    val provider = new JwtTokenAuthenticationProvider(multiIssuerConf)
    assert(provider.issuers === Set(ipIssuer, dnsIssuer, inClusterIssuer))

    def subjectFor(iss: String): String =
      provider.authenticate(credential(signedToken(iss = iss, typ = "at+jwt"))).getName

    // Token with IP issuer passes
    assert(subjectFor(ipIssuer) === "alice")
    // Token with DNS issuer passes (both with and without trailing slash on token)
    assert(subjectFor(dnsIssuer) === "alice")
    assert(subjectFor(dnsIssuer + "/") === "alice")
    // Token with in-cluster issuer passes
    assert(subjectFor(inClusterIssuer) === "alice")

    // Unknown issuer fails
    intercept[AuthenticationException] {
      subjectFor("https://unknown.issuer.com/realms/lakehouse")
    }
  }

  test("JwtIssuers.parse handles whitespace, empty items, and trailing slashes") {
    assert(JwtIssuers.parse(" a/ , ,b") === Set("a", "b"))
    assert(JwtIssuers.parse("https://host:8080/path/ , https://other/ ") ===
      Set("https://host:8080/path", "https://other"))
    assert(JwtIssuers.parse("") === Set.empty)
    assert(JwtIssuers.parse(" , , ") === Set.empty)
    assert(JwtIssuers.parse(null) === Set.empty)
  }

  test("empty or blank issuer configuration fails fast on initialization") {
    intercept[IllegalArgumentException] {
      new JwtTokenAuthenticationProvider(baseConf(Map(JwtTokenAuthenticationProvider.ISSUER -> "")))
    }
    intercept[IllegalArgumentException] {
      new JwtTokenAuthenticationProvider(
        baseConf(Map(JwtTokenAuthenticationProvider.ISSUER -> " , , ")))
    }
  }

  test("token missing iss claim is rejected") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    val now = System.currentTimeMillis()
    val builder = new JWTClaimsSet.Builder()
      .subject("alice-subject")
      .audience(audience)
      .expirationTime(new Date(now + 600000L))
      .issueTime(new Date(now))
      .claim("preferred_username", "alice")
    val header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build()
    val jwt = new SignedJWT(header, builder.build())
    jwt.sign(new RSASSASigner(rsaKey))
    intercept[AuthenticationException] {
      provider.authenticate(credential(jwt.serialize()))
    }
  }

  test("valid issuer with bad aud or signature still fails") {
    val dnsIssuer = "https://keycloak.dns/realms/lakehouse"
    val provider = new JwtTokenAuthenticationProvider(
      baseConf(Map(JwtTokenAuthenticationProvider.ISSUER -> s"$issuer, $dnsIssuer")))

    // Right issuer but wrong aud
    intercept[AuthenticationException] {
      provider.authenticate(
        credential(signedToken(iss = dnsIssuer, aud = "wrong-aud", typ = "at+jwt")))
    }
    // Right issuer but wrong signature
    intercept[AuthenticationException] {
      provider.authenticate(
        credential(signedToken(iss = dnsIssuer, signer = otherKey, typ = "at+jwt")))
    }
  }

  test("allowed.azp accepts a listed azp and rejects others / missing") {
    val provider = new JwtTokenAuthenticationProvider(
      baseConf(Map(JwtTokenAuthenticationProvider.ALLOWED_AZP -> "ssa-client, other-client")))

    // Listed azp accepted
    val p1 = provider.authenticate(credential(signedToken(typ = "at+jwt", azp = "ssa-client")))
    assert(p1.getName === "alice")
    val p2 = provider.authenticate(credential(signedToken(typ = "at+jwt", azp = "other-client")))
    assert(p2.getName === "alice")

    // Missing azp rejected
    val e1 = intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(typ = "at+jwt", azp = null)))
    }
    assert(e1.getMessage.contains("JWT 'azp' '<missing>' is not an allowed authorized party"))

    // Other azp rejected
    val e2 = intercept[AuthenticationException] {
      provider.authenticate(credential(signedToken(typ = "at+jwt", azp = "unauthorized-client")))
    }
    assert(e2.getMessage.contains(
      "JWT 'azp' 'unauthorized-client' is not an allowed authorized party"))
  }

  test("allowed.azp unset accepts any azp") {
    val provider = new JwtTokenAuthenticationProvider(baseConf())
    val p1 = provider.authenticate(credential(signedToken(typ = "at+jwt", azp = "any-client")))
    assert(p1.getName === "alice")
    val p2 = provider.authenticate(credential(signedToken(typ = "at+jwt", azp = null)))
    assert(p2.getName === "alice")
  }
}
