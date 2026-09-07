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

import org.apache.kyuubi.KyuubiFunSuite
import org.apache.kyuubi.server.notebook.api.OutputType

/**
 * The payloads here are the shapes `execute_python.py` actually produces, read off the worker in
 * `externals/kyuubi-spark-sql-engine`: a mime bundle on success, and on failure an engine wrapper
 * whose text still contains the python response.
 */
class PySparkResponseCodecSuite extends KyuubiFunSuite {

  test("printed text becomes a single console stream") {
    val outputs = PySparkResponseCodec.bundleOutputs(Some("""{"text/plain":"hello\n"}"""))

    assert(outputs.size === 1)
    assert(outputs.head.mimeType === "text/plain")
    assert(outputs.head.outputType === OutputType.STREAM.toString)
    assert(outputs.head.stream === Some("stdout"))
    assert(outputs.head.data === "hello\n")
  }

  test("a rich bundle keeps the console first and renders the rest after it") {
    val outputs = PySparkResponseCodec.bundleOutputs(Some(
      """{"text/plain":"<pandas>","text/html":"<table></table>","image/png":"aGk="}"""))

    assert(outputs.map(_.mimeType) === Seq("text/plain", "image/png", "text/html"))
    assert(outputs.map(_.outputType) === Seq(
      OutputType.STREAM.toString,
      OutputType.IMAGE.toString,
      OutputType.HTML.toString))
    // Sequences have to be distinct and ascending or polling with afterSequence loses outputs.
    assert(outputs.map(_.sequence) === Seq(1L, 2L, 3L))
  }

  // The engine's AUTO mode - its default - does not wrap a text-only result in JSON at all: it
  // hands back the bare string. A cell as ordinary as print("hello") arrives this way, and
  // treating it as malformed is what made successful cells show nothing.

  test("bare text from the engine's AUTO mode is shown, not discarded") {
    val outputs = PySparkResponseCodec.bundleOutputs(Some("hello"))

    assert(outputs.size === 1)
    assert(outputs.head.mimeType === "text/plain")
    assert(outputs.head.outputType === OutputType.STREAM.toString)
    assert(outputs.head.data === "hello")
  }

  test("a bare value that happens to be valid JSON is still text") {
    // `1+1` reaches the server as the two characters `2`, which parses as a JSON number.
    assert(PySparkResponseCodec.bundleOutputs(Some("2")).map(_.data) === Seq("2"))
    // A cell printing an object of its own must not be mistaken for a mime bundle.
    val printed = PySparkResponseCodec.bundleOutputs(Some("""{"a":1}"""))
    assert(printed.map(_.mimeType) === Seq("text/plain"))
    assert(printed.map(_.data) === Seq("""{"a":1}"""))
  }

  test("a cell that printed nothing and rendered nothing yields no outputs") {
    assert(PySparkResponseCodec.bundleOutputs(Some("""{"text/plain":""}""")).isEmpty)
    assert(PySparkResponseCodec.bundleOutputs(Some("{}")).isEmpty)
    assert(PySparkResponseCodec.bundleOutputs(None).isEmpty)
  }

  test("a non-text bundle value keeps its own encoding instead of being flattened") {
    val outputs = PySparkResponseCodec.bundleOutputs(Some(
      """{"application/json":{"a":1}}"""))

    assert(outputs.size === 1)
    assert(outputs.head.outputType === OutputType.JSON.toString)
    assert(outputs.head.data === """{"a":1}""")
  }

  /** What the engine raises: `Interpret error:` then the pretty-printed python response. */
  private val engineFailure =
    """Interpret error:
      |{
      |  "code" : "1/0",
      |  "response" : {
      |    "msg_type" : "execute_reply",
      |    "content" : {
      |      "ename" : "ZeroDivisionError",
      |      "evalue" : "division by zero",
      |      "traceback" : [ "Traceback (most recent call last):\n", "ZeroDivisionError\n" ],
      |      "status" : "error"
      |    }
      |  }
      |}""".stripMargin

  test("a failure surfaces the whole traceback as stderr") {
    val outputs = PySparkResponseCodec.errorOutputs(engineFailure)

    assert(outputs.size === 1)
    assert(outputs.head.stream === Some("stderr"))
    assert(outputs.head.outputType === OutputType.STREAM.toString)
    assert(outputs.head.data.contains("Traceback (most recent call last):"))
    assert(outputs.head.data.contains("ZeroDivisionError"))
  }

  test("the execution message names the python exception rather than the engine wrapper") {
    assert(PySparkResponseCodec.summarize(engineFailure) === "ZeroDivisionError: division by zero")
  }

  test("an error the engine did not wrap is still reported instead of being swallowed") {
    assert(PySparkResponseCodec.summarize("connection reset\nsecond line") === "connection reset")
    assert(PySparkResponseCodec.errorOutputs("connection reset").head.data === "connection reset")
    assert(PySparkResponseCodec.errorOutputs("").isEmpty)
  }

  test("an unparseable error message still names something") {
    assert(PySparkResponseCodec.summarize(null) === "the cell failed")
  }
}
