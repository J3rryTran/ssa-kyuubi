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

import java.security.Principal
import java.util.concurrent.atomic.AtomicInteger
import javax.security.sasl.AuthenticationException

import org.apache.kyuubi.{KyuubiException, KyuubiFunSuite, Utils}
import org.apache.kyuubi.config.KyuubiConf

class CountingTokenProvider(conf: KyuubiConf) extends TokenAuthenticationProvider {
  CountingTokenProvider.constructed.incrementAndGet()
  override def authenticate(credential: TokenCredential): Principal = null
}

object CountingTokenProvider {
  val constructed = new AtomicInteger(0)
}

class ThrowingTokenProvider(conf: KyuubiConf) extends TokenAuthenticationProvider {
  throw new RuntimeException("boom")
  override def authenticate(credential: TokenCredential): Principal = null
}

class AuthenticationProviderFactorySuite extends KyuubiFunSuite {

  import AuthenticationProviderFactory._

  test("get auth provider") {
    val conf = KyuubiConf()
    val p1 = getAuthenticationProvider(AuthMethods.withName("NONE"), conf)
    p1.authenticate(Utils.currentUser, "")
    val p2 = getAuthenticationProvider(AuthMethods.withName("LDAP"), conf)
    val e1 = intercept[AuthenticationException](p2.authenticate("test", "test"))
    assert(e1.getMessage.contains("Error validating LDAP user:"))
    val e2 = intercept[AuthenticationException](
      AuthenticationProviderFactory.getAuthenticationProvider(null, conf))
    assert(e2.getMessage === "Not a valid authentication method")
  }

  test("bearer provider is created once per class and reused") {
    AuthenticationProviderFactory.resetBearerProviderCache()
    val conf = KyuubiConf()
    val cls = classOf[CountingTokenProvider].getName
    CountingTokenProvider.constructed.set(0)
    val a = AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(cls, conf)
    val b = AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(cls, conf)
    assert(a eq b)
    assert(CountingTokenProvider.constructed.get() === 1)
  }

  test("a failing constructor is not cached") {
    AuthenticationProviderFactory.resetBearerProviderCache()
    val cls = classOf[ThrowingTokenProvider].getName
    intercept[KyuubiException] {
      AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(cls, KyuubiConf())
    }
    // the second call must retry construction, not return null or a broken instance
    intercept[KyuubiException] {
      AuthenticationProviderFactory.getHttpBearerAuthenticationProvider(cls, KyuubiConf())
    }
  }
}
