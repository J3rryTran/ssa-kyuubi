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
import java.util.UUID

import scala.collection.JavaConverters._

import org.mockito.ArgumentMatchers.{any, anyBoolean, anyInt, eq => argEq}
import org.mockito.Mockito.{mock, never, verify, when}

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.config.KyuubiConf.OPERATION_RESULT_FORMAT
import org.apache.kyuubi.operation.{FetchOrientation, OperationHandle}
import org.apache.kyuubi.operation.FetchOrientation.FetchOrientation
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.service.BackendService
import org.apache.kyuubi.shaded.hive.service.rpc.thrift._

/**
 * Covers the step between the engine and the results endpoint that
 * [[ThriftRowSetConverterSuite]] cannot see: which decoder the adapter picks, and how it drives
 * the Kyuubi cursor.
 *
 * The regression this guards against returned an empty page for every statement, because the
 * adapter read only `TRowSet.getRows` while a protocol V11 engine answers column-based: a shape
 * both a converter-level test and a service-level test with a stub adapter happily miss.
 */
class KyuubiSqlRuntimeAdapterSuite extends KyuubiFunSuite {

  private val handle = UUID.randomUUID().toString

  /** What a V11 engine actually sends: values in columns, `rows` left empty. */
  private def columnBasedRowSet: TRowSet = {
    val rowSet = new TRowSet(0L, Seq.empty[TRow].asJava)
    rowSet.setColumns(Seq(
      TColumn.i32Val(new TI32Column(Seq[Integer](1, 2, 0).asJava, nulls(2))),
      TColumn.stringVal(new TStringColumn(Seq("one", "two", "").asJava, nulls(2)))).asJava)
    rowSet
  }

  private def nulls(indexes: Int*): ByteBuffer = {
    val bits = new java.util.BitSet()
    indexes.foreach(bits.set)
    ByteBuffer.wrap(bits.toByteArray)
  }

  private def backendReturning(
      rowSet: TRowSet,
      schema: TTableSchema = new TTableSchema(Seq.empty[TColumnDesc].asJava)): BackendService = {
    val backend = mock(classOf[BackendService])
    val status = new TStatus(TStatusCode.SUCCESS_STATUS)
    val fetched = new TFetchResultsResp(status)
    fetched.setResults(rowSet)
    when(backend.fetchResults(
      any[OperationHandle],
      any[FetchOrientation],
      anyInt,
      anyBoolean)).thenReturn(fetched)
    val metadata = new TGetResultSetMetadataResp(status)
    metadata.setSchema(schema)
    when(backend.getResultSetMetadata(any[OperationHandle])).thenReturn(metadata)
    backend
  }

  private def adapterFor(backend: BackendService, format: String): KyuubiSqlRuntimeAdapter = {
    val conf = new KyuubiConf(false).set(OPERATION_RESULT_FORMAT, format)
    new KyuubiSqlRuntimeAdapter(() => backend, () => "test-instance", conf)
  }

  private def execution: CellExecution = CellExecution(
    id = "execution-1",
    notebookId = "notebook-1",
    notebookSessionId = "session-1",
    runtimeId = "runtime-1",
    runtimeGeneration = 1L,
    cellId = Some("cell-1"),
    cellVersion = Some(1L),
    language = CellLanguage.SQL,
    sourceSnapshot = "select 1",
    state = ExecutionState.SUCCEEDED,
    submittedAt = 1L,
    startedAt = Some(1L),
    finishedAt = Some(2L),
    submittedBy = "alice",
    errorCode = None,
    errorMessage = None,
    clientRequestId = None,
    notebookRunId = None,
    internalOperationHandle = Some(handle),
    version = 1L)

  test("a thrift column-based result reaches the results page with its values and nulls") {
    val backend = backendReturning(columnBasedRowSet)
    val page = adapterFor(backend, "thrift").fetchResults(execution, 0L, 10)

    assert(page.rows === Seq(Seq("1", "one"), Seq("2", "two"), Seq(null, null)))
    assert(page.nextOffset === 3L)
    assert(!page.hasMore)
    // The thrift path must not pay for a metadata round trip; only the arrow decoder needs one.
    verify(backend, never()).getResultSetMetadata(any[OperationHandle])
  }

  test("the arrow decoder is chosen when the operation runs with the arrow format") {
    val backend = backendReturning(columnBasedRowSet)
    val page = adapterFor(backend, "arrow").fetchResults(execution, 0L, 10)

    // An empty schema makes the arrow decoder stop before touching the payload, so what is
    // asserted here is purely the dispatch: arrow reads the schema, thrift never does.
    verify(backend).getResultSetMetadata(any[OperationHandle])
    assert(page.rows.isEmpty)
  }

  test("an empty result set yields an empty page rather than a failure") {
    val backend = backendReturning(new TRowSet(0L, Seq.empty[TRow].asJava))
    val page = adapterFor(backend, "thrift").fetchResults(execution, 0L, 10)

    assert(page.rows.isEmpty)
    assert(page.nextOffset === 0L)
    assert(!page.hasMore)
  }

  test("only the first page restarts the Kyuubi cursor") {
    val backend = backendReturning(columnBasedRowSet)
    val adapter = adapterFor(backend, "thrift")

    adapter.fetchResults(execution, 0L, 10)
    val second = adapter.fetchResults(execution, 3L, 10)

    verify(backend).fetchResults(
      any[OperationHandle],
      argEq(FetchOrientation.FETCH_FIRST),
      anyInt,
      anyBoolean)
    verify(backend).fetchResults(
      any[OperationHandle],
      argEq(FetchOrientation.FETCH_NEXT),
      anyInt,
      anyBoolean)
    // A later page continues from the offset it was asked for instead of counting from zero.
    assert(second.nextOffset === 6L)
  }

  test("a page filled to the requested size reports that more rows may follow") {
    val backend = backendReturning(columnBasedRowSet)
    val page = adapterFor(backend, "thrift").fetchResults(execution, 0L, 3)

    assert(page.rows.size === 3)
    assert(page.hasMore)
  }
}
