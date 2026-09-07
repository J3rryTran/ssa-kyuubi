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

package org.apache.kyuubi.server.notebook.routing

import org.apache.kyuubi.KyuubiFunSuite

class NotebookRouterSuite extends KyuubiFunSuite {

  import RoutingDecision._

  private val self = "pod-a:10099"
  private val other = "pod-b:10099"

  private def router(
      owners: Map[String, String] = Map.empty,
      local: Set[String] = Set.empty): NotebookRouter =
    new NotebookRouter(() => self, owners.get, local.contains)

  test("a path naming no session is served wherever it lands") {
    assert(router().decide(None, alreadyProxied = false) === Local)
  }

  test("a session this process holds is served here whatever the registry says") {
    // The registry can lag a moment behind an open; the process holding the handle cannot.
    val stale = router(owners = Map("s1" -> other), local = Set("s1"))
    assert(stale.decide(Some("s1"), alreadyProxied = false) === Local)
  }

  test("a session owned elsewhere is forwarded to its owner") {
    assert(router(owners = Map("s1" -> other)).decide(Some("s1"), alreadyProxied = false)
      === Forward(other))
  }

  test("a session the registry says is ours is served here") {
    assert(router(owners = Map("s1" -> self)).decide(Some("s1"), alreadyProxied = false) === Local)
  }

  test("a session no live instance owns is reported lost rather than retried") {
    // The owning pod died; its ephemeral node went with it. Nothing will bring the session back.
    assert(router().decide(Some("gone"), alreadyProxied = false) === Lost)
  }

  test("a request that was already forwarded is never forwarded again") {
    assert(router(owners = Map("s1" -> other)).decide(Some("s1"), alreadyProxied = true) === Looped)
  }

  test("an already-forwarded request still runs locally when this really is the owner") {
    // Arriving proxied is not by itself an error - only arriving proxied at a non-owner is.
    assert(router(owners = Map("s1" -> self)).decide(Some("s1"), alreadyProxied = true) === Local)
    assert(router(local = Set("s1")).decide(Some("s1"), alreadyProxied = true) === Local)
  }

  test("only the runtime paths carry ownership") {
    assert(NotebookRoutePaths.sessionKeyOf("/v1/notebook-sessions/s1")
      === Some(RouteKey.Session("s1")))
    assert(NotebookRoutePaths.sessionKeyOf("/v1/notebook-sessions/s1:restart")
      === Some(RouteKey.Session("s1")))
    assert(NotebookRoutePaths.sessionKeyOf("/v1/notebook-sessions/s1/executions")
      === Some(RouteKey.Session("s1")))
    assert(NotebookRoutePaths.sessionKeyOf("/v1/executions/e1/logs")
      === Some(RouteKey.Execution("e1")))
    assert(NotebookRoutePaths.sessionKeyOf("/v1/notebooks/n1/executions")
      === Some(RouteKey.Notebook("n1")))

    // Content is shared, so routing it would buy a network hop and nothing else.
    assert(NotebookRoutePaths.sessionKeyOf("/v1/notebooks/n1").isEmpty)
    assert(NotebookRoutePaths.sessionKeyOf("/v1/notebooks/n1/cells").isEmpty)
    assert(NotebookRoutePaths.sessionKeyOf("/v1/notebook-folders").isEmpty)
    assert(NotebookRoutePaths.sessionKeyOf("/v1/notebooks:search").isEmpty)
  }
}
