/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package software.aws.clientrt.smithy.test

import kotlin.test.assertEquals
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import software.aws.clientrt.http.HttpBody
import software.aws.clientrt.http.readAll

/**
 * Assert JSON strings for equality ignoring key order
 */
@OptIn(UnstableDefault::class)
fun assertJsonStringsEqual(expected: String, actual: String) {
    val config = JsonConfiguration()
    val expectedElement = Json(config).parseJson(expected)
    val actualElement = Json(config).parseJson(actual)

    assertEquals(expectedElement, actualElement, "expected JSON:\n\n$expected\n\nactual:\n\n$actual\n")
}

/**
 * Assert HTTP bodies are equal as JSON documents
 */
@OptIn(ExperimentalStdlibApi::class)
suspend fun assertJsonBodiesEqual(expected: HttpBody?, actual: HttpBody?) {
    val expectedStr = expected?.readAll()?.decodeToString()
    val actualStr = actual?.readAll()?.decodeToString()
    if (expectedStr == null && actualStr == null) {
        return
    }

    requireNotNull(expectedStr) { "expected JSON body cannot be null" }
    requireNotNull(actualStr) { "actual JSON body cannot be null" }

    assertJsonStringsEqual(expectedStr, actualStr)
}