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

import org.apache.kyuubi.server.notebook.api.NotebookException

/**
 * Resolves an Engine Profile selected by an authenticated Thrift client into the server-owned
 * configuration used to launch or reuse a Spark engine.
 *
 * A client supplies only [[ProfileIdKey]]. Resource settings, engine sharing and Python PVC
 * settings are read from the immutable profile snapshot after the effective session user has
 * been authorized. This makes DBT CLI clients use the same engine key as Notebook and SQL Editor.
 */
class EngineProfileSessionConfigResolver(
    profiles: EngineProfileService,
    pythonEnvironments: PythonEnvironmentService,
    runSnapshots: EngineProfileRunSnapshotService) {

  import EngineProfileSessionConfigResolver._

  def resolve(
      sessionUser: String,
      clientConfiguration: Map[String, String]): Map[String, String] = {
    clientConfiguration.get(ProfileIdKey).map(_.trim) match {
      case Some("") =>
        throw NotebookException.invalid(s"$ProfileIdKey must not be empty")
      case Some(id) =>
        if (clientConfiguration.contains(LegacyProfileRevisionKey)) {
          throw NotebookException.invalid(
            s"$LegacyProfileRevisionKey is a removed legacy parameter; select only " +
              s"$ProfileIdKey")
        }
        rejectRawLaunchConfiguration(clientConfiguration)

        val principal = EngineProfilePrincipal(sessionUser, admin = false)
        val snapshot = clientConfiguration.get(RunIdKey).map(_.trim) match {
          case Some("") =>
            throw NotebookException.invalid(s"$RunIdKey must not be empty")
          case Some(runId) =>
            runSnapshots.resolve(runId, id, principal) {
              // Promote a completed Python environment only while establishing a new run pin.
              // A later session of the same run must retain its original immutable revision.
              profiles.snapshotForUse(id, principal)
              pythonEnvironments.prepareForEngineLaunch(id)
              profiles.snapshotForUse(id, principal)
            }
          case None =>
            // Existing non-DBT Thrift clients retain the active-revision behavior.
            profiles.snapshotForUse(id, principal)
            pythonEnvironments.prepareForEngineLaunch(id)
            profiles.snapshotForUse(id, principal)
        }

        val profileConfiguration = snapshot.sparkConfig ++
          EngineProfileSnapshot.engineSessionConfig(snapshot) ++
          pythonEnvironments.launchConfig(snapshot) ++
          Map(
            EngineTypeKey -> "SPARK_SQL",
            EngineShareLevelKey -> "USER",
            EngineShareSubdomainKey -> snapshot.subdomain,
            LegacyEngineShareSubdomainKey -> snapshot.subdomain)
        val remainingClientConfiguration =
          clientConfiguration - ProfileIdKey - LegacyProfileRevisionKey - RunIdKey
        remainingClientConfiguration ++ profileConfiguration
      case None if clientConfiguration.contains(RunIdKey) =>
        throw NotebookException.invalid(s"$RunIdKey requires $ProfileIdKey")
      case None => clientConfiguration
    }
  }

  private def rejectRawLaunchConfiguration(configuration: Map[String, String]): Unit = {
    val rejected = configuration.keys.filter { key =>
      key == EngineShareLevelKey ||
      key == EngineShareSubdomainKey ||
      key == LegacyEngineShareSubdomainKey ||
      key == EngineTypeKey ||
      key == EngineIdleTimeoutKey ||
      key.startsWith("spark.") && !key.startsWith("spark.sql.")
    }.toSeq.sorted
    if (rejected.nonEmpty) {
      throw NotebookException.invalid(
        s"Engine Profile sessions cannot override server-owned settings: " +
          rejected.mkString(", "))
    }
  }
}

object EngineProfileSessionConfigResolver {
  val ProfileIdKey = "kyuubi.engine.profile.id"
  val LegacyProfileRevisionKey = "kyuubi.engine.profile.revision"
  val RunIdKey = "kyuubi.engine.profile.run.id"

  private val EngineShareLevelKey = "kyuubi.engine.share.level"
  private val EngineShareSubdomainKey = "kyuubi.engine.share.level.subdomain"
  private val LegacyEngineShareSubdomainKey = "kyuubi.engine.share.level.sub.domain"
  private val EngineTypeKey = "kyuubi.engine.type"
  private val EngineIdleTimeoutKey = "kyuubi.session.engine.idle.timeout"
}
