package com.prirai.android.nira.devtools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prirai.android.nira.R
import com.prirai.android.nira.ext.components
import com.prirai.android.nira.ext.enableEdgeToEdgeMode
import com.prirai.android.nira.ext.isAppInDarkTheme
import com.prirai.android.nira.theme.FirefoxTheme
import com.prirai.android.nira.theme.applyCompleteTheme
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SecurityInfo
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * In-app developer tools. GeckoView exposes no on-device DevTools panels, so this
 * surface provides request-level network logging, a JS console via a bundled
 * WebExtension bridge, view-source, cookie inspection, and TLS security info.
 */
class DeveloperToolsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NOTE: setContent must come first so the decor view exists — applyCompleteTheme
        // touches window.insetsController (ThemeManager.applySystemBarsTheme) and NPEs
        // with a null decor view. Mirrors SettingsActivity's content-then-theme order.
        setContent {
            FirefoxTheme(darkTheme = isAppInDarkTheme()) {
                DevToolsScreen(onBack = { finish() })
            }
        }
        applyCompleteTheme(this)
        enableEdgeToEdgeMode()
    }
}

@Composable
private fun DevToolsScreen(onBack: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.devtools_network),
        stringResource(R.string.devtools_console),
        stringResource(R.string.devtools_view_source),
        stringResource(R.string.devtools_cookies),
        stringResource(R.string.devtools_security)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FirefoxTheme.colors.layer1)
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "‹",
                fontSize = 30.sp,
                color = FirefoxTheme.colors.textPrimary,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(8.dp)
            )
            Text(
                text = stringResource(R.string.devtools_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = FirefoxTheme.colors.textPrimary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            tabs.forEachIndexed { index, title ->
                Text(
                    text = title,
                    fontSize = 12.sp,
                    color = if (index == selectedTab) FirefoxTheme.colors.textAccent
                    else FirefoxTheme.colors.textSecondary,
                    fontWeight = if (index == selectedTab) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedTab = index }
                        .padding(vertical = 10.dp)
                )
            }
        }
        HorizontalDivider(color = FirefoxTheme.colors.borderPrimary, thickness = 1.dp)

        when (selectedTab) {
            0 -> NetworkTab()
            1 -> ConsoleTab()
            2 -> ViewSourceTab(onBack)
            3 -> CookiesTab()
            else -> SecurityTab()
        }
    }
}

@Composable
private fun NetworkTab() {
    val entries by NetworkLog.entries.collectAsState()
    var expandedId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.devtools_network_note),
                fontSize = 11.sp,
                color = FirefoxTheme.colors.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { NetworkLog.clear() }) {
                Text(stringResource(R.string.devtools_clear))
            }
        }
        HorizontalDivider(color = FirefoxTheme.colors.borderPrimary, thickness = 1.dp)

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.devtools_no_requests),
                color = FirefoxTheme.colors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.requestId }) { entry ->
                    NetworkEntryRow(
                        entry = entry,
                        expanded = expandedId == entry.requestId,
                        onToggle = {
                            expandedId = if (expandedId == entry.requestId) null else entry.requestId
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkEntryRow(
    entry: NetworkLog.Entry,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.method,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = FirefoxTheme.colors.textAccent
            )
            Spacer(Modifier.width(10.dp))
            entry.statusCode?.let {
                Text(
                    text = it.toString(),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (it >= 400) FirefoxTheme.colors.textWarning else FirefoxTheme.colors.textAccent
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = entry.requestType,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = FirefoxTheme.colors.textSecondary,
                maxLines = 1
            )
        }
        Text(
            text = entry.url,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = FirefoxTheme.colors.textPrimary,
            maxLines = if (expanded) Int.MAX_VALUE else 2
        )
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            if (entry.requestHeaders.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.devtools_request_headers),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = FirefoxTheme.colors.textSecondary
                )
                entry.requestHeaders.forEach { HeaderRow(it) }
            }
            if (entry.responseHeaders.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.devtools_response_headers),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = FirefoxTheme.colors.textSecondary
                )
                entry.responseHeaders.forEach { HeaderRow(it) }
            }
            if (entry.requestHeaders.isEmpty() && entry.responseHeaders.isEmpty()) {
                Text(
                    text = stringResource(R.string.devtools_no_headers),
                    fontSize = 11.sp,
                    color = FirefoxTheme.colors.textDisabled
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(header: NetworkLog.Header) {
    Text(
        text = "${header.name}: ${header.value}",
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = FirefoxTheme.colors.textSecondary,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
    )
}

@Composable
private fun ConsoleTab() {
    val consoleEntries by DevToolsBridge.consoleEntries.collectAsState()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val notReady = stringResource(R.string.devtools_console_unavailable)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.devtools_console_hint),
            fontSize = 11.sp,
            color = FirefoxTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        status?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = FirefoxTheme.colors.textWarning,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            items(consoleEntries) { entry ->
                Text(
                    text = entry.text,
                    fontSize = 13.sp,
                    color = FirefoxTheme.colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                )
            }
        }
        HorizontalDivider(color = FirefoxTheme.colors.borderPrimary, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .background(FirefoxTheme.colors.layer3, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(
                    color = FirefoxTheme.colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                singleLine = true,
                cursorBrush = SolidColor(FirefoxTheme.colors.layerAccent),
                decorationBox = { innerTextField ->
                    if (input.isEmpty()) {
                        Text(
                            text = "document.title",
                            color = FirefoxTheme.colors.textDisabled,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val code = input.trim()
                    input = ""
                    if (code.isNotEmpty()) {
                        status = if (DevToolsBridge.eval(code)) null else notReady
                    }
                }
            ) {
                Text(stringResource(R.string.devtools_run))
            }
        }
    }
}

@Composable
private fun ViewSourceTab(onBack: () -> Unit) {
    val context = LocalContext.current
    val browserState by context.components.store.stateFlow.collectAsState()
    val url = browserState.selectedTab?.content?.url
    val isHttp = url != null && (url.startsWith("http://") || url.startsWith("https://"))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.devtools_view_source),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = FirefoxTheme.colors.textPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = url ?: stringResource(R.string.devtools_no_url),
            fontSize = 14.sp,
            color = FirefoxTheme.colors.textPrimary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Button(
            onClick = {
                url?.let {
                    context.components.tabsUseCases.addTab("view-source:$it", selectTab = true)
                    onBack()
                }
            },
            enabled = isHttp
        ) {
            Text(stringResource(R.string.devtools_open_source))
        }
    }
}

@Composable
private fun CookiesTab() {
    val context = LocalContext.current
    val cookiesResult by DevToolsBridge.cookies.collectAsState()
    val browserState by context.components.store.stateFlow.collectAsState()
    val url = browserState.selectedTab?.content?.url
    var status by remember { mutableStateOf<String?>(null) }
    val notReady = stringResource(R.string.devtools_console_unavailable)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.devtools_cookies),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = FirefoxTheme.colors.textPrimary
        )
        Text(
            text = url ?: stringResource(R.string.devtools_no_url),
            fontSize = 14.sp,
            color = FirefoxTheme.colors.textPrimary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = {
            status = if (DevToolsBridge.refreshCookies()) null else notReady
        }) {
            Text(stringResource(R.string.devtools_refresh))
        }
        status?.let {
            Text(
                text = it,
                color = FirefoxTheme.colors.textWarning,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            cookiesResult == null -> Text(
                text = "Press ${stringResource(R.string.devtools_refresh)} to read document.cookie for this page.",
                color = FirefoxTheme.colors.textSecondary,
                fontSize = 13.sp
            )
            !cookiesResult!!.ok -> Text(
                text = "Error: ${cookiesResult!!.value}",
                color = FirefoxTheme.colors.textWarning,
                fontSize = 13.sp
            )
            cookiesResult!!.value.isBlank() -> Text(
                text = stringResource(R.string.devtools_no_cookies),
                color = FirefoxTheme.colors.textSecondary,
                fontSize = 13.sp
            )
            else -> Text(
                text = cookiesResult!!.value,
                color = FirefoxTheme.colors.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SecurityTab() {
    val context = LocalContext.current
    val browserState by context.components.store.stateFlow.collectAsState()
    val info = browserState.selectedTab?.content?.securityInfo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        when (info) {
            null -> Text(
                text = stringResource(R.string.devtools_no_security_info),
                color = FirefoxTheme.colors.textSecondary,
                fontSize = 13.sp
            )
            is SecurityInfo.Secure -> {
                Text(
                    text = "SECURE",
                    color = Color(0xFF4CAF50),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                KeyValue("Host", info.host)
                KeyValue("Issuer", info.issuer)
                info.certificate?.let { cert ->
                    val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                    KeyValue("Valid from", cert.notBefore?.let { fmt.format(it) })
                    KeyValue("Valid to", cert.notAfter?.let { fmt.format(it) })
                    KeyValue("Subject", cert.subjectX500Principal?.name)
                }
            }
            is SecurityInfo.Insecure -> Text(
                text = "NOT SECURE",
                color = FirefoxTheme.colors.textWarning,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            is SecurityInfo.Unknown -> Text(
                text = "Unknown",
                color = FirefoxTheme.colors.textSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "$key:",
            fontSize = 13.sp,
            color = FirefoxTheme.colors.textSecondary,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value ?: "—",
            fontSize = 13.sp,
            color = FirefoxTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}
