package com.cellnet.collector

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiResult(val code: Int, val body: JSONObject?, val error: String?)

object Api {
    private fun call(
        method: String, host: String, port: Int, path: String,
        json: JSONObject?, token: String?, connectMs: Int, readMs: Int
    ): ApiResult {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val body = try { JSONObject(text) } catch (e: Exception) { null }
            ApiResult(code, body, null)
        } catch (e: Exception) {
            ApiResult(-1, null, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    fun get(host: String, port: Int, path: String): ApiResult =
        call("GET", host, port, path, null, null, 3000, 3000)

    fun post(host: String, port: Int, path: String, json: JSONObject, token: String?): ApiResult =
        call("POST", host, port, path, json, token, 5000, 8000)
}
