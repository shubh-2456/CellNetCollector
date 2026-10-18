package com.cellnet.collector

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiResult(val code: Int, val body: JSONObject?, val error: String?)

object Api {
    fun post(host: String, port: Int, path: String, json: JSONObject, token: String?): ApiResult {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
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
}
