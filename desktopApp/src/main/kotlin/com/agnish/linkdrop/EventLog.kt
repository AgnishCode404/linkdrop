package com.agnish.linkdrop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Thread-safe list of recent server events, shown in the window. Keeps the newest [capacity]. */
class EventLog(private val capacity: Int = 200) {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun add(text: String) {
        val line = "${LocalTime.now().format(TIME)}  $text"
        _entries.update { (it + line).takeLast(capacity) }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
