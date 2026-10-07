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
  <el-dialog :model-value="modelValue" width="920px" title="Engine Profile lifecycle"
    @update:model-value="emit('update:modelValue', $event)">
    <template v-if="profile">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="Profile">{{ profile.name }}</el-descriptions-item>
        <el-descriptions-item label="Engine">
          <el-tag :type="stateType(status?.state)" size="small">{{ status?.state || 'STOPPED' }}</el-tag>
          <span class="muted"> {{ status?.engineCount || 0 }} active</span>
        </el-descriptions-item>
        <el-descriptions-item label="Persistent environment">
          {{ shortId(profile.pythonEnvironmentRevisionId) || 'None' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-alert class="lifecycle-note" type="info" :closable="false"
        title="%pip changes are available on the live driver first. A READY persistent environment is mounted on the next engine launch after that driver stops." />
      <el-divider content-position="left">Persistent Python environments</el-divider>
      <el-table v-loading="loading" :data="environments" size="small">
        <el-table-column label="Environment" min-width="150"><template #default="{ row }">{{ shortId(row.id) }}</template></el-table-column>
        <el-table-column label="Build revision" width="125"><template #default="{ row }">env-{{ row.revision }}</template></el-table-column>
        <el-table-column label="State" width="115"><template #default="{ row }"><el-tag :type="environmentStateType(row.state)" size="small">{{ row.state }}</el-tag></template></el-table-column>
        <el-table-column label="Resolved packages" min-width="250"><template #default="{ row }">{{ row.requirements.join(', ') || 'No packages' }}</template></el-table-column>
        <el-table-column label="Ready" min-width="155"><template #default="{ row }">{{ formatTime(row.readyAt || row.createdAt) }}</template></el-table-column>
      </el-table>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
  import { onBeforeUnmount, ref, watch } from 'vue'
  import { ElMessage } from 'element-plus'
  import { getEngineProfileEngineStatus, listPythonEnvironments } from '@/api/notebook'
  import type { EngineProfile, EngineProfileEngineStatus, PythonEnvironmentRevision } from '@/api/notebook/types'

  const props = defineProps<{ modelValue: boolean; profile: EngineProfile | null }>()
  const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'changed'): void }>()
  const loading = ref(false)
  const status = ref<EngineProfileEngineStatus>()
  const environments = ref<PythonEnvironmentRevision[]>([])

  const load = async () => {
    if (!props.profile) return
    loading.value = true
    try {
      const [engine, python] = await Promise.all([
        getEngineProfileEngineStatus(props.profile.profileId),
        listPythonEnvironments(props.profile.profileId)
      ])
      status.value = engine
      environments.value = python
    } catch (error: any) {
      ElMessage.error(`Failed to load profile lifecycle: ${error?.message || error}`)
    } finally { loading.value = false }
  }
  const shortId = (value?: string | null) => value ? value.slice(0, 12) : ''
  const formatTime = (value?: number | null) => value ? new Date(value).toLocaleString() : '-'
  const stateType = (value?: string): 'success' | 'warning' | 'danger' | 'info' => {
    if (value === 'RUNNING') return 'success'
    if (value === 'FAILED') return 'danger'
    if (value === 'STARTING' || value === 'STOPPING') return 'warning'
    return 'info'
  }
  const environmentStateType = (value: string): 'success' | 'warning' | 'danger' | 'info' => {
    if (value === 'READY') return 'success'
    if (value === 'FAILED') return 'danger'
    if (value === 'RETIRED') return 'info'
    return 'warning'
  }
  let poll: ReturnType<typeof setInterval> | undefined
  watch(() => [props.modelValue, props.profile?.profileId], ([visible]) => {
    if (poll) clearInterval(poll)
    if (visible) { load(); poll = setInterval(load, 5000) }
  }, { immediate: true })
  onBeforeUnmount(() => { if (poll) clearInterval(poll) })
</script>

<style scoped>
.lifecycle-note { margin: 20px 0; }
.muted { color: var(--el-text-color-secondary); margin-left: 4px; }
</style>
