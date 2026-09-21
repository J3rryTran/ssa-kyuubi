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
  <div class="dbt-page">
    <div class="page-header">
      <div>
        <h1>DBT Workspaces</h1>
        <p>Run dbt jobs with a server-authorized Engine Profile.</p>
      </div>
      <el-button type="primary" icon="Plus" @click="openCreate"
        >New DBT Workspace</el-button
      >
    </div>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      show-icon
      :closable="false"
      class="alert" />

    <el-card>
      <el-table
        v-loading="loading"
        :data="workspaces"
        empty-text="No DBT workspaces yet.">
        <el-table-column label="Workspace" min-width="230">
          <template #default="{ row }">
            <el-link
              type="primary"
              @click="router.push(`/dbt/workspaces/${row.id}`)">
              {{ row.name }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column prop="projectRef" label="Project" min-width="180" />
        <el-table-column
          prop="selectedEngineProfileId"
          label="Engine Profile"
          min-width="180">
          <template #default="{ row }"
            ><el-tag effect="plain">{{
              row.selectedEngineProfileId
            }}</el-tag></template
          >
        </el-table-column>
        <el-table-column label="Updated" min-width="160">
          <template #default="{ row }">{{
            formatTime(row.updatedAt)
          }}</template>
        </el-table-column>
        <el-table-column label="Actions" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push(`/dbt/workspaces/${row.id}`)"
              >Open</el-button
            >
            <el-button link @click="openEdit(row)">Edit</el-button>
            <el-button link type="danger" @click="remove(row)"
              >Delete</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? 'Edit DBT Workspace' : 'New DBT Workspace'"
      width="520px">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="Workspace name" required
          ><el-input v-model="form.name"
        /></el-form-item>
        <el-form-item label="Project reference" required>
          <el-input v-model="form.projectRef" placeholder="phase-c-demo" />
          <div class="help"
            >A server-controlled project registry ID, not a Git URL.</div
          >
        </el-form-item>
        <el-form-item label="Engine Profile" required>
          <el-select
            v-model="form.engineProfileId"
            filterable
            style="width: 100%"
            placeholder="Select profile">
            <el-option
              v-for="profile in profiles"
              :key="profile.profileId"
              :label="profile.name || profile.subdomain"
              :value="profile.profileId" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="dialogVisible = false">Cancel</el-button
        ><el-button type="primary" :loading="saving" @click="save"
          >Save</el-button
        ></template
      >
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
  import { onMounted, ref } from 'vue'
  import { useRouter } from 'vue-router'
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { listEngineProfiles } from '@/api/notebook'
  import type { EngineProfile } from '@/api/notebook/types'
  import {
    createDbtWorkspace,
    deleteDbtWorkspace,
    listDbtWorkspaces,
    updateDbtWorkspace
  } from '@/api/dbt'
  import type { DbtWorkspace } from '@/api/dbt/types'

  const router = useRouter()
  const loading = ref(false)
  const saving = ref(false)
  const loadError = ref('')
  const workspaces = ref<DbtWorkspace[]>([])
  const profiles = ref<EngineProfile[]>([])
  const dialogVisible = ref(false)
  const editing = ref<DbtWorkspace | null>(null)
  const form = ref({
    name: '',
    projectRef: 'phase-c-demo',
    engineProfileId: ''
  })

  const formatTime = (value: number) => new Date(value).toLocaleString()
  const errorMessage = (error: any) =>
    error?.response?.data?.error?.message || error?.message || 'Request failed'

  async function load() {
    loading.value = true
    loadError.value = ''
    try {
      const [items, engineProfiles] = await Promise.all([
        listDbtWorkspaces(),
        listEngineProfiles()
      ])
      workspaces.value = items
      profiles.value = engineProfiles
    } catch (error) {
      loadError.value = errorMessage(error)
    } finally {
      loading.value = false
    }
  }

  function openCreate() {
    editing.value = null
    form.value = {
      name: '',
      projectRef: 'phase-c-demo',
      engineProfileId: profiles.value[0]?.profileId || ''
    }
    dialogVisible.value = true
  }

  function openEdit(workspace: DbtWorkspace) {
    editing.value = workspace
    form.value = {
      name: workspace.name,
      projectRef: workspace.projectRef,
      engineProfileId: workspace.selectedEngineProfileId
    }
    dialogVisible.value = true
  }

  async function save() {
    if (
      !form.value.name.trim() ||
      !form.value.projectRef.trim() ||
      !form.value.engineProfileId
    ) {
      ElMessage.warning(
        'Workspace name, project reference and Engine Profile are required.'
      )
      return
    }
    saving.value = true
    try {
      if (editing.value) {
        await updateDbtWorkspace(editing.value.id, {
          ...form.value,
          version: editing.value.version
        })
      } else {
        await createDbtWorkspace(form.value)
      }
      dialogVisible.value = false
      ElMessage.success('DBT workspace saved.')
      await load()
    } catch (error) {
      ElMessage.error(errorMessage(error))
    } finally {
      saving.value = false
    }
  }

  async function remove(workspace: DbtWorkspace) {
    try {
      await ElMessageBox.confirm(
        `Delete workspace “${workspace.name}”? Jobs must be deleted first.`,
        'Delete DBT Workspace',
        { type: 'warning' }
      )
      await deleteDbtWorkspace(workspace.id, workspace.version)
      ElMessage.success('DBT workspace deleted.')
      await load()
    } catch (error: any) {
      if (error !== 'cancel' && error !== 'close')
        ElMessage.error(errorMessage(error))
    }
  }

  onMounted(load)
</script>

<style scoped lang="scss">
  .dbt-page {
    padding: 24px;
  }
  .page-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 22px;
  }
  h1 {
    margin: 0;
    color: #0f172a;
    font-size: 24px;
  }
  p,
  .help {
    color: #64748b;
    margin: 6px 0 0;
    font-size: 13px;
  }
  .alert {
    margin-bottom: 16px;
  }
</style>
