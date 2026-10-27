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

package org.apache.kyuubi.server.engineprofile

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.shaded.curator.framework.CuratorFramework
import org.apache.kyuubi.shaded.curator.framework.recipes.locks.InterProcessMutex

/** Serializes changes to one owner document across Kyuubi replicas. */
private[engineprofile] class EngineProfileOwnerLock(
    client: CuratorFramework,
    namespace: String,
    timeoutMillis: Long) {

  require(timeoutMillis > 0, "Engine Profile ZooKeeper lock timeout must be positive")

  // InterProcessMutex is reentrant only when nested calls reuse the same mutex instance. Profile
  // services intentionally hold this owner lock across authorization/guard checks and then call a
  // store mutation which takes the same lock again. Keep one mutex per owner path so that nesting
  // is reentrant while different threads and Kyuubi replicas are still serialized by Curator.
  private val locks = new ConcurrentHashMap[String, InterProcessMutex]()

  def withLock[T](ownerHash: String)(body: => T): T = {
    val lock = mutex(ownerHash)
    val acquired =
      try lock.acquire(timeoutMillis, TimeUnit.MILLISECONDS)
      catch {
        case error: Exception =>
          throw new KyuubiException("Could not acquire Engine Profile ZooKeeper owner lock", error)
      }
    if (!acquired) {
      throw new KyuubiException(
        s"Timed out after ${timeoutMillis}ms waiting for the Engine Profile owner lock")
    }
    try body
    finally {
      try lock.release()
      catch {
        case error: Exception =>
          throw new KyuubiException("Could not release Engine Profile ZooKeeper owner lock", error)
      }
    }
  }

  private def mutex(ownerHash: String): InterProcessMutex = {
    val existing = locks.get(ownerHash)
    if (existing != null) {
      existing
    } else {
      val created = new InterProcessMutex(
        client,
        s"/$namespace/engine-profile-store/owners/$ownerHash")
      val raced = locks.putIfAbsent(ownerHash, created)
      if (raced == null) created else raced
    }
  }
}
