package com.cellnet.collector

import android.content.Context
import java.util.UUID

class Store(context: Context) {
    private val p = context.getSharedPreferences("cellnet", Context.MODE_PRIVATE)

    val deviceUid: String
        get() {
            val existing = p.getString("device_uid", null)
            if (existing != null) return existing
            val created = UUID.randomUUID().toString()
            p.edit().putString("device_uid", created).apply()
            return created
        }

    var host: String?
        get() = p.getString("host", null)
        set(v) { p.edit().putString("host", v).apply() }

    var port: Int
        get() = p.getInt("port", 5000)
        set(v) { p.edit().putInt("port", v).apply() }

    var deviceToken: String?
        get() = p.getString("device_token", null)
        set(v) { p.edit().putString("device_token", v).apply() }

    fun isPaired(): Boolean = !host.isNullOrBlank() && !deviceToken.isNullOrBlank()

    fun unpair() {
        p.edit().remove("device_token").apply()
    }
}
