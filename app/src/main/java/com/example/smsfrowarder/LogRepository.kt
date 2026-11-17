package com.example.smsfrowarder

import android.content.Context
import com.example.smsfrowarder.model.LogEntry
import com.example.smsfrowarder.model.LogStatus
import com.google.gson.GsonBuilder
import java.io.File

class LogRepository private constructor(private val file: File) {

    companion object {
        private const val FILE_NAME = "forward_log.json"
        private const val MAX_ENTRIES = 500   // keep last 500 entries

        fun create(context: Context) =
            LogRepository(File(context.applicationContext.filesDir, FILE_NAME))
    }

    private val gson = GsonBuilder().create()

    @Synchronized
    fun getAll(): List<LogEntry> {
        if (!file.exists()) return emptyList()
        return try {
            gson.fromJson(file.readText(), Array<LogEntry>::class.java).toList()
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun add(entry: LogEntry) {
        val list = (getAll() + entry).takeLast(MAX_ENTRIES)
        file.writeText(gson.toJson(list))
    }

    @Synchronized
    fun update(entry: LogEntry) {
        val list = getAll().map { if (it.id == entry.id) entry else it }
        file.writeText(gson.toJson(list))
    }

    @Synchronized
    fun clearAll() {
        file.writeText("[]")
    }

    fun updateStatus(id: String, status: LogStatus, error: String = "") {
        val list = getAll().map {
            if (it.id == id) it.copy(status = status, errorMessage = error) else it
        }
        file.writeText(gson.toJson(list))
    }
}
