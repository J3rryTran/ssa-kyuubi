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

import java.net.URL
import java.security.Principal
import java.text.ParseException
import java.util.{Collections, Date}
import javax.security.sasl.AuthenticationException

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm}
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{BadJWTException, ConfigurableJWTProcessor, DefaultJWTClaimsVerifier, DefaultJWTProcessor}

import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.service.authentication.{TokenAuthenticationProvider, TokenCredential}

/**
 * A server-side [[TokenAuthenticationProvider]] that validates OAuth2 / OIDC access-token JWTs
 * (e.g. issued by Keycloak) against the provider's published JWKS.
 *
 * It is provider-independent and wired via `kyuubi.authentication.custom.bearer.class`. Validation
 * covers signature (allow-listed algorithms only, `alg=none` rejected), `iss`, `aud`, `exp`,
 * `nbf`, `iat`, and (when configured) the JOSE `typ` header to reject OIDC ID tokens.
 * On success, the configured username claim (default `preferred_username`) is the session user.
 *
 * Configuration keys (read raw from [[KyuubiConf]], so Kyuubi core is untouched):
 *  - `kyuubi.authentication.jwt.issuer` (required) the OIDC issuer
 *  - `kyuubi.authentication.jwt.audience` (required) accepted audience(s), comma-separated
 *  - `kyuubi.authentication.jwt.jwks.url` (optional) JWKS URL; else discovered from the issuer
 *  - `kyuubi.authentication.jwt.username.claim` (default `preferred_username`)
 *  - `kyuubi.authentication.jwt.allowed.algorithms` (default `RS256`, comma-separated)
 *  - `kyuubi.authentication.jwt.expected.typ` (default `at+jwt`; set to empty to disable)
 *  - `kyuubi.authentication.jwt.allowed.azp` (optional) allowed authorized party (`azp`), comma-separated
 *  - `kyuubi.authentication.jwt.clock.skew.seconds` (default `30`)
 *  - `kyuubi.authentication.jwt.connect.timeout.ms` (default `5000`)
 *  - `kyuubi.authentication.jwt.read.timeout.ms` (default `5000`)
 *
 * The audience is always taken from config (typically the client id), never the realm name.
 */
class JwtTokenAuthenticationProvider(conf: KyuubiConf)
  extends TokenAuthenticationProvider with Logging {

  import JwtTokenAuthenticationProvider._

  private val rawIssuer: String = required(conf, ISSUER)
  val issuers: Set[String] = {
    val parsed = JwtIssuers.parse(rawIssuer)
    if (parsed.isEmpty) {
      throw new IllegalArgumentException(s"$ISSUER must contain at least one valid issuer")
    }
    parsed
  }
  private val primaryIssuer: String =
    rawIssuer.split(",").map(JwtIssuers.normalize).find(_.nonEmpty).get

  private val audiences: Set[String] = {
    val aud = required(conf, AUDIENCE)
    aud.split(",").map(_.trim).filter(_.nonEmpty).toSet
  }
  private val usernameClaim: String =
    conf.getOption(USERNAME_CLAIM).map(_.trim).filter(_.nonEmpty).getOrElse("preferred_username")
  private val allowedAlgorithms: Set[JWSAlgorithm] = {
    val raw = conf.getOption(ALLOWED_ALGORITHMS).map(_.trim).filter(_.nonEmpty).getOrElse("RS256")
    raw.split(",").map(_.trim).filter(_.nonEmpty).map(JWSAlgorithm.parse).toSet
  }
  /**
   * JOSE `typ` bắt buộc. Mặc định `at+jwt` (RFC 9068) để chỉ nhận access token và loại
   * id_token. Đặt rõ giá trị rỗng (`kyuubi.authentication.jwt.expected.typ=`) để tắt kiểm tra,
   * ví dụ với IdP không ghi typ.
   */
  private val expectedTyp: Option[String] = conf.getOption(EXPECTED_TYP) match {
    case None => Some(DEFAULT_EXPECTED_TYP)
    case Some(raw) => Option(raw.trim).filter(_.nonEmpty)
  }
  private val allowedAzp: Set[String] = conf.getOption(ALLOWED_AZP)
    .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty)
  private val clockSkewSeconds: Int =
    conf.getOption(CLOCK_SKEW_SECONDS).map(_.trim.toInt).getOrElse(30)
  private val connectTimeoutMs: Int =
    conf.getOption(CONNECT_TIMEOUT_MS).map(_.trim.toInt).getOrElse(5000)
  private val readTimeoutMs: Int =
    conf.getOption(READ_TIMEOUT_MS).map(_.trim.toInt).getOrElse(5000)

  private val resourceRetriever = new DefaultResourceRetriever(connectTimeoutMs, readTimeoutMs)

  private val jwksUrl: String =
    conf.getOption(JWKS_URL).map(_.trim).filter(_.nonEmpty).getOrElse(discoverJwksUri())

  private val jwtProcessor: ConfigurableJWTProcessor[SecurityContext] = buildProcessor()

  info(s"Initialized JWT authentication: issuers=${issuers.mkString(",")}, jwksUrl=$jwksUrl, " +
    s"audience=${audiences.mkString(",")}, usernameClaim=$usernameClaim, " +
    s"allowedAlgorithms=${allowedAlgorithms.map(_.getName).mkString(",")}, " +
    s"expectedTyp=${expectedTyp.getOrElse("<disabled>")}, " +
    s"allowedAzp=${if (allowedAzp.isEmpty) "<any>" else allowedAzp.mkString(",")}")
  if (expectedTyp.isEmpty) {
    warn("JOSE typ check is disabled; id_tokens will be accepted as access tokens")
  }

  override def authenticate(credential: TokenCredential): Principal = {
    val token = credential.token
    if (token == null || token.isEmpty) {
      throw new AuthenticationException("Bearer token is empty")
    }
    val claims =
      try {
        jwtProcessor.process(token, null.asInstanceOf[SecurityContext])
      } catch {
        case e: ParseException =>
          throw new AuthenticationException("Malformed JWT: " + e.getMessage)
        case e: Exception =>
          // BadJOSEException (bad signature / claims), JOSEException (crypto), etc.
          throw new AuthenticationException("JWT validation failed: " + e.getMessage)
      }

    if (allowedAzp.nonEmpty) {
      val azp = claims.getStringClaim("azp")
      if (azp == null || !allowedAzp.contains(azp)) {
        throw new AuthenticationException(
          s"JWT 'azp' '${Option(azp).getOrElse("<missing>")}' is not an allowed authorized party")
      }
    }

    // Manual issued-at sanity: reject tokens issued in the future (beyond the allowed skew).
    val iat: Date = claims.getIssueTime
    if (iat != null && iat.getTime > System.currentTimeMillis() + clockSkewSeconds * 1000L) {
      throw new AuthenticationException("JWT 'iat' is in the future")
    }

    val username = claims.getStringClaim(usernameClaim)
    if (username == null || username.isEmpty) {
      throw new AuthenticationException(s"JWT is missing the username claim '$usernameClaim'")
    }
    debug(s"Authenticated bearer token for user '$username' (subject=${claims.getSubject})")
    new org.apache.kyuubi.service.authentication.BasicPrincipal(username)
  }

  private def discoverJwksUri(): String = {
    val base = primaryIssuer
    val discoveryUrl = base + "/.well-known/openid-configuration"
    try {
      val content = resourceRetriever.retrieveResource(new URL(discoveryUrl)).getContent
      val node = MAPPER.readTree(content)
      val jwks = node.get("jwks_uri")
      if (jwks == null || jwks.isNull) {
        throw new IllegalStateException(s"discovery document at $discoveryUrl has no jwks_uri")
      }
      jwks.asText
    } catch {
      case e: Exception =>
        throw new IllegalStateException(
          s"Failed OIDC discovery at $discoveryUrl: ${e.getMessage}",
          e)
    }
  }

  private def buildProcessor(): ConfigurableJWTProcessor[SecurityContext] = {
    val jwkSource: JWKSource[SecurityContext] =
      JWKSourceBuilder.create[SecurityContext](new URL(jwksUrl), resourceRetriever).build()

    val processor = new DefaultJWTProcessor[SecurityContext]()
    processor.setJWSKeySelector(
      new JWSVerificationKeySelector[SecurityContext](allowedAlgorithms.asJava, jwkSource))

    expectedTyp.foreach { typ =>
      processor.setJWSTypeVerifier(
        new com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier[SecurityContext](
          new JOSEObjectType(typ)))
    }

    val exactMatch = new JWTClaimsSet.Builder().build()
    val required = Set("exp", "iat", "iss").asJava
    val verifier = new MultiIssuerJWTClaimsVerifier[SecurityContext](
      issuers,
      audiences.asJava,
      exactMatch,
      required,
      Collections.emptySet[String]())
    verifier.setMaxClockSkew(clockSkewSeconds)
    processor.setJWTClaimsSetVerifier(verifier)
    processor
  }
}

class MultiIssuerJWTClaimsVerifier[C <: SecurityContext](
    val acceptedIssuers: Set[String],
    exactMatchAudience: java.util.Set[String],
    exactMatchClaims: JWTClaimsSet,
    requiredClaims: java.util.Set[String],
    prohibitedClaims: java.util.Set[String])
  extends DefaultJWTClaimsVerifier[C](
    exactMatchAudience,
    exactMatchClaims,
    requiredClaims,
    prohibitedClaims)
  with Logging {

  @throws[BadJWTException]
  override def verify(claimsSet: JWTClaimsSet, context: C): Unit = {
    super.verify(claimsSet, context)
    val tokenIssuer = claimsSet.getIssuer
    if (tokenIssuer == null || tokenIssuer.trim.isEmpty) {
      throw new BadJWTException("Missing 'iss' claim in JWT")
    }
    val normalizedTokenIssuer = JwtIssuers.normalize(tokenIssuer)
    if (!acceptedIssuers.contains(normalizedTokenIssuer)) {
      warn(s"JWT issuer '$tokenIssuer' not in accepted issuers [${acceptedIssuers.mkString(", ")}]")
      throw new BadJWTException(s"JWT issuer '$tokenIssuer' not in accepted issuers")
    }
  }
}

object JwtIssuers {

  def normalize(iss: String): String = {
    if (iss == null) ""
    else {
      val trimmed = iss.trim
      if (trimmed.endsWith("/")) trimmed.dropRight(1) else trimmed
    }
  }

  def parse(raw: String): Set[String] = {
    if (raw == null) Set.empty
    else {
      raw.split(",")
        .map(normalize)
        .filter(_.nonEmpty)
        .toSet
    }
  }
}

object JwtTokenAuthenticationProvider {
  private val PREFIX = "kyuubi.authentication.jwt."
  val ISSUER: String = PREFIX + "issuer"
  val AUDIENCE: String = PREFIX + "audience"
  val JWKS_URL: String = PREFIX + "jwks.url"
  val USERNAME_CLAIM: String = PREFIX + "username.claim"
  val ALLOWED_ALGORITHMS: String = PREFIX + "allowed.algorithms"
  val EXPECTED_TYP: String = PREFIX + "expected.typ"
  val DEFAULT_EXPECTED_TYP: String = "at+jwt"
  val ALLOWED_AZP: String = PREFIX + "allowed.azp"
  val CLOCK_SKEW_SECONDS: String = PREFIX + "clock.skew.seconds"
  val CONNECT_TIMEOUT_MS: String = PREFIX + "connect.timeout.ms"
  val READ_TIMEOUT_MS: String = PREFIX + "read.timeout.ms"

  private val MAPPER = new ObjectMapper()

  private def required(conf: KyuubiConf, key: String): String = {
    conf.getOption(key).map(_.trim).filter(_.nonEmpty).getOrElse {
      throw new IllegalArgumentException(s"$key must be set for JWT bearer authentication")
    }
  }
}
