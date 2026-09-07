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

package org.apache.kyuubi.server.notebook.api

import org.apache.kyuubi.server.notebook.api.NotebookLanguage.NotebookLanguage

/**
 * How a notebook's language is decided when it was not stated.
 *
 * A notebook used to be a bag of cells that each chose their own language, so rows and exported
 * documents written before the change carry no notebook-level value. Rather than refuse to open
 * them, the language is inferred once, here, and every caller - the store, import, clone - uses
 * the same rule so a notebook cannot be read as SQL in one path and Python in another.
 */
object NotebookLanguages {

  /**
   * The first CODE cell that names an executable language wins; a notebook with no such cell
   * (empty, or markdown only) is SQL.
   *
   * "First" means lowest position, not storage order, so the answer does not depend on how the
   * rows came back.
   */
  def infer(cells: Seq[NotebookCell]): NotebookLanguage =
    cells
      .filter(_.cellType == CellType.CODE)
      .sortBy(_.position)
      .flatMap(cell => NotebookLanguage.fromCellLanguage(cell.language))
      .headOption
      .getOrElse(NotebookLanguage.default)

  /** Same rule for the portable document form, where cell fields are plain strings. */
  def inferFromDocument(cells: Seq[NotebookDocumentCell]): NotebookLanguage =
    cells
      .filter(cell =>
        CellType.values.exists(t =>
          t == CellType.CODE && t.toString.equalsIgnoreCase(cell.cellType)))
      .sortBy(_.position)
      .flatMap(cell => NotebookLanguage.parse(cell.language))
      .headOption
      .getOrElse(NotebookLanguage.default)

  /**
   * Whether the notebook may still change language. Once a CODE cell holds anything, switching
   * would leave that source being interpreted by the wrong engine, so it is refused; an empty
   * notebook is free to change.
   */
  def canChangeLanguage(cells: Seq[NotebookCell]): Boolean =
    !cells.exists(cell => cell.cellType == CellType.CODE && cell.source.trim.nonEmpty)
}
