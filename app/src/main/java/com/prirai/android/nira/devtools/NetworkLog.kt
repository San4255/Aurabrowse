package com.prirai.android.nira.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory network log, fed by the DevTools bridge extension's background script via
 * browser.webRequest (the only GeckoView API that exposes real request headers, method,
 * type and status). Request and response events are correlated by [Entry.requestId] so
 * each entry shows both header sets and the status code in one row.
 */
object NetworkLog {

    data class Header(val name: String, val value: String)

    data class Entry(
        val time: Long,
        val url: String,
        val method: String,
        val requestType: String,
        val statusCode: Int?,
        val requestId: String,
        val requestHeaders: List<Header>,
        val responseHeaders: List<Header>
    )

    private const val MAX_ENTRIES = 300
    private const val MAX_PENDING = 500

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    // requestId -> entry still awaiting its response event (webRequest correlates the two).
    private val pending =
        java.util.Collections.synchronizedMap(java.util.LinkedHashMap<String, Entry>())

    /**
     * Records one webRequest event. Call with event="request" (onBeforeSendHeaders) and
     * event="response" (onHeadersReceived); the two are merged per requestId. May be called
     * from the Gecko message thread, so all state here is thread-safe.
     */
    fun recordEvent(
        requestId: String,
        event: String,
        url: String,
        method: String,
        requestType: String,
        statusCode: Int?,
        headers: List<Header>
    ) {
        if (event == "response") {
            val merged = pending[requestId]?.copy(statusCode = statusCode, responseHeaders = headers)
            if (merged != null) {
                pending.remove(requestId)
                _entries.update { list ->
                    (listOf(merged) + list.filterNot { it.requestId == requestId }).take(MAX_ENTRIES)
                }
                return
            }
        }
        val entry = Entry(
            time = System.currentTimeMillis(),
            url = url,
            method = method,
            requestType = requestType,
            statusCode = if (event == "response") statusCode else null,
            requestId = requestId,
            requestHeaders = if (event == "request") headers else emptyList(),
            responseHeaders = if (event == "response") headers else emptyList()
        )
        if (event == "request") {
            if (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
            pending[requestId] = entry
        }
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    fun clear() {
        pending.clear()
        _entries.value = emptyList()
    }
}
