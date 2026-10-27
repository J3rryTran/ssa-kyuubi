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

import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.notebook.NotebookConf.ENGINE_PROFILE_STORE_CLASS
import org.apache.kyuubi.server.notebook.api.EngineProfile

/**
 * Persistence boundary for current Engine Profile configuration.
 *
 * Python environment build state and DBT run leases deliberately remain in [[NotebookStore]].
 * This separation lets an S3 owner-document store replace JDBC for profile configuration without
 * changing the durable state machines that still need relational coordination.
 */
trait EngineProfileStore extends AutoCloseable {

  def initialize(): Unit

  def healthCheck(): Unit

  /** Serializes profile reads/mutations with compute reservation for the same owner. */
  def withOwnerLock[T](owner: String)(body: => T): T

  def list(owner: String): Seq[EngineProfile]

  def getOwned(profileId: String, owner: String): Option[EngineProfile]

  /** Used only by privileged/background paths which have a profile ID but no owner. */
  def getById(profileId: String): Option[EngineProfile]

  def create(profile: EngineProfile): EngineProfile

  def update(profile: EngineProfile, expectedVersion: Long): Option[EngineProfile]

  def delete(profileId: String, owner: String, expectedVersion: Long): Boolean

  override def close(): Unit
}

object EngineProfileStore {
  private val S3StoreClass = classOf[S3EngineProfileStore].getName

  def create(conf: KyuubiConf): EngineProfileStore =
    conf.get(ENGINE_PROFILE_STORE_CLASS).trim match {
      case S3StoreClass => S3EngineProfileStore.create(conf)
      case other =>
        throw new IllegalArgumentException(
          s"Unsupported Engine Profile store class '$other'; expected $S3StoreClass")
    }
}
