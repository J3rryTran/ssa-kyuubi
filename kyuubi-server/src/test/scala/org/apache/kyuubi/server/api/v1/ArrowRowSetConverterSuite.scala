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

package org.apache.kyuubi.server.api.v1

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.channels.Channels

import scala.collection.JavaConverters._

import org.apache.arrow.vector.{BigIntVector, DateDayVector, DecimalVector, Float8Vector, IntVector, VarCharVector, VectorSchemaRoot, VectorUnloader}
import org.apache.arrow.vector.ipc.WriteChannel
import org.apache.arrow.vector.ipc.message.MessageSerializer
import org.apache.arrow.vector.util.Text

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.jdbc.hive.JdbcColumnAttributes
import org.apache.kyuubi.jdbc.hive.arrow.ArrowUtils
import org.apache.kyuubi.shaded.hive.service.rpc.thrift._

/**
 * The wire format under test is the one the engine's `SparkArrowTRowSetGenerator` emits: a
 * `TRowSet` with a single BINARY column holding one serialized Arrow record batch. Batches here
 * are built through the same [[ArrowUtils]] schema construction the engine and the JDBC driver
 * use, and serialized with the exact inverse of the call the converter decodes with, so the bytes
 * are the ones a real fetch would carry.
 */
class ArrowRowSetConverterSuite extends KyuubiFunSuite {

  private def columnDesc(
      name: String,
      typeId: TTypeId,
      position: Int,
      qualifiers: Map[String, TTypeQualifierValue] = Map.empty): TColumnDesc = {
    val entry = new TPrimitiveTypeEntry(typeId)
    if (qualifiers.nonEmpty) {
      entry.setTypeQualifiers(new TTypeQualifiers(qualifiers.asJava))
    }
    val typeDesc = new TTypeDesc(Seq(TTypeEntry.primitiveEntry(entry)).asJava)
    val desc = new TColumnDesc()
    desc.setColumnName(name)
    desc.setTypeDesc(typeDesc)
    desc.setPosition(position)
    desc
  }

  private def schemaOf(columns: TColumnDesc*): TTableSchema =
    new TTableSchema(columns.asJava)

  /** Wrap batch bytes exactly as the engine does: one binary column, one value. */
  private def rowSetOf(batch: Array[Byte]): TRowSet = {
    val values = new java.util.ArrayList[ByteBuffer](1)
    values.add(ByteBuffer.wrap(batch))
    val column = TColumn.binaryVal(new TBinaryColumn(values, ByteBuffer.wrap(Array.empty[Byte])))
    val rowSet = new TRowSet(0, new java.util.ArrayList[TRow](0))
    rowSet.addToColumns(column)
    rowSet
  }

  private def serialize(root: VectorSchemaRoot): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val batch = new VectorUnloader(root).getRecordBatch
    try {
      MessageSerializer.serialize(new WriteChannel(Channels.newChannel(out)), batch)
    } finally {
      batch.close()
    }
    out.toByteArray
  }

  private def field(row: org.apache.kyuubi.client.api.v1.dto.Row, ordinal: Int) =
    row.getFields.get(ordinal)

  test("primitive columns keep the thrift field names and value types") {
    val schema = schemaOf(
      columnDesc("c_int", TTypeId.INT_TYPE, 1),
      columnDesc("c_bigint", TTypeId.BIGINT_TYPE, 2),
      columnDesc("c_double", TTypeId.DOUBLE_TYPE, 3),
      columnDesc("c_string", TTypeId.STRING_TYPE, 4))
    val types = Seq(TTypeId.INT_TYPE, TTypeId.BIGINT_TYPE, TTypeId.DOUBLE_TYPE, TTypeId.STRING_TYPE)
    val attributes = Seq.fill(4)(null.asInstanceOf[JdbcColumnAttributes])
    val arrowSchema = ArrowUtils.toArrowSchema(
      Seq("c_int", "c_bigint", "c_double", "c_string").asJava,
      types.asJava,
      attributes.asJava)

    val root = VectorSchemaRoot.create(arrowSchema, ArrowUtils.rootAllocator)
    val bytes =
      try {
        root.allocateNew()
        root.getVector("c_int").asInstanceOf[IntVector].setSafe(0, 42)
        root.getVector("c_bigint").asInstanceOf[BigIntVector].setSafe(0, 9876543210L)
        root.getVector("c_double").asInstanceOf[Float8Vector].setSafe(0, 1.5d)
        root.getVector("c_string").asInstanceOf[VarCharVector].setSafe(0, new Text("hello"))
        root.setRowCount(1)
        serialize(root)
      } finally {
        root.close()
      }

    val rows = ArrowRowSetConverter.toRows(rowSetOf(bytes), schema, timestampAsString = true, 100)
    assert(rows.size === 1)
    val row = rows.head
    // Field names mirror TColumnValueGenerator's union branches, not the arrow types.
    assert(field(row, 0).getDataType === "I32_VAL")
    assert(field(row, 0).getValue === 42)
    assert(field(row, 1).getDataType === "I64_VAL")
    assert(field(row, 1).getValue === 9876543210L)
    assert(field(row, 2).getDataType === "DOUBLE_VAL")
    assert(field(row, 2).getValue === 1.5d)
    assert(field(row, 3).getDataType === "STRING_VAL")
    assert(field(row, 3).getValue === "hello")
  }

  test("decimal, date and stringified types come back as strings like the thrift path") {
    val decimalQualifiers = Map(
      TCLIServiceConstants.PRECISION -> TTypeQualifierValue.i32Value(10),
      TCLIServiceConstants.SCALE -> TTypeQualifierValue.i32Value(2))
    val schema = schemaOf(
      columnDesc("c_decimal", TTypeId.DECIMAL_TYPE, 1, decimalQualifiers),
      columnDesc("c_date", TTypeId.DATE_TYPE, 2),
      columnDesc("c_ts", TTypeId.TIMESTAMP_TYPE, 3),
      columnDesc("c_array", TTypeId.ARRAY_TYPE, 4))
    // The engine rewrites timestamp (when timestampAsString) and complex types to UTF-8 before
    // encoding, so the arrow schema uses STRING for those two.
    val encodedTypes =
      Seq(TTypeId.DECIMAL_TYPE, TTypeId.DATE_TYPE, TTypeId.STRING_TYPE, TTypeId.STRING_TYPE)
    val attributes = Seq(new JdbcColumnAttributes(10, 2), null, null, null)
    val arrowSchema = ArrowUtils.toArrowSchema(
      Seq("c_decimal", "c_date", "c_ts", "c_array").asJava,
      encodedTypes.asJava,
      attributes.asJava)

    val root = VectorSchemaRoot.create(arrowSchema, ArrowUtils.rootAllocator)
    val bytes =
      try {
        root.allocateNew()
        root.getVector("c_decimal").asInstanceOf[DecimalVector]
          .setSafe(0, new BigDecimal("123.45"))
        // Arrow DateDay counts days since the epoch; 19723 is 2024-01-01.
        root.getVector("c_date").asInstanceOf[DateDayVector].setSafe(0, 19723)
        root.getVector("c_ts").asInstanceOf[VarCharVector]
          .setSafe(0, new Text("2024-01-01 10:20:30.0"))
        root.getVector("c_array").asInstanceOf[VarCharVector].setSafe(0, new Text("[1,2,3]"))
        root.setRowCount(1)
        serialize(root)
      } finally {
        root.close()
      }

    val rows = ArrowRowSetConverter.toRows(rowSetOf(bytes), schema, timestampAsString = true, 100)
    assert(rows.size === 1)
    val row = rows.head
    // thrift renders all four through toHiveString, so every one is a STRING_VAL.
    assert(row.getFields.asScala.forall(_.getDataType === "STRING_VAL"))
    assert(field(row, 0).getValue === "123.45")
    assert(field(row, 1).getValue === "2024-01-01")
    assert(field(row, 2).getValue === "2024-01-01 10:20:30.0")
    assert(field(row, 3).getValue === "[1,2,3]")
  }

  test("nulls survive as null rather than a zero value") {
    val schema = schemaOf(
      columnDesc("c_int", TTypeId.INT_TYPE, 1),
      columnDesc("c_string", TTypeId.STRING_TYPE, 2))
    val types = Seq(TTypeId.INT_TYPE, TTypeId.STRING_TYPE)
    val arrowSchema = ArrowUtils.toArrowSchema(
      Seq("c_int", "c_string").asJava,
      types.asJava,
      Seq.fill(2)(null.asInstanceOf[JdbcColumnAttributes]).asJava)

    val root = VectorSchemaRoot.create(arrowSchema, ArrowUtils.rootAllocator)
    val bytes =
      try {
        root.allocateNew()
        root.getVector("c_int").asInstanceOf[IntVector].setNull(0)
        root.getVector("c_string").asInstanceOf[VarCharVector].setNull(0)
        root.setRowCount(1)
        serialize(root)
      } finally {
        root.close()
      }

    val rows = ArrowRowSetConverter.toRows(rowSetOf(bytes), schema, timestampAsString = true, 100)
    assert(rows.size === 1)
    assert(field(rows.head, 0).getValue === null)
    assert(field(rows.head, 1).getValue === null)
    // The type label is still reported so the client can tell what the column was.
    assert(field(rows.head, 0).getDataType === "I32_VAL")
  }

  test("maxrows bounds what is decoded") {
    val schema = schemaOf(columnDesc("c_int", TTypeId.INT_TYPE, 1))
    val arrowSchema = ArrowUtils.toArrowSchema(
      Seq("c_int").asJava,
      Seq(TTypeId.INT_TYPE).asJava,
      Seq(null.asInstanceOf[JdbcColumnAttributes]).asJava)

    val root = VectorSchemaRoot.create(arrowSchema, ArrowUtils.rootAllocator)
    val bytes =
      try {
        root.allocateNew()
        val vector = root.getVector("c_int").asInstanceOf[IntVector]
        (0 until 10).foreach(i => vector.setSafe(i, i))
        root.setRowCount(10)
        serialize(root)
      } finally {
        root.close()
      }

    val rowSet = rowSetOf(bytes)
    assert(ArrowRowSetConverter.toRows(rowSet, schema, timestampAsString = true, 3).size === 3)
    assert(ArrowRowSetConverter.toRows(rowSet, schema, timestampAsString = true, 100).size === 10)
  }

  test("an empty or absent row set decodes to no rows") {
    val schema = schemaOf(columnDesc("c_int", TTypeId.INT_TYPE, 1))
    assert(ArrowRowSetConverter.toRows(null, schema, timestampAsString = true, 100).isEmpty)
    val empty = new TRowSet(0, new java.util.ArrayList[TRow](0))
    assert(ArrowRowSetConverter.toRows(empty, schema, timestampAsString = true, 100).isEmpty)
  }

  test("the arrow format is recognised from configuration, not from the payload") {
    assert(ArrowRowSetConverter.isArrowRowSet("arrow"))
    assert(ArrowRowSetConverter.isArrowRowSet("ARROW"))
    assert(ArrowRowSetConverter.isArrowRowSet(" arrow "))
    assert(!ArrowRowSetConverter.isArrowRowSet("thrift"))
    assert(!ArrowRowSetConverter.isArrowRowSet(""))
    assert(!ArrowRowSetConverter.isArrowRowSet(null))
  }

  test("decoding does not leak the child allocator") {
    val schema = schemaOf(columnDesc("c_int", TTypeId.INT_TYPE, 1))
    val arrowSchema = ArrowUtils.toArrowSchema(
      Seq("c_int").asJava,
      Seq(TTypeId.INT_TYPE).asJava,
      Seq(null.asInstanceOf[JdbcColumnAttributes]).asJava)
    val root = VectorSchemaRoot.create(arrowSchema, ArrowUtils.rootAllocator)
    val bytes =
      try {
        root.allocateNew()
        root.getVector("c_int").asInstanceOf[IntVector].setSafe(0, 1)
        root.setRowCount(1)
        serialize(root)
      } finally {
        root.close()
      }

    val before = ArrowUtils.rootAllocator.getAllocatedMemory
    (1 to 20).foreach { _ =>
      ArrowRowSetConverter.toRows(rowSetOf(bytes), schema, timestampAsString = true, 100)
    }
    assert(ArrowUtils.rootAllocator.getAllocatedMemory === before)
  }
}
