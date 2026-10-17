package com.vgs.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Base64
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Runs on both phones.
 *  - GATEWAY (phone with SIM/internet): listens for the remote over Bluetooth, reports
 *    SIM call state, answers/rejects/dials on request.
 *  - REMOTE (watch-mode phone): connects to the gateway, rings on incoming calls,
 *    sends answer/reject/dial commands.
 * Both sides can open a two-way voice link (mic <-> speaker) over the same socket.
 */
class LinkService : Service() {

    companion object {
        const val ROLE_GATEWAY = "gateway"
        const val ROLE_REMOTE = "remote"

        const val ACTION_START = "com.vgs.app.START"
        const val ACTION_STOP = "com.vgs.app.STOP"
        const val ACTION_ANSWER = "com.vgs.app.ANSWER"
        const val ACTION_REJECT = "com.vgs.app.REJECT"
        const val ACTION_NOTIF_DISMISSED = "com.vgs.app.NOTIF_DISMISSED"
        const val EXTRA_KEY = "key"
        const val EXTRA_ROLE = "role"
        const val EXTRA_DEVICE = "device"

        const val CALL_IDLE = "IDLE"
        const val CALL_RINGING = "RINGING"
        const val CALL_OFFHOOK = "OFFHOOK"

        private const val CH_STATUS = "vgs_status"
        private const val CH_CALLS = "vgs_calls"
        private const val CH_MIRROR = "vgs_mirror"
        private const val TAG_MIRROR = "mirror"
        private const val ID_STATUS = 1
        private const val ID_INCOMING = 2
    }

    @Volatile var role: String = ROLE_REMOTE
        private set
    @Volatile var connectionState: String = "Stopped"
        private set
    @Volatile var connected: Boolean = false
        private set
    @Volatile var callState: String = CALL_IDLE
        private set
    @Volatile var callNumber: String = ""
        private set
    @Volatile var callName: String = ""
        private set
    /** "" = normal SIM call, otherwise the app, e.g. "WhatsApp". */
    @Volatile var callSource: String = ""
        private set
    /** Remote: the call's audio is relayed to this phone (gateway loudspeaker + voice link). */
    @Volatile var takenHere: Boolean = false
        private set
    /** Remote: last loudspeaker setting sent to the gateway. */
    @Volatile var gatewaySpeakerOn: Boolean = false
        private set

    // Gateway: SIM call and WhatsApp call are tracked separately; the SIM call wins.
    @Volatile private var simState: String = CALL_IDLE
    @Volatile private var simNumber: String = ""
    @Volatile private var simName: String = ""
    @Volatile private var appCall: AppCall? = null
    private var lastCallSent = ""
    private val main = Handler(Looper.getMainLooper())
    private val endAppCall = Runnable {
        appCall = null
        publishCall()
    }

    // Remote: take the call here automatically once it connects (answered / dialed from here).
    @Volatile private var autoTake = false

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var deviceAddress: String? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var writer: FrameWriter? = null
    private var worker: Thread? = null
    private val io = Executors.newSingleThreadExecutor()
    private var receiverRegistered = false
    private var ringtone: Ringtone? = null
    private var contactBuf: StringBuilder? = null

    val isActive: Boolean get() = running
    val voiceActive: Boolean get() = audio.isRunning
    val speakerOn: Boolean get() = audio.speakerOn

    private val audio: AudioBridge by lazy {
        AudioBridge(this) { buf, n ->
            val w = writer ?: throw IOException("not connected")
            w.writeAudio(buf, n)
        }
    }

    private val nm: NotificationManager by lazy { getSystemService(NotificationManager::class.java)!! }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.service = this
        createChannels()
    }

    override fun onDestroy() {
        shutdown()
        io.shutdownNow()
        Hub.service = null
        Hub.notifyUi()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (running) shutdown()
                role = intent.getStringExtra(EXTRA_ROLE) ?: ROLE_REMOTE
                deviceAddress = intent.getStringExtra(EXTRA_DEVICE)
                running = true
                connectionState = "Starting…"
                Hub.log("Started as ${if (role == ROLE_GATEWAY) "gateway" else "remote"}")
                goForeground()
                if (role == ROLE_GATEWAY) {
                    registerPhoneReceiver()
                    checkNotificationAccess()
                }
                startWorker()
            }
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
            ACTION_ANSWER -> if (running) answer() else stopSelf()
            ACTION_REJECT -> if (running) reject() else stopSelf()
            ACTION_NOTIF_DISMISSED -> {
                val key = intent.getStringExtra(EXTRA_KEY)
                if (running && key != null) send("NOTIF_DISMISS", key) else if (!running) stopSelf()
            }
        }
        Hub.notifyUi()
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- public actions (UI)

    fun answer() {
        stopRinging()
        if (role == ROLE_GATEWAY) gatewayAnswer() else {
            autoTake = true
            send("ANSWER")
        }
    }

    fun reject() {
        stopRinging()
        if (role == ROLE_GATEWAY) gatewayEnd() else send("REJECT")
    }

    fun hangUp() {
        if (role == ROLE_GATEWAY) gatewayEnd() else send("HANGUP")
    }

    /** [whatsApp] = call through WhatsApp on the gateway instead of the SIM. */
    fun dial(number: String, whatsApp: Boolean = false) {
        if (role == ROLE_GATEWAY) gatewayDial(number, whatsApp) else {
            autoTake = true
            send("DIAL", number, if (whatsApp) "wa" else "sim")
        }
    }

    fun setGatewaySpeaker(on: Boolean) {
        gatewaySpeakerOn = on
        send("SPEAKER", if (on) "1" else "0")
        Hub.notifyUi()
    }

    /** Remote: talk on this phone (gateway loudspeaker ON + voice link). */
    fun takeCallHere() {
        if (!connected) {
            Hub.log("Not connected yet")
            return
        }
        takenHere = true
        setGatewaySpeaker(true)
        if (!audio.isRunning) toggleVoice()
        Hub.log("📞 Call moved to this phone (gateway loudspeaker + voice link)")
        updateStatus()
    }

    /** Remote: hand the audio back to the gateway phone. */
    fun giveCallBack() {
        takenHere = false
        if (audio.isRunning) toggleVoice()
        if (connected) setGatewaySpeaker(false) else gatewaySpeakerOn = false
        updateStatus()
    }

    fun setLocalSpeaker(on: Boolean) {
        audio.setSpeaker(on)
        Hub.notifyUi()
    }

    fun toggleVoice() {
        if (audio.isRunning) {
            audio.stop()
            send("VOICE_STOP")
        } else {
            if (!connected) {
                Hub.log("Not connected yet")
                return
            }
            if (!has(Manifest.permission.RECORD_AUDIO)) {
                Hub.log("Microphone permission missing")
                return
            }
            audio.start()
            send("VOICE_START")
        }
        updateStatus()
    }

    // ---------------------------------------------------------------- connection

    private fun startWorker() {
        val gen = ++generation
        worker = Thread({ workerLoop(gen) }, "callcompanion-link").also { it.start() }
    }

    private fun alive(gen: Int) = running && gen == generation

    private fun workerLoop(gen: Int) {
        while (alive(gen)) {
            var s: BluetoothSocket? = null
            try {
                s = if (role == ROLE_GATEWAY) acceptOne(gen) else connectOnce()
                if (s != null && alive(gen)) runSession(s, gen)
            } catch (e: Exception) {
                if (alive(gen)) Hub.log("Link: ${e.message}")
            } finally {
                try {
                    s?.close()
                } catch (e: Exception) {
                }
            }
            if (!alive(gen)) break
            setConn(false, if (role == ROLE_GATEWAY) "Disconnected – waiting again…" else "Disconnected – retrying in 3s…")
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun adapter(): BluetoothAdapter {
        val a = getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This phone has no Bluetooth")
        if (!a.isEnabled) throw IOException("Bluetooth is OFF – turn it on")
        return a
    }

    @SuppressLint("MissingPermission")
    private fun acceptOne(gen: Int): BluetoothSocket? {
        val a = adapter()
        setConn(false, "Waiting for remote phone…")
        val ss = a.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID)
        serverSocket = ss
        try {
            val s = ss.accept()
            if (!alive(gen)) {
                s.close()
                return null
            }
            return s
        } finally {
            try {
                ss.close()
            } catch (e: Exception) {
            }
            serverSocket = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectOnce(): BluetoothSocket {
        val a = adapter()
        val addr = deviceAddress ?: throw IOException("No gateway phone selected")
        val dev = a.getRemoteDevice(addr)
        setConn(false, "Connecting to ${dev.name ?: addr}…")
        val s = dev.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
        socket = s
        try {
            s.connect()
        } catch (e: IOException) {
            throw IOException("Can't reach gateway (is CallCompanion started there?)")
        }
        return s
    }

    @SuppressLint("MissingPermission")
    private fun runSession(s: BluetoothSocket, gen: Int) {
        socket = s
        val w = FrameWriter(s.outputStream)
        writer = w
        val peer = try {
            s.remoteDevice.name ?: s.remoteDevice.address
        } catch (e: Exception) {
            "peer"
        }
        setConn(true, "Connected to $peer")
        send("HELLO", role)
        if (role == ROLE_GATEWAY) {
            sendCallState()
            sendContacts()
            checkNotificationAccess()
        }
        val input = DataInputStream(BufferedInputStream(s.inputStream, 16384))
        try {
            while (alive(gen)) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > Protocol.MAX_FRAME) throw IOException("bad data from peer")
                val data = ByteArray(len)
                input.readFully(data)
                when (type) {
                    Protocol.TYPE_CMD -> handleCommand(String(data, Charsets.UTF_8))
                    Protocol.TYPE_AUDIO -> audio.onAudio(data)
                }
            }
        } finally {
            if (writer === w) writer = null
            if (socket === s) socket = null
            connected = false
            audio.stop()
            if (role == ROLE_REMOTE) {
                stopRinging()
                callState = CALL_IDLE
                takenHere = false
                autoTake = false
                nm.cancel(ID_INCOMING)
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
    }

    private fun shutdown() {
        if (!running && worker == null) return
        running = false
        generation++
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        closeSocket()
        worker?.interrupt()
        worker = null
        writer = null
        connected = false
        audio.stop()
        stopRinging()
        if (receiverRegistered) {
            try {
                unregisterReceiver(phoneReceiver)
            } catch (e: Exception) {
            }
            receiverRegistered = false
        }
        nm.cancel(ID_INCOMING)
        stopForeground(STOP_FOREGROUND_REMOVE)
        connectionState = "Stopped"
        callState = CALL_IDLE
        callSource = ""
        simState = CALL_IDLE
        main.removeCallbacks(endAppCall)
        appCall = null
        lastCallSent = ""
        takenHere = false
        autoTake = false
        Hub.log("Stopped")
    }

    private fun send(vararg parts: String) {
        val w = writer
        if (w == null) {
            Hub.log("Not connected – command not sent")
            return
        }
        val msg = parts.joinToString("|") { it.replace('|', '/') }
        try {
            io.execute {
                try {
                    w.writeCmd(msg)
                } catch (e: Exception) {
                    Hub.log("Send failed: ${e.message}")
                    closeSocket()
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun handleCommand(msg: String) {
        val p = msg.split("|")
        val arg1 = p.getOrElse(1) { "" }
        when (p[0]) {
            "HELLO" -> Hub.log("Peer is the $arg1 phone")
            // gateway -> remote
            "CALL" -> {
                callState = arg1.ifEmpty { CALL_IDLE }
                callNumber = p.getOrElse(2) { "" }
                callName = p.getOrElse(3) { "" }
                callSource = p.getOrElse(4) { "" }
                if (role == ROLE_REMOTE) onRemoteCallUpdate()
            }
            // remote -> gateway
            "ANSWER" -> gatewayAnswer()
            "REJECT", "HANGUP" -> gatewayEnd()
            "DIAL" -> gatewayDial(arg1, p.getOrElse(2) { "sim" } == "wa")
            "SPEAKER" -> {
                gatewaySpeakerOn = arg1 == "1"
                AudioRoute.setSpeaker(this, arg1 == "1")
                Hub.log("Speaker ${if (arg1 == "1") "ON" else "OFF"} (requested by remote)")
            }
            // both directions
            "VOICE_START" -> {
                if (has(Manifest.permission.RECORD_AUDIO)) audio.start()
                else Hub.log("Peer started voice link but microphone permission is missing")
                updateStatus()
            }
            "VOICE_STOP" -> {
                audio.stop()
                updateStatus()
            }
            // contacts: gateway -> remote
            "CONTACTS_BEGIN" -> contactBuf = StringBuilder()
            "CONTACTS" -> contactBuf?.append(msg.substringAfter('|'))
            "CONTACTS_END" -> {
                val data = contactBuf?.toString() ?: ""
                contactBuf = null
                ContactStore.saveSynced(this, data)
                Hub.contactsVersion++
                Hub.log("Received ${ContactStore.parse(data).size} contacts from gateway")
            }
            // notifications: gateway -> remote
            "NOTIF" -> showMirrored(arg1, p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, p.drop(4).joinToString("/"))
            "NOTIF_REMOVE" -> nm.cancel(TAG_MIRROR, arg1.hashCode())
            // remote -> gateway: user swiped it away on the remote
            "NOTIF_DISMISS" -> {
                val key = decodeKey(arg1)
                if (key != null) NotifListener.instance?.dismiss(key)
            }
            else -> Hub.log("Unknown command: ${p[0]}")
        }
        Hub.notifyUi()
    }

    private fun setConn(isConnected: Boolean, text: String) {
        connected = isConnected
        connectionState = text
        Hub.log(text)
        updateStatus()
    }

    // ---------------------------------------------------------------- gateway: SIM calls

    private val phoneReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            // Number typed in the phone's own dialer (arrives just before OFFHOOK).
            if (i.action == Intent.ACTION_NEW_OUTGOING_CALL) {
                val out = i.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                if (!out.isNullOrEmpty() && out != simNumber) {
                    simNumber = out
                    simName = lookupName(out)
                }
                return
            }
            val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            simState = when (st) {
                TelephonyManager.EXTRA_STATE_RINGING -> CALL_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> CALL_OFFHOOK
                else -> CALL_IDLE
            }
            if (!num.isNullOrEmpty() && num != simNumber) {
                simNumber = num
                simName = lookupName(num)
            }
            publishCall()
            if (simState == CALL_IDLE) {
                simNumber = ""
                simName = ""
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        f.addAction(Intent.ACTION_NEW_OUTGOING_CALL)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(phoneReceiver, f)
        receiverRegistered = true
    }

    /** Gateway: work out the current call (SIM first, then WhatsApp) and tell the remote if it changed. */
    @Synchronized
    private fun publishCall() {
        val a = appCall
        if (simState != CALL_IDLE || a == null) {
            callState = simState
            callNumber = simNumber
            callName = simName
            callSource = ""
        } else {
            callState = if (a.ringing) CALL_RINGING else CALL_OFFHOOK
            callNumber = ""
            callName = a.name
            callSource = a.app
        }
        if (callState == CALL_IDLE) {
            callNumber = ""
            callName = ""
            callSource = ""
        }
        val sig = listOf(callState, callNumber, callName, callSource).joinToString("|")
        if (sig != lastCallSent) {
            lastCallSent = sig
            Hub.log(if (callState == CALL_IDLE) "Call ended" else "${kindLabel()}call: $callState ${display()}")
            sendCallState()
        }
        Hub.notifyUi()
    }

    /** Gateway (from NotifListener): a WhatsApp call notification was posted or updated. */
    fun onAppCall(c: AppCall) {
        if (!running || role != ROLE_GATEWAY) return
        main.removeCallbacks(endAppCall)
        val prev = appCall
        appCall = c
        if (prev == null || prev.ringing != c.ringing) {
            Hub.log("${c.app} call ${if (c.ringing) "ringing" else "active"}: ${c.name.ifEmpty { "?" }} (buttons: ${c.buttons})")
        }
        publishCall()
    }

    /** Gateway: the WhatsApp call notification is gone. Wait a moment: WhatsApp swaps it when answering. */
    fun onAppCallRemoved(key: String) {
        if (appCall?.key != key) return
        main.removeCallbacks(endAppCall)
        main.postDelayed(endAppCall, 1500)
    }

    private fun pressAppButton(a: AppCall, pi: PendingIntent?, what: String) {
        if (pi == null) {
            Hub.log("Couldn't find the ${a.app} '$what' button (buttons: ${a.buttons})")
            return
        }
        if (AppCalls.press(this, pi)) Hub.log("${a.app}: $what")
    }

    private fun sendCallState() {
        if (writer != null) send("CALL", callState, callNumber, callName, callSource)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun gatewayAnswer() {
        val a = appCall
        if (simState == CALL_IDLE && a != null) {
            pressAppButton(a, a.answer, "answer")
            return
        }
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            Hub.log("Answer failed: 'Phone' permission missing on gateway")
            return
        }
        try {
            getSystemService(TelecomManager::class.java)!!.acceptRingingCall()
            Hub.log("Answered the call on this phone")
        } catch (e: Exception) {
            Hub.log("Answer failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun gatewayEnd() {
        val a = appCall
        if (simState == CALL_IDLE && a != null) {
            if (a.ringing) pressAppButton(a, a.decline ?: a.hangUp, "decline")
            else pressAppButton(a, a.hangUp, "hang up")
            return
        }
        if (Build.VERSION.SDK_INT < 28) {
            Hub.log("Ending calls needs Android 9+ on the gateway")
            return
        }
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            Hub.log("End call failed: 'Phone' permission missing on gateway")
            return
        }
        try {
            val ok = getSystemService(TelecomManager::class.java)!!.endCall()
            Hub.log(if (ok) "Call ended" else "No call to end")
        } catch (e: Exception) {
            Hub.log("End call failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun gatewayDial(number: String, whatsApp: Boolean = false) {
        val n = number.trim()
        if (n.isEmpty()) return
        if (whatsApp) {
            val err = AppCalls.startWhatsAppCall(this, n)
            Hub.log(err ?: "WhatsApp calling $n")
            return
        }
        if (!has(Manifest.permission.CALL_PHONE)) {
            Hub.log("Dial failed: 'Phone' permission missing on gateway")
            return
        }
        try {
            simNumber = n
            simName = lookupName(n)
            getSystemService(TelecomManager::class.java)!!
                .placeCall(Uri.fromParts("tel", n, null), Bundle())
            Hub.log("Dialing $n")
        } catch (e: Exception) {
            Hub.log("Dial failed: ${e.message}")
        }
    }

    private fun lookupName(number: String): String {
        if (!has(Manifest.permission.READ_CONTACTS)) return ""
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** "WhatsApp " for WhatsApp calls, "" for SIM calls. */
    fun kindLabel(): String = if (callSource.isEmpty()) "" else "$callSource "

    fun display(): String = when {
        callName.isNotEmpty() && callNumber.isNotEmpty() -> "$callName ($callNumber)"
        callName.isNotEmpty() -> callName
        callNumber.isNotEmpty() -> callNumber
        else -> "Unknown number"
    }

    // ---------------------------------------------------------------- remote: ringing

    private fun onRemoteCallUpdate() {
        when (callState) {
            CALL_RINGING -> {
                Hub.log("Incoming ${kindLabel()}call: ${display()}")
                showIncoming()
                startRinging()
            }
            CALL_OFFHOOK -> {
                stopRinging()
                nm.cancel(ID_INCOMING)
                Hub.log("${kindLabel()}call in progress on gateway: ${display()}")
                if (autoTake) {
                    autoTake = false
                    if (!takenHere) takeCallHere()
                }
            }
            else -> {
                stopRinging()
                nm.cancel(ID_INCOMING)
                autoTake = false
                if (takenHere) giveCallBack()
                Hub.log("Call ended")
            }
        }
        updateStatus()
    }

    private fun startRinging() {
        try {
            if (ringtone == null) {
                val r = RingtoneManager.getRingtone(
                    this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                )
                if (r != null && Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r?.play()
                ringtone = r
            }
        } catch (e: Exception) {
            Hub.log("Ringtone error: ${e.message}")
        }
        try {
            vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (e: Exception) {
        }
    }

    private fun stopRinging() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
        }
        ringtone = null
        try {
            vibrator()?.cancel()
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = getSystemService(Vibrator::class.java)

    private fun checkNotificationAccess() {
        when {
            !NotifListener.isEnabled(this) ->
                Hub.log("⚠️ Notification sync: access is OFF – allow CallCompanion in Notification access settings")
            NotifListener.instance == null -> {
                Hub.log("Notification sync: access ON, waiting for listener to connect…")
                NotifListener.rebind(this)
            }
            else -> Hub.log("Notification sync: ready")
        }
    }

    // ---------------------------------------------------------------- contacts sync

    private fun sendContacts() {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            Hub.log("Contacts permission missing – contacts not sent to remote")
            return
        }
        val w = writer ?: return
        val list = ContactStore.readDevice(this)
        val chunks = ContactStore.toChunks(list)
        try {
            io.execute {
                try {
                    w.writeCmd("CONTACTS_BEGIN")
                    for (c in chunks) w.writeCmd("CONTACTS|$c")
                    w.writeCmd("CONTACTS_END")
                    Hub.log("Sent ${list.size} contacts to remote")
                } catch (e: Exception) {
                    Hub.log("Contacts sync failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- notification mirroring

    /** null = OK to forward; otherwise the reason it can't be sent (shown in the log). */
    fun mirrorBlockReason(): String? = when {
        !running -> "CallCompanion is stopped (press Start)"
        role != ROLE_GATEWAY -> "this phone is not set as Gateway"
        !getSharedPreferences("vgs", MODE_PRIVATE).getBoolean("mirror", true) -> "notification sync switch is OFF"
        !connected -> "remote phone not connected"
        else -> null
    }

    /** Sends a fake notification over Bluetooth to test the link without other apps. */
    fun sendTestNotification() {
        if (!connected) {
            Hub.log("Test notification NOT sent: remote phone not connected")
            return
        }
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        mirrorPosted("vgs-test-$stamp", "CallCompanion", "Test notification", "Sent from the gateway at $stamp")
    }

    private fun encodeKey(k: String): String =
        Base64.encodeToString(k.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decodeKey(k: String): String? = try {
        String(Base64.decode(k, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Gateway: forward a phone notification to the remote. */
    fun mirrorPosted(key: String, app: String, title: String, text: String) {
        Hub.log("🔔 → remote: $app · ${title.take(40)}")
        send("NOTIF", encodeKey(key), app, title, text.take(2000))
    }

    /** Gateway: the notification was cleared on this phone, clear the copy too. */
    fun mirrorRemoved(key: String) {
        if (running && connected && role == ROLE_GATEWAY) send("NOTIF_REMOVE", encodeKey(key))
    }

    /** Remote: show a copy of a gateway notification. */
    private fun showMirrored(encKey: String, app: String, title: String, text: String) {
        Hub.log("🔔 from gateway: $app · ${title.take(40)}${if (text.isNotEmpty()) " – " + text.take(60) else ""}")
        if (!nm.areNotificationsEnabled()) {
            Hub.log("⚠️ Can't show it: notifications are blocked for CallCompanion on this phone (allow them in App info)")
        }
        val id = encKey.hashCode()
        val deletePi = PendingIntent.getService(
            this, id,
            Intent(this, LinkService::class.java)
                .setAction(ACTION_NOTIF_DISMISSED)
                .putExtra(EXTRA_KEY, encKey),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_MIRROR)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setSubText(app)
            .setContentTitle(title.ifEmpty { app })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openAppPi())
            .setDeleteIntent(deletePi)
            .build()
        try {
            nm.notify(TAG_MIRROR, id, n)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW)
        )
        val calls = NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        calls.setSound(null, null)
        calls.enableVibration(false)
        nm.createNotificationChannel(calls)
        nm.createNotificationChannel(
            NotificationChannel(CH_MIRROR, "Notifications from gateway phone", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppPi(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionPi(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req,
        Intent(this, LinkService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(icon: Int, title: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()

    private fun statusNotification(): Notification {
        val text = if (audio.isRunning) "$connectionState · voice link ON" else connectionState
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(if (role == ROLE_GATEWAY) "CallCompanion gateway" else "CallCompanion remote")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppPi())
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionPi(ACTION_STOP, 10)))
            .build()
    }

    private fun goForeground() {
        val n = statusNotification()
        when {
            Build.VERSION.SDK_INT >= 30 -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (has(Manifest.permission.RECORD_AUDIO)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(ID_STATUS, n, type)
            }
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else -> startForeground(ID_STATUS, n)
        }
    }

    private fun updateStatus() {
        if (running) {
            try {
                nm.notify(ID_STATUS, statusNotification())
            } catch (e: Exception) {
            }
        }
        Hub.notifyUi()
    }

    private fun showIncoming() {
        val n = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming ${kindLabel()}call")
            .setContentText(display())
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openAppPi())
            .setFullScreenIntent(openAppPi(), true)
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Reject", actionPi(ACTION_REJECT, 11)))
            .addAction(action(android.R.drawable.sym_action_call, "Answer", actionPi(ACTION_ANSWER, 12)))
            .build()
        try {
            nm.notify(ID_INCOMING, n)
        } catch (e: Exception) {
        }
    }

    private fun has(perm: String) =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}
