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

import java.nio.ByteBuffer

import scala.collection.JavaConverters._

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.shaded.hive.service.rpc.thrift._

class ThriftRowSetConverterSuite extends KyuubiFunSuite {

  test("column-based thrift rows preserve integers, strings, nulls and order") {
    val rowSet = new TRowSet(0L, Seq.empty[TRow].asJava)
    rowSet.setColumns(Seq(
      TColumn.i32Val(new TI32Column(Seq[Integer](1, 2, 0).asJava, nulls(2))),
      TColumn.stringVal(new TStringColumn(Seq("one", "two", "").asJava, nulls(2)))).asJava)

    assert(ThriftRowSetConverter.toRows(rowSet) === Seq(
      Seq("1", "one"),
      Seq("2", "two"),
      Seq(null, null)))
  }

  test("row-based thrift rows and an empty result are supported") {
    val first = new TRow(Seq(
      TColumnValue.i32Val(i32(7)),
      TColumnValue.stringVal(string("seven"))).asJava)
    val second = new TRow(Seq(
      TColumnValue.i32Val(new TI32Value()),
      TColumnValue.stringVal(string("nullable"))).asJava)
    val rowSet = new TRowSet(0L, Seq(first, second).asJava)

    assert(ThriftRowSetConverter.toRows(rowSet) === Seq(
      Seq("7", "seven"),
      Seq(null, "nullable")))
    assert(ThriftRowSetConverter.toRows(new TRowSet(0L, Seq.empty[TRow].asJava)).isEmpty)
  }

  private def nulls(indexes: Int*): ByteBuffer = {
    val bits = new java.util.BitSet()
    indexes.foreach(bits.set)
    ByteBuffer.wrap(bits.toByteArray)
  }

  private def i32(value: Int): TI32Value = {
    val result = new TI32Value()
    result.setValue(value)
    result
  }

  private def string(value: String): TStringValue = {
    val result = new TStringValue()
    result.setValue(value)
    result
  }
}
