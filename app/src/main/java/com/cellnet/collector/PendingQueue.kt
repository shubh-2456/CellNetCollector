package com.cellnet.collector

import android.content.Context
import org.json.JSONObject
import java.io.File

class PendingQueue(context: Context) {
    private val file = File(context.filesDir, "pending.jsonl")
    private val items = ArrayList<JSONObject>()

    init {
        try {
            if (file.exists()) {
                file.forEachLine { line ->
                    if (line.isNotBlank()) {
                        try { items.add(JSONObject(line)) } catch (e: Exception) { }
                    }
                }
            }
        } catch (e: Exception) { }
    }

    private fun save() {
        try {
            file.bufferedWriter().use { w ->
                for (o in items) {
                    w.write(o.toString())
                    w.newLine()
                }
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun add(o: JSONObject) {
        items.add(o)
        if (items.size > 5000) items.removeAt(0)
        save()
    }

    @Synchronized
    fun peek(n: Int): List<JSONObject> = items.take(n)

    @Synchronized
    fun drop(n: Int) {
        var i = 0
        while (i < n && items.isNotEmpty()) {
            items.removeAt(0)
            i++
        }
        save()
    }

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun clear() {
        items.clear()
        save()
    }
}
