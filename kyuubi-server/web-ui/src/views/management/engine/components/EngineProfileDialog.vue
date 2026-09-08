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
    v-model="visible"
    :title="isEdit ? 'Edit Engine Profile' : 'Create New Engine Profile'"
    width="680px"
    destroy-on-close
    :close-on-click-modal="false"
    @close="handleClose">
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="150px"
      label-position="right">
      <el-form-item label="Engine Name" prop="subdomain">
        <el-input
          v-model="form.subdomain"
          :disabled="isEdit"
          placeholder="e.g. heavy-engine, ml-cluster" />
        <span class="help-text">Unique identifier used by Spark engine subdomain and Zookeeper discovery</span>
      </el-form-item>

      <el-divider content-position="left">Standard Resource Allocations</el-divider>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="Driver Memory" prop="driverMemory">
            <el-input v-model="form.driverMemory" placeholder="e.g. 2g, 4g, 1024m" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="Driver Cores" prop="driverCores">
            <el-input-number
              v-model="form.driverCores"
              :min="0.5"
              :max="32"
              :step="0.5"
              style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="Executor Memory" prop="executorMemory">
            <el-input v-model="form.executorMemory" placeholder="e.g. 4g, 8g, 2048m" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="Executor Cores" prop="executorCores">
            <el-input-number
              v-model="form.executorCores"
              :min="1"
              :max="32"
              :step="1"
              style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="Executor Instances" prop="executorInstances">
        <el-input-number
          v-model="form.executorInstances"
          :min="1"
          :max="64"
          :step="1"
          style="width: 100%" />
      </el-form-item>

      <el-divider content-position="left">Advanced Spark Configurations</el-divider>
      
      <div class="custom-conf-box">
        <div
          v-for="(conf, idx) in customConfigs"
          :key="idx"
          class="conf-row">
          <el-input
            v-model="conf.key"
            placeholder="spark.sql.shuffle.partitions"
            style="flex: 1" />
          <el-input
            v-model="conf.value"
            placeholder="200"
            style="flex: 1; margin: 0 8px" />
          <el-button
            type="danger"
            icon="Delete"
            circle
            size="small"
            @click="removeConf(idx)" />
        </div>
        <el-button
          type="primary"
          link
          icon="Plus"
          style="margin-top: 6px"
          @click="addConf">
          Add Spark Configuration
        </el-button>
      </div>
    </el-form>

    <template #footer>
      <span class="dialog-footer">
        <el-button @click="visible = false">Cancel</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ isEdit ? 'Save Changes' : 'Create Profile' }}
        </el-button>
      </span>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
  import { ref, reactive, watch } from 'vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { ElMessage } from 'element-plus'

  const props = defineProps<{
    modelValue: boolean
    profileData?: any
  }>()

  const emit = defineEmits<{
    (e: 'update:modelValue', value: boolean): void
    (e: 'save', profile: { subdomain: string; sparkConfig: Record<string, string> }): void
  }>()

  const visible = ref(props.modelValue)
  const isEdit = ref(false)
  const saving = ref(false)
  const formRef = ref<FormInstance>()

  const form = reactive({
    subdomain: '',
    driverMemory: '1g',
    driverCores: 1,
    executorMemory: '2g',
    executorCores: 1,
    executorInstances: 1
  })

  const customConfigs = ref<Array<{ key: string; value: string }>>([])

  const rules: FormRules = {
    subdomain: [
      { required: true, message: 'Engine Name is required', trigger: 'blur' },
      {
        pattern: /^[a-z0-9][-a-z0-9]*[a-z0-9]$/,
        message: 'Must contain only lowercase letters, numbers, and hyphens (e.g. heavy-engine)',
        trigger: 'blur'
      },
      { min: 2, max: 63, message: 'Length must be between 2 and 63 characters', trigger: 'blur' }
    ],
    driverMemory: [
      { required: true, message: 'Please enter Driver Memory', trigger: 'blur' },
      {
        pattern: /^\d+([gG]|[mM]|[kK]|[tT]|[pP]|[gG][bB]|[mM][bB]|[kK][bB]|[tT][bB]|[pP][bB])$/,
        message: 'Invalid memory format (e.g. 2g, 4g, 1024m)',
        trigger: 'blur'
      }
    ],
    executorMemory: [
      { required: true, message: 'Please enter Executor Memory', trigger: 'blur' },
      {
        pattern: /^\d+([gG]|[mM]|[kK]|[tT]|[pP]|[gG][bB]|[mM][bB]|[kK][bB]|[tT][bB]|[pP][bB])$/,
        message: 'Invalid memory format (e.g. 4g, 8g, 2048m)',
        trigger: 'blur'
      }
    ]
  }

  watch(
    () => props.modelValue,
    (val) => {
      visible.value = val
      if (val) {
        if (props.profileData) {
          isEdit.value = true
          form.subdomain = props.profileData.subdomain || ''
          const sparkConfig = props.profileData.sparkConfig || {}
          form.driverMemory = sparkConfig['spark.driver.memory'] || props.profileData.driverMemory || '1g'
          form.driverCores = sparkConfig['spark.driver.cores'] ? Number(sparkConfig['spark.driver.cores']) : (Number(props.profileData.driverCores) || 1)
          form.executorMemory = sparkConfig['spark.executor.memory'] || props.profileData.executorMemory || '2g'
          form.executorCores = sparkConfig['spark.executor.cores'] ? Number(sparkConfig['spark.executor.cores']) : (Number(props.profileData.executorCores) || 1)
          form.executorInstances = sparkConfig['spark.executor.instances'] ? Number(sparkConfig['spark.executor.instances']) : (Number(props.profileData.executorInstances) || 1)
          
          const extras: Array<{ key: string; value: string }> = []
          const standardKeys = [
            'spark.driver.memory',
            'spark.driver.cores',
            'spark.executor.memory',
            'spark.executor.cores',
            'spark.executor.instances'
          ]
          Object.entries(sparkConfig).forEach(([k, v]) => {
            if (!standardKeys.includes(k)) {
              extras.push({ key: k, value: String(v) })
            }
          })
          customConfigs.value = extras
        } else {
          isEdit.value = false
          form.subdomain = ''
          form.driverMemory = '1g'
          form.driverCores = 1
          form.executorMemory = '2g'
          form.executorCores = 1
          form.executorInstances = 1
          customConfigs.value = []
        }
      }
    }
  )

  watch(
    () => visible.value,
    (val) => {
      emit('update:modelValue', val)
    }
  )

  const addConf = () => {
    customConfigs.value.push({ key: '', value: '' })
  }

  const removeConf = (idx: number) => {
    customConfigs.value.splice(idx, 1)
  }

  const handleClose = () => {
    formRef.value?.resetFields()
  }

  const handleSave = async () => {
    if (!formRef.value) return
    await formRef.value.validate((valid) => {
      if (!valid) {
        ElMessage.error('Please fix form validation errors before saving')
        return
      }

      saving.value = true
      const sparkConfig: Record<string, string> = {
        'spark.driver.memory': form.driverMemory,
        'spark.driver.cores': String(form.driverCores),
        'spark.executor.memory': form.executorMemory,
        'spark.executor.cores': String(form.executorCores),
        'spark.executor.instances': String(form.executorInstances)
      }
      customConfigs.value.forEach((c) => {
        const trimmedKey = c.key.trim()
        if (trimmedKey) {
          if (!trimmedKey.startsWith('spark.') && !trimmedKey.startsWith('kyuubi.')) {
            ElMessage.warning(`Key '${trimmedKey}' should start with 'spark.' or 'kyuubi.'`)
          }
          sparkConfig[trimmedKey] = c.value.trim()
        }
      })

      const result = {
        subdomain: form.subdomain.trim(),
        sparkConfig
      }

      emit('save', result)
      saving.value = false
      visible.value = false
    })
  }
</script>

<style scoped lang="scss">
  .help-text {
    font-size: 11px;
    color: #909399;
    margin-top: 4px;
    line-height: 1.2;
    display: block;
  }
  .custom-conf-box {
    margin-top: 4px;
    .conf-row {
      display: flex;
      align-items: center;
      margin-bottom: 8px;
    }
  }
</style>
