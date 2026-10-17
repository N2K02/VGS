package com.vgs.app

import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Wire format over the Bluetooth RFCOMM socket:
 *   [1 byte type][4 byte length][payload]
 * type 1 = text command "NAME|arg1|arg2", type 2 = raw PCM audio (8 kHz, 16-bit mono).
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("7a1e5d2c-3b4f-4c8a-9e61-2f5d8c0b9a41")
    const val SERVICE_NAME = "CallCompanion"
    const val TYPE_CMD = 1
    const val TYPE_AUDIO = 2
    const val MAX_FRAME = 64 * 1024
}

class FrameWriter(stream: OutputStream) {
    private val out = DataOutputStream(BufferedOutputStream(stream, 8192))

    @Synchronized
    fun writeCmd(cmd: String) {
        val b = cmd.toByteArray(Charsets.UTF_8)
        writeFrame(Protocol.TYPE_CMD, b, b.size)
    }

    @Synchronized
    fun writeAudio(buf: ByteArray, len: Int) {
        writeFrame(Protocol.TYPE_AUDIO, buf, len)
    }

    private fun writeFrame(type: Int, b: ByteArray, len: Int) {
        out.writeByte(type)
        out.writeInt(len)
        out.write(b, 0, len)
        out.flush()
    }
}

/** Tiny shared state between the service and the screen. */
object Hub {
    @Volatile
    var service: LinkService? = null
    var listener: (() -> Unit)? = null

    /** Bumped when new contacts arrive from the gateway, so the screen reloads them. */
    @Volatile
    var contactsVersion = 0

    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        android.util.Log.i("CallCompanion", msg)
        synchronized(lines) {
            lines.addLast(fmt.format(Date()) + "  " + msg)
            while (lines.size > 60) lines.removeFirst()
        }
        notifyUi()
    }

    fun logText(): String = synchronized(lines) { lines.reversed().joinToString("\n") }

    fun notifyUi() {
        main.post { listener?.invoke() }
    }
}
