package com.prirai.android.nira.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory log of navigation requests, recorded by [com.prirai.android.nira.request.AppRequestInterceptor].
 * GeckoView only surfaces top-level navigation loads to apps (not XHR/images/WebSockets),
 * so this is a request-level log, not a full network capture.
 */
object NetworkLog {

    data class Entry(
        val time: Long,
        val url: String,
        val lastUrl: String?,
        val isRedirect: Boolean,
        val isSubframe: Boolean,
        val isDirectNavigation: Boolean,
        val hasUserGesture: Boolean
    )

    private const val MAX_ENTRIES = 300

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun record(
        url: String,
        lastUrl: String?,
        isRedirect: Boolean,
        isSubframe: Boolean,
        isDirectNavigation: Boolean,
        hasUserGesture: Boolean
    ) {
        _entries.update {
            (listOf(
                Entry(
                    time = System.currentTimeMillis(),
                    url = url,
                    lastUrl = lastUrl,
                    isRedirect = isRedirect,
                    isSubframe = isSubframe,
                    isDirectNavigation = isDirectNavigation,
                    hasUserGesture = hasUserGesture
                )
            ) + it).take(MAX_ENTRIES)
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
