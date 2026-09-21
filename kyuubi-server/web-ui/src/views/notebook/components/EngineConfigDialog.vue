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
  <el-dialog
    v-model="dialogVisible"
    title="Engine Configuration Presets"
    width="680px"
    class="engine-config-dialog"
    destroy-on-close
    :close-on-click-modal="false"
    @close="handleClose">
    
    <!-- Saved Presets List Table -->
    <div v-if="savedProfiles.length > 0" class="presets-list-section">
      <div class="section-title">
        <span>📋 Saved Engine Presets</span>
      </div>
      <el-table :data="savedProfiles" size="small" border style="width: 100%; margin-bottom: 16px">
        <el-table-column prop="name" label="Engine Name" min-width="130" />
        <el-table-column label="Driver" width="100">
          <template #default="{ row }">
            {{ row.driverMemory || '1g' }} / {{ row.driverCores || 1 }}c
          </template>
        </el-table-column>
        <el-table-column label="Executor" width="110">
          <template #default="{ row }">
            {{ row.executorMemory || '2g' }} / {{ row.executorCores || 1 }}c
          </template>
        </el-table-column>
        <el-table-column label="Actions" width="120" align="center">
          <template #default="{ row }">
            <el-button size="small" type="primary" link icon="Edit" @click="loadProfileForEdit(row)">
              Edit
            </el-button>
            <el-button
              v-if="row.profileId !== 'default'"
              size="small"
              type="danger"
              link
              icon="Delete"
              @click="deleteProfile(row.profileId)">
              Delete
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-divider content-position="left">
      {{ form.profileId ? `Edit Preset: ${form.name}` : 'Create New Engine Preset' }}
    </el-divider>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="left"
      label-width="140px"
      size="default"
      @submit.prevent>
      <el-form-item label="Engine Name" prop="name">
        <el-input
          v-model="form.name"
          placeholder="e.g. engine-analytics-heavy"
          clearable />
        <span class="field-hint">
          A name is unique only within your account. The server creates the runtime subdomain.
        </span>
      </el-form-item>

      <el-divider content-position="left">Standard Resource Allocations</el-divider>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="Driver Memory" prop="driverMemory">
          <el-input v-model="form.driverMemory" placeholder="1g or 1024m" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="Driver Cores" prop="driverCores">
            <el-input-number
              v-model="form.driverCores"
              :min="0.5"
              :max="16"
              :step="0.5"
            class="resource-input" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="Executor Memory" prop="executorMemory">
          <el-input v-model="form.executorMemory" placeholder="2g or 2048m" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="Executor Cores" prop="executorCores">
            <el-input-number
              v-model="form.executorCores"
              :min="1"
              :max="16"
              :step="1"
            class="resource-input" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="Executor Instances" prop="executorInstances">
        <el-input-number
          v-model="form.executorInstances"
          :min="1"
          :max="20"
          :step="1"
          class="resource-input" />
      </el-form-item>

      <el-divider content-position="left">Idle timeout policy</el-divider>
      <el-form-item label="Notebook runtime idle">
        <el-select v-model="form.notebookRuntimeIdleTimeout" style="width: 100%">
          <el-option label="Inherit platform default" value="inherit" />
          <el-option label="5 minutes" value="PT5M" />
          <el-option label="15 minutes" value="PT15M" />
          <el-option label="30 minutes" value="PT30M" />
          <el-option label="1 hour" value="PT1H" />
          <el-option label="6 hours" value="PT6H" />
          <el-option label="24 hours" value="PT24H" />
        </el-select>
      </el-form-item>
      <el-form-item label="Spark engine idle">
        <el-select v-model="form.engineIdleTimeout" style="width: 100%">
          <el-option label="Inherit platform default" value="inherit" />
          <el-option label="5 minutes" value="PT5M" />
          <el-option label="15 minutes" value="PT15M" />
          <el-option label="30 minutes" value="PT30M" />
          <el-option label="1 hour" value="PT1H" />
          <el-option label="6 hours" value="PT6H" />
          <el-option label="24 hours" value="PT24H" />
        </el-select>
        <span class="field-hint">Applies to all sessions opened with this profile.</span>
      </el-form-item>

      <!-- Collapsible Advanced Settings for custom Spark Key-Values -->
      <el-collapse v-model="activeCollapse" class="advanced-collapse">
        <el-collapse-item name="advanced" title="⚙️ Advanced Spark Configurations">
          <div class="custom-configs-header">
            <span>Additional Spark/Kyuubi properties (Key-Value)</span>
            <el-button size="small" type="primary" plain icon="Plus" @click="addConfigRow">
              Add Property
            </el-button>
          </div>

          <div
            v-for="(row, index) in form.customConfigs"
            :key="index"
            class="config-row">
            <el-input
              v-model="row.key"
              placeholder="spark.sql.shuffle.partitions"
              size="small"
              style="flex: 1" />
            <el-input
              v-model="row.value"
              placeholder="200"
              size="small"
              style="flex: 1" />
            <el-button
              size="small"
              type="danger"
              plain
              icon="Delete"
              circle
              @click="removeConfigRow(index)" />
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-form>

    <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleClose">Cancel</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          Save Engine Profile
        </el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
  import { ref, reactive, computed, watch } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage } from 'element-plus'
  import type { EngineProfile } from '@/api/notebook/types'
  import {
    createEngineProfile,
    deleteEngineProfile,
    listEngineProfiles,
    updateEngineProfile
  } from '@/api/notebook'

  const props = defineProps<{
    visible: boolean
    initialProfile?: EngineProfile | null
  }>()

  const emit = defineEmits<{
    (e: 'update:visible', value: boolean): void
    (e: 'save', profile: EngineProfile): void
    (e: 'change'): void
  }>()

  const dialogVisible = computed({
    get: () => props.visible,
    set: (val) => emit('update:visible', val)
  })

  const formRef = ref<FormInstance>()
  const saving = ref(false)
  const activeCollapse = ref<string[]>([])

  interface CustomConfigRow {
    key: string
    value: string
  }

  const form = reactive({
    profileId: '',
    name: '',
    driverMemory: '1g',
    driverCores: 1,
    executorMemory: '2g',
    executorCores: 1,
    executorInstances: 1,
    notebookRuntimeIdleTimeout: 'inherit',
    engineIdleTimeout: 'inherit',
    customConfigs: [] as CustomConfigRow[]
  })

  // Validation rules enforcing strict input boundaries
  const rules = reactive<FormRules>({
    name: [
      { required: true, message: 'Please enter an Engine Name', trigger: 'blur' },
      {
        pattern: /^[a-z0-9][-a-z0-9]*[a-z0-9]$/,
        message: 'Engine Name must contain only lowercase letters, numbers, and hyphens (e.g. heavy-engine)',
        trigger: 'blur'
      },
      { min: 3, max: 30, message: 'Length must be between 3 and 30 characters', trigger: 'blur' }
    ],
    driverMemory: [
      { required: true, message: 'Please enter Driver Memory', trigger: 'blur' },
      {
        pattern: /^\d+([gG]|[mM])$/,
        message: 'Invalid memory format. Use number + g or m (e.g. 2g, 4g, 1024m)',
        trigger: 'blur'
      }
    ],
    executorMemory: [
      { required: true, message: 'Please enter Executor Memory', trigger: 'blur' },
      {
        pattern: /^\d+([gG]|[mM])$/,
        message: 'Invalid memory format. Use number + g or m (e.g. 4g, 8g, 2048m)',
        trigger: 'blur'
      }
    ]
  })

  const parseProfileFromApi = (apiProfile: EngineProfile): EngineProfile => {
    const sparkConfig = apiProfile.sparkConfig || {}
    const customConfigs: Record<string, string> = {}
    for (const [k, v] of Object.entries(sparkConfig)) {
      if (![
        'spark.driver.memory',
        'spark.driver.cores',
        'spark.executor.memory',
        'spark.executor.cores',
        'spark.executor.instances'
      ].includes(k)) {
        customConfigs[k] = v
      }
    }
    return {
      ...apiProfile,
      name: apiProfile.name || apiProfile.subdomain,
      driverMemory: sparkConfig['spark.driver.memory'] || apiProfile.driverMemory || '1g',
      driverCores: sparkConfig['spark.driver.cores'] ? Number(sparkConfig['spark.driver.cores']) : (Number(apiProfile.driverCores) || 1),
      executorMemory: sparkConfig['spark.executor.memory'] || apiProfile.executorMemory || '2g',
      executorCores: sparkConfig['spark.executor.cores'] ? Number(sparkConfig['spark.executor.cores']) : (Number(apiProfile.executorCores) || 1),
      executorInstances: sparkConfig['spark.executor.instances'] ? Number(sparkConfig['spark.executor.instances']) : (Number(apiProfile.executorInstances) || 1),
      customConfigs: Object.keys(customConfigs).length > 0 ? customConfigs : apiProfile.customConfigs
    }
  }

  const savedProfiles = ref<EngineProfile[]>([])

  const loadSavedProfiles = async () => {
    try {
      const res = await listEngineProfiles()
      if (Array.isArray(res) && res.length > 0) {
        savedProfiles.value = res.map(parseProfileFromApi)
        return
      }
    } catch (e) {
      console.error('Failed to load engine profiles from backend:', e)
    }
    savedProfiles.value = [{
      name: 'default',
      profileId: 'default',
      subdomain: 'default',
      driverMemory: '1g',
      driverCores: 1,
      executorMemory: '2g',
      executorCores: 1,
      executorInstances: 1
    }]
  }

  watch(dialogVisible, (val) => {
    if (val) loadSavedProfiles()
  }, { immediate: true })

  function loadProfileForEdit(profile: EngineProfile) {
    const parsed = parseProfileFromApi(profile)
    form.profileId = parsed.profileId || ''
    form.name = parsed.name || ''
    form.driverMemory = parsed.driverMemory || '1g'
    form.driverCores = Number(parsed.driverCores) || 1
    form.executorMemory = parsed.executorMemory || '2g'
    form.executorCores = Number(parsed.executorCores) || 1
    form.executorInstances = parsed.executorInstances || 1
    form.notebookRuntimeIdleTimeout = parsed.notebookRuntimeIdleTimeout || 'inherit'
    form.engineIdleTimeout = parsed.engineIdleTimeout || 'inherit'
    if (parsed.customConfigs) {
      form.customConfigs = Object.entries(parsed.customConfigs).map(([key, value]) => ({
        key,
        value
      }))
    } else {
      form.customConfigs = []
    }
  }

  watch(
    () => props.initialProfile,
    (profile) => {
      if (profile) {
        loadProfileForEdit(profile)
      } else {
        resetForm()
      }
    },
    { immediate: true }
  )

  async function deleteProfile(profileId: string) {
    try {
      await deleteEngineProfile(profileId)
      savedProfiles.value = savedProfiles.value.filter((p) => p.profileId !== profileId)
      emit('change')
      ElMessage.success('Engine preset removed')
    } catch (e: any) {
      ElMessage.error(`Failed to delete engine profile: ${e?.message || e}`)
    }
  }

  function resetForm() {
    form.profileId = ''
    form.name = ''
    form.driverMemory = '1g'
    form.driverCores = 1
    form.executorMemory = '2g'
    form.executorCores = 1
    form.executorInstances = 1
    form.notebookRuntimeIdleTimeout = 'inherit'
    form.engineIdleTimeout = 'inherit'
    form.customConfigs = []
  }

  function addConfigRow() {
    form.customConfigs.push({ key: '', value: '' })
  }

  function removeConfigRow(index: number) {
    form.customConfigs.splice(index, 1)
  }

  function handleClose() {
    dialogVisible.value = false
  }

  async function handleSave() {
    if (!formRef.value) return
    await formRef.value.validate(async (valid) => {
      if (!valid) {
        ElMessage.error('Please fix the errors in the form before saving')
        return
      }

      const sparkConfig: Record<string, string> = {
        'spark.driver.memory': form.driverMemory,
        'spark.driver.cores': String(form.driverCores),
        'spark.executor.memory': form.executorMemory,
        'spark.executor.cores': String(form.executorCores),
        'spark.executor.instances': String(form.executorInstances)
      }

      for (const row of form.customConfigs) {
        const trimmedKey = row.key.trim()
        if (trimmedKey) {
          if (!trimmedKey.startsWith('spark.') && !trimmedKey.startsWith('kyuubi.')) {
            ElMessage.warning(`Custom config key '${trimmedKey}' should start with 'spark.' or 'kyuubi.'`)
          }
          sparkConfig[trimmedKey] = row.value.trim()
        }
      }

      saving.value = true
      try {
        const updated = form.profileId
          ? await updateEngineProfile(form.profileId, form.name, sparkConfig, {
            notebookRuntimeIdleTimeout: form.notebookRuntimeIdleTimeout,
            engineIdleTimeout: form.engineIdleTimeout
          })
          : await createEngineProfile(form.name, sparkConfig, {
            notebookRuntimeIdleTimeout: form.notebookRuntimeIdleTimeout,
            engineIdleTimeout: form.engineIdleTimeout
          })
        const parsed = parseProfileFromApi(updated)
        const existingIndex = savedProfiles.value.findIndex(
          (p) => p.profileId === parsed.profileId
        )
        if (existingIndex >= 0) {
          savedProfiles.value[existingIndex] = parsed
        } else {
          savedProfiles.value.push(parsed)
        }

        emit('save', parsed)
        emit('change')
        ElMessage.success(`Engine profile '${parsed.name}' saved successfully`)
        handleClose()
      } catch (e: any) {
        ElMessage.error(`Failed to save engine profile: ${e?.message || e}`)
      } finally {
        saving.value = false
      }
    })
  }
</script>

<style scoped>
  :deep(.el-form-item__label) {
    white-space: nowrap;
  }

  :deep(.el-form-item__content) {
    min-width: 0;
  }

  :deep(.el-divider__text) {
    white-space: nowrap;
  }

  .resource-input {
    width: 100%;
  }

  .field-hint {
    display: block;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    margin-top: 4px;
    line-height: 1.2;
  }

  .advanced-collapse {
    margin-top: 16px;
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .custom-configs-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
    font-size: 13px;
    color: var(--el-text-color-regular);
  }

  .config-row {
    display: flex;
    gap: 8px;
    align-items: center;
    margin-bottom: 8px;
  }
</style>
