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

import java.io.ByteArrayInputStream
import java.nio.channels.Channels
import java.util.TimeZone

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{VectorLoader, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.ReadChannel
import org.apache.arrow.vector.ipc.message.MessageSerializer

import org.apache.kyuubi.Logging
import org.apache.kyuubi.client.api.v1.dto.{Field, Row}
import org.apache.kyuubi.jdbc.hive.JdbcColumnAttributes
import org.apache.kyuubi.jdbc.hive.KyuubiArrowQueryResultSet
import org.apache.kyuubi.jdbc.hive.arrow.{ArrowColumnarBatch, ArrowColumnVector, ArrowUtils}
import org.apache.kyuubi.shaded.hive.service.rpc.thrift.{TColumnValue, TRowSet, TTableSchema, TTypeId}

/**
 * Turns an Arrow-encoded `TRowSet` into the same JSON rows the thrift result format produces.
 *
 * With `kyuubi.operation.result.format=arrow` a `TRowSet` is not rows at all: it carries a single
 * BINARY column whose values are serialized Arrow IPC record batches. Handing that straight to
 * Jackson - which is what the REST layer does for thrift results - sends the client binary noise,
 * so notebook cells come back empty.
 *
 * No decoder is written here. The one in the JDBC driver
 * ([[org.apache.kyuubi.jdbc.hive.KyuubiArrowQueryResultSet]] and the `arrow` package next to it)
 * already reads exactly this wire format, and this object drives it: same schema construction,
 * same batch deserialization, same per-value accessor. Only the last step differs - values are
 * emitted as [[Field]]s rather than through the JDBC `ResultSet` API.
 */
private[server] object ArrowRowSetConverter extends Logging {

  /**
   * Arrow schemas cannot represent every Hive type, so the driver rewrites some columns to UTF-8
   * before encoding. The same rewrite has to happen here or the vectors will not line up with the
   * bytes on the wire.
   */
  private def schemaType(original: TTypeId, timestampAsString: Boolean): TTypeId = original match {
    case TTypeId.ARRAY_TYPE | TTypeId.MAP_TYPE | TTypeId.STRUCT_TYPE => TTypeId.STRING_TYPE
    case TTypeId.TIMESTAMP_TYPE if timestampAsString => TTypeId.STRING_TYPE
    // Neither ArrowUtils.toArrowType nor ArrowColumnarBatchRow.get knows these two; Spark reports
    // char/varchar columns as STRING anyway, so folding them keeps an exotic schema from
    // throwing instead of returning rows.
    case TTypeId.CHAR_TYPE | TTypeId.VARCHAR_TYPE => TTypeId.STRING_TYPE
    case other => other
  }

  /**
   * The `TColumnValue` union branch the thrift path would have used for this type. The REST
   * response labels every value with it, so an arrow-backed operation has to report the same
   * names or clients see a different shape depending on a server-side setting.
   *
   * Mirrors `TColumnValueGenerator`: only the seven primitives get their own branch and floats
   * ride along with doubles; everything else was already stringified by the engine.
   */
  private def thriftFieldName(t: TTypeId): String = t match {
    case TTypeId.BOOLEAN_TYPE => TColumnValue._Fields.BOOL_VAL.name()
    case TTypeId.TINYINT_TYPE => TColumnValue._Fields.BYTE_VAL.name()
    case TTypeId.SMALLINT_TYPE => TColumnValue._Fields.I16_VAL.name()
    case TTypeId.INT_TYPE => TColumnValue._Fields.I32_VAL.name()
    case TTypeId.BIGINT_TYPE => TColumnValue._Fields.I64_VAL.name()
    case TTypeId.FLOAT_TYPE | TTypeId.DOUBLE_TYPE => TColumnValue._Fields.DOUBLE_VAL.name()
    case _ => TColumnValue._Fields.STRING_VAL.name()
  }

  /**
   * Values are shaped like the thrift path's: the seven primitive branches carry a number or a
   * boolean, and every other type arrives as a string. Decimals, dates and timestamps come out of
   * the accessor as `BigDecimal`/`Date`/`Timestamp`, which is exactly what `toHiveString` prints
   * on the thrift side.
   */
  private def jsonValue(raw: Any, t: TTypeId): Any = (raw, t) match {
    case (null, _) => null
    case (
          v,
          TTypeId.BOOLEAN_TYPE | TTypeId.TINYINT_TYPE | TTypeId.SMALLINT_TYPE |
          TTypeId.INT_TYPE | TTypeId.BIGINT_TYPE) => v
    case (v: java.lang.Float, _) => java.lang.Double.valueOf(v.doubleValue())
    case (v, TTypeId.DOUBLE_TYPE) => v
    case (v: Array[Byte], _) => new String(v, java.nio.charset.StandardCharsets.UTF_8)
    case (v, _) => String.valueOf(v)
  }

  private def columnTypes(schema: TTableSchema): Seq[TTypeId] =
    schema.getColumns.asScala.map(_.getTypeDesc.getTypes.get(0).getPrimitiveEntry.getType).toSeq

  private def columnAttributes(schema: TTableSchema): Seq[JdbcColumnAttributes] =
    schema.getColumns.asScala.map { column =>
      val entry = column.getTypeDesc.getTypes.get(0).getPrimitiveEntry
      val attributes = KyuubiArrowQueryResultSet.getColumnAttributes(entry)
      // A timestamp column with no session.timeZone qualifier would leave the driver's decoder
      // without a zone; fall back to this JVM's rather than fail the fetch.
      if (entry.getType == TTypeId.TIMESTAMP_TYPE &&
        (attributes == null || attributes.timeZone == null || attributes.timeZone.isEmpty)) {
        new JdbcColumnAttributes(TimeZone.getDefault.getID)
      } else {
        attributes
      }
    }.toSeq

  /** True when this row set carries Arrow IPC batches rather than thrift rows. */
  def isArrowRowSet(resultFormat: String): Boolean =
    resultFormat != null && resultFormat.trim.equalsIgnoreCase("arrow")

  /**
   * Decode every batch in `rowSet` into rows.
   *
   * Each batch gets its own child allocator, schema root and columnar batch, all closed before
   * the next one is read, so a large fetch cannot pile up direct memory. `maxRows` bounds the
   * result the same way the client asked the engine to bound it.
   */
  def toRows(
      rowSet: TRowSet,
      schema: TTableSchema,
      timestampAsString: Boolean,
      maxRows: Int): Seq[Row] = {
    val originalTypes = columnTypes(schema)
    if (originalTypes.isEmpty || rowSet == null || rowSet.getColumns == null ||
      rowSet.getColumnsSize == 0) {
      return Seq.empty
    }

    val names = schema.getColumns.asScala.map(_.getColumnName).toSeq
    val attributes = columnAttributes(schema)
    val encodedTypes = originalTypes.map(schemaType(_, timestampAsString))
    val arrowSchema =
      ArrowUtils.toArrowSchema(names.asJava, encodedTypes.asJava, attributes.asJava)
    val fieldNames = originalTypes.map(thriftFieldName)

    val batches = rowSet.getColumns.get(0).getBinaryVal.getValues.asScala
    val rows = new ArrayBuffer[Row]()
    val allocator: BufferAllocator =
      ArrowUtils.rootAllocator.newChildAllocator("rest-rowset", 0, Long.MaxValue)
    try {
      batches.iterator
        .takeWhile(_ => maxRows <= 0 || rows.size < maxRows)
        .foreach { buffer =>
          decodeBatch(
            buffer.array(),
            arrowSchema,
            allocator,
            originalTypes,
            attributes,
            fieldNames,
            timestampAsString,
            maxRows,
            rows)
        }
    } finally {
      allocator.close()
    }
    rows.toSeq
  }

  private def decodeBatch(
      bytes: Array[Byte],
      arrowSchema: org.apache.arrow.vector.types.pojo.Schema,
      allocator: BufferAllocator,
      originalTypes: Seq[TTypeId],
      attributes: Seq[JdbcColumnAttributes],
      fieldNames: Seq[String],
      timestampAsString: Boolean,
      maxRows: Int,
      out: ArrayBuffer[Row]): Unit = {
    if (bytes == null || bytes.isEmpty) return
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    try {
      val recordBatch = MessageSerializer.deserializeRecordBatch(
        new ReadChannel(Channels.newChannel(new ByteArrayInputStream(bytes))),
        allocator)
      try {
        new VectorLoader(root).load(recordBatch)
      } finally {
        recordBatch.close()
      }
      val vectors = root.getFieldVectors.asScala.map(new ArrowColumnVector(_)).toArray
      val batch = new ArrowColumnarBatch(vectors, root.getRowCount)
      try {
        val iterator = batch.rowIterator()
        while (iterator.hasNext && (maxRows <= 0 || out.size < maxRows)) {
          val row = iterator.next()
          val fields = originalTypes.indices.map { ordinal =>
            val value =
              if (row.isNullAt(ordinal)) {
                null
              } else {
                val zone = Option(attributes(ordinal)).map(_.timeZone).orNull
                jsonValue(
                  row.get(ordinal, originalTypes(ordinal), zone, timestampAsString),
                  originalTypes(ordinal))
              }
            new Field(fieldNames(ordinal), value)
          }
          out += new Row(fields.asJava)
        }
      } finally {
        batch.close()
      }
    } finally {
      root.close()
    }
  }
}
