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
  <div class="editor">
    <div class="editor-toolbar">
      <div class="toolbar-left">
        <el-button
          :disabled="!param.engineType || !editorVariables.content"
          :loading="resultLoading"
          type="primary"
          class="run-sql-btn"
          icon="VideoPlay"
          @click="handleQuerySql">
          {{ $t('operation.run') }}
        </el-button>

        <div class="toolbar-divider" />

        <!-- Engine Profile Selector -->
        <el-tooltip
          :content="selectedProfileSpecs"
          placement="bottom"
          :disabled="!selectedProfileSpecs">
          <el-select
            v-model="selectedEngine"
            style="width: 280px"
            placeholder="Select Engine Profile"
            popper-class="engine-profile-dropdown"
            @change="handleEngineChange">
            <el-option
              v-for="p in engineProfiles"
              :key="p.subdomain"
              :label="p.name"
              :value="p.subdomain"
              class="engine-profile-option">
              <div class="option-content">
                <div class="option-header">
                  <span class="profile-name">{{ p.name }}</span>
                  <span class="profile-subdomain">{{ p.subdomain }}</span>
                </div>
                <div class="option-specs">
                  <el-icon class="spec-icon"><Cpu /></el-icon>
                  <span>{{ formatSpecs(p) }}</span>
                </div>
              </div>
            </el-option>
          </el-select>
        </el-tooltip>

        <el-select
          v-model="limit"
          style="width: 125px"
          placeholder="Limit">
          <el-option :value="10" label="Limit: 10" />
          <el-option :value="50" label="Limit: 50" />
          <el-option :value="100" label="Limit: 100" />
          <el-option :value="500" label="Limit: 500" />
          <el-option :value="1000" label="Limit: 1000" />
        </el-select>
      </div>

      <div class="toolbar-right">
        <el-button size="small" icon="MagicStick" @click="handleFormatSql">
          Format SQL
        </el-button>
        <el-button size="small" icon="Delete" @click="editorVariables.content = ''">
          Clear
        </el-button>
      </div>
    </div>
    <div ref="box" class="box">
      <div ref="sqlEditor" class="sqlEditor">
        <MonacoEditor
          ref="monacoEditor"
          v-model="editorVariables.content"
          :language="editorVariables.language"
          :theme="theme"
          @editor-mounted="editorMounted"
          @change="handleContentChange"
          @editor-save="editorSave" />
      </div>
      <div ref="resizer" class="resizer"></div>
      <div ref="queryResult" class="queryResult">
        <el-tabs
          v-model="activeTab"
          type="card"
          class="result-el-tabs"
          :class="{ 'hide-query-detail': !showQueryDetail.valueOf() }">
          <el-tab-pane
            v-loading="resultLoading"
            :label="`${$t('result')}${
              sqlResult?.length ? ` (${sqlResult?.length})` : ''
            }`"
            name="result">
            <Result :data="sqlResult" :error-messages="errorMessages" />
          </el-tab-pane>
          <el-tab-pane v-loading="logLoading" :label="$t('log')" name="log">
            <Log :data="sqlLog" />
          </el-tab-pane>
        </el-tabs>

        <div style="position: absolute; right: 0; top: 0">
          <div class="el-tabs__item" @click="minimizeQueryResultTab">
            <el-icon>
              <ArrowDown />
            </el-icon>
          </div>
          <div class="el-tabs__item" @click="maximizeQueryResultTab">
            <el-icon>
              <ArrowUp />
            </el-icon>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import MonacoEditor from '@/components/monaco-editor/index.vue'
  import Result from './Result.vue'
  import Log from './Log.vue'
  import { ref, reactive, onUnmounted, toRaw, onMounted, computed } from 'vue'
  import type { Ref } from 'vue'
  import * as monaco from 'monaco-editor'
  import { format } from 'sql-formatter'
  import { ElMessage } from 'element-plus'
  import { useI18n } from 'vue-i18n'
  import {
    openSession,
    closeSession,
    runSql,
    getSqlRowset,
    getSqlMetadata,
    getLog,
    closeOperation
  } from '@/api/editor'
  import type {
    IResponse,
    ISqlResult,
    IFields,
    ILog,
    IErrorMessage,
    IError
  } from './types'

  import { listEngineProfiles } from '@/api/notebook'
  import type { EngineProfile } from '@/api/notebook/types'

  const { t } = useI18n()
  const param = reactive({
    engineType: 'SPARK_SQL'
  })
  const limit = ref(10)
  const engineProfiles = ref<EngineProfile[]>([])
  const selectedEngine = ref('default')

  const parseProfile = (apiProfile: EngineProfile): EngineProfile => {
    const sparkConfig = apiProfile.sparkConfig || {}
    const isDefault = apiProfile.subdomain === 'default'
    return {
      ...apiProfile,
      name: apiProfile.name || apiProfile.subdomain,
      driverMemory: sparkConfig['spark.driver.memory'] || apiProfile.driverMemory || (isDefault ? '1g' : ''),
      executorMemory: sparkConfig['spark.executor.memory'] || apiProfile.executorMemory || (isDefault ? '2g' : ''),
      driverCores: sparkConfig['spark.driver.cores'] ? Number(sparkConfig['spark.driver.cores']) : apiProfile.driverCores || (isDefault ? 1 : undefined),
      executorCores: sparkConfig['spark.executor.cores'] ? Number(sparkConfig['spark.executor.cores']) : apiProfile.executorCores || (isDefault ? 1 : undefined),
      executorInstances: sparkConfig['spark.executor.instances'] ? Number(sparkConfig['spark.executor.instances']) : apiProfile.executorInstances || (isDefault ? 1 : undefined)
    }
  }

  const loadEngineProfiles = async () => {
    try {
      const res = await listEngineProfiles()
      if (Array.isArray(res) && res.length > 0) {
        const mapped: EngineProfile[] = res.map(parseProfile)
        if (!mapped.some((p) => p.subdomain === 'default')) {
          mapped.unshift({
            name: 'default',
            subdomain: 'default',
            driverMemory: '1g',
            driverCores: 1,
            executorMemory: '2g',
            executorCores: 1,
            executorInstances: 1
          })
        }
        engineProfiles.value = mapped
      } else {
        engineProfiles.value = [
          {
            name: 'default',
            subdomain: 'default',
            driverMemory: '1g',
            driverCores: 1,
            executorMemory: '2g',
            executorCores: 1,
            executorInstances: 1
          }
        ]
      }
    } catch {
      engineProfiles.value = [
        {
          name: 'default',
          subdomain: 'default',
          driverMemory: '1g',
          driverCores: 1,
          executorMemory: '2g',
          executorCores: 1,
          executorInstances: 1
        }
      ]
    }
    if (engineProfiles.value.length > 0 && !engineProfiles.value.some((p) => p.subdomain === selectedEngine.value)) {
      selectedEngine.value = engineProfiles.value[0].subdomain
    }
  }

  const formatSpecs = (p: any): string => {
    const sparkConfig = p?.sparkConfig || {}
    const isDefault = p?.subdomain === 'default'
    const driver = sparkConfig['spark.driver.memory'] || p?.driverMemory || (isDefault ? '1g' : undefined)
    const exec = sparkConfig['spark.executor.memory'] || p?.executorMemory || (isDefault ? '2g' : undefined)
    const cores = sparkConfig['spark.executor.cores'] || p?.executorCores || (isDefault ? 1 : undefined)
    const inst = sparkConfig['spark.executor.instances'] || p?.executorInstances || (isDefault ? 1 : undefined)

    if (!driver && !exec && !cores && !inst) {
      if (p?.subdomain === 'default') {
        return 'Default cluster compute settings'
      }
      return 'Standard cluster settings'
    }

    const parts: string[] = []
    if (driver) parts.push(`Driver: ${String(driver).toUpperCase()}`)
    if (exec) parts.push(`Exec: ${String(exec).toUpperCase()}`)
    const execDetails: string[] = []
    if (cores) execDetails.push(`${cores} cores`)
    if (inst) execDetails.push(`${inst} inst`)
    if (execDetails.length > 0) {
      parts.push(`(${execDetails.join(', ')})`)
    }
    return parts.join(' • ')
  }

  const selectedProfileSpecs = computed(() => {
    const found = engineProfiles.value.find((p) => p.subdomain === selectedEngine.value)
    return found ? formatSpecs(found) : ''
  })

  /**
   * The SQL Editor uses the generic Sessions API rather than the Notebook runtime API, so it
   * must carry the selected profile's Spark config and Kyuubi subdomain itself.
   */
  const selectedSessionConfigs = (): Record<string, string> => {
    const profile = engineProfiles.value.find(
      (candidate) => candidate.subdomain === selectedEngine.value
    )
    const subdomain = selectedEngine.value || 'default'
    return {
      ...(profile?.sparkConfig || {}),
      'kyuubi.engine.type': param.engineType,
      // Keep both spellings for the mixed Kyuubi versions/components in this deployment.
      'kyuubi.engine.share.level.subdomain': subdomain,
      'kyuubi.engine.share.level.sub.domain': subdomain
    }
  }

  const handleEngineChange = async (_val: string) => {
    param.engineType = 'SPARK_SQL'
    // A Kyuubi session is bound to its Engine at creation time. Reusing it after a selection
    // change would continue sending SQL to the previous profile's Driver.
    if (sessionIdentifier.value) {
      const previousSession = sessionIdentifier.value
      sessionIdentifier.value = ''
      try {
        await closeSession(previousSession)
        ElMessage.info('SQL Editor session closed; the next run will use the selected Engine Profile')
      } catch (error) {
        // Keep the new selection usable even when cleanup races with a completed operation; the
        // previous session remains eligible for Kyuubi idle cleanup.
        console.warn('Failed to close the previous SQL Editor session:', error)
      }
    }
  }

  const handleFormatSql = () => {
    if (editorVariables.content) {
      try {
        editorVariables.content = format(editorVariables.content, { language: 'spark' })
      } catch (e) {
        try {
          editorVariables.content = format(editorVariables.content)
        } catch (_) {}
      }
    }
  }

  const sqlResult: Ref<any[] | null> = ref(null)
  const monacoEditor = ref()
  const sqlLog = ref('')
  const activeTab = ref('result')
  const resultLoading = ref(false)
  const logLoading = ref(false)
  const sessionIdentifier = ref('')
  const theme = ref('customTheme')
  const errorMessages: Ref<IErrorMessage[]> = ref([])
  const editorVariables = reactive({
    editor: {} as any,
    language: 'sql',
    content: '',
    options: {}
  })

  const sqlEditor = ref()
  const queryResult = ref()
  const box = ref()
  const resizer = ref()

  const showQueryDetail = ref(true)
  const sqlEditorMinHeight = 64
  const resizerHeight = 10
  const queryResultHiddenThreshold = 210

  onMounted(() => {
    handleResizerDrag()
    loadEngineProfiles()
  })

  function setEditorAndResultHeights(
    sqlEditorHeight: number,
    queryResultHeight: number
  ): void {
    sqlEditor.value.style.height = `${sqlEditorHeight}px`
    queryResult.value.style.height = `${queryResultHeight}px`
  }

  function maximizeQueryResultTab(): void {
    showQueryDetail.value = true
    const sqlEditorHeight = sqlEditorMinHeight
    const queryResultHeight =
      box.value.clientHeight - sqlEditorMinHeight - resizerHeight
    setEditorAndResultHeights(sqlEditorHeight, queryResultHeight)
  }

  function minimizeQueryResultTab(): void {
    showQueryDetail.value = false
    const sqlEditorHeight =
      box.value.clientHeight - resizerHeight - sqlEditorMinHeight
    setEditorAndResultHeights(sqlEditorHeight, 0)
  }

  function handleResizerDrag(): void {
    const onMouseDown = (event: MouseEvent): void => {
      const initialMouseY = event.clientY
      const initialResizerTop = resizer.value.offsetTop

      const onMouseMove = (moveEvent: MouseEvent): void => {
        const currentMouseY = moveEvent.clientY
        const distanceMoved =
          initialResizerTop + (currentMouseY - initialMouseY)
        const newSqlEditorHeight = Math.max(distanceMoved, sqlEditorMinHeight)
        const newQueryResultHeight =
          box.value.clientHeight - distanceMoved - resizerHeight
        if (newQueryResultHeight < queryResultHiddenThreshold) {
          minimizeQueryResultTab()
        } else {
          showQueryDetail.value = true
          resizer.value.style.top = `${distanceMoved}px`
          setEditorAndResultHeights(newSqlEditorHeight, newQueryResultHeight)
        }
      }

      const onMouseUp = (): void => {
        document.removeEventListener('mousemove', onMouseMove)
        document.removeEventListener('mouseup', onMouseUp)
        if (resizer.value.releaseCapture) {
          resizer.value.releaseCapture()
        }
      }

      document.addEventListener('mousemove', onMouseMove)
      document.addEventListener('mouseup', onMouseUp)
      if (resizer.value.setCapture) {
        resizer.value.setCapture()
      }
      event.preventDefault()
    }
    resizer.value.addEventListener('mousedown', onMouseDown)
  }

  const editorMounted = (editor: monaco.editor.IStandaloneCodeEditor) => {
    editorVariables.editor = editor
  }
  const handleFormat = () => {
    toRaw(editorVariables.editor).setValue(
      format(toRaw(editorVariables.editor).getValue())
    )
  }

  const editorSave = () => {
    handleFormat()
  }

  const handleContentChange = (value: string) => {
    editorVariables.content = value
  }

  const handleQuerySql = async () => {
    resultLoading.value = true
    logLoading.value = true
    errorMessages.value = []

    if (!sessionIdentifier.value) {
      const openSessionResponse: IResponse = await openSession({
        configs: selectedSessionConfigs()
      }).catch(catchSessionError)
      if (!openSessionResponse) return
      sessionIdentifier.value = openSessionResponse.identifier
    }
    const selectValue = monacoEditor.value.getSelectValue()
    const runSqlResponse: IResponse = await runSql(
      {
        statement: selectValue || editorVariables.content,
        runAsync: false
      },
      sessionIdentifier.value
    ).catch(catchSessionError)
    if (!runSqlResponse) return

    const getSqlResultPromise = Promise.all([
      getSqlRowset({
        operationHandleStr: runSqlResponse.identifier,
        fetchorientation: 'FETCH_NEXT',
        maxrows: limit.value
      }).catch((err: IError) => {
        catchOperationError(err, t('message.get_sql_result_failed'))
      }),
      getSqlMetadata({
        operationHandleStr: runSqlResponse.identifier
      }).catch((err: IError) =>
        catchOperationError(err, t('message.get_sql_metadata_failed'))
      )
    ])
      .then((result) => {
        sqlResult.value = result[0]?.rows?.map((row: IFields) => {
          const map: { [key: string]: any } = {}
          row.fields?.forEach(({ value }: ISqlResult, index: number) => {
            map[result[1].columns[index]?.columnName] = value
          })
          return map
        })
      })
      .finally(() => {
        resultLoading.value = false
      })

    const getSqlLogPromise = getLog(runSqlResponse.identifier)
      .then((res: ILog) => {
        sqlLog.value = res?.logRowSet?.join('\r\n')
      })
      .catch((err: IError) => {
        postError(err, t('message.get_sql_log_failed'))
        sqlLog.value = ''
      })
      .finally(() => {
        logLoading.value = false
      })

    Promise.all([getSqlResultPromise, getSqlLogPromise]).then(() =>
      closeOperation(runSqlResponse.identifier)
    )
  }

  const postError = (err: IError, title = t('message.run_sql_failed')) => {
    errorMessages.value.push({
      title,
      description: err?.response?.data?.message || err?.message || ''
    })
    ElMessage({
      message: title,
      type: 'error'
    })
  }

  const catchSessionError = (err: IError) => {
    sqlResult.value = []
    sqlLog.value = ''
    postError(err)
    resultLoading.value = false
    logLoading.value = false
  }

  const catchOperationError = (err: IError, title: string) => {
    postError(err, title)
    sqlResult.value = []
  }

  const handleChangeLimit = (command: number) => {
    limit.value = command
  }

  const customMonacoEditorTheme = () => {
    monaco.editor.defineTheme(theme.value, {
      base: 'vs',
      inherit: true,
      rules: [],
      colors: {
        'editor.foreground': '#000000',
        'editor.background': '#ffffff',
        'editor.lineHighlightBackground': '#f6f6f6',
        'editorGutter.background': '#e2e2e2'
      }
    })
    monaco.editor.setTheme(theme.value)
  }
  customMonacoEditorTheme()

  onUnmounted(() => {
    if (sessionIdentifier.value) {
      closeSession(sessionIdentifier.value)
    }
  })
</script>

<style lang="scss" scoped>
  .editor {
    .editor-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      margin-bottom: 10px;
      background: #ffffff;
      border: 1px solid #e4e7ed;
      border-radius: 6px;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);

      .toolbar-left,
      .toolbar-right {
        display: flex;
        align-items: center;
        gap: 10px;
      }

      .toolbar-divider {
        width: 1px;
        height: 20px;
        background: #dcdfe6;
        margin: 0 4px;
      }

      .run-sql-btn {
        background: #0e8a16;
        border-color: #0e8a16;
        font-weight: 600;
        padding: 8px 16px;
        &:hover {
          background: #0c7513;
          border-color: #0c7513;
        }
      }
    }

    .box {
      height: calc(100vh - 205px);
    }

    .sqlEditor {
      width: 100%;
      height: calc(60% - 10px);
      background: #ffffff;
      float: left;
      box-sizing: border-box;
    }

    .resizer {
      cursor: row-resize;
      float: left;
      width: 100%;
      height: 2px;
      background-color: #e4e7ed;
      margin-top: 6px;
      margin-bottom: 6px;
    }

    .queryResult {
      position: relative;
      float: left;
      width: 100%;
      height: calc(32% - 10px);
      box-sizing: border-box;
    }
  }

  :deep(.hide-query-detail) {
    .el-tabs__content {
      display: none;
    }
  }
</style>

<style lang="scss">
  .engine-profile-dropdown {
    min-width: 340px !important;

    .el-select-dropdown__item {
      height: auto !important;
      padding: 9px 12px !important;
      line-height: normal !important;

      &.hover,
      &:hover {
        background-color: #f6f8fa;
      }

      &.selected {
        background-color: #fff0ed;
        color: #ff3621;
        font-weight: normal;

        .profile-name {
          color: #ff3621 !important;
        }
      }
    }

    .option-content {
      display: flex;
      flex-direction: column;
      gap: 4px;
      width: 100%;

      .option-header {
        display: flex;
        justify-content: space-between;
        align-items: center;

        .profile-name {
          font-weight: 600;
          color: #1f2328;
          font-size: 13px;
        }

        .profile-subdomain {
          color: #8c959f;
          font-size: 11px;
          background: #f6f8fa;
          padding: 1px 6px;
          border-radius: 4px;
          font-family: monospace;
        }
      }

      .option-specs {
        display: flex;
        align-items: center;
        gap: 5px;
        font-size: 11px;
        color: #57606a;

        .spec-icon {
          font-size: 12px;
          color: #ff3621;
        }
      }
    }
  }
</style>
