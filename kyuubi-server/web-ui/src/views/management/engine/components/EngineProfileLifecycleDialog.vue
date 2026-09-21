<!--
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
-->
<template>
  <el-dialog
    :model-value="modelValue"
    width="920px"
    title="Engine Profile lifecycle"
    @update:model-value="emit('update:modelValue', $event)">
    <template v-if="profile">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="Profile">{{ profile.name }}</el-descriptions-item>
        <el-descriptions-item label="Current revision">
          r{{ profile.revision }}
        </el-descriptions-item>
        <el-descriptions-item label="Persistent environment">
          {{ shortId(profile.pythonEnvironmentRevisionId) || 'None' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        class="lifecycle-note"
        type="info"
        :closable="false"
        title="%pip installs are immediately available to Python workers on the same live Spark driver. The PVC environment is applied only after that driver has stopped; restarting a Notebook session alone can reuse the same driver." />

      <el-divider content-position="left">Engine revisions</el-divider>
      <el-table v-loading="loading" :data="revisions" size="small">
        <el-table-column label="Revision" width="105">
          <template #default="{ row }">r{{ row.revision }}</template>
        </el-table-column>
        <el-table-column label="State" width="120">
          <template #default="{ row }">
            <el-tag :type="row.state === 'ACTIVE' ? 'success' : 'warning'" size="small">
              {{ row.state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Engine" width="135">
          <template #default="{ row }">
            <el-tag :type="engineStateType(row.revision)" size="small">
              {{ engineState(row.revision) }}
            </el-tag>
            <div class="muted">{{ engineCount(row.revision) }} active</div>
          </template>
        </el-table-column>
        <el-table-column label="Driver / Executor" min-width="180">
          <template #default="{ row }">
            {{ resources(row) }}
          </template>
        </el-table-column>
        <el-table-column label="Timeouts" min-width="150">
          <template #default="{ row }">
            Notebook: {{ row.notebookRuntimeIdleTimeout || 'inherit' }}<br>
            Engine: {{ row.engineIdleTimeout || 'inherit' }}
          </template>
        </el-table-column>
        <el-table-column label="Environment" min-width="140">
          <template #default="{ row }">
            {{ shortId(row.pythonEnvironmentRevisionId) || 'None' }}
          </template>
        </el-table-column>
        <el-table-column label="Created" min-width="155">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="Action" width="105">
          <template #default="{ row }">
            <el-button
              v-if="row.state === 'DRAINING' && engineState(row.revision) === 'RUNNING'"
              size="small"
              type="danger"
              :loading="terminatingRevision === row.revision"
              @click="terminate(row.revision)">
              Stop engine
            </el-button>
            <span v-else class="muted">
              {{ row.state === 'ACTIVE' ? 'Current' : 'No active engine' }}
            </span>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">Persistent Python environments</el-divider>
      <el-table v-loading="loading" :data="environments" size="small">
        <el-table-column label="Environment" min-width="150">
          <template #default="{ row }">{{ shortId(row.id) }}</template>
        </el-table-column>
        <el-table-column label="Build revision" width="125">
          <template #default="{ row }">env-{{ row.revision }}</template>
        </el-table-column>
        <el-table-column label="State" width="115">
          <template #default="{ row }">
            <el-tag :type="environmentStateType(row.state)" size="small">
              {{ row.state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Resolved packages" min-width="250">
          <template #default="{ row }">
            {{ row.requirements.join(', ') || 'No packages' }}
          </template>
        </el-table-column>
        <el-table-column label="Ready" min-width="155">
          <template #default="{ row }">
            {{ formatTime(row.readyAt || row.createdAt) }}
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
  import { onBeforeUnmount, ref, watch } from 'vue'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import {
    listEngineProfileRevisions,
    getEngineProfileEngineStatus,
    getEngineProfileRevisionEngineStatus,
    listPythonEnvironments,
    terminateEngineProfileRevision
  } from '@/api/notebook'
  import type {
    EngineProfile,
    EngineProfileRevision,
    EngineProfileEngineStatus,
    PythonEnvironmentRevision
  } from '@/api/notebook/types'

  const props = defineProps<{
    modelValue: boolean
    profile: EngineProfile | null
  }>()

  const emit = defineEmits<{
    (e: 'update:modelValue', value: boolean): void
    (e: 'changed'): void
  }>()

  const loading = ref(false)
  const terminatingRevision = ref<number | null>(null)
  const revisions = ref<EngineProfileRevision[]>([])
  const environments = ref<PythonEnvironmentRevision[]>([])
  const engineStatuses = ref<Record<number, EngineProfileEngineStatus>>({})

  const load = async () => {
    if (!props.profile) return
    loading.value = true
    try {
      const [revisionResult, environmentResult] = await Promise.all([
        listEngineProfileRevisions(props.profile.profileId),
        listPythonEnvironments(props.profile.profileId)
      ])
      revisions.value = revisionResult
      environments.value = environmentResult
      await refreshEngineStatuses()
    } catch (error: any) {
      ElMessage.error(`Failed to load profile lifecycle: ${error?.message || error}`)
    } finally {
      loading.value = false
    }
  }

  // The current revision endpoint also exposes transient STARTING/STOPPING states. Former
  // revisions use their immutable revision subdomain, so each row remains independent.
  const refreshEngineStatuses = async () => {
    if (!props.profile) return
    const profile = props.profile
    const statuses = await Promise.all(revisions.value.map(async (revision) => {
      try {
        return revision.revision === profile.revision
          ? await getEngineProfileEngineStatus(profile.profileId)
          : await getEngineProfileRevisionEngineStatus(profile.profileId, revision.revision)
      } catch {
        return {
          profileId: profile.profileId,
          revision: revision.revision,
          state: 'UNKNOWN' as const,
          engineCount: 0
        }
      }
    }))
    engineStatuses.value = Object.fromEntries(
      statuses.map((status) => [status.revision, status])
    )
  }

  const terminate = async (revision: number) => {
    if (!props.profile) return
    try {
      await ElMessageBox.confirm(
        `Terminate all engines for draining revision r${revision}? ` +
          'Active Notebook or SQL sessions using it will be interrupted.',
        'Terminate old engine revision',
        { confirmButtonText: 'Terminate engine', cancelButtonText: 'Cancel', type: 'warning' }
      )
    } catch {
      return
    }
    terminatingRevision.value = revision
    try {
      const result = await terminateEngineProfileRevision(
        props.profile.profileId,
        revision
      )
      ElMessage.success(
        `Terminated ${result.terminatedEngineNodes} engine node(s) for r${revision}`
      )
      await load()
      emit('changed')
    } catch (error: any) {
      ElMessage.error(`Failed to terminate revision: ${error?.message || error}`)
    } finally {
      terminatingRevision.value = null
    }
  }

  const resources = (revision: EngineProfileRevision) => {
    const config = revision.sparkConfig || {}
    const driver =
      `${config['spark.driver.memory'] || 'default'} / ` +
      `${config['spark.driver.cores'] || 'default'}c`
    const executor =
      `${config['spark.executor.memory'] || 'default'} / ` +
      `${config['spark.executor.cores'] || 'default'}c`
    return `Driver ${driver}; Executor ${executor}`
  }

  const engineState = (revision: number) =>
    engineStatuses.value[revision]?.state || 'STOPPED'
  const engineCount = (revision: number) =>
    engineStatuses.value[revision]?.engineCount || 0
  const engineStateType = (revision: number) => {
    const state = engineState(revision)
    if (state === 'RUNNING') return 'success'
    if (state === 'FAILED') return 'danger'
    if (state === 'STARTING' || state === 'STOPPING') return 'warning'
    return 'info'
  }

  const shortId = (value?: string | null) => (value ? value.slice(0, 12) : '')
  const formatTime = (value?: number | null) =>
    value ? new Date(value).toLocaleString() : '-'
  const environmentStateType = (
    state: string
  ): 'success' | 'warning' | 'danger' | 'info' => {
    if (state === 'READY') return 'success'
    if (state === 'FAILED') return 'danger'
    if (state === 'RETIRED') return 'info'
    return 'warning'
  }

  let statusPoll: ReturnType<typeof setInterval> | undefined

  watch(
    () => [props.modelValue, props.profile?.profileId],
    ([visible]) => {
      if (statusPoll) {
        clearInterval(statusPoll)
        statusPoll = undefined
      }
      if (visible) {
        load()
        statusPoll = setInterval(refreshEngineStatuses, 5000)
      }
    },
    { immediate: true }
  )

  onBeforeUnmount(() => {
    if (statusPoll) clearInterval(statusPoll)
  })
</script>

<style scoped lang="scss">
  .lifecycle-note {
    margin-top: 16px;
  }

  .muted {
    color: var(--el-text-color-secondary);
  }
</style>
