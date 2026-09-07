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

import { readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const webUiRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const indexHtml = await readFile(join(webUiRoot, 'dist', 'index.html'), 'utf8')
const stylesheetHrefs = [...indexHtml.matchAll(/href="([^"]+\.css)"/g)].map(
  (match) => match[1]
)

if (stylesheetHrefs.length === 0) {
  throw new Error('The built index.html does not import a stylesheet')
}

const entryCss = (
  await Promise.all(
    stylesheetHrefs.map((href) =>
      readFile(join(webUiRoot, 'dist', href.replace(/^\/ui\//, '')), 'utf8')
    )
  )
).join('\n')

const requiredSelectors = [
  '.cell-result-log',
  '.cell-stream-output',
  '.cell-stream-stderr',
  '.cell-rich-image',
  '.cell-rich-frame'
]
const missingSelectors = requiredSelectors.filter(
  (selector) => !entryCss.includes(selector)
)

if (missingSelectors.length > 0) {
  throw new Error(
    `Notebook styles are missing from the entry CSS: ${missingSelectors.join(
      ', '
    )}`
  )
}

console.log('Verified notebook result styles in the entry CSS')
