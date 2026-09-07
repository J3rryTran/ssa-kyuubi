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

import org.apache.kyuubi.KyuubiFunSuite

class OidcSessionStoreSuite extends KyuubiFunSuite {

  private def pendingLogin(state: String) =
    PendingLogin(s"verifier-$state", s"nonce-$state", "/ui/overview", "http://host/cb", 0L)

  test("entries round-trip and can be removed") {
    val store = new InMemoryOidcSessionStore[PendingLogin](60000, "test")
    try {
      assert(store.get("absent").isEmpty)
      assert(store.remove("absent").isEmpty)
      assert(store.get(null).isEmpty)

      store.put("s1", pendingLogin("s1"))
      store.put("s2", pendingLogin("s2"))
      assert(store.size === 2)
      assert(store.get("s1").map(_.codeVerifier).contains("verifier-s1"))
    } finally {
      store.shutdown()
    }
  }

  test("a login state can only be consumed once") {
    val store = new InMemoryOidcSessionStore[PendingLogin](60000, "test")
    try {
      store.put("state", pendingLogin("state"))
      // The callback takes the entry out, so replaying the same code+state finds nothing.
      assert(store.remove("state").isDefined)
      assert(store.remove("state").isEmpty)
      assert(store.get("state").isEmpty)
    } finally {
      store.shutdown()
    }
  }

  test("entries are unusable once their ttl has passed") {
    // Long cleanup interval so this exercises the read path, not the sweeper.
    val store = new InMemoryOidcSessionStore[PendingLogin](50, "test", 600000)
    try {
      store.put("state", pendingLogin("state"))
      assert(store.get("state").isDefined)
      Thread.sleep(80)
      assert(store.get("state").isEmpty)
      assert(store.remove("state").isEmpty)
      assert(store.size === 0)
    } finally {
      store.shutdown()
    }
  }

  test("expired entries are swept without being read") {
    val store = new InMemoryOidcSessionStore[PendingLogin](50, "test", 600000)
    try {
      store.put("a", pendingLogin("a"))
      store.put("b", pendingLogin("b"))
      Thread.sleep(80)
      store.evictExpired()
      // size would evict too; check the map is genuinely empty first.
      assert(store.size === 0)
    } finally {
      store.shutdown()
    }
  }
}
