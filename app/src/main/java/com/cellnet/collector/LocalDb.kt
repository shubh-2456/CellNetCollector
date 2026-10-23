package com.cellnet.collector

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SessionInfo(
    val id: Long, val name: String, val startedAt: Long, val endedAt: Long?,
    val serverId: Int?, val count: Int, val unsent: Int
)

class ReadingRow(
    val id: Long, val measuredAt: Long, val lat: Double?, val lon: Double?, val acc: Double?,
    val operator: String?, val network: String?, val rsrp: Int?, val rsrq: Int?, val sinr: Double?,
    val rssi: Int?, val level: Int?, val cellId: String?, val state: String?
)

class LocalDb(context: Context) : SQLiteOpenHelper(context, "collector.db", null, 1) {

    private val cols = "id, measured_at, latitude, longitude, gps_accuracy, operator, network_type, " +
        "rsrp, rsrq, sinr, rssi, signal_level, cell_id, state"

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "started_at INTEGER NOT NULL, ended_at INTEGER, server_session_id INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE readings (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, " +
                "measured_at INTEGER NOT NULL, latitude REAL, longitude REAL, gps_accuracy REAL, " +
                "operator TEXT, network_type TEXT, rsrp INTEGER, rsrq INTEGER, sinr REAL, rssi INTEGER, " +
                "signal_level INTEGER, cell_id TEXT, state TEXT, sent INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_readings_session ON readings (session_id, sent)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
    private fun Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private fun Cursor.stringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun rows(c: Cursor): List<ReadingRow> {
        val out = ArrayList<ReadingRow>()
        c.use {
            while (it.moveToNext()) {
                out.add(
                    ReadingRow(
                        it.getLong(0), it.getLong(1), it.doubleOrNull(2), it.doubleOrNull(3), it.doubleOrNull(4),
                        it.stringOrNull(5), it.stringOrNull(6), it.intOrNull(7), it.intOrNull(8), it.doubleOrNull(9),
                        it.intOrNull(10), it.intOrNull(11), it.stringOrNull(12), it.stringOrNull(13)
                    )
                )
            }
        }
        return out
    }

    private fun count(sql: String, arg: String): Int {
        var n = 0
        writableDatabase.rawQuery(sql, arrayOf(arg)).use {
            if (it.moveToFirst()) n = it.getInt(0)
        }
        return n
    }

    fun createSession(name: String, startedAt: Long): Long {
        val v = ContentValues()
        v.put("name", name)
        v.put("started_at", startedAt)
        return writableDatabase.insert("sessions", null, v)
    }

    fun setServerSession(id: Long, serverId: Int) {
        val v = ContentValues()
        v.put("server_session_id", serverId)
        writableDatabase.update("sessions", v, "id = ?", arrayOf(id.toString()))
    }

    fun clearServerSessions() {
        writableDatabase.execSQL("UPDATE sessions SET server_session_id = NULL")
    }

    fun endSession(id: Long, endedAt: Long) {
        val v = ContentValues()
        v.put("ended_at", endedAt)
        writableDatabase.update("sessions", v, "id = ?", arrayOf(id.toString()))
    }

    fun addReading(sessionId: Long, t: Long, lat: Double?, lon: Double?, acc: Double?, r: CellReading) {
        val v = ContentValues()
        v.put("session_id", sessionId)
        v.put("measured_at", t)
        if (lat != null) v.put("latitude", lat)
        if (lon != null) v.put("longitude", lon)
        if (acc != null) v.put("gps_accuracy", acc)
        if (r.operator != null) v.put("operator", r.operator)
        if (r.networkType != null) v.put("network_type", r.networkType)
        if (r.rsrp != null) v.put("rsrp", r.rsrp)
        if (r.rsrq != null) v.put("rsrq", r.rsrq)
        if (r.sinr != null) v.put("sinr", r.sinr)
        if (r.rssi != null) v.put("rssi", r.rssi)
        if (r.level != null) v.put("signal_level", r.level)
        if (r.cellId != null) v.put("cell_id", r.cellId)
        if (r.state != null) v.put("state", r.state)
        writableDatabase.insert("readings", null, v)
    }

    fun unsent(sessionId: Long, limit: Int): List<ReadingRow> =
        rows(
            writableDatabase.rawQuery(
                "SELECT $cols FROM readings WHERE session_id = ? AND sent = 0 ORDER BY measured_at ASC LIMIT $limit",
                arrayOf(sessionId.toString())
            )
        )

    fun markSent(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL("UPDATE readings SET sent = 1 WHERE id IN (" + ids.joinToString(",") + ")")
    }

    fun sessionReadings(sessionId: Long, limit: Int, newestFirst: Boolean): List<ReadingRow> {
        val order = if (newestFirst) "DESC" else "ASC"
        val lim = if (limit < 0) -1 else limit
        return rows(
            writableDatabase.rawQuery(
                "SELECT $cols FROM readings WHERE session_id = ? ORDER BY measured_at $order LIMIT $lim",
                arrayOf(sessionId.toString())
            )
        )
    }

    fun sessions(): List<SessionInfo> {
        val out = ArrayList<SessionInfo>()
        val c = writableDatabase.rawQuery(
            "SELECT s.id, s.name, s.started_at, s.ended_at, s.server_session_id, " +
                "(SELECT COUNT(*) FROM readings r WHERE r.session_id = s.id), " +
                "(SELECT COUNT(*) FROM readings r WHERE r.session_id = s.id AND r.sent = 0) " +
                "FROM sessions s ORDER BY s.started_at DESC",
            null
        )
        c.use {
            while (it.moveToNext()) {
                out.add(
                    SessionInfo(
                        it.getLong(0), it.getString(1), it.getLong(2), it.longOrNull(3),
                        it.intOrNull(4), it.getInt(5), it.getInt(6)
                    )
                )
            }
        }
        return out
    }

    fun totals(): Pair<Int, Int> {
        var s = 0
        var r = 0
        writableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM sessions), (SELECT COUNT(*) FROM readings)", null
        ).use {
            if (it.moveToFirst()) {
                s = it.getInt(0)
                r = it.getInt(1)
            }
        }
        return Pair(s, r)
    }

    fun readingCount(sessionId: Long): Int =
        count("SELECT COUNT(*) FROM readings WHERE session_id = ?", sessionId.toString())

    fun pendingCount(sessionId: Long): Int =
        count("SELECT COUNT(*) FROM readings WHERE session_id = ? AND sent = 0", sessionId.toString())

    fun deleteSession(id: Long) {
        writableDatabase.execSQL("DELETE FROM readings WHERE session_id = $id")
        writableDatabase.execSQL("DELETE FROM sessions WHERE id = $id")
    }

    fun deleteAll() {
        writableDatabase.execSQL("DELETE FROM readings")
        writableDatabase.execSQL("DELETE FROM sessions")
    }

    private fun q(s: String?): String {
        if (s == null) return ""
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return s
    }

    fun exportCsv(sessionId: Long?): String {
        val sb = StringBuilder()
        sb.append("session_id,session_name,measured_at_utc,latitude,longitude,gps_accuracy_m,operator,")
        sb.append("network_type,rsrp_dbm,rsrq_db,sinr_db,rssi_dbm,signal_level,cell_id,connection_state,rating\n")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        for (s in sessions().reversed()) {
            if (sessionId != null && s.id != sessionId) continue
            for (r in sessionReadings(s.id, -1, false)) {
                sb.append(s.id).append(',')
                sb.append(q(s.name)).append(',')
                sb.append(fmt.format(Date(r.measuredAt))).append(',')
                sb.append(r.lat?.toString() ?: "").append(',')
                sb.append(r.lon?.toString() ?: "").append(',')
                sb.append(r.acc?.toString() ?: "").append(',')
                sb.append(q(r.operator)).append(',')
                sb.append(q(r.network)).append(',')
                sb.append(r.rsrp?.toString() ?: "").append(',')
                sb.append(r.rsrq?.toString() ?: "").append(',')
                sb.append(r.sinr?.toString() ?: "").append(',')
                sb.append(r.rssi?.toString() ?: "").append(',')
                sb.append(r.level?.toString() ?: "").append(',')
                sb.append(q(r.cellId)).append(',')
                sb.append(q(r.state)).append(',')
                sb.append(Rules.label(Rules.overall(r.rsrp, r.rsrq, r.sinr))).append('\n')
            }
        }
        return sb.toString()
    }
}
