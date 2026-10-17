package com.shizucallcompanion.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's shell process. Exposes PCM uplink injection and opt-in downlink capture;
 * the normal ShizuCallCompanion app and Bluetooth transport remain unprivileged.
 *
 * Experimental: relies on hidden telephony audio APIs and device-specific audio policy.
 */
@Keep
class UplinkInjectionService : IShizuUplinkInjectionService.Stub {
    private val lock = Any()
    private var shellContext: Context? = null
    private var audioTrack: AudioTrack? = null
    private var readEnd: ParcelFileDescriptor? = null
    private var writeEnd: ParcelFileDescriptor? = null
    private var audioThread: Thread? = null
    private var downlinkRecord: AudioRecord? = null
    private var downlinkWriteEnd: ParcelFileDescriptor? = null
    private var downlinkThread: Thread? = null
    @Volatile private var downlinkRunning = false

    @Keep
    constructor() : this(null)

    @Keep
    constructor(context: Context?) {
        shellContext = context?.let { ShellContext(it) }
    }

    override fun isPstnCallAudioInterceptable(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            enableHiddenApiAccess()
            val am = shellAudioManager()
            AudioManager::class.java.getDeclaredMethod("isPstnCallAudioInterceptable")
                .apply { isAccessible = true }
                .invoke(am) as Boolean
        }.getOrDefault(false)
    }

    @SuppressLint("NewApi")
    override fun startUplinkInjection(): ParcelFileDescriptor? = synchronized(lock) {
        stopLocked()
        check(Build.VERSION.SDK_INT >= 33) { "Call uplink injection requires Android 13 or later" }
        enableHiddenApiAccess()
        val am = shellAudioManager()
        check(isCellularMode(am.mode)) { "Start a cellular call before enabling uplink injection" }
        check(isPstnCallAudioInterceptable()) { "This device does not expose telephony audio injection routes" }

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val track = AudioManager::class.java
            .getDeclaredMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
            .apply { isAccessible = true }
            .invoke(am, format) as? AudioTrack
            ?: throw IllegalStateException("Android did not create the call-uplink AudioTrack")

        val pipe = ParcelFileDescriptor.createPipe()
        try {
            track.play()
            audioTrack = track
            readEnd = pipe[0]
            writeEnd = pipe[1]
            val reader = pipe[0]
            audioThread = Thread({ pumpPcm(reader, track) }, "shizu-uplink-pcm").apply {
                isDaemon = true
                start()
            }
            // Ownership of the write end passes to the app process.
            writeEnd = null
            pipe[1]
        } catch (t: Throwable) {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            runCatching { track.stop() }
            track.release()
            audioTrack = null
            readEnd = null
            throw t
        }
    }

    override fun startDownlinkCapture(): ParcelFileDescriptor? = synchronized(lock) {
        stopDownlinkLocked()
        check(Build.VERSION.SDK_INT >= 33) { "Call downlink capture requires Android 13 or later" }
        enableHiddenApiAccess()
        val am = shellAudioManager()
        check(isCellularMode(am.mode)) { "Start a cellular call before capturing downlink audio" }
        check(isPstnCallAudioInterceptable()) { "This device does not expose telephony audio interception" }

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val intType = Int::class.javaPrimitiveType
        val captureAttributes = AudioAttributes.Builder()
            .also {
                AudioAttributes.Builder::class.java
                    .getDeclaredMethod("setInternalCapturePreset", intType)
                    .apply { isAccessible = true }
                    .invoke(it, MediaRecorder.AudioSource.VOICE_DOWNLINK)
            }
            .build()
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(FRAME_BYTES * 10)
        runCatching {
            AudioRecord.Builder::class.java.getMethod("setContext", Context::class.java)
                .invoke(builder, shellContext)
        }
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(builder, captureAttributes)
        val pstnMode = AudioManager::class.java.getDeclaredField("CALL_REDIRECT_PSTN")
            .apply { isAccessible = true }.getInt(null)
        AudioRecord.Builder::class.java.getDeclaredMethod("setCallRedirectionMode", intType)
            .apply { isAccessible = true }.invoke(builder, pstnMode)
        val record = builder.build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Android rejected the telephony downlink AudioRecord")
        }
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Telephony downlink recording did not start"
            }
            downlinkRecord = record
            downlinkWriteEnd = pipe[1]
            downlinkRunning = true
            val writerFd = pipe[1]
            downlinkThread = Thread({ pumpDownlink(record, writerFd) }, "shizu-downlink-pcm").apply {
                isDaemon = true
                start()
            }
            // The app receives the read end; the shell service streams PCM into the write end.
            pipe[0]
        } catch (t: Throwable) {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            runCatching { record.stop() }
            record.release()
            downlinkRecord = null
            downlinkWriteEnd = null
            downlinkRunning = false
            throw t
        }
    }

    override fun stopDownlinkCapture() {
        synchronized(lock) { stopDownlinkLocked() }
    }

    private fun pumpDownlink(record: AudioRecord, fd: ParcelFileDescriptor) {
        try {
            FileOutputStream(fd.fileDescriptor).use { output ->
                val frame = ByteArray(FRAME_BYTES)
                while (downlinkRunning) {
                    var offset = 0
                    while (offset < frame.size && downlinkRunning) {
                        val count = record.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
                        if (count < 0) throw IOException("AudioRecord.read failed: $count")
                        if (count == 0) continue
                        offset += count
                    }
                    if (offset == frame.size && downlinkRunning) output.write(frame)
                }
            }
        } catch (e: Exception) {
            if (downlinkRunning) Log.w(TAG, "Downlink capture stopped: ${e.message}")
        } finally {
            synchronized(lock) {
                if (downlinkWriteEnd === fd) stopDownlinkLocked()
            }
        }
    }

    private fun stopDownlinkLocked() {
        downlinkRunning = false
        downlinkThread?.interrupt()
        downlinkThread = null
        downlinkRecord?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            it.release()
        }
        downlinkRecord = null
        downlinkWriteEnd?.let { runCatching { it.close() } }
        downlinkWriteEnd = null
    }

    override fun stopUplinkInjection() {
        synchronized(lock) { stopLocked() }
    }

    private fun pumpPcm(fd: ParcelFileDescriptor, track: AudioTrack) {
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                val frame = ByteArray(FRAME_BYTES)
                while (true) {
                    var offset = 0
                    while (offset < frame.size) {
                        val count = input.read(frame, offset, frame.size - offset)
                        if (count < 0) return
                        if (count == 0) continue
                        offset += count
                    }
                    var written = 0
                    while (written < frame.size) {
                        val count = track.write(frame, written, frame.size - written, AudioTrack.WRITE_BLOCKING)
                        if (count < 0) throw IOException("AudioTrack.write failed: $count")
                        if (count == 0) break
                        written += count
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Uplink PCM pipe stopped: ${e.message}")
        } finally {
            synchronized(lock) {
                if (readEnd === fd) {
                    readEnd = null
                    writeEnd?.let { runCatching { it.close() } }
                    writeEnd = null
                    audioTrack?.let { trackToRelease ->
                        runCatching { if (trackToRelease.playState == AudioTrack.PLAYSTATE_PLAYING) trackToRelease.stop() }
                        trackToRelease.release()
                    }
                    audioTrack = null
                    audioThread = null
                }
            }
            runCatching { fd.close() }
        }
    }

    private fun stopLocked() {
        val fd = readEnd
        readEnd = null
        writeEnd?.let { runCatching { it.close() } }
        writeEnd = null
        if (fd != null) runCatching { fd.close() }
        audioThread?.interrupt()
        audioThread = null
        audioTrack?.let {
            runCatching { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop() }
            runCatching { it.flush() }
            it.release()
        }
        audioTrack = null
    }

    private fun isCellularMode(mode: Int): Boolean = mode == AudioManager.MODE_IN_CALL ||
        (Build.VERSION.SDK_INT >= 33 && mode == 5)

    private fun shellAudioManager(): AudioManager {
        val context = shellContext ?: throw IllegalStateException("Shizuku did not provide a shell Context")
        // AudioManager(Context) is hidden on the public SDK, so instantiate it reflectively
        // with the shell-attributed Context instead of reusing the app-attributed manager.
        return AudioManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context) as AudioManager
    }

    private fun enableHiddenApiAccess() {
        // Shizuku already runs this service as shell; this exemption permits reflection on the
        // hidden AudioManager call-redirection helpers on Android builds that enforce API lists.
        runCatching {
            val runtimeClass = Class.forName("dalvik.system.VMRuntime")
            val runtime = runtimeClass.getDeclaredMethod("getRuntime").invoke(null)
            runtimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime, arrayOf("Landroid/media/") as Any)
        }
    }

    @Keep
    override fun destroy() {
        stopUplinkInjection()
        stopDownlinkCapture()
        exitProcess(0)
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = "com.android.shell"
        override fun getOpPackageName(): String = "com.android.shell"
        override fun getApplicationContext(): Context = this

        @SuppressLint("NewApi")
        override fun getAttributionSource(): android.content.AttributionSource =
            android.content.AttributionSource.Builder(2000)
                .setPackageName("com.android.shell")
                .build()
    }

    companion object {
        private const val TAG = "ShizuCallCompanion-Uplink"
        private const val SAMPLE_RATE = 8000
        private const val FRAME_BYTES = 320 // 20 ms, mono PCM16 at 8 kHz
    }
}
