package com.widscore.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

// Logs embarqués : ce qui est grabbé (ligues, comptes, erreurs, dedup, cache).
// Écran Settings : lecture + bouton copier tout + effacer.
object LogStore {
    private const val FILE = "football_log.txt"
    private const val MAX_LINES = 500
    private const val MAX_BYTES = 200_000L
    private val buf = ArrayDeque<String>()
    @Volatile private var appCtx: Context? = null
    private val lock = Any()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        synchronized(lock) {
            if (buf.isEmpty()) {
                try {
                    val f = File(appCtx!!.filesDir, FILE)
                    if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { buf.addLast(it) }
                } catch (_: Exception) {}
            }
        }
    }

    fun log(tag: String, msg: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + " [$tag] $msg"
        synchronized(lock) {
            buf.addLast(line)
            while (buf.size > MAX_LINES) buf.removeFirst()
        }
        try {
            val ctx = appCtx ?: return
            val f = File(ctx.filesDir, FILE)
            if (f.exists() && f.length() > MAX_BYTES) {
                f.writeText(buf.joinToString("\n") + "\n")
            } else {
                f.appendText(line + "\n")
            }
        } catch (_: Exception) {}
    }

    fun lines(): List<String> = synchronized(lock) { buf.toList() }

    fun clear(ctx: Context) {
        synchronized(lock) { buf.clear() }
        try { File(ctx.filesDir, FILE).delete() } catch (_: Exception) {}
    }
}
