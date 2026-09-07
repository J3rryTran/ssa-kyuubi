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

/**
 * Enumerations of the notebook domain. Values are persisted and returned over REST by name, so
 * renaming one is a breaking change for both stored rows and clients.
 */
object CellType extends Enumeration {
  type CellType = Value
  val CODE, MARKDOWN = Value
}

object CellLanguage extends Enumeration {
  type CellLanguage = Value
  val SQL, PYTHON, MARKDOWN = Value

  /** Languages that can be submitted for execution. */
  val executable: Set[Value] = Set(SQL, PYTHON)
}

/**
 * The single language a notebook's CODE cells are written in. A notebook is not polyglot: this is
 * the source of truth, and a cell's own language field only ever mirrors it.
 */
object NotebookLanguage extends Enumeration {
  type NotebookLanguage = Value
  val SQL, PYTHON = Value

  val default: Value = SQL

  def parse(raw: String): Option[Value] =
    Option(raw).map(_.trim).filter(_.nonEmpty)
      .flatMap(value => values.find(_.toString.equalsIgnoreCase(value)))

  /** The cell language a CODE cell of this notebook must carry. */
  def toCellLanguage(language: Value): CellLanguage.Value = language match {
    case SQL => CellLanguage.SQL
    case PYTHON => CellLanguage.PYTHON
  }

  def fromCellLanguage(language: CellLanguage.Value): Option[Value] = language match {
    case CellLanguage.SQL => Some(SQL)
    case CellLanguage.PYTHON => Some(PYTHON)
    case _ => None
  }
}

object PermissionRole extends Enumeration {
  type PermissionRole = Value
  val OWNER, EDITOR, VIEWER = Value
}

object PrincipalType extends Enumeration {
  type PrincipalType = Value
  val USER, GROUP = Value
}

object NotebookSessionState extends Enumeration {
  type NotebookSessionState = Value
  val CREATING, IDLE, BUSY, RESETTING, STOPPING, STOPPED, LOST, FAILED = Value

  val terminal: Set[Value] = Set(STOPPED, LOST, FAILED)
}

object RuntimeState extends Enumeration {
  type RuntimeState = Value
  val CREATING, IDLE, BUSY, INTERRUPTING, RESTARTING, STOPPING, STOPPED, LOST, FAILED = Value

  val terminal: Set[Value] = Set(STOPPED, LOST, FAILED)
}

object ExecutionState extends Enumeration {
  type ExecutionState = Value
  val QUEUED, STARTING, RUNNING, CANCELING, CANCELED, SUCCEEDED, FAILED, CLOSED, LOST = Value

  val terminal: Set[Value] = Set(CANCELED, SUCCEEDED, FAILED, CLOSED, LOST)
}

object OutputType extends Enumeration {
  type OutputType = Value
  val STREAM, TEXT, TABLE, DISPLAY_DATA, EXECUTE_RESULT, ERROR, IMAGE, JSON, HTML = Value
}

object RunState extends Enumeration {
  type RunState = Value
  val QUEUED, RUNNING, CANCELING, CANCELED, SUCCEEDED, FAILED = Value

  val terminal: Set[Value] = Set(CANCELED, SUCCEEDED, FAILED)
}

object FailurePolicy extends Enumeration {
  type FailurePolicy = Value
  val STOP_ON_ERROR, CONTINUE_ON_ERROR = Value
}

object OverlapPolicy extends Enumeration {
  type OverlapPolicy = Value
  val SKIP_IF_RUNNING, QUEUE = Value
}
