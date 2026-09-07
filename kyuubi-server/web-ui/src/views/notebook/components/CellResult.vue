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
  <div class="cell-result">
    <div class="cell-result-status">
      <el-tag :type="stateType" size="small" effect="plain">
        {{ execution.state }}
      </el-tag>
      <span v-if="elapsed" class="cell-result-elapsed">{{ elapsed }}</span>
    </div>

    <p v-if="execution.errorMessage" class="cell-result-error">
      {{ execution.errorMessage }}
    </p>

    <el-tabs v-model="activeTab" type="card" class="cell-result-tabs">
      <el-tab-pane label="Result" name="result">
        <div v-if="rows && rows.length">
          <el-table :data="tableRows" size="small" border max-height="320">
            <el-table-column
              v-for="(column, index) in schema?.columns || []"
              :key="column.name"
              :label="`${column.name} (${column.dataType})`"
              :prop="`c${index}`"
              min-width="140">
              <template #default="scope">
                <!-- A SQL NULL is not the string "null"; showing them alike would mislead. -->
                <span v-if="scope.row[`c${index}`] === null" class="cell-null">
                  NULL
                </span>
                <span v-else>{{ scope.row[`c${index}`] }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="cell-result-more">
            <span>{{ rows.length }} row(s) loaded</span>
            <el-button
              v-if="hasMore"
              size="small"
              text
              @click="$emit('load-more')">
              Load more
            </el-button>
          </div>
        </div>
        <template v-else-if="hasOutputs">
          <template v-for="output in orderedOutputs" :key="output.sequence">
            <pre
              v-if="!isRichOutput(output)"
              class="cell-stream-output cell-output-item"
              :class="{ 'cell-stream-stderr': output.stream === 'stderr' }"
              >{{ output.data }}</pre
            >
            <!--
              Rich output is whatever the cell chose to emit. The server strips the obvious
              script vectors, but the isolation actually relied on is here: a sandboxed iframe
              with no allow-scripts and no allow-same-origin, so markup can neither run nor read
              this page.
            -->
            <div v-else class="cell-rich cell-output-item">
              <img
                v-if="
                  output.mimeType.startsWith('image/') &&
                  output.mimeType !== 'image/svg+xml'
                "
                :src="`data:${output.mimeType};base64,${output.data}`"
                class="cell-rich-image"
                alt="cell output" />
              <iframe
                v-else
                sandbox=""
                referrerpolicy="no-referrer"
                class="cell-rich-frame"
                :srcdoc="output.data"></iframe>
            </div>
          </template>
        </template>
        <p v-else class="cell-result-empty">
          {{ emptyMessage }}
        </p>
      </el-tab-pane>

      <el-tab-pane
        :label="`Log${logs?.length ? ` (${logs.length})` : ''}`"
        name="log">
        <pre v-if="logs && logs.length" class="cell-result-log">{{
          logs.join('\n')
        }}</pre>
        <p v-else class="cell-result-empty">No log output.</p>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue'
  import type {
    CellExecution,
    ExecutionOutput,
    ExecutionSchema
  } from '@/api/notebook/types'

  const props = defineProps<{
    execution: CellExecution
    schema?: ExecutionSchema
    rows?: string[][]
    hasMore?: boolean
    logs?: string[]
    outputs?: ExecutionOutput[]
  }>()

  const orderedOutputs = computed(() =>
    [...(props.outputs || [])].sort(
      (left, right) => left.sequence - right.sequence
    )
  )

  const hasOutputs = computed(() => orderedOutputs.value.length > 0)

  /** Markup and images are framed; streams and all other MIME types remain readable text. */
  const isRichOutput = (output: ExecutionOutput) =>
    output.mimeType === 'text/html' || output.mimeType.startsWith('image/')

  defineEmits<{ (e: 'load-more'): void }>()

  const activeTab = ref('result')

  const stateType = computed(() => {
    switch (props.execution.state) {
      case 'SUCCEEDED':
        return 'success'
      case 'FAILED':
      case 'LOST':
        return 'danger'
      case 'CANCELED':
        return 'info'
      default:
        return 'warning'
    }
  })

  const elapsed = computed(() => {
    const { startedAt, finishedAt } = props.execution
    if (!startedAt) return ''
    const end = finishedAt || Date.now()
    return `${((end - startedAt) / 1000).toFixed(1)}s`
  })

  /** Element Plus tables want objects, so positional rows are keyed by column index. */
  const tableRows = computed(() =>
    (props.rows || []).map((row) => {
      const record: Record<string, string | null> = {}
      row.forEach((value, index) => {
        record[`c${index}`] = value
      })
      return record
    })
  )

  const emptyMessage = computed(() => {
    if (props.execution.state === 'SUCCEEDED') {
      return props.execution.language === 'PYTHON'
        ? 'Execution completed.'
        : 'The statement produced no rows.'
    }
    if (props.execution.errorMessage) return 'The statement failed.'
    return 'Waiting for results…'
  })
</script>

<style scoped>
  .cell-result {
    border-top: 1px solid var(--el-border-color-lighter);
    padding: 8px 12px 4px;
  }

  .cell-result-status {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
  }

  .cell-result-elapsed {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .cell-result-error {
    color: var(--el-color-danger);
    white-space: pre-wrap;
    margin: 4px 0;
  }

  .cell-result-empty {
    color: var(--el-text-color-secondary);
    margin: 8px 0;
  }

  .cell-result-log,
  .cell-stream-output {
    display: block;
    width: 100%;
    min-height: 32px;
    max-height: 320px;
    overflow: auto;
    box-sizing: border-box;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 4px;
    background: var(--el-fill-color-lighter, #fafafa);
    color: var(--el-text-color-primary, #303133);
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas,
      'Liberation Mono', 'Courier New', monospace;
    font-size: 12px;
    line-height: 1.5;
    margin: 0 0 8px;
    padding: 8px 10px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }

  .cell-stream-output {
    min-height: 32px;
  }

  .cell-stream-stderr {
    color: var(--el-color-danger, #f56c6c);
  }

  .cell-result-more {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    padding: 4px 0;
  }

  .cell-rich {
    margin: 8px 0;
  }

  .cell-rich-image {
    display: block;
    max-width: 100%;
    height: auto;
  }

  .cell-rich-frame {
    display: block;
    width: 100%;
    height: 320px;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 4px;
    background: #fff;
  }

  .cell-null {
    color: var(--el-text-color-secondary);
    font-style: italic;
  }
</style>
