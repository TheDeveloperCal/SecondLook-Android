package com.developercal.secondlook

import android.app.Activity
import android.app.role.RoleManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.developercal.secondlook.analysis.*
import com.developercal.secondlook.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent { SecondLookRoot() }
    }
}

private enum class Page(val label: String) { HOME("Home"), MESSAGE("Message"), LINK("Link"), CALL("Call"), PROTECTION("Protection"), SETTINGS("Settings"), HISTORY("History"), RESULT("Result") }

private val Navy = Color(0xFF14263D)
private val Canvas = Color(0xFFF7F6F2)
private val Amber = Color(0xFFB66A14)
private val Ink = Color(0xFF1F2937)
private val Muted = Color(0xFF637083)

@Composable
private fun SecondLookRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as SecondLookApp
    val settings = app.settings
    var page by remember { mutableStateOf(Page.HOME) }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var resultKind by remember { mutableStateOf("Message") }
    var history by remember { mutableStateOf(emptyList<SavedScan>()) }
    var blocklist by remember { mutableStateOf(emptyList<BlockedNumber>()) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(primary = Color(0xFFFFBD68), background = Color(0xFF101923), surface = Color(0xFF182433), onSurface = Color(0xFFEAF0F5)) else lightColorScheme(primary = Navy, background = Canvas, surface = Color.White, onSurface = Ink, secondary = Amber)

    fun refresh() {
        scope.launch {
            history = withContext(Dispatchers.IO) { app.database.dao().listScans() }
            blocklist = withContext(Dispatchers.IO) { app.database.dao().listBlocked() }
        }
    }
    LaunchedEffect(page, revision) { refresh() }
    fun analyze(kind: String, analysis: AnalysisResult) {
        resultKind = kind
        result = analysis
        if (settings.historyEnabled) scope.launch(Dispatchers.IO) {
            app.database.dao().saveScan(SavedScan(kind = kind, level = analysis.level.name, report = reportText(kind, analysis)))
        }
        page = Page.RESULT
    }

    MaterialTheme(colorScheme = scheme, typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp), titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold), titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Navy), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.RemoveRedEye, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                        Text("SecondLook", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    } },
                    navigationIcon = { if (page == Page.RESULT || page == Page.HISTORY) IconButton(onClick = { page = Page.HOME }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                    actions = { Text("CHECK BEFORE YOU TRUST", fontSize = 9.sp, letterSpacing = 1.sp, color = Muted, modifier = Modifier.padding(end = 14.dp)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            bottomBar = {
                if (page !in listOf(Page.RESULT, Page.HISTORY)) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    val tabs = listOf(Page.HOME to Icons.Outlined.Home, Page.MESSAGE to Icons.Outlined.Forum, Page.LINK to Icons.Outlined.Link, Page.CALL to Icons.Outlined.Call, Page.PROTECTION to Icons.Outlined.Shield, Page.SETTINGS to Icons.Outlined.Settings)
                    tabs.forEach { (tab, icon) -> NavigationBarItem(selected = page == tab, onClick = { page = tab }, icon = { Icon(icon, null) }, label = { Text(tab.label, fontSize = 10.sp) }) }
                }
            }
        ) { padding ->
            when (page) {
                Page.HOME -> HomeScreen(onNavigate = { page = it }, onSample = { sample -> analyze("Example", MessageAnalyzer.analyze(sample)) }, historyEnabled = settings.historyEnabled, historyCount = history.size)
                Page.MESSAGE -> MessageScreen(onAnalyze = { text -> analyze("Message", MessageAnalyzer.analyze(text)) })
                Page.LINK -> LinkScreen(onAnalyze = { link -> analyze("Link", UrlInspector.inspect(link)) })
                Page.CALL -> CallAssistantScreen(onAnalyze = { text -> analyze("Call notes", MessageAnalyzer.analyze(text)) })
                Page.PROTECTION -> ProtectionScreen(settings = settings, entries = blocklist, refresh = { revision++ })
                Page.SETTINGS -> SettingsScreen(settings = settings, historyCount = history.size, onOpenHistory = { page = Page.HISTORY }, onClearHistory = { scope.launch(Dispatchers.IO) { app.database.dao().clearScans(); history = emptyList() } }, onChanged = { revision++ })
                Page.HISTORY -> HistoryScreen(entries = history, onClear = { scope.launch(Dispatchers.IO) { app.database.dao().clearScans(); history = emptyList() } })
                Page.RESULT -> ResultScreen(kind = resultKind, result = result, onCopy = { value ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SecondLook explanation", value))
                    Toast.makeText(context, "Explanation copied", Toast.LENGTH_SHORT).show()
                }, onShare = { value -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value) }, "Share explanation")) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(onNavigate: (Page) -> Unit, onSample: (String) -> Unit, historyEnabled: Boolean, historyCount: Int) {
    val samples = listOf(
        "A delivery company says a package is held and asks you to pay a small fee using this link.",
        "Your bank account will be locked today. Reply with the one-time verification code to keep access.",
        "Your appointment is confirmed for Tuesday at 2 PM. Reply C to confirm or call the clinic using your usual number."
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(2.dp))
        Text("Take a second look.", style = MaterialTheme.typography.titleLarge)
        Text("Understand warning signs before you reply, pay, or click.", color = Muted)
        Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Outlined.Lock, null, tint = Color(0xFFFFBD68)); Text("Private by design", color = Color.White, fontWeight = FontWeight.SemiBold) }
                Text("Messages and links are examined on this device. Nothing is sent to a server. Results are guidance, never a guarantee.", color = Color(0xFFE7EDF4), lineHeight = 22.sp)
            }
        }
        Text("What would you like to check?", style = MaterialTheme.typography.titleMedium)
        ActionCard("Check a message", "Texts, emails, payment requests", Icons.Outlined.Forum, "Start with the words they sent", onClick = { onNavigate(Page.MESSAGE) })
        ActionCard("Check a link", "Inspect its address without opening it", Icons.Outlined.Link, "A format check cannot verify a site", onClick = { onNavigate(Page.LINK) })
        ActionCard("Talk through a call", "Review what the caller claimed or asked", Icons.Outlined.Call, "Caller ID can be spoofed", onClick = { onNavigate(Page.CALL) })
        ActionCard("Call protection", "Manage your local blocklist and options", Icons.Outlined.Shield, "Optional Android call-screening role", onClick = { onNavigate(Page.PROTECTION) })
        if (historyEnabled) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent checks", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onNavigate(Page.HISTORY) }) { Text("See all · $historyCount") }
        }
        Text("Try a fictional example", style = MaterialTheme.typography.titleMedium)
        samples.forEachIndexed { i, text ->
            OutlinedCard(onClick = { onSample(text) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(if (i == 2) Color(0xFFE8F0E9) else Color(0xFFFFF0DA)), contentAlignment = Alignment.Center) { Text("${i + 1}", color = if (i == 2) Color(0xFF276341) else Amber, fontWeight = FontWeight.Bold) }
                    Text(text, modifier = Modifier.weight(1f), lineHeight = 21.sp)
                    Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
                }
            }
        }
        Text("SecondLook checks a limited set of explainable English-language patterns. It cannot identify every scam, verify a sender, or check a site's current reputation.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, footnote: String, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFEAF0F5)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Navy) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, fontSize = 14.sp); Text(footnote, color = Amber, fontSize = 12.sp) }
            Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
private fun MessageScreen(onAnalyze: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Check a message", style = MaterialTheme.typography.titleLarge)
        Text("Paste a text, email, social message, or payment request. It stays on this device.", color = Muted, lineHeight = 21.sp)
        OutlinedTextField(value = text, onValueChange = { text = it.take(100_000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp), label = { Text("Message or email") }, placeholder = { Text("Paste or type the message here…") }, shape = RoundedCornerShape(16.dp), supportingText = { Text("${text.length} characters · English-focused checks") })
        Button(onClick = { onAnalyze(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Outlined.Search, null); Spacer(Modifier.width(9.dp)); Text("Analyze on this device") }
        Text("We look for specific patterns such as requests for codes, unusual payment methods, threats, and pressure. No spelling or grammar checks are used.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun LinkScreen(onAnalyze: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Check a link", style = MaterialTheme.typography.titleLarge)
        Text("We inspect the address structure only. The link is never opened, fetched, or checked against a live list.", color = Muted, lineHeight = 21.sp)
        OutlinedTextField(value = value, onValueChange = { value = it.take(16_384) }, modifier = Modifier.fillMaxWidth(), label = { Text("Web address") }, placeholder = { Text("https://example.com/path") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Button(onClick = { onAnalyze(value) }, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Outlined.TravelExplore, null); Spacer(Modifier.width(9.dp)); Text("Inspect address") }
        InfoPanel("A normal-looking address is not proof that a website is legitimate. A shortened link hides its destination; an IP host or punycode can be legitimate too.")
    }
}

@Composable
private fun CallAssistantScreen(onAnalyze: (String) -> Unit) {
    var claim by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    var immediate by remember { mutableStateOf(false) }
    var threat by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(false) }
    var payment by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("Talk through a call", style = MaterialTheme.typography.titleLarge)
        Text("Remember what happened? Describe the call; no recording or microphone is used.", color = Muted, lineHeight = 21.sp)
        InfoPanel("Caller ID can be spoofed. A familiar-looking number does not prove who called.")
        OutlinedTextField(claim, { claim = it.take(2_000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Who did they say they were?") }, placeholder = { Text("For example, a bank or delivery company") }, shape = RoundedCornerShape(14.dp))
        OutlinedTextField(request, { request = it.take(2_000) }, modifier = Modifier.fillMaxWidth(), label = { Text("What did they ask you to do?") }, placeholder = { Text("Describe the request in your own words") }, shape = RoundedCornerShape(14.dp))
        Text("Did they…", style = MaterialTheme.typography.titleMedium)
        CheckRow("Demand immediate action", immediate) { immediate = it }
        CheckRow("Threaten consequences", threat) { threat = it }
        CheckRow("Ask for a code or password", code) { code = it }
        CheckRow("Ask for a payment or transfer", payment) { payment = it }
        CheckRow("Ask you to install remote access", remote) { remote = it }
        Button(onClick = {
            val details = buildList { if (claim.isNotBlank()) add("This is $claim."); if (request.isNotBlank()) add(request.trim().trimEnd('.') + "."); if (immediate) add("They demanded that I act now."); if (threat) add("They threatened account closure or legal consequences."); if (code) add("They requested a verification code or password."); if (payment) add("They asked for an unexpected payment to resolve an account issue."); if (remote) add("They asked to install remote-access software.") }.joinToString(" ")
            onAnalyze(details)
        }, enabled = claim.isNotBlank() || request.isNotBlank() || immediate || threat || code || payment || remote, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) { Text("Review call details") }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, onCheckedChange = onChecked); Text(label, modifier = Modifier.weight(1f)) }
}

@Composable
private fun ResultScreen(kind: String, result: AnalysisResult?, onCopy: (String) -> Unit, onShare: (String) -> Unit) {
    val current = result ?: return
    val summary = reportText(kind, current)
    val (label, color, icon) = when (current.level) {
        ResultLevel.HIGH -> Triple("High-risk indicators found", Color(0xFF9E3E34), Icons.Outlined.Report)
        ResultLevel.SEVERAL -> Triple("Several warning signs found", Color(0xFFAD6111), Icons.Outlined.WarningAmber)
        ResultLevel.CAUTION -> Triple("Some caution warranted", Color(0xFF9A701A), Icons.Outlined.Info)
        ResultLevel.NONE -> Triple("No obvious warning signs found", Color(0xFF526273), Icons.Outlined.Search)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("$kind check", style = MaterialTheme.typography.titleLarge)
        Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.09f)), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) { Icon(icon, null, tint = color); Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 19.sp) }
                Text(current.summary, lineHeight = 22.sp)
            }
        }
        Text(if (current.findings.isEmpty()) "What this means" else "What we noticed", style = MaterialTheme.typography.titleMedium)
        if (current.findings.isEmpty()) InfoPanel("No listed rule matched this time. SecondLook cannot verify who sent the content or guarantee that it is safe.")
        current.findings.forEach { finding ->
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(finding.title, fontWeight = FontWeight.SemiBold)
                    Text(finding.explanation, color = Muted, lineHeight = 21.sp)
                }
            }
        }
        if (current.recommendations.isNotEmpty()) {
            Text("Safer next steps", style = MaterialTheme.typography.titleMedium)
            current.recommendations.take(if (current.level == ResultLevel.NONE) 2 else 4).forEach { recommendation -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.CheckCircleOutline, null, tint = Navy, modifier = Modifier.size(19.dp)); Text(recommendation, modifier = Modifier.weight(1f), lineHeight = 21.sp) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onCopy(summary) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(7.dp)); Text("Copy explanation") }
            Button(onClick = { onShare(summary) }, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Outlined.Send, null); Spacer(Modifier.width(7.dp)); Text("Share report") }
        }
        Text("This result is guidance, not a guarantee. The original message is not included when you share.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun ProtectionScreen(settings: LocalSettings, entries: List<BlockedNumber>, refresh: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(settings.protectionEnabled) }
    var blockEnabled by remember { mutableStateOf(settings.blocklistEnabled) }
    var privateEnabled by remember { mutableStateOf(settings.privateNumbersEnabled) }
    var number by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var roleHeld by remember { mutableStateOf(false) }
    var roleAvailable by remember { mutableStateOf(Build.VERSION.SDK_INT >= 29) }
    val scope = rememberCoroutineScope()
    fun updateRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val manager = context.getSystemService(RoleManager::class.java)
            roleAvailable = manager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
            roleHeld = roleAvailable && manager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } else { roleAvailable = false; roleHeld = false }
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateRole() }
    LaunchedEffect(Unit) { updateRole() }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Call protection", style = MaterialTheme.typography.titleLarge)
        Text("SecondLook only screens calls when Android grants its call-screening role. It does not check unknown numbers against a reputation database.", color = Muted, lineHeight = 21.sp)
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Outlined.Shield, null, tint = Navy); Text(if (roleHeld) "Android screening role is active" else "Android screening role is not active", fontWeight = FontWeight.SemiBold) }
        Text(when { !roleAvailable -> "Role-based call screening requires Android 10 or newer."; roleHeld -> "Configured rules run locally. Unknown numbers are allowed."; else -> "Choose SecondLook in Android's official role prompt to enable screening." }, color = Muted, lineHeight = 21.sp)
                Text("Android may not pass calls from saved contacts to screening apps. SecondLook does not read your contacts.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                if (roleAvailable && !roleHeld) Button(onClick = {
                    try {
                        val manager = context.getSystemService(RoleManager::class.java)
                        if (manager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true) roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                    } catch (_: Exception) { Toast.makeText(context, "Android call screening is unavailable on this device", Toast.LENGTH_LONG).show() }
                }, modifier = Modifier.fillMaxWidth()) { Text("Choose call-screening app") }
            }
        }
        SettingSwitch("Enable my call rules", "Only active when the Android role is granted", enabled) { enabled = it; settings.protectionEnabled = it }
        SettingSwitch("Block numbers on my list", "Uses an exact normalized match", blockEnabled) { blockEnabled = it; settings.blocklistEnabled = it }
        SettingSwitch("Silence private or withheld calls", "Off by default · This can silence legitimate callers", privateEnabled) { privateEnabled = it; settings.privateNumbersEnabled = it }
        Text("Your local blocklist", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(number, { number = it.take(64) }, modifier = Modifier.fillMaxWidth(), label = { Text("Phone number") }, placeholder = { Text("Include country code when known") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        OutlinedTextField(label, { label = it.take(60) }, modifier = Modifier.fillMaxWidth(), label = { Text("Label (optional)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        OutlinedButton(onClick = {
            val normalized = CallDecisionEngine.normalize(number)
            if (normalized == null) Toast.makeText(context, "Enter a number with 7–15 digits", Toast.LENGTH_SHORT).show()
            else scope.launch(Dispatchers.IO) { (context.applicationContext as SecondLookApp).database.dao().addBlocked(BlockedNumber(normalized, label)); withContext(Dispatchers.Main) { number = ""; label = ""; refresh() } }
        }, enabled = number.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to blocklist") }
        if (entries.isEmpty()) Text("No numbers added. Unknown callers are not blocked.", color = Muted)
        entries.forEach { entry ->
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(entry.label.ifBlank { entry.normalized }, fontWeight = FontWeight.Medium); if (entry.label.isNotBlank()) Text(entry.normalized, color = Muted, fontSize = 13.sp) }
                    IconButton(onClick = { scope.launch(Dispatchers.IO) { (context.applicationContext as SecondLookApp).database.dao().removeBlocked(entry.normalized); withContext(Dispatchers.Main) { refresh() } } }, modifier = Modifier.semantics { contentDescription = "Remove ${entry.normalized}" }) { Icon(Icons.Outlined.DeleteOutline, "Remove number") }
                }
            }
        }
        Text("To disable screening, turn off these rules here or select another call-screening app in Android Settings. The service still allows calls when local storage fails.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun SettingsScreen(settings: LocalSettings, historyCount: Int, onOpenHistory: () -> Unit, onClearHistory: () -> Unit, onChanged: () -> Unit) {
    var historyEnabled by remember { mutableStateOf(settings.historyEnabled) }
    var theme by remember { mutableStateOf(settings.theme) }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Settings & privacy", style = MaterialTheme.typography.titleLarge)
        InfoPanel("Manual checks happen locally. SecondLook has no account, ads, analytics, or network permission. Your blocklist stays on this device.")
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) -> FilterChip(selected = theme == value, onClick = { theme = value; settings.theme = value; onChanged() }, label = { Text(label) }) }
        }
        Text("Scan history", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("Save results on this device", "Off by default · Original submitted content is not saved", historyEnabled) { checked ->
            historyEnabled = checked
            settings.historyEnabled = checked
            if (!checked) onClearHistory()
            onChanged()
        }
        if (historyEnabled) {
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.History, null); Spacer(Modifier.width(7.dp)); Text("View saved results ($historyCount)") }
            TextButton(onClick = onClearHistory, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Clear history") }
        }
        Text("About checks", style = MaterialTheme.typography.titleMedium)
        Text("The initial rules focus on English. They cannot verify identities, know about newly reported scams, or confirm a website's reputation. A result with no obvious warning signs is not a safety guarantee.", color = Muted, lineHeight = 21.sp)
    }
}

@Composable
private fun HistoryScreen(entries: List<SavedScan>, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Saved checks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); if (entries.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear all") } }
        if (entries.isEmpty()) EmptyState("No saved checks", "Enable scan history in Settings to keep future results on this device.", Icons.Outlined.History)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(entries, key = { it.id }) { entry ->
            Card(shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("${entry.kind} · ${entry.level.lowercase().replaceFirstChar { it.uppercase() }}", fontWeight = FontWeight.SemiBold); Text(entry.report, color = Muted, maxLines = 4, lineHeight = 19.sp); Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.createdAt)), color = Muted, fontSize = 12.sp) }
            }
        } }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 70.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(description, color = Muted, fontSize = 13.sp, lineHeight = 18.sp) }
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoPanel(text: String) {
    Card(shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2DF))) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.Info, null, tint = Amber, modifier = Modifier.size(19.dp)); Text(text, color = Color(0xFF5A4731), lineHeight = 20.sp, fontSize = 14.sp) } }
}

@Composable
private fun EmptyState(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Icon(icon, null, tint = Muted, modifier = Modifier.size(38.dp)); Text(title, style = MaterialTheme.typography.titleMedium); Text(body, color = Muted) }
}

private fun reportText(kind: String, result: AnalysisResult): String = buildString {
    append("SecondLook · $kind check\n")
    append(when (result.level) { ResultLevel.HIGH -> "High-risk indicators found"; ResultLevel.SEVERAL -> "Several warning signs found"; ResultLevel.CAUTION -> "Some caution warranted"; ResultLevel.NONE -> "No obvious warning signs found" })
    append("\n${result.summary}\n")
    result.findings.forEach { append("\n• ${it.title}: ${it.explanation}") }
    append("\n\nGuidance, not a guarantee. Verify independently through a trusted channel.")
}
