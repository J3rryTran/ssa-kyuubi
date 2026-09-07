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

import org.apache.kyuubi.KyuubiFunSuite

/**
 * The rules that let notebooks written before they had a language keep working, and that stop a
 * language from being swapped out from under existing code.
 */
class NotebookLanguagesSuite extends KyuubiFunSuite {

  private def cell(
      position: Int,
      cellType: CellType.Value,
      language: CellLanguage.Value,
      source: String = ""): NotebookCell = NotebookCell(
    id = s"cell-$position",
    notebookId = "nb",
    position = position,
    cellType = cellType,
    language = language,
    source = source,
    metadata = Map.empty,
    configuration = Map.empty,
    createdAt = 0L,
    updatedAt = 0L,
    version = 1L)

  private def documentCell(
      position: Int,
      cellType: CellType.Value,
      language: String): NotebookDocumentCell =
    NotebookDocumentCell(position, cellType.toString, language, "", Map.empty, Map.empty)

  test("the first CODE cell decides the language") {
    val cells = Seq(
      cell(0, CellType.MARKDOWN, CellLanguage.MARKDOWN),
      cell(1, CellType.CODE, CellLanguage.PYTHON),
      cell(2, CellType.CODE, CellLanguage.SQL))
    assert(NotebookLanguages.infer(cells) === NotebookLanguage.PYTHON)
  }

  test("position decides which CODE cell is first, not the order rows came back") {
    val cells = Seq(
      cell(2, CellType.CODE, CellLanguage.SQL),
      cell(1, CellType.CODE, CellLanguage.PYTHON))
    assert(NotebookLanguages.infer(cells) === NotebookLanguage.PYTHON)
  }

  test("a notebook with no usable CODE cell falls back to SQL") {
    assert(NotebookLanguages.infer(Seq.empty) === NotebookLanguage.SQL)
    assert(NotebookLanguages.infer(Seq(cell(0, CellType.MARKDOWN, CellLanguage.MARKDOWN))) ===
      NotebookLanguage.SQL)
    // MARKDOWN is not an executable language, so a CODE cell carrying it is ignored rather than
    // making the notebook markdown.
    assert(NotebookLanguages.infer(Seq(cell(0, CellType.CODE, CellLanguage.MARKDOWN))) ===
      NotebookLanguage.SQL)
  }

  test("the same rule applies to the portable document form") {
    assert(NotebookLanguages.inferFromDocument(Seq(
      documentCell(0, CellType.MARKDOWN, "MARKDOWN"),
      documentCell(1, CellType.CODE, "PYTHON"))) === NotebookLanguage.PYTHON)
    // Case and stray whitespace come from hand-edited files and ipynb metadata.
    assert(NotebookLanguages.inferFromDocument(Seq(
      documentCell(0, CellType.CODE, " python "))) === NotebookLanguage.PYTHON)
    assert(NotebookLanguages.inferFromDocument(Seq(
      documentCell(0, CellType.CODE, "nonsense"))) === NotebookLanguage.SQL)
    assert(NotebookLanguages.inferFromDocument(Seq.empty) === NotebookLanguage.SQL)
  }

  test("the language may only change while no code has been written") {
    assert(NotebookLanguages.canChangeLanguage(Seq.empty))
    assert(NotebookLanguages.canChangeLanguage(Seq(cell(0, CellType.CODE, CellLanguage.SQL))))
    // Whitespace is not content.
    assert(NotebookLanguages.canChangeLanguage(
      Seq(cell(0, CellType.CODE, CellLanguage.SQL, "  \n\t "))))
    // A markdown cell with text does not pin the language.
    assert(NotebookLanguages.canChangeLanguage(
      Seq(cell(0, CellType.MARKDOWN, CellLanguage.MARKDOWN, "# title"))))

    assert(!NotebookLanguages.canChangeLanguage(
      Seq(cell(0, CellType.CODE, CellLanguage.SQL, "SELECT 1"))))
    assert(!NotebookLanguages.canChangeLanguage(Seq(
      cell(0, CellType.MARKDOWN, CellLanguage.MARKDOWN, "# title"),
      cell(1, CellType.CODE, CellLanguage.PYTHON, "print(1)"))))
  }

  test("parsing accepts what clients actually send and rejects the rest") {
    assert(NotebookLanguage.parse("SQL").contains(NotebookLanguage.SQL))
    assert(NotebookLanguage.parse("python").contains(NotebookLanguage.PYTHON))
    assert(NotebookLanguage.parse(" Python ").contains(NotebookLanguage.PYTHON))
    assert(NotebookLanguage.parse("MARKDOWN").isEmpty)
    assert(NotebookLanguage.parse("").isEmpty)
    assert(NotebookLanguage.parse(null).isEmpty)
  }

  test("a notebook language maps onto the cell language its CODE cells carry") {
    assert(NotebookLanguage.toCellLanguage(NotebookLanguage.SQL) === CellLanguage.SQL)
    assert(NotebookLanguage.toCellLanguage(NotebookLanguage.PYTHON) === CellLanguage.PYTHON)
    assert(NotebookLanguage.fromCellLanguage(CellLanguage.SQL).contains(NotebookLanguage.SQL))
    assert(NotebookLanguage.fromCellLanguage(CellLanguage.MARKDOWN).isEmpty)
  }
}
