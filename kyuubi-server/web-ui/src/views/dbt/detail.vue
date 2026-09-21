<!--
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
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
  <div v-loading="loading" class="dbt-page">
    <el-page-header @back="router.push('/dbt/workspaces')">
      <template #content
        ><span class="title">{{
          workspace?.name || 'DBT Workspace'
        }}</span></template
      >
      <template #extra
        ><el-button @click="editWorkspace">Edit workspace</el-button></template
      >
    </el-page-header>
    <div v-if="workspace" class="workspace-meta">
      <span
        >Project: <b>{{ workspace.projectRef }}</b></span
      >
      <span
        >Engine Profile:
        <el-tag effect="plain">{{
          workspace.selectedEngineProfileId
        }}</el-tag></span
      >
    </div>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      class="section" />
    <section class="section">
      <div class="section-header"
        ><h2>DBT Jobs</h2
        ><div
          ><el-button @click="openPreview">Preview</el-button
          ><el-button type="primary" icon="Plus" @click="openJob()"
            >New Job</el-button
          ></div
        ></div
      >
      <el-card
        ><el-table :data="jobs" empty-text="No DBT jobs yet.">
          <el-table-column prop="name" label="Job" min-width="180" />
          <el-table-column label="Action" min-width="130"
            ><template #default="{ row }">{{
              actionOf(row.action)
            }}</template></el-table-column
          >
          <el-table-column prop="selector" label="Selector" min-width="160"
            ><template #default="{ row }">{{
              row.selector || '—'
            }}</template></el-table-column
          >
          <el-table-column label="Profile" min-width="170"
            ><template #default="{ row }">{{
              row.engineProfileIdOverride ||
              `Inherit: ${workspace?.selectedEngineProfileId}`
            }}</template></el-table-column
          >
          <el-table-column label="Actions" width="190" fixed="right"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="runJob(row)"
                >Run</el-button
              ><el-button link @click="openJob(row)">Edit</el-button
              ><el-button link type="danger" @click="removeJob(row)"
                >Delete</el-button
              ></template
            ></el-table-column
          >
        </el-table></el-card
      >
    </section>

    <section class="section"
      ><div class="section-header"
        ><h2>Recent Runs</h2
        ><el-button icon="Refresh" @click="load">Refresh</el-button></div
      >
      <el-card
        ><el-table
          :data="runs"
          empty-text="No DBT runs yet."
          @row-click="openRun">
          <el-table-column label="Run" min-width="145"
            ><template #default="{ row }"
              ><code>{{ row.id.slice(0, 8) }}</code></template
            ></el-table-column
          >
          <el-table-column label="Action" width="125"
            ><template #default="{ row }">{{
              actionOf(row.action)
            }}</template></el-table-column
          >
          <el-table-column label="State" width="130"
            ><template #default="{ row }"
              ><el-tag :type="stateType(stateOf(row.state))">{{
                stateOf(row.state)
              }}</el-tag></template
            ></el-table-column
          >
          <el-table-column label="Profile snapshot" min-width="165"
            ><template #default="{ row }">{{
              row.profile.subdomain
            }}</template></el-table-column
          >
          <el-table-column label="Started" min-width="150"
            ><template #default="{ row }">{{
              row.startedAt ? formatTime(row.startedAt) : '—'
            }}</template></el-table-column
          >
          <el-table-column label="Duration" width="110"
            ><template #default="{ row }">{{
              duration(row)
            }}</template></el-table-column
          >
          <el-table-column label="Actions" width="130" fixed="right"
            ><template #default="{ row }"
              ><el-button link @click.stop="openRun(row)">Details</el-button
              ><el-button
                v-if="isActive(row)"
                link
                type="danger"
                @click.stop="cancel(row)"
                >Cancel</el-button
              ></template
            ></el-table-column
          >
        </el-table></el-card
      >
    </section>

    <el-dialog
      v-model="jobDialog"
      :title="editingJob ? 'Edit DBT Job' : 'New DBT Job'"
      width="520px"
      ><el-form label-position="top">
        <el-form-item label="Job name" required
          ><el-input v-model="jobForm.name"
        /></el-form-item>
        <el-form-item label="Action" required
          ><el-select v-model="jobForm.action" style="width: 100%"
            ><el-option label="Preview" value="PREVIEW" /><el-option
              label="Run model"
              value="RUN_MODEL" /><el-option
              label="Run project"
              value="RUN_PROJECT" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="jobForm.action !== 'RUN_PROJECT'"
          label="Selector"
          required
          ><el-input v-model="jobForm.selector" placeholder="model_name"
        /></el-form-item>
        <el-form-item label="Engine Profile"
          ><el-select
            v-model="jobForm.engineProfileIdOverride"
            clearable
            placeholder="Inherit workspace profile"
            style="width: 100%"
            ><el-option
              v-for="profile in profiles"
              :key="profile.profileId"
              :label="profile.name || profile.subdomain"
              :value="profile.profileId" /></el-select
        ></el-form-item> </el-form
      ><template #footer
        ><el-button @click="jobDialog = false">Cancel</el-button
        ><el-button type="primary" :loading="saving" @click="saveJob"
          >Save</el-button
        ></template
      ></el-dialog
    >

    <el-dialog v-model="previewDialog" title="Preview DBT model" width="440px"
      ><el-form label-position="top"
        ><el-form-item label="Selector" required
          ><el-input
            v-model="previewSelector"
            placeholder="model_name" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="previewDialog = false">Cancel</el-button
        ><el-button type="primary" :loading="saving" @click="preview"
          >Preview</el-button
        ></template
      ></el-dialog
    >

    <el-dialog
      v-model="workspaceDialog"
      title="Edit DBT Workspace"
      width="500px"
      ><el-form label-position="top"
        ><el-form-item label="Workspace name"
          ><el-input v-model="workspaceForm.name" /></el-form-item
        ><el-form-item label="Project reference"
          ><el-input v-model="workspaceForm.projectRef" /></el-form-item
        ><el-form-item label="Engine Profile"
          ><el-select
            v-model="workspaceForm.engineProfileId"
            style="width: 100%"
            ><el-option
              v-for="profile in profiles"
              :key="profile.profileId"
              :label="profile.name || profile.subdomain"
              :value="profile.profileId" /></el-select></el-form-item></el-form
      ><template #footer
        ><el-button @click="workspaceDialog = false">Cancel</el-button
        ><el-button type="primary" :loading="saving" @click="saveWorkspace"
          >Save</el-button
        ></template
      ></el-dialog
    >

    <el-drawer v-model="drawer" title="DBT Run" size="55%"
      ><template v-if="selectedRun"
        ><el-descriptions :column="1" border
          ><el-descriptions-item label="State"
            ><el-tag :type="stateType(stateOf(selectedRun.state))">{{
              stateOf(selectedRun.state)
            }}</el-tag></el-descriptions-item
          ><el-descriptions-item label="Engine Profile snapshot">{{
            selectedRun.profile.subdomain
          }}</el-descriptions-item
          ><el-descriptions-item label="Created">{{
            formatTime(selectedRun.createdAt)
          }}</el-descriptions-item
          ><el-descriptions-item
            v-if="selectedRun.errorSummary"
            label="Error"
            >{{ selectedRun.errorSummary }}</el-descriptions-item
          ></el-descriptions
        ><div class="log-header"
          ><h3>Log</h3
          ><el-button
            v-if="isActive(selectedRun)"
            type="danger"
            plain
            @click="cancelSelectedRun"
            >Cancel run</el-button
          ></div
        ><el-alert
          v-if="selectedRun.logTruncated"
          title="This log was truncated by the server limit."
          type="warning"
          :closable="false" /><pre class="log">{{
          logContent || 'Waiting for log output…'
        }}</pre>
      </template></el-drawer
    >
  </div>
</template>

<script setup lang="ts">
  import { onBeforeUnmount, onMounted, ref } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { listEngineProfiles } from '@/api/notebook'
  import type { EngineProfile } from '@/api/notebook/types'
  import {
    cancelDbtRun,
    createDbtJob,
    deleteDbtJob,
    getDbtRun,
    getDbtRunLogs,
    getDbtWorkspace,
    listDbtJobs,
    listDbtWorkspaceRuns,
    previewDbtWorkspace,
    runDbtJob,
    updateDbtJob,
    updateDbtWorkspace
  } from '@/api/dbt'
  import {
    ACTIVE_DBT_RUN_STATES,
    enumValue,
    type DbtAction,
    type DbtJob,
    type DbtJobRun,
    type DbtRunState,
    type DbtWorkspace
  } from '@/api/dbt/types'

  const route = useRoute()
  const router = useRouter()
  const workspaceId = route.params.workspaceId as string
  const workspace = ref<DbtWorkspace | null>(null)
  const jobs = ref<DbtJob[]>([])
  const runs = ref<DbtJobRun[]>([])
  const profiles = ref<EngineProfile[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  const jobDialog = ref(false)
  const previewDialog = ref(false)
  const workspaceDialog = ref(false)
  const drawer = ref(false)
  const editingJob = ref<DbtJob | null>(null)
  const selectedRun = ref<DbtJobRun | null>(null)
  const previewSelector = ref('')
  const logContent = ref('')
  const logOffset = ref(0)
  const jobForm = ref<{
    name: string
    action: DbtAction
    selector: string
    engineProfileIdOverride: string
  }>({
    name: '',
    action: 'RUN_PROJECT',
    selector: '',
    engineProfileIdOverride: ''
  })
  const workspaceForm = ref({ name: '', projectRef: '', engineProfileId: '' })
  let poller: number | undefined
  function actionOf(value: DbtJob['action'] | DbtJobRun['action']): DbtAction {
    return enumValue(value)
  }
  function stateOf(value: DbtJobRun['state']): DbtRunState {
    return enumValue(value)
  }
  const isActive = (run: DbtJobRun) =>
    ACTIVE_DBT_RUN_STATES.includes(stateOf(run.state))
  const formatTime = (value: number) => new Date(value).toLocaleString()
  const duration = (run: DbtJobRun) => {
    const end = run.finishedAt || (isActive(run) ? Date.now() : null)
    return run.startedAt && end
      ? `${Math.max(0, Math.round((end - run.startedAt) / 1000))}s`
      : '—'
  }
  const stateType = (state: DbtRunState) =>
    state === 'SUCCEEDED'
      ? 'success'
      : state === 'FAILED'
      ? 'danger'
      : state === 'CANCELLED'
      ? 'info'
      : state === 'CANCELLING'
      ? 'warning'
      : 'primary'
  const message = (e: any) =>
    e?.response?.data?.error?.message || e?.message || 'Request failed'

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const [w, j, r, p] = await Promise.all([
        getDbtWorkspace(workspaceId),
        listDbtJobs(workspaceId),
        listDbtWorkspaceRuns(workspaceId),
        listEngineProfiles()
      ])
      workspace.value = w
      jobs.value = j
      runs.value = r
      profiles.value = p
    } catch (e) {
      error.value = message(e)
    } finally {
      loading.value = false
    }
  }
  function openJob(job?: DbtJob) {
    editingJob.value = job || null
    jobForm.value = job
      ? {
          name: job.name,
          action: actionOf(job.action),
          selector: job.selector || '',
          engineProfileIdOverride: job.engineProfileIdOverride || ''
        }
      : {
          name: '',
          action: 'RUN_PROJECT',
          selector: '',
          engineProfileIdOverride: ''
        }
    jobDialog.value = true
  }
  async function saveJob() {
    if (
      !jobForm.value.name.trim() ||
      (jobForm.value.action !== 'RUN_PROJECT' && !jobForm.value.selector.trim())
    )
      return ElMessage.warning(
        'Name and selector are required for this action.'
      )
    saving.value = true
    try {
      const data = {
        ...jobForm.value,
        selector:
          jobForm.value.action === 'RUN_PROJECT'
            ? undefined
            : jobForm.value.selector,
        engineProfileIdOverride:
          jobForm.value.engineProfileIdOverride || undefined
      }
      if (editingJob.value)
        await updateDbtJob(editingJob.value.id, {
          ...data,
          clearEngineProfileIdOverride: !data.engineProfileIdOverride,
          version: editingJob.value.version
        })
      else await createDbtJob(workspaceId, data)
      jobDialog.value = false
      await load()
    } catch (e) {
      ElMessage.error(message(e))
    } finally {
      saving.value = false
    }
  }
  async function removeJob(job: DbtJob) {
    try {
      await ElMessageBox.confirm(
        `Delete job “${job.name}”?`,
        'Delete DBT Job',
        { type: 'warning' }
      )
      await deleteDbtJob(job.id, job.version)
      await load()
    } catch (e: any) {
      if (e !== 'cancel' && e !== 'close') ElMessage.error(message(e))
    }
  }
  async function runJob(job: DbtJob) {
    try {
      const run = await runDbtJob(job.id)
      await load()
      openRun(run)
      ElMessage.success('DBT run submitted.')
    } catch (e) {
      ElMessage.error(message(e))
    }
  }
  function openPreview() {
    previewSelector.value = ''
    previewDialog.value = true
  }
  async function preview() {
    if (!previewSelector.value.trim())
      return ElMessage.warning('Selector is required.')
    saving.value = true
    try {
      const run = await previewDbtWorkspace(workspaceId, previewSelector.value)
      previewDialog.value = false
      await load()
      openRun(run)
    } catch (e) {
      ElMessage.error(message(e))
    } finally {
      saving.value = false
    }
  }
  function editWorkspace() {
    if (!workspace.value) return
    workspaceForm.value = {
      name: workspace.value.name,
      projectRef: workspace.value.projectRef,
      engineProfileId: workspace.value.selectedEngineProfileId
    }
    workspaceDialog.value = true
  }
  async function saveWorkspace() {
    if (!workspace.value) return
    saving.value = true
    try {
      await updateDbtWorkspace(workspace.value.id, {
        ...workspaceForm.value,
        version: workspace.value.version
      })
      workspaceDialog.value = false
      await load()
    } catch (e) {
      ElMessage.error(message(e))
    } finally {
      saving.value = false
    }
  }
  async function openRun(run: DbtJobRun) {
    selectedRun.value = run
    logContent.value = ''
    logOffset.value = 0
    drawer.value = true
    await refreshSelectedRun()
  }
  async function refreshSelectedRun() {
    if (!selectedRun.value) return
    try {
      selectedRun.value = await getDbtRun(selectedRun.value.id)
      const page = await getDbtRunLogs(selectedRun.value.id, logOffset.value)
      if (page.content) {
        logContent.value += page.content.replace(/\u001b\[[0-9;]*m/g, '')
        logOffset.value = page.nextOffset
      }
    } catch (e) {
      ElMessage.error(message(e))
    }
  }
  async function cancel(run: DbtJobRun) {
    try {
      await ElMessageBox.confirm(
        'Cancel this DBT run? The shared Spark engine will remain available.',
        'Cancel DBT Run',
        { type: 'warning' }
      )
      await cancelDbtRun(run.id)
      await load()
      if (selectedRun.value?.id === run.id) await refreshSelectedRun()
    } catch (e: any) {
      if (e !== 'cancel' && e !== 'close') ElMessage.error(message(e))
    }
  }
  async function cancelSelectedRun() {
    if (selectedRun.value) await cancel(selectedRun.value)
  }
  async function poll() {
    if (runs.value.some(isActive)) await load()
    if (drawer.value && selectedRun.value && isActive(selectedRun.value))
      await refreshSelectedRun()
  }
  onMounted(async () => {
    await load()
    poller = window.setInterval(poll, 3000)
  })
  onBeforeUnmount(() => {
    if (poller) window.clearInterval(poller)
  })
</script>

<style scoped lang="scss">
  .dbt-page {
    padding: 24px;
  }
  .title {
    color: #0f172a;
    font-size: 22px;
    font-weight: 650;
  }
  .workspace-meta {
    display: flex;
    gap: 28px;
    margin: 18px 0;
    color: #475569;
  }
  .section {
    margin-top: 24px;
  }
  .section-header,
  .log-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
  }
  h2,
  h3 {
    margin: 0;
    color: #0f172a;
  }
  .log {
    min-height: 280px;
    max-height: 60vh;
    overflow: auto;
    margin-top: 12px;
    padding: 16px;
    white-space: pre-wrap;
    word-break: break-word;
    color: #dbeafe;
    background: #0f172a;
    border-radius: 6px;
    font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, monospace;
  }
  code {
    color: #475569;
  }
  @media (max-width: 700px) {
    .workspace-meta {
      display: grid;
      gap: 8px;
    }
  }
</style>
