package com.prirai.android.nira.devtools

import com.prirai.android.nira.components.Components
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Owns the bundled DevTools bridge [WebExtension] and the in-memory console/cookie logs.
 *
 * The extension's content script (assets/extensions/devtools/content.js) connects a port
 * named [PORT_NAME] on every page load. The handler is registered for every session so the
 * port connects with a listener already in place; results then flow back into the
 * [consoleEntries] / [cookies] state flows.
 */
object DevToolsBridge {

    const val EXTENSION_ID = "devtools-bridge@aurabrowse"
    const val PORT_NAME = "aurabrowse-devtools"

    // Native-app port name used by background.js's browser.runtime.connectNative() to
    // stream browser.webRequest events to the app.
    const val NETWORK_NATIVE_APP = "network"

    @Volatile
    var extension: WebExtension? = null

    private var components: Components? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val registeredSessions =
        Collections.newSetFromMap(java.util.IdentityHashMap<EngineSession, Boolean>())

    data class ConsoleEntry(val time: Long, val text: String)
    data class CookiesResult(val ok: Boolean, val value: String)

    private const val MAX_CONSOLE_ENTRIES = 200

    private val _consoleEntries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val consoleEntries: StateFlow<List<ConsoleEntry>> = _consoleEntries

    private val _cookies = MutableStateFlow<CookiesResult?>(null)
    val cookies: StateFlow<CookiesResult?> = _cookies

    private fun appendConsole(text: String) {
        _consoleEntries.update {
            (listOf(ConsoleEntry(System.currentTimeMillis(), text)) + it).take(MAX_CONSOLE_ENTRIES)
        }
    }

    private val messageHandler = object : MessageHandler {
        override fun onPortConnected(port: Port) = Unit

        override fun onPortMessage(message: Any, port: Port) {
            when (extractString(message, "type")) {
                "network" -> handleNetworkMessage(message)
                // Only surface eval results; ignore the "ready" ping.
                "evalResult" -> {
                    val ok = extractString(message, "ok") == "true"
                    val value = extractString(message, "result") ?: extractString(message, "error") ?: ""
                    when (extractString(message, "target")) {
                        "cookies" -> _cookies.value = CookiesResult(ok = ok, value = value)
                        else -> appendConsole(if (ok) "› $value" else "✗ $value")
                    }
                }
            }
        }
    }

    private fun handleNetworkMessage(message: Any) {
        val requestId = extractString(message, "requestId") ?: return
        val event = extractString(message, "event") ?: return
        android.util.Log.d(
            "DevTools",
            "network: $event ${extractString(message, "method")} ${extractString(message, "url")}"
        )
        NetworkLog.recordEvent(
            requestId = requestId,
            event = event,
            url = extractString(message, "url") ?: "",
            method = extractString(message, "method") ?: "",
            requestType = extractString(message, "requestType") ?: "",
            statusCode = extractInt(message, "statusCode"),
            headers = extractHeaders(
                message,
                if (event == "request") "requestHeaders" else "responseHeaders"
            )
        )
    }

    /** Installs the message handler for every existing session and every future one. */
    fun init(components: Components, extension: WebExtension) {
        this.components = components
        this.extension = extension

        // Existing tabs (including ones restored from a previous session).
        components.store.state.tabs.forEach { tab ->
            tab.engineState.engineSession?.let { register(it) }
        }

        // Future tabs.
        scope.launch {
            components.store.stateFlow.collect { state ->
                state.tabs.forEach { tab ->
                    tab.engineState.engineSession?.let { register(it) }
                }
            }
        }

        // Receives browser.webRequest events streamed by background.js over the
        // "network" native-app port.
        extension.registerBackgroundMessageHandler(NETWORK_NATIVE_APP, messageHandler)
    }

    private fun register(session: EngineSession) {
        if (registeredSessions.add(session)) {
            extension?.registerContentMessageHandler(session, PORT_NAME, messageHandler)
        }
    }

    /**
     * Sends an eval request to the currently selected tab. Returns false when no port is
     * connected yet (reload the page once so the content script connects).
     */
    private fun sendEval(code: String, target: String): Boolean {
        val components = components ?: return false
        val ext = extension ?: return false
        val session = components.store.state.selectedTab?.engineState?.engineSession ?: return false

        register(session)
        val port = ext.getConnectedPort(PORT_NAME, session) ?: return false
        if (code.isBlank()) return true

        port.postMessage(JSONObject().put("type", "eval").put("code", code).put("target", target))
        return true
    }

    /** Evaluates [code] in the current page; results appear in [consoleEntries]. */
    fun eval(code: String): Boolean {
        appendConsole("> $code")
        return sendEval(code, "console")
    }

    /** Reads document.cookie on the current page; result appears in [cookies]. */
    fun refreshCookies(): Boolean = sendEval("document.cookie", "cookies")

    fun isPortConnected(): Boolean {
        val components = components ?: return false
        val ext = extension ?: return false
        val session = components.store.state.selectedTab?.engineState?.engineSession ?: return false
        return ext.getConnectedPort(PORT_NAME, session) != null
    }

    fun clearConsole() {
        _consoleEntries.value = emptyList()
    }

    private fun extractString(message: Any, key: String): String? = when (message) {
        is JSONObject -> if (message.has(key)) message.get(key)?.toString() else null
        is Map<*, *> -> message[key]?.toString()
        else -> null
    }

    private fun extractInt(message: Any, key: String): Int? = when (message) {
        is JSONObject -> (message.opt(key) as? Number)?.toInt()
        is Map<*, *> -> (message[key] as? Number)?.toInt()
        else -> null
    }

    private fun extractHeaders(message: Any, key: String): List<NetworkLog.Header> {
        val raw: Any? = when (message) {
            is JSONObject -> if (message.has(key)) message.get(key) else null
            is Map<*, *> -> message[key]
            else -> null
        } ?: return emptyList()
        return when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { i ->
                raw.optJSONObject(i)?.let { h ->
                    h.optString("name").takeIf { it.isNotEmpty() }
                        ?.let { NetworkLog.Header(it, h.optString("value")) }
                }
            }
            is List<*> -> raw.mapNotNull { h ->
                val map = h as? Map<*, *> ?: return@mapNotNull null
                val name = map["name"]?.toString() ?: return@mapNotNull null
                name.takeIf { it.isNotEmpty() }
                    ?.let { NetworkLog.Header(it, map["value"]?.toString() ?: "") }
            }
            else -> emptyList()
        }
    }
}
