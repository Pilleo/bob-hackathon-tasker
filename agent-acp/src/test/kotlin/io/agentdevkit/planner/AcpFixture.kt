package io.agentdevkit.planner

import kotlinx.serialization.json.*

/** Real subprocess fixture; no installed ACP server, sandbox, or Python required. */
object AcpFixture {
    @JvmStatic fun main(args: Array<String>) {
        val mode = args.single()
        if (mode == "sandbox") {
            val failure = try { java.io.File("forbidden.txt").writeText("modified"); false } catch (_: java.io.IOException) { true }
            check(failure) { "Sandbox allowed checkout modification" }
        }
        if (mode == "exit") return
        if (mode == "malformed") { println("not-json"); return }
        if (mode == "flood") { print("x".repeat(100_000)); System.out.flush(); Thread.sleep(10_000); return }
        if (mode == "hang") { Thread.sleep(30_000); return }
        val input = System.`in`.bufferedReader()
        fun send(value: JsonObject) { println(value); System.out.flush() }
        while (true) {
            val request = input.readLine()?.let { Json.parseToJsonElement(it).jsonObject } ?: return
            val method = request["method"]?.jsonPrimitive?.content
            val id = request["id"] ?: JsonPrimitive(0)
            fun reply(result: JsonObject) = send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
            when (method) {
                "initialize" -> reply(buildJsonObject { put("protocolVersion", if (mode == "wrong-version") 99 else 1) })
                "session/new" -> if (mode == "auth") {
                    send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", -32000); put("message", "Authentication required") }) })
                } else {
                    reply(buildJsonObject { put("sessionId", "s1") })
                    if (mode == "no-read") { Thread.sleep(30_000); return }
                }
                "session/set_config_option" -> if (mode == "no-model-config") {
                    send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject {
                        put("code", -32601); put("message", "Unsupported config option: model")
                    }) })
                    return
                } else reply(buildJsonObject {})
                "session/prompt" -> {
                    if (mode == "needs-client-filesystem") {
                        send(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":88,"method":"fs/read_text_file","params":{"sessionId":"s1","path":"src/Validator.kt"}}""").jsonObject)
                        val unsupported = Json.parseToJsonElement(input.readLine()).jsonObject
                        check(unsupported["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content == "-32601")
                        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject {
                            put("code", -32000); put("message", "Client filesystem capability unavailable")
                        }) })
                        return
                    }
                    send(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":99,"method":"session/request_permission","params":{"sessionId":"s1","options":[{"optionId":"no","kind":"reject_once"}]}}""").jsonObject)
                    check(Json.parseToJsonElement(input.readLine()).jsonObject["result"]?.jsonObject?.get("outcome")?.jsonObject?.get("optionId")?.jsonPrimitive?.content == "no")
                    val text = if (mode == "invalid-review") "I refuse JSON" else """{"verdict":"CHANGES_REQUESTED","summary":"Need tests","findings":[],"questions":[]}"""
                    for (chunk in text.chunked(17)) send(buildJsonObject {
                        put("jsonrpc", "2.0"); put("method", "session/update"); put("params", buildJsonObject {
                            put("sessionId", if (mode == "wrong-session") "wrong" else "s1")
                            put("update", buildJsonObject { put("sessionUpdate", "agent_message_chunk"); put("content", buildJsonObject { put("type", "text"); put("text", chunk) }) })
                        })
                    })
                    reply(buildJsonObject { put("stopReason", if (mode == "refusal") "refusal" else "end_turn") })
                    return
                }
            }
        }
    }
}
