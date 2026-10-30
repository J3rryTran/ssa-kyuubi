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

package org.apache.kyuubi.service.authentication

import java.nio.charset.StandardCharsets.UTF_8
import java.security.Security
import java.util.Collections
import javax.security.sasl.{AuthenticationException, SaslException}

import org.apache.kyuubi.{KYUUBI_VERSION, KyuubiFunSuite}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.service.{NoopTBinaryFrontendServer, TBinaryFrontendService}
import org.apache.kyuubi.service.authentication.PlainSASLServer.SaslPlainProvider
import org.apache.kyuubi.service.authentication.PlainSASLServer.SaslPlainServerFactory
import org.apache.kyuubi.shaded.thrift.transport.{TSaslServerTransport, TSocket}
import org.apache.kyuubi.util.SemanticVersion

class PlainSASLHelperSuite extends KyuubiFunSuite {

  test("PlainSASLHelper") {
    val server = new NoopTBinaryFrontendServer()
    val conf = KyuubiConf().set(KyuubiConf.FRONTEND_THRIFT_BINARY_BIND_PORT, 0)
    server.initialize(conf)
    val service = server.frontendServices.head.asInstanceOf[TBinaryFrontendService]
    val tProcessorFactory = PlainSASLHelper.getProcessFactory(service)
    val tSocket = new TSocket("0.0.0.0", 0)

    val tProcessor = tProcessorFactory.getProcessor(tSocket)
    assert(tProcessor.isInstanceOf[TSetIpAddressProcessor[_]])
    val e = intercept[IllegalArgumentException] {
      PlainSASLHelper.getTransportFactory("KERBEROS", conf)
    }
    assert(e.getMessage === "Illegal authentication type KERBEROS for plain transport")
    val e2 = intercept[IllegalArgumentException] {
      PlainSASLHelper.getTransportFactory("NOSASL", conf)
    }
    assert(e2.getMessage === "Illegal authentication type NOSASL for plain transport")

    val e3 = intercept[IllegalArgumentException] {
      PlainSASLHelper.getTransportFactory("ELSE", conf)
    }
    assert(e3.getMessage === "Illegal authentication type ELSE for plain transport")

    val tTransportFactory = PlainSASLHelper.getTransportFactory("NONE", conf)
    assert(tTransportFactory.isInstanceOf[TSaslServerTransport.Factory])
    Security.getProviders.exists(_.isInstanceOf[SaslPlainProvider])
  }

  test("Sasl Plain Provider") {
    val saslPlainProvider = new SaslPlainProvider()
    assert(saslPlainProvider.containsKey("SaslServerFactory.PLAIN"))
    assert(saslPlainProvider.getName === "KyuubiSaslPlain")
    assertResult(saslPlainProvider.getVersion)(SemanticVersion(KYUUBI_VERSION).toDouble)
  }

  test("PlainServerCallbackHandler authenticates and resolves effective user") {
    val conf = KyuubiConf()
    conf.set(
      KyuubiConf.AUTHENTICATION_CUSTOM_CLASS,
      classOf[TestResolvePasswdAuthProvider].getName)
    val handler = new PlainSASLHelper.PlainServerCallbackHandler("CUSTOM", conf, true)
    val serverFactory = new SaslPlainServerFactory()
    val server = serverFactory.createSaslServer(
      PlainSASLServer.PLAIN_METHOD,
      "CUSTOM",
      "KYUUBI",
      Collections.emptyMap[String, String](),
      handler)

    server.evaluateResponse("\u0000token\u0000good".getBytes(UTF_8))
    assert(server.isComplete)
    assert(server.getAuthorizationID === "alice")

    val badServer = serverFactory.createSaslServer(
      PlainSASLServer.PLAIN_METHOD,
      "CUSTOM",
      "KYUUBI",
      Collections.emptyMap[String, String](),
      handler)
    intercept[SaslException] {
      badServer.evaluateResponse("\u0000token\u0000bad".getBytes(UTF_8))
    }
  }
}

class TestResolvePasswdAuthProvider(conf: KyuubiConf) extends PasswdAuthenticationProvider {
  override def authenticate(user: String, password: String): Unit = {
    if (password != "good") {
      throw new AuthenticationException("bad password")
    }
  }

  override def authenticateAndResolve(user: String, password: String): String = {
    authenticate(user, password)
    "alice"
  }
}
