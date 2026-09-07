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

import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CellResult from './CellResult.vue'
import type { CellExecution, ExecutionOutput } from '@/api/notebook/types'

const execution = (
  language: 'SQL' | 'PYTHON',
  changes: Partial<CellExecution> = {}
): CellExecution =>
  ({
    id: 'execution-1',
    notebookId: 'notebook-1',
    notebookSessionId: 'session-1',
    runtimeId: 'runtime-1',
    cellId: 'cell-1',
    language,
    source: '',
    state: 'SUCCEEDED',
    submittedAt: 1,
    startedAt: 1,
    finishedAt: 2,
    errorCode: null,
    errorMessage: null,
    ...changes
  } as CellExecution)

const output = (
  sequence: number,
  stream: 'stdout' | 'stderr',
  data: string
): ExecutionOutput => ({
  sequence,
  outputType: 'STREAM',
  stream,
  mimeType: 'text/plain',
  data
})

const richOutput = (
  sequence: number,
  mimeType: string,
  data: string
): ExecutionOutput => ({
  sequence,
  outputType: 'DISPLAY_DATA',
  stream: null,
  mimeType,
  data
})

const mountResult = (props: Record<string, unknown>) =>
  mount(CellResult, {
    props: { execution: execution('PYTHON'), ...props },
    global: {
      stubs: {
        ElTag: { template: '<span><slot /></span>' },
        ElTabs: { template: '<div><slot /></div>' },
        ElTabPane: {
          props: ['label', 'name'],
          template:
            '<section class="tab-pane" :data-name="name">{{ label }}<slot /></section>'
        },
        ElTable: { template: '<div class="el-table"><slot /></div>' },
        ElTableColumn: { template: '<div />' },
        ElButton: { template: '<button><slot /></button>' }
      }
    }
  })

describe('CellResult', () => {
  it('renders stdout and stderr in sequence inside Result with no Output tab', () => {
    const wrapper = mountResult({
      outputs: [
        output(2, 'stderr', 'problem\n'),
        output(1, 'stdout', 'hello\n')
      ]
    })

    expect(
      wrapper.findAll('.tab-pane').map((pane) => pane.attributes('data-name'))
    ).toEqual(['result', 'log'])
    expect(wrapper.text()).not.toContain('Output (')
    const items = wrapper.findAll('.cell-output-item')
    expect(items.map((item) => item.text())).toEqual(['hello', 'problem'])
    expect(items[1].classes()).toContain('cell-stream-stderr')
    expect((wrapper.vm as unknown as { activeTab: string }).activeTab).toBe(
      'result'
    )
  })

  it('keeps stream and rich blocks in sequence order', () => {
    const wrapper = mountResult({
      outputs: [
        richOutput(3, 'image/png', 'base64-image'),
        richOutput(2, 'text/html', '<strong>two</strong>'),
        output(1, 'stdout', 'one')
      ]
    })

    const items = wrapper.findAll('.cell-output-item')
    expect(items.map((item) => item.classes()[0])).toEqual([
      'cell-stream-output',
      'cell-rich',
      'cell-rich'
    ])
    expect(items[1].find('iframe').attributes('srcdoc')).toBe(
      '<strong>two</strong>'
    )
    expect(items[2].find('img').attributes('src')).toBe(
      'data:image/png;base64,base64-image'
    )
  })

  it('shows a failed execution error and stderr traceback in Result', () => {
    const wrapper = mountResult({
      execution: execution('PYTHON', {
        state: 'FAILED',
        errorMessage: 'Python execution failed'
      }),
      outputs: [output(1, 'stderr', 'Traceback\nZeroDivisionError')]
    })

    expect(wrapper.find('.cell-result-error').text()).toBe(
      'Python execution failed'
    )
    expect(wrapper.find('.cell-stream-stderr').text()).toContain(
      'ZeroDivisionError'
    )
    expect((wrapper.vm as unknown as { activeTab: string }).activeTab).toBe(
      'result'
    )
  })

  it('keeps the SQL table in Result and does not replace it with outputs', () => {
    const wrapper = mountResult({
      execution: execution('SQL'),
      schema: { columns: [{ name: 'value', dataType: 'INT', position: 0 }] },
      rows: [['1']],
      outputs: [output(1, 'stdout', 'not shown ahead of rows')]
    })

    expect(wrapper.find('.el-table').exists()).toBe(true)
    expect(wrapper.find('.cell-output-item').exists()).toBe(false)
  })

  it('uses language-specific empty messages only without rows or outputs', () => {
    const sql = mountResult({
      execution: execution('SQL'),
      rows: [],
      outputs: []
    })
    const python = mountResult({ rows: [], outputs: [] })

    expect(sql.text()).toContain('The statement produced no rows.')
    expect(python.text()).toContain('Execution completed.')
  })

  it('keeps log lines in the Log pane', () => {
    const wrapper = mountResult({ logs: ['line one', 'line two'] })

    expect(wrapper.find('[data-name="log"] .cell-result-log').text()).toContain(
      'line one\nline two'
    )
  })
})
