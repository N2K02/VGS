@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.shizucallcompanion.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Pixel-style Material 3 Expressive theme: wallpaper colors + system light/dark mode. */
@Composable
fun ShizuCallCompanionTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    // Compose state
    private var tick by mutableIntStateOf(0)
    private var role by mutableStateOf(LinkService.ROLE_REMOTE)
    private var devices by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var selectedDevice by mutableStateOf<String?>(null)
    private var contacts by mutableStateOf<List<Contact>>(emptyList())
    /** The "Make a call" input, kept here so the contacts page can fill it. */
    private var dialQuery by mutableStateOf("")
    private var dialPicked by mutableStateOf<Contact?>(null)
    private var showContacts by mutableStateOf(false)
    /** False until the user has picked a role (first launch). */
    private var roleChosen by mutableStateOf(false)
    /** false = setup page (role + permissions), true = main page. */
    private var inMain by mutableStateOf(false)
    private var loadedContactsVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppUpdater.init(this, "N2K02/ShizuCallCompanion", "ShizuCallCompanion")
        prefs = getSharedPreferences("vgs", MODE_PRIVATE)
        val savedRole = prefs.getString("role", null)
        roleChosen = savedRole != null
        role = savedRole ?: LinkService.ROLE_REMOTE
        selectedDevice = prefs.getString("device", null)
        setContent {
            ShizuCallCompanionTheme {
                BackHandler(enabled = showContacts) { showContacts = false }
                if (showContacts) ContactsPage()
                else if (inMain) {
                    if (role == LinkService.ROLE_GATEWAY) GatewayPage() else Screen()
                } else SetupPage()
            }
        }
        // Straight to the main page when everything is in order, otherwise the setup page.
        inMain = roleChosen && allOk()
        loadDevices()
        loadContacts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.onResume(this)
        Hub.listener = {
            tick++
            if (Hub.contactsVersion != loadedContactsVersion) loadContacts()
        }
        val running = Hub.service?.isActive == true
        if (!running && !(roleChosen && allOk())) inMain = false // something was switched off: set up again
        if (role == LinkService.ROLE_GATEWAY) NotifListener.rebind(this)
        // Started in the background (no microphone service type)? We are in front now: upgrade it.
        Hub.service?.let { svc ->
            if (role == LinkService.ROLE_REMOTE && svc.isActive && !svc.hasMicType && granted(Manifest.permission.RECORD_AUDIO)) {
                try {
                    startForegroundService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_UPGRADE))
                } catch (e: Exception) {
                }
            }
        }
        tick++
    }

    override fun onPause() {
        Hub.listener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
        loadContacts()
        tick++
    }

    // ---------------------------------------------------------------- permissions

    private fun corePerms(r: String): List<String> {
        val l = mutableListOf<String>()
        if (r == LinkService.ROLE_REMOTE) l.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun gatewayPerms(): List<String> = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
    )

    /** Nice to have: names for calls, and the number dialed in the phone's own dialer. */
    @Suppress("DEPRECATION")
    private fun optionalPerms(): List<String> = listOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.PROCESS_OUTGOING_CALLS,
    )

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** One line in the setup checklist. [ask] = runtime permissions still to request. */
    private class Req(
        val label: String,
        val why: String,
        val ok: Boolean,
        val ask: List<String>,
        val required: Boolean = true,
        val fix: () -> Unit
    )

    private fun List<Req>.satisfied() = all { !it.required || it.ok }

    private fun runtimeReq(label: String, why: String, required: List<String>, extra: List<String> = emptyList()): Req {
        val missing = (required + extra).filter { !granted(it) }
        return Req(label, why, required.all { granted(it) }, missing) {
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        }
    }

    /** Everything this phone needs for the given role. */
    @Suppress("DEPRECATION")
    private fun requirements(r: String): List<Req> {
        val l = mutableListOf<Req>()
        if (r == LinkService.ROLE_REMOTE) {
            l += runtimeReq("Microphone", "Capture the remote phone's voice for the gateway's cellular uplink", listOf(Manifest.permission.RECORD_AUDIO))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            l += runtimeReq("Bluetooth", "Connect to the other phone", listOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            l += runtimeReq("Notifications", "Show the call and status notifications", listOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        l += Req(
            "Unrestricted battery",
            "Stops Android from putting ShizuCallCompanion to sleep, so the link stays up in the background",
            getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true,
            emptyList()
        ) { askBatteryExemption() }
        if (r == LinkService.ROLE_GATEWAY) {
            l += runtimeReq(
                "Phone and calls", "See, answer, end and place SIM calls",
                gatewayPerms(), listOf(Manifest.permission.PROCESS_OUTGOING_CALLS)
            )
            l += runtimeReq("Contacts", "Send your contacts to the remote", listOf(Manifest.permission.READ_CONTACTS))
            l += runtimeReq("Call log", "Show who is calling", listOf(Manifest.permission.READ_CALL_LOG))
            l += Req(
                "Notification access",
                "Mirror notifications to the remote. If it is greyed out: App info → ⋮ → Allow restricted settings.",
                NotifListener.isEnabled(this), emptyList()
            ) { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }

            // Optional: ordinary call control still works without Shizuku; only uplink injection needs it.
            val shizukuReady = UplinkInjectionClient.isShizukuReadyAndGranted()
            val shizukuAvailable = UplinkInjectionClient.isShizukuAvailable()
            l += Req(
                "Shizuku · cellular uplink injection",
                when {
                    Build.VERSION.SDK_INT < 33 -> "Experimental call-audio injection requires Android 13 or newer on the gateway."
                    shizukuReady -> "Granted. ShizuCallCompanion can test experimental remote-microphone injection during a SIM call."
                    shizukuAvailable -> "Allow ShizuCallCompanion in Shizuku to enable experimental cellular-call uplink injection."
                    else -> "Start the Shizuku server, then return here and grant ShizuCallCompanion access. This is only needed for uplink injection."
                },
                shizukuReady, emptyList(), required = false
            ) { requestShizukuPermission() }

        }
        return l
    }

    private fun requestShizukuPermission() {
        if (!UplinkInjectionClient.isPlatformSupported()) {
            toast("Cellular uplink injection requires Android 13 or newer")
            return
        }
        if (UplinkInjectionClient.isShizukuAvailable()) {
            UplinkInjectionClient.requestPermission()
            return
        }
        // Shizuku permissions are managed by the Shizuku app, not Android's runtime permission dialog.
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) {
            startActivity(launch)
            toast("Start the Shizuku server, then return to ShizuCallCompanion and tap Allow")
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        } catch (e: Exception) {
            openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    private fun allOk(): Boolean = requirements(role).satisfied()

    // ---------------------------------------------------------------- actions

    private fun startLink() {
        val needed = if (role == LinkService.ROLE_GATEWAY) corePerms(role) + gatewayPerms() else corePerms(role)
        val missing = needed.filter { !granted(it) }
        if (missing.isNotEmpty()) {
            requestPermissions(
                (missing + optionalPerms())
                    .filter { !granted(it) }.toTypedArray(), 2
            )
            toast("Please allow the permissions, then press Start again")
            return
        }
        val i = Intent(this, LinkService::class.java)
            .setAction(LinkService.ACTION_START)
            .putExtra(LinkService.EXTRA_ROLE, role)
        if (role == LinkService.ROLE_REMOTE) {
            val addr = selectedDevice
            if (addr == null || devices.none { it.second == addr }) {
                toast("Choose the gateway phone first (pair it in Bluetooth settings)")
                return
            }
            prefs.edit().putString("device", addr).apply()
            i.putExtra(LinkService.EXTRA_DEVICE, addr)
        }
        prefs.edit().putString("role", role).apply()
        startForegroundService(i)
    }

    private fun stopLink() {
        startService(Intent(this, LinkService::class.java).setAction(LinkService.ACTION_STOP))
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = if (adapter == null ||
            (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
        ) {
            emptyList()
        } else {
            try {
                (adapter.bondedDevices ?: emptySet()).map { (it.name ?: it.address) to it.address }
            } catch (e: SecurityException) {
                emptyList()
            }
        }
        if (selectedDevice == null && devices.size == 1) selectedDevice = devices[0].second
    }

    private fun loadContacts() {
        loadedContactsVersion = Hub.contactsVersion
        Thread {
            val list = ContactStore.all(applicationContext)
            runOnUiThread {
                contacts = list
            }
        }.start()
    }

    private fun openSettings(action: String, withPackage: Boolean = false) {
        try {
            val i = Intent(action)
            if (withPackage) i.data = Uri.fromParts("package", packageName, null)
            startActivity(i)
        } catch (e: Exception) {
            toast("Couldn't open settings")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- UI

    @Composable
    private fun Screen() {
        tick // re-read service state whenever the service reports a change
        val s = Hub.service
        val running = s != null && s.isActive
        val effRole = if (running) s!!.role else role
        val isRemote = effRole == LinkService.ROLE_REMOTE

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("ShizuCallCompanion · Remote", fontWeight = FontWeight.Bold) },
                    actions = { BackToSetupButton() }
                )
            },
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                UpdateCard()
                if (isRemote && !running) DeviceCard()
                ConnectionCard(s, running)
                if (running && isRemote) CallCard(s!!)
                if (running && isRemote) DialCard(s!!)
                if (running && isRemote) SpeakerCard(s!!)
                if (running && isRemote) AudioTestCard(s!!)
                LogCard()
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    /** Top-right button on the main pages: back to the setup page (change role, check permissions). */
    @Composable
    private fun BackToSetupButton() {
        IconButton(onClick = { inMain = false }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to setup")
        }
    }

    /** The gateway is only a mediator: status, Start/Stop and logs. */
    @Composable
    private fun GatewayPage() {
        tick // read the refresh counter so this page redraws on every update
        val s = Hub.service
        val running = s != null && s.isActive
        val connected = running && s!!.connected
        val status = if (!running) "Stopped" else s!!.connectionState
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("ShizuCallCompanion · Gateway", fontWeight = FontWeight.Bold) },
                    actions = { BackToSetupButton() }
                )
            },
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                UpdateCard()
                SectionCard("Status") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            !running -> Dot(MaterialTheme.colorScheme.outline)
                            connected -> Dot(MaterialTheme.colorScheme.primary)
                            else -> LoadingIndicator(Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(status, style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        "This phone only passes calls and notifications between the remote and the outside. " +
                            "You can close the app: the notification keeps showing the status.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { startLink() },
                            enabled = !running,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text("Start") }
                        OutlinedButton(
                            onClick = { stopLink() },
                            enabled = running,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) { Text("Stop") }
                    }
                }
                SectionCard("Test") {
                    OutlinedButton(
                        onClick = {
                            if (s == null || !running) toast("Press Start first") else s.sendTestNotification()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Send test notification to remote") }
                }
                LogCard()
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun SetupPage() {
        tick // permissions are re-checked on every update (also after returning from Settings)
        val running = Hub.service?.isActive == true
        val chosen = roleChosen
        val sel = if (chosen) role else ""
        val reqs = if (chosen) requirements(role) else emptyList()
        val allOk = chosen && reqs.satisfied()
        val toAsk = reqs.flatMap { it.ask }.distinct()
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text("ShizuCallCompanion", fontWeight = FontWeight.Bold) },
                    scrollBehavior = scroll
                )
            },
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                UpdateCard()
                SectionCard("1 · What is this phone?") {
                    if (running) {
                        Text(
                            "ShizuCallCompanion is running. Stop it first if you want to change the role.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RoleChoice(
                        LinkService.ROLE_GATEWAY, "Gateway",
                        "Has the SIM and internet. Sends calls and notifications to the remote.", sel, !running
                    )
                    RoleChoice(
                        LinkService.ROLE_REMOTE, "Remote",
                        "Watch mode: no internet. Rings, answers and dials through the gateway.", sel, !running
                    )
                }
                SectionCard("2 · Permissions") {
                    if (!chosen) {
                        Text(
                            "Choose a role above first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        reqs.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(
                                    when {
                                        r.ok -> MaterialTheme.colorScheme.primary
                                        r.required -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.tertiary
                                    }
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.label + if (!r.required) " (optional)" else "",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        r.why,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!r.ok) {
                                    Spacer(Modifier.width(8.dp))
                                    FilledTonalButton(onClick = { r.fix() }) { Text("Allow") }
                                }
                            }
                        }
                        if (toAsk.isNotEmpty()) {
                            Button(
                                onClick = { requestPermissions(toAsk.toTypedArray(), 1) },
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) { Text("Allow all permissions") }
                        }
                        if (!allOk) {
                            OutlinedButton(
                                onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Open App info (if a permission won't show its prompt)") }
                        }
                    }
                }
                Button(
                    onClick = {
                        prefs.edit().putString("role", role).apply()
                        loadDevices()
                        loadContacts()
                        inMain = true
                    },
                    enabled = allOk,
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text("Continue") }
                if (!allOk) {
                    Text(
                        if (chosen) "Allow every permission above to continue." else "Choose Gateway or Remote to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun RoleChoice(r: String, title: String, desc: String, selected: String, enabled: Boolean) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(enabled = enabled) {
                    role = r
                    roleChosen = true
                    prefs.edit().putString("role", r).apply()
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected == r, onClick = null, enabled = enabled)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun SectionCard(
        title: String,
        action: (@Composable () -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    action?.invoke()
                }
                content()
            }
        }
    }

    @Composable
    private fun DeviceCard() {
        tick // read the refresh counter so this card redraws on every update
        SectionCard("Gateway phone") {
            if (devices.isEmpty()) {
                Text(
                    "No paired phones. Pair both phones in Android Bluetooth settings first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            devices.forEach { (name, addr) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { selectedDevice = addr }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedDevice == addr, onClick = { selectedDevice = addr })
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(addr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = { loadDevices() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Refresh paired phones")
            }
        }
    }

    @Composable
    private fun ConnectionCard(s: LinkService?, running: Boolean) {
        tick // read the refresh counter so this card redraws on every update
        // Read service state outside the lambda so the card refreshes (see LogCard).
        val connected = running && s!!.connected
        val status = if (!running) "Stopped" else s!!.connectionState
        SectionCard("Connection") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !running -> Dot(MaterialTheme.colorScheme.outline)
                    connected -> Dot(MaterialTheme.colorScheme.primary)
                    else -> LoadingIndicator(Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { startLink() },
                    enabled = !running,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Start") }
                OutlinedButton(
                    onClick = { stopLink() },
                    enabled = running,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Stop") }
            }
        }
    }

    @Composable
    private fun Dot(color: Color) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
    }

    @Composable
    private fun CallCard(s: LinkService) {
        tick // read the refresh counter so this card redraws on every update
        val state = s.callState
        val ringing = state == LinkService.CALL_RINGING
        val inCall = state == LinkService.CALL_OFFHOOK
        // Read service state outside the Card lambda so the card refreshes (see LogCard).
        val who = s.display()
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (ringing || inCall) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (ringing || inCall) Icons.Filled.Call else Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    when {
                        ringing -> "Incoming call"
                        inCall -> "In call"
                        else -> "No active phone call"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
                if (ringing || inCall) {
                    Text(who, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                }
                if (inCall) {
                    Text(
                        "Call is active on the gateway. Digital call audio routing is not implemented yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                if (ringing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { s.reject() },
                            modifier = Modifier.weight(1f).height(64.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Reject")
                        }
                        Button(
                            onClick = { s.answer() },
                            modifier = Modifier.weight(1f).height(64.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E8E3E),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Filled.Call, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Answer")
                        }
                    }
                }
                if (inCall) {
                    Button(
                        onClick = { s.hangUp() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) { Text("Hang up") }
                }
            }
        }
    }

    @Composable
    private fun DialCard(s: LinkService) {
        tick // read the refresh counter so this card redraws on every update
        val query = dialQuery
        val picked = dialPicked
        val source = contacts
        val suggestions = remember(query, source, picked) {
            if (picked != null && picked.label() == query) emptyList()
            else ContactStore.search(source, query)
        }
        val number = if (picked != null && picked.label() == query.trim()) picked.number
        else query.substringBefore("·").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val valid = number.count { it.isDigit() } >= 3
        SectionCard("Make a call") {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    dialQuery = it
                    if (picked?.label() != it) dialPicked = null
                },
                label = { Text("Name or number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (suggestions.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    suggestions.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dialPicked = c
                                    dialQuery = c.label()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(c.name.ifEmpty { c.number }, style = MaterialTheme.typography.bodyLarge)
                                Text(c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { showContacts = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Filled.Person, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Choose from contacts")
            }
            // Extra gap so the Call button is not hit by accident when choosing a contact.
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { s.dial(number) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Call")
            }
        }
    }

    /** Starts the remote microphone stream; the gateway attempts cellular uplink injection. */
    @Composable
    private fun AudioTestCard(s: LinkService) {
        tick // re-read voice state whenever the service reports a change
        val active = s.voiceActive
        SectionCard("Cellular uplink audio test") {
            Text(
                "Place or answer a SIM call on the gateway first. Then start this test to send the remote phone's microphone into the caller's audio uplink. Shizuku permission must be granted on the gateway.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { s.toggleVoice() },
                enabled = s.connected,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (active) "Stop uplink test" else "Start uplink test")
            }
            Text(
                if (active) "Microphone streaming is active. Ask the other caller whether they can hear you."
                else "This is experimental and requires a compatible gateway audio route. If injection is unavailable, check the gateway's ShizuCallCompanion log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    /** The voice link itself runs automatically over Bluetooth; the only choice is the loudspeaker. */
    @Composable
    private fun SpeakerCard(s: LinkService) {
        tick // read the refresh counter so this card redraws on every update
        val loud = s.speakerOn
        SectionCard("Speaker") {
            SwitchRow("Loudspeaker", loud) { on -> s.setLocalSpeaker(on) }
            Text(
                "On: sound plays on this phone's loudspeaker. Off: in the earpiece.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    /** Full-screen contact list: tap a contact to call it with the chosen method. */
    @Composable
    private fun ContactsPage() {
        tick // read the refresh counter so this page redraws on every update
        var query by remember { mutableStateOf("") }
        val q = query.trim().lowercase()
        val qd = ContactStore.digits(q)
        val source = contacts
        LaunchedEffect(Unit) { Hub.service?.requestContacts() } // refresh the lists from the gateway
        val shown = if (q.isEmpty()) source else source.filter { c ->
            c.name.lowercase().contains(q) || (qd.isNotEmpty() && ContactStore.digits(c.number).contains(qd))
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Contacts", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { showContacts = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Gateway phone contacts" +
                        " · ${shown.size} of ${source.size} · tap one to choose it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (source.isEmpty()) {
                    Text(
                        "No contacts yet. Connect to the gateway so it can send its contacts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dialPicked = c
                                    dialQuery = c.label()
                                    showContacts = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name.ifEmpty { c.number }, style = MaterialTheme.typography.bodyLarge)
                                if (c.name.isNotEmpty()) {
                                    Text(
                                        c.number,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onChange(!checked) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun LogCard() {
        tick // read the refresh counter so this card redraws on every update
        // Read outside the SectionCard lambda: Compose memoizes that lambda, so values read
        // only inside it would never refresh. A new value here makes a new lambda.
        val text = Hub.logText()
        SectionCard("Log", action = {
            IconButton(onClick = {
                val all = Hub.logCopyText()
                if (all.isEmpty()) toast("Nothing to copy yet") else {
                    getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("ShizuCallCompanion log", all))
                    toast("Log copied")
                }
            }) {
                Icon(CopyIcon, contentDescription = "Copy log", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = {
                Hub.clearLog()
                toast("Log cleared")
            }) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear log", tint = MaterialTheme.colorScheme.primary)
            }
        }) {
            // Fixed-height box that scrolls on its own, so a long log never stretches the page.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text.ifEmpty { "Nothing yet" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Material "content copy" icon (drawn here so we don't need the large extended icon set). */
private val CopyIcon: ImageVector = ImageVector.Builder(
    name = "ContentCopy",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).addPath(
    pathData = addPathNodes(
        "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z"
    ),
    fill = SolidColor(Color.Black)
).build()
