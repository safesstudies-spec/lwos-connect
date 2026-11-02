package com.lwos.connect.ui

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lwos.connect.transport.SecondScreenClient
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Turns this phone/tablet into a live mirror of the paired PC's screen,
 * received over USB — reachable at 127.0.0.1 only because the desktop
 * daemon has set up `adb reverse` tunnels (see run_adb_reverse_loop in
 * lwos_continuity_daemon.py). The PC captures its real X11 display with
 * ffmpeg and sends raw H.264; this screen decodes it straight to a
 * Surface and shows it fullscreen.
 *
 * This is MIRROR mode, not a true extended desktop — see README/
 * ROADMAP for what a real second-monitor mode would additionally need
 * (a virtual X11 output on the PC side). There's also no touch/mouse
 * input going back to the PC yet; this is display-only for now.
 *
 * Driving MediaCodec: two background threads, the standard shape for
 * its synchronous (non-callback) API —
 *  - the RECEIVE thread blocks reading the socket, splits NAL units,
 *    and queues each one into the decoder's input side as a free input
 *    buffer becomes available;
 *  - the DRAIN thread continuously asks the decoder for finished output
 *    buffers and releases them straight to the Surface (`true` in
 *    releaseOutputBuffer means "render it" — there's no manual YUV/
 *    color-conversion handling on our side at all; the hardware decoder
 *    and the Surface take care of that).
 * Both stop together when this Activity goes away.
 */
class SecondScreenActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView

    private val client = SecondScreenClient()
    // Written on the UI thread (configureDecoder), read on the receive
    // thread (feedDecoder) — needs @Volatile so the receive thread is
    // guaranteed to see it as soon as it's set, not some stale null.
    @Volatile private var decoder: MediaCodec? = null
    private var receiveThread: Thread? = null
    private var drainThread: Thread? = null
    @Volatile private var draining = false
    // Set right before we intentionally close the connection, so the
    // receive thread's "disconnected" error handling (below) can tell
    // a deliberate shutdown apart from the PC actually dropping the
    // stream, and skip touching views on an Activity that's closing.
    @Volatile private var stopping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        surfaceView = SurfaceView(this)
        statusText = TextView(this).apply {
            text = "Connecting to PC…"
            setTextColor(Color.WHITE)
            textSize = 18f
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(statusText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
        }
        setContentView(root)

        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startStreaming(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopStreaming()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun startStreaming(holder: SurfaceHolder) {
        receiveThread = thread(name = "SecondScreen-receive") {
            try {
                client.connectAndStream(
                    host = PC_HOST,
                    port = SecondScreenClient.DEFAULT_PORT,
                    onHeader = { header -> runOnUiThread { configureDecoder(header, holder) } },
                    onNalUnit = { nal -> feedDecoder(nal) }
                )
            } catch (e: IOException) {
                Log.w(TAG, "Second screen stream ended: ${e.message}")
                if (!stopping) {
                    runOnUiThread {
                        statusText.text = "Disconnected — make sure the daemon is running on the PC " +
                            "and the cable/adb connection is still up.\n(${e.message})"
                        statusText.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun configureDecoder(header: SecondScreenClient.StreamHeader, holder: SurfaceHolder) {
        statusText.visibility = View.GONE
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, header.width, header.height)
            val codec = MediaCodec.createDecoderByType(MIME_TYPE)
            codec.configure(format, holder.surface, null, 0)
            codec.start()
            decoder = codec

            draining = true
            drainThread = thread(name = "SecondScreen-drain") { drainLoop(codec) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start decoder for ${header.width}x${header.height}", e)
            statusText.text = "Could not start video decoder: ${e.message}"
            statusText.visibility = View.VISIBLE
        }
    }

    /**
     * Pushes one NAL unit into the next available decoder input buffer.
     * If none is free within the short timeout, the NAL is dropped
     * rather than blocking the receive thread — under normal conditions
     * this doesn't happen, and preferring a dropped frame over a
     * growing backlog keeps the stream live instead of falling behind.
     */
    private fun feedDecoder(nal: ByteArray) {
        val codec = decoder ?: return // header hasn't arrived / decoder not ready yet
        try {
            val inputIndex = codec.dequeueInputBuffer(BUFFER_TIMEOUT_US)
            if (inputIndex < 0) return
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(nal)
            codec.queueInputBuffer(inputIndex, 0, nal.size, System.nanoTime() / 1000, 0)
        } catch (e: IllegalStateException) {
            // Decoder was stopped/released mid-flight (e.g. screen closing) — safe to ignore.
        } catch (e: java.nio.BufferOverflowException) {
            Log.w(TAG, "Dropped an oversized NAL unit (${nal.size} bytes) that didn't fit the input buffer")
        }
    }

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (draining) {
            try {
                val outputIndex = codec.dequeueOutputBuffer(info, BUFFER_TIMEOUT_US)
                if (outputIndex >= 0) {
                    codec.releaseOutputBuffer(outputIndex, true) // true = render straight to the Surface
                }
            } catch (e: IllegalStateException) {
                break // codec released out from under us — stop draining
            }
        }
    }

    private fun stopStreaming() {
        stopping = true
        draining = false
        client.stop()
        receiveThread?.interrupt()
        drainThread?.join(500)
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: IllegalStateException) {
            // already stopped
        }
        decoder = null
    }

    private fun hideSystemBars() {
        // minSdk is 31 (Android 12), which is already past the API 30
        // cutoff for this inset API, so there's no need for an older
        // systemUiVisibility fallback path here.
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val TAG = "SecondScreenActivity"
        private const val MIME_TYPE = "video/avc"

        // Reachable only because the desktop daemon's run_adb_reverse_loop
        // has tunneled this port over the USB cable. There's no LAN/
        // wireless mode yet, so this is hardcoded rather than taken from
        // a discovered device's real IP — see README's "Wired & Wireless"
        // note for what wireless mode will need to change here.
        private const val PC_HOST = "127.0.0.1"

        private const val BUFFER_TIMEOUT_US = 10_000L
    }
}
