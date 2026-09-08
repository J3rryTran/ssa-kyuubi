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
  <div class="db-cell-wrapper">
    <div
      class="db-cell"
      :class="{
        'is-running': isRunning || isInitializing,
        'is-selected': isFocused,
        'is-markdown': cell.cellType === 'MARKDOWN'
      }"
      @click="isFocused = true">
      <!-- LEFT GUTTER -->
      <div class="db-cell-gutter">
        <el-tooltip v-if="isInitializing" content="Starting Engine..." placement="right">
          <span class="gutter-starting-indicator">
            <el-icon class="is-loading"><Loading /></el-icon>
          </span>
        </el-tooltip>
        <el-button
          v-else-if="isRunning"
          circle
          size="small"
          type="warning"
          class="gutter-run-btn"
          title="Stop execution"
          @click.stop="$emit('stop', cell)">
          <el-icon class="is-loading"><Loading /></el-icon>
        </el-button>
        <el-button
          v-else-if="isExecutable"
          circle
          size="small"
          class="gutter-run-btn"
          title="Run cell (Shift+Enter)"
          :disabled="readOnly || pythonUnavailable"
          @click.stop="triggerRun">
          <el-icon><CaretRight /></el-icon>
        </el-button>
        <span v-else class="gutter-md-icon">
          <el-icon><Document /></el-icon>
        </span>
        <span class="cell-index">[{{ cellIndex }}]</span>
      </div>

      <!-- CELL MAIN BODY -->
      <div class="db-cell-body">
        <!-- CELL HEADER BAR -->
        <div class="cell-top-bar">
          <div class="bar-left">
            <span class="cell-lang-tag">{{ languageMagic }}</span>
            <span v-if="executionTime" class="cell-duration">Took {{ executionTime }}</span>
            <span v-if="isInitializing" class="cell-starting-note">Starting Engine...</span>
            <span v-if="pythonUnavailable" class="cell-warning-note">
              (Python runtime not configured)
            </span>
          </div>
          <div class="bar-right">
            <el-button
              link
              size="small"
              icon="Top"
              title="Move Up"
              @click.stop="$emit('move-up', cell)" />
            <el-button
              link
              size="small"
              icon="Bottom"
              title="Move Down"
              @click.stop="$emit('move-down', cell)" />
            <el-button
              link
              size="small"
              icon="Delete"
              title="Delete Cell"
              :disabled="readOnly"
              @click.stop="$emit('remove', cell)" />
          </div>
        </div>

        <!-- CODE INPUT -->
        <div class="cell-editor-container">
          <el-input
            v-model="localSource"
            type="textarea"
            class="cell-textarea"
            :autosize="{ minRows: 2, maxRows: 30 }"
            :readonly="readOnly"
            :placeholder="placeholderText"
            @keydown.enter.ctrl="triggerRun"
            @keydown.enter.shift="triggerRun"
            @change="onSourceChange" />
        </div>

        <!-- MARKDOWN PREVIEW IF MARKDOWN -->
        <div
          v-if="cell.cellType === 'MARKDOWN' && localSource"
          class="cell-md-preview">
          <div class="md-content" v-html="renderedMarkdown" />
        </div>

        <!-- CELL OUTPUT -->
        <div v-if="execution || output" class="cell-output-container">
          <CellResult
            v-if="execution"
            :execution="execution"
            :schema="output?.schema"
            :rows="output?.rows"
            :has-more="output?.hasMore"
            :logs="output?.logs"
            :outputs="output?.outputs"
            @load-more="$emit('load-more', cell)" />
        </div>
      </div>
    </div>

    <!-- BETWEEN-CELL INSERTION BAR -->
    <div class="add-cell-divider">
      <div class="divider-line" />
      <div class="divider-buttons">
        <el-button
          size="small"
          round
          class="insert-btn"
          icon="Plus"
          @click="$emit('add-cell', 'CODE', cell.id)">
          Code
        </el-button>
        <el-button
          size="small"
          round
          class="insert-btn"
          icon="Plus"
          @click="$emit('add-cell', 'MARKDOWN', cell.id)">
          Text
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import CellResult from './CellResult.vue'
  import type { CellExecution, NotebookCell } from '@/api/notebook/types'
  import { TERMINAL_EXECUTION_STATES } from '@/api/notebook/types'
  import type { CellOutput } from '../use-notebook'

  const props = withDefaults(
    defineProps<{
      cell: NotebookCell
      execution?: CellExecution
      output?: CellOutput
      isInitializing?: boolean
      readOnly: boolean
      pythonEnabled: boolean
      notebookLanguage: string
      index?: number
    }>(),
    {
      index: 1,
      pythonEnabled: true,
      isInitializing: false
    }
  )

  const emit = defineEmits<{
    (e: 'run', cell: NotebookCell, source: string): void
    (e: 'stop', cell: NotebookCell): void
    (e: 'remove', cell: NotebookCell): void
    (e: 'load-more', cell: NotebookCell): void
    (e: 'save', cell: NotebookCell, changes: Record<string, string>): void
    (e: 'move-up', cell: NotebookCell): void
    (e: 'move-down', cell: NotebookCell): void
    (e: 'add-cell', type: 'CODE' | 'MARKDOWN', afterCellId: string): void
  }>()

  const localSource = ref(props.cell.source)
  const isFocused = ref(false)

  watch(
    () => props.cell.source,
    (value) => {
      localSource.value = value
    }
  )

  const cellIndex = computed(() => props.index || 1)

  const isExecutable = computed(() => props.cell.cellType === 'CODE')

  const isRunning = computed(() => {
    const state = props.execution?.state
    return Boolean(state && !TERMINAL_EXECUTION_STATES.includes(state))
  })

  const pythonUnavailable = computed(() => {
    return isExecutable.value && props.notebookLanguage === 'PYTHON' && !props.pythonEnabled
  })

  const languageMagic = computed(() => {
    if (props.cell.cellType === 'MARKDOWN') return '%md'
    return props.notebookLanguage === 'PYTHON' ? '%python' : '%sql'
  })

  const placeholderText = computed(() => {
    if (props.cell.cellType === 'MARKDOWN') {
      return '# Enter markdown documentation...'
    }
    return props.notebookLanguage === 'PYTHON'
      ? '# Enter Python / PySpark code here...'
      : '-- Enter SQL query here...'
  })

  const executionTime = computed(() => {
    if (!props.execution?.startedAt) return ''
    if (props.execution?.finishedAt) {
      const ms = props.execution.finishedAt - props.execution.startedAt
      return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`
    }
    return 'Running...'
  })

  const renderedMarkdown = computed(() => {
    const s = localSource.value || ''
    // Simple basic markdown formatting
    return s
      .replace(/^# (.*$)/gim, '<h2 style="margin: 6px 0">$1</h2>')
      .replace(/^## (.*$)/gim, '<h3 style="margin: 4px 0">$1</h3>')
      .replace(/\*\*(.*)\*\*/gim, '<b>$1</b>')
      .replace(/\*(.*)\*/gim, '<i>$1</i>')
      .replace(/\n/gim, '<br/>')
  })

  const triggerRun = (e?: Event) => {
    if (e) e.preventDefault()
    if (!props.readOnly && !pythonUnavailable.value) {
      emit('run', props.cell, localSource.value)
    }
  }

  const onSourceChange = () => {
    emit('save', props.cell, { source: localSource.value })
  }
</script>

<style scoped lang="scss">
  .db-cell-wrapper {
    position: relative;
    margin-bottom: 2px;

    .db-cell {
      display: flex;
      border: 1px solid #e1e4e8;
      border-radius: 6px;
      background: #ffffff;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
      transition: all 0.15s ease;

      &:hover,
      &.is-selected {
        border-color: #ff3621;
        box-shadow: 0 2px 8px rgba(255, 54, 33, 0.08);
      }

      &.is-running {
        border-color: #faad14;
        box-shadow: 0 2px 8px rgba(250, 173, 20, 0.12);
      }

      .db-cell-gutter {
        display: flex;
        flex-direction: column;
        align-items: center;
        width: 44px;
        padding: 8px 4px;
        background: #f8f9fa;
        border-right: 1px solid #f0f0f0;
        border-radius: 5px 0 0 5px;
        user-select: none;

        .gutter-run-btn {
          width: 24px;
          height: 24px;
          color: #262626;
          border-color: #d9d9d9;
          &:hover {
            color: #ff3621;
            border-color: #ff3621;
            background: #fff;
          }
        }

        .gutter-starting-indicator {
          display: flex;
          align-items: center;
          justify-content: center;
          width: 24px;
          height: 24px;
          color: #f59e0b;
          font-size: 17px;
        }

        .gutter-md-icon {
          color: #8c8c8c;
          font-size: 14px;
          margin-top: 4px;
        }

        .cell-index {
          margin-top: 8px;
          font-size: 10px;
          color: #bfbfbf;
          font-family: monospace;
        }
      }

      .db-cell-body {
        flex: 1;
        display: flex;
        flex-direction: column;
        overflow: hidden;

        .cell-top-bar {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 4px 12px;
          background: #fafafa;
          border-bottom: 1px solid #f0f0f0;

          .bar-left {
            display: flex;
            align-items: center;
            gap: 8px;

            .cell-lang-tag {
              font-family: 'JetBrains Mono', 'Fira Code', Menlo, monospace;
              font-size: 11px;
              font-weight: 700;
              color: #ff3621;
              background: #fff1f0;
              padding: 1px 6px;
              border-radius: 3px;
              border: 1px solid #ffccc7;
            }

            .cell-duration {
              font-size: 11px;
              color: #8c8c8c;
            }

            .cell-starting-note {
              color: #b45309;
              font-size: 11px;
              font-weight: 600;
            }

            .cell-warning-note {
              font-size: 11px;
              color: #fa8c16;
            }
          }

          .bar-right {
            display: flex;
            align-items: center;
            gap: 2px;
            opacity: 0.6;
            &:hover {
              opacity: 1;
            }
          }
        }

        .cell-editor-container {
          padding: 6px 12px;

          :deep(.cell-textarea) {
            .el-textarea__inner {
              border: none !important;
              box-shadow: none !important;
              padding: 4px 0 !important;
              font-family: 'JetBrains Mono', 'Fira Code', Menlo, Monaco, Consolas, monospace;
              font-size: 13px;
              line-height: 1.5;
              color: #1f2328;
              background: transparent;
            }
          }
        }

        .cell-md-preview {
          padding: 6px 12px 12px;
          border-top: 1px dashed #f0f0f0;
          color: #24292f;
          font-size: 14px;
          line-height: 1.6;
        }

        .cell-output-container {
          border-top: 1px solid #f0f0f0;
          padding: 8px 12px;
          background: #fafbfc;
        }
      }
    }

    /* HOVER ADD BUTTONS */
    .add-cell-divider {
      position: relative;
      height: 18px;
      display: flex;
      align-items: center;
      justify-content: center;
      opacity: 0;
      transition: opacity 0.2s ease;

      &:hover {
        opacity: 1;
      }

      .divider-line {
        position: absolute;
        width: 100%;
        height: 1px;
        background: #dcdfe6;
      }

      .divider-buttons {
        position: relative;
        z-index: 2;
        display: flex;
        gap: 6px;
        background: #ffffff;
        padding: 0 8px;

        .insert-btn {
          height: 22px;
          font-size: 11px;
          padding: 0 10px;
          background: #ffffff;
          border-color: #dcdfe6;
          color: #595959;
          &:hover {
            color: #ff3621;
            border-color: #ff3621;
          }
        }
      }
    }
  }
</style>
