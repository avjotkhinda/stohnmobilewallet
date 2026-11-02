package org.stohncoin.wallet.runtime

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Core's loopback JSON-RPC client, shared by every backend: cookie auth, a short timeout so a
 * wedged node cannot hang a screen, and the plaintext key-export guard on every method.
 *
 * It lives outside the backend classes so the glibc and native runtimes talk to Core through
 * exactly one client — one place to audit, one place to fix.
 */
internal class LocalRpc(private val dataDir: File, private val rpcPort: Int, private val walletName: String? = null) {
    private fun request(method: String, params: org.json.JSONArray): org.json.JSONObject {
        val guardedMethod = KeyExportGuard.requireRpcMethod(method)
        val cookie = File(dataDir, ".cookie")
        check(cookie.isFile && cookie.length() > 0L) { "Core RPC cookie not available yet" }
        val endpoint = walletName?.let { "/wallet/" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "/"
        check(rpcPort in 1024..65535) { "Core RPC port is not available" }
        val connection = (URL("http://localhost:$rpcPort$endpoint").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2500
            connection.readTimeout = 5000
            val auth = android.util.Base64.encodeToString(cookie.readBytes(), android.util.Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $auth")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = org.json.JSONObject().apply {
                put("jsonrpc", "1.0")
                put("id", "stohn-wallet")
                put("method", guardedMethod)
                put("params", params)
            }.toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = org.json.JSONObject(response)
            if (json.has("error") && !json.isNull("error")) {
                error(json.optJSONObject("error")?.optString("message") ?: "RPC error")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    fun objectResult(method: String, params: org.json.JSONArray = org.json.JSONArray()): org.json.JSONObject =
        request(method, params).optJSONObject("result") ?: error("RPC result for $method is not an object")

    fun arrayResult(method: String, params: org.json.JSONArray = org.json.JSONArray()): org.json.JSONArray =
        request(method, params).optJSONArray("result") ?: error("RPC result for $method is not an array")

    fun stringResult(method: String, params: org.json.JSONArray = org.json.JSONArray()): String =
        request(method, params).optString("result")

    fun voidResult(method: String, params: org.json.JSONArray = org.json.JSONArray()) {
        val response = request(method, params)
        require(response.has("result")) { "RPC response for $method has no result field" }
        require(response.isNull("result")) { "RPC result for $method was expected to be null" }
    }
}