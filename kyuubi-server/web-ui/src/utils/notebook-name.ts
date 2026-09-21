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

const MAX_NOTEBOOK_NAME_LENGTH = 255

const pad = (value: number, length = 2): string =>
  value.toString().padStart(length, '0')

const timestamp = (now: Date): string => {
  return (
    [now.getFullYear(), pad(now.getMonth() + 1), pad(now.getDate())].join('-') +
    ` ${pad(now.getHours())}-${pad(now.getMinutes())}-${pad(
      now.getSeconds()
    )}`
  )
}

const appendSuffix = (name: string, suffix: string): string => {
  return `${name.slice(0, MAX_NOTEBOOK_NAME_LENGTH - suffix.length)}${suffix}`
}

/** Uses local time and milliseconds so consecutive clicks propose distinct names. */
const defaultNotebookName = (now = new Date()): string =>
  `New Notebook ${timestamp(now)}`

const defaultCloneNotebookName = (
  sourceName: string,
  now = new Date()
): string => {
  return appendSuffix(sourceName.trim(), ` (Copy ${timestamp(now)})`)
}

export { defaultCloneNotebookName, defaultNotebookName }
