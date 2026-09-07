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

package org.apache.kyuubi.server.notebook.runtime

import scala.collection.JavaConverters._

import org.apache.kyuubi.jdbc.hive.cli.{ColumnBasedSet, RowBasedSet, RowSet}
import org.apache.kyuubi.shaded.hive.service.rpc.thrift.TRowSet

/** Decodes both protocol-era row-based and modern column-based thrift result sets. */
private[notebook] object ThriftRowSetConverter {

  def toRows(rowSet: TRowSet): Seq[Seq[String]] = {
    if (rowSet == null) return Seq.empty

    val decoded: RowSet =
      if ((rowSet.getColumns != null && !rowSet.getColumns.isEmpty) || rowSet.isSetBinaryColumns) {
        new ColumnBasedSet(rowSet)
      } else {
        new RowBasedSet(rowSet)
      }
    decoded.iterator.asScala.map { row =>
      row.toSeq.map(value => if (value == null) null else value.toString)
    }.toSeq
  }
}
