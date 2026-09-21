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

package org.apache.kyuubi.server.dbt

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

import scala.collection.JavaConverters._

import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.batch.v1.{Job, JobBuilder}
import io.fabric8.kubernetes.client.{Config, ConfigBuilder, KubernetesClient, KubernetesClientBuilder, KubernetesClientException}

import org.apache.kyuubi.KyuubiException
import org.apache.kyuubi.Logging
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.engineprofile.{EngineProfileSnapshot, PythonEnvironmentService}
import org.apache.kyuubi.server.notebook.NotebookConf._

/** Resolves an opaque project ID to a ConfigMap managed outside the browser. */
class ConfigMapDbtProjectResolver(conf: KyuubiConf) {
  private val prefix = "kyuubi.dbt.runner.project."

  def resolve(projectRef: String): String = {
    val configMap = conf.getOption(s"$prefix$projectRef.configmap").map(_.trim)
      .filter(_.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?"))
    configMap.getOrElse(throw new KyuubiException(
      s"No server-controlled DBT project ConfigMap is configured for projectRef: $projectRef"))
  }
}

/**
 * Runs a DBT action in one Kubernetes Job. The project and all connection parameters are
 * produced by the server; callers supply neither a Git URL nor a Spark/Kyuubi property.
 */
class KubernetesJobDbtRunner(
    conf: KyuubiConf,
    client: KubernetesClient,
    pythonEnvironments: Option[PythonEnvironmentService] = None)
  extends DbtRunner with Logging {
  import DbtAction._
  import DbtRunState._

  private val namespace = conf.get(DBT_RUNNER_KUBERNETES_NAMESPACE)
  private val image = required(DBT_RUNNER_KUBERNETES_IMAGE)
  private val serviceAccount = conf.get(DBT_RUNNER_KUBERNETES_SERVICE_ACCOUNT)
  private val projectResolver = new ConfigMapDbtProjectResolver(conf)

  override def submit(request: DbtRunRequest): DbtRunnerSubmission = {
    val run = request.run
    findSubmission(run.id) match {
      case Some(existing) => return existing
      case None =>
    }
    val jobName = resourceName("kyuubi-dbt", run.id)
    val profileConfigMap = resourceName("kyuubi-dbt-profile", run.id)
    val projectConfigMap = projectResolver.resolve(run.projectRef)
    val labels = Map(
      "app.kubernetes.io/managed-by" -> "kyuubi-dbt-runner",
      "kyuubi.apache.org/dbt-run-id" -> run.id) ++
      run.dispatchToken.map("kyuubi.apache.org/dbt-dispatch-token" -> _)

    val profile = new ConfigMapBuilder()
      .withNewMetadata()
      .withName(profileConfigMap)
      .addToLabels(labels.asJava)
      .endMetadata()
      .addToData("profiles.yml", renderProfile(run))
      .build()
    client.configMaps().inNamespace(namespace).resource(profile).createOrReplace()

    val job = new JobBuilder()
      .withNewMetadata()
      .withName(jobName)
      .addToLabels(labels.asJava)
      .endMetadata()
      .withNewSpec()
      .withBackoffLimit(0)
      .withActiveDeadlineSeconds((conf.get(DBT_RUNNER_KUBERNETES_JOB_TIMEOUT) / 1000).toInt)
      .withTtlSecondsAfterFinished((conf.get(DBT_RUNNER_KUBERNETES_JOB_TTL) / 1000).toInt)
      .withNewTemplate()
      .withNewMetadata()
      .addToLabels(labels.asJava)
      .endMetadata()
      .withNewSpec()
      .withRestartPolicy("Never")
      .withServiceAccountName(serviceAccount)
      .addNewVolume()
      .withName("dbt-project")
      .withNewConfigMap()
      .withName(projectConfigMap)
      .endConfigMap()
      .endVolume()
      .addNewVolume()
      .withName("dbt-workspace")
      .withNewEmptyDir()
      .endEmptyDir()
      .endVolume()
      .addNewVolume()
      .withName("dbt-profile")
      .withNewConfigMap()
      .withName(profileConfigMap)
      .endConfigMap()
      .endVolume()
      .addNewInitContainer()
      .withName("copy-project")
      .withImage(image)
      .withCommand("sh", "-c")
      .withArgs(
        "find -L /project-source -maxdepth 1 -type f ! -name '..*' " +
          "-exec cp {} /workspace/ \\; && chown -R 10001:10001 /workspace")
      .withNewSecurityContext()
      .withRunAsUser(0L)
      .endSecurityContext()
      .addNewVolumeMount()
      .withName("dbt-project")
      .withMountPath("/project-source")
      .withReadOnly(true)
      .endVolumeMount()
      .addNewVolumeMount()
      .withName("dbt-workspace")
      .withMountPath("/workspace")
      .endVolumeMount()
      .endInitContainer()
      .addNewContainer()
      .withName("dbt")
      .withImage(image)
      .withWorkingDir("/workspace")
      .withCommand("dbt")
      .withArgs(commandArguments(run): _*)
      .addNewEnv()
      .withName("DBT_PROFILES_DIR")
      .withValue("/opt/dbt")
      .endEnv()
      .addNewVolumeMount()
      .withName("dbt-workspace")
      .withMountPath("/workspace")
      .endVolumeMount()
      .addNewVolumeMount()
      .withName("dbt-profile")
      .withMountPath("/opt/dbt")
      .withReadOnly(true)
      .endVolumeMount()
      .endContainer()
      .endSpec()
      .endTemplate()
      .endSpec()
      .build()

    val created =
      try {
        client.batch().v1().jobs().inNamespace(namespace).resource(job).create()
      } catch {
        case error: KubernetesClientException if error.getCode == 409 =>
          client.batch().v1().jobs().inNamespace(namespace).withName(jobName).get()
      }
    info(s"Submitted DBT run ${run.id} as Kubernetes Job $jobName in namespace $namespace")
    DbtRunnerSubmission(jobName, Option(created).flatMap(job => Option(job.getMetadata.getUid)))
  }

  override def findSubmission(runId: String): Option[DbtRunnerSubmission] = {
    val jobs = client.batch().v1().jobs().inNamespace(namespace)
      .withLabel("kyuubi.apache.org/dbt-run-id", runId).list().getItems.asScala
    jobs.sortBy(_.getMetadata.getCreationTimestamp).lastOption.map { job =>
      DbtRunnerSubmission(job.getMetadata.getName, Option(job.getMetadata.getUid))
    }
  }

  override def status(runnerRunId: String): DbtRunnerStatus = {
    val job = client.batch().v1().jobs().inNamespace(namespace).withName(runnerRunId).get()
    if (job == null) {
      DbtRunnerStatus(FAILED, errorSummary = Some("DBT Kubernetes Job was not found"))
    } else {
      toStatus(job)
    }
  }

  override def logs(runnerRunId: String, offset: Long): DbtRunnerLogPage = {
    require(offset >= 0, "DBT log offset must not be negative")
    val content = client.pods().inNamespace(namespace).withLabel("job-name", runnerRunId).list()
      .getItems.asScala.sortBy(_.getMetadata.getName).lastOption.map { pod =>
        Option(client.pods().inNamespace(namespace).withName(pod.getMetadata.getName).getLog)
          .getOrElse("")
      }.getOrElse("")
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    val start = math.min(offset, bytes.length.toLong).toInt
    DbtRunnerLogPage(new String(bytes.drop(start), StandardCharsets.UTF_8), bytes.length, true)
  }

  override def cancel(runnerRunId: String): Unit = {
    client.batch().v1().jobs().inNamespace(namespace).withName(runnerRunId).delete()
    info(s"Requested cancellation of DBT Kubernetes Job $runnerRunId in namespace $namespace")
  }

  override def cleanup(runId: String): Unit = {
    val configMap = resourceName("kyuubi-dbt-profile", runId)
    client.configMaps().inNamespace(namespace).withName(configMap).delete()
  }

  private def toStatus(job: Job): DbtRunnerStatus = {
    val status = Option(job.getStatus)
    val conditions = status.flatMap(s => Option(s.getConditions)).map(_.asScala)
      .getOrElse(Seq.empty)
    if (conditions.exists(c => c.getType == "Complete" && c.getStatus == "True")) {
      DbtRunnerStatus(
        SUCCEEDED,
        finishedAt = status.flatMap(s => instantMillis(s.getCompletionTime)))
    } else if (conditions.exists(c => c.getType == "Failed" && c.getStatus == "True")) {
      DbtRunnerStatus(
        FAILED,
        finishedAt = status.flatMap(s => instantMillis(s.getCompletionTime)),
        errorSummary = conditions.find(_.getType == "Failed").flatMap(c => Option(c.getMessage)))
    } else if (Option(job.getMetadata.getDeletionTimestamp).nonEmpty) {
      DbtRunnerStatus(CANCELLING)
    } else if (status.flatMap(s => Option(s.getActive)).exists(_ > 0)) {
      DbtRunnerStatus(RUNNING, startedAt = status.flatMap(s => instantMillis(s.getStartTime)))
    } else {
      DbtRunnerStatus(SUBMITTED)
    }
  }

  private def renderProfile(run: DbtJobRun): String = {
    val parameters = (Map(
      "hive.server2.proxy.user" -> run.effectiveKyuubiUser,
      "kyuubi.engine.share.level.subdomain" -> run.profile.subdomain) ++
      run.profile.sparkConfig ++ EngineProfileSnapshot.engineSessionConfig(run.profile) ++
      pythonEnvironments.map(_.launchConfig(run.profile)).getOrElse(Map.empty))
      .toSeq.sortBy(_._1).map { case (key, value) =>
        s"        ${yaml(key)}: ${yaml(value)}"
      }
      .mkString("\n")
    s"""kyuubi_dbt:
       |  target: kyuubi
       |  outputs:
       |    kyuubi:
       |      type: spark
       |      method: thrift
       |      host: ${yaml(required(DBT_RUNNER_KYUUBI_HOST))}
       |      port: ${conf.get(DBT_RUNNER_KYUUBI_PORT)}
       |      user: ${yaml(conf.get(DBT_RUNNER_KYUUBI_USER))}
       |      auth: ${yaml(conf.get(DBT_RUNNER_KYUUBI_AUTH))}
       |      schema: ${yaml(conf.get(DBT_RUNNER_KYUUBI_SCHEMA))}
       |      threads: 1
       |      server_side_parameters:
       |$parameters
       |""".stripMargin
  }

  private def commandArguments(run: DbtJobRun): Seq[String] = run.action match {
    case PREVIEW => Seq("show", "--select", run.selector.get)
    case RUN_MODEL => Seq("run", "--select", run.selector.get)
    case RUN_PROJECT => Seq("run")
  }

  private def required(entry: org.apache.kyuubi.config.ConfigEntry[String]): String = {
    val value = conf.get(entry).trim
    if (value.isEmpty) {
      throw new KyuubiException(s"${entry.key} must be set when DBT runner is enabled")
    }
    value
  }

  private def resourceName(prefix: String, runId: String): String =
    s"$prefix-${runId.take(20).toLowerCase(Locale.ROOT)}"

  private def instantMillis(value: String): Option[Long] =
    Option(value).flatMap { timestamp =>
      try Some(Instant.parse(timestamp).toEpochMilli)
      catch { case _: Exception => None }
    }

  private def yaml(value: String): String = "'" + value.replace("'", "''") + "'"
}

object KubernetesJobDbtRunner {
  def create(
      conf: KyuubiConf,
      pythonEnvironments: Option[PythonEnvironmentService] = None): KubernetesJobDbtRunner = {
    val namespace = conf.get(DBT_RUNNER_KUBERNETES_NAMESPACE)
    val client = new KubernetesClientBuilder()
      .withConfig(new ConfigBuilder(Config.autoConfigure(null)).withNamespace(namespace).build())
      .build()
    new KubernetesJobDbtRunner(conf, client, pythonEnvironments)
  }
}
