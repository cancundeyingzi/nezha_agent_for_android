package com.nezhahq.agent.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private val _logs = MutableStateFlow<List<String>>(listOf("System Logger Initialized."))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun i(message: String) {
        val time = dateFormat.format(Date())
        val newLog = "[$time] $message"
        addLog(newLog)
        android.util.Log.i("NezhaAgent", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        val time = dateFormat.format(Date())
        val err = throwable?.let { " - ${it.message ?: it.javaClass.simpleName}" } ?: ""
        val newLog = "[$time] ERROR: $message$err"
        addLog(newLog)
        android.util.Log.e("NezhaAgent", message, throwable)
    }

    private fun addLog(log: String) {
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(log)
        if (currentLogs.size > 200) {
            currentLogs.removeAt(0)
        }
        _logs.value = currentLogs
    }
    
    fun getLogString(): String {
        return _logs.value.joinToString("\n")
    }
}
