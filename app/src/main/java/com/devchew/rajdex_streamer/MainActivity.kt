package com.devchew.rajdex_streamer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var resolution: Spinner
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var connection: UsbDeviceConnection? = null
    private var activeDevice: UsbDevice? = null
    private var availableModes: List<MjpegMode> = emptyList()
    private var selectedMode: MjpegMode? = null
    private var updatingResolutionList = false
    private var restartGeneration = 0
    private var reconnectAttempts = 0

    private val permissionAction by lazy { "$packageName.USB_PERMISSION" }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val device = intent.usbDeviceExtra() ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openCamera(device)
                    else showStatus("Brak zgody na dostęp do grabbera USB")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> intent.usbDeviceExtra()?.let(::requestAccess)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.usbDeviceExtra()
                    if (detached?.deviceId == activeDevice?.deviceId) closeCamera("Odłączono grabber USB")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(12, 15, 20)
        usbManager = getSystemService(UsbManager::class.java)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.rgb(12, 15, 20))
        }
        root.addView(TextView(this).apply {
            text = "PODGLĄD GRABBERA USB"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(4, 4, 4, 8)
        })
        resolution = Spinner(this)
        root.addView(resolution, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        resolution.isEnabled = false
        resolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                android.util.Log.i("RawUvc", "resolution selected position=$position updating=$updatingResolutionList")
                if (updatingResolutionList || position !in availableModes.indices) return
                val mode = availableModes[position]
                if (mode == selectedMode) return
                selectedMode = mode
                reconnectAttempts = 0
                val device = activeDevice ?: return
                showStatus("Zmieniam rozdzielczość na ${mode.width}×${mode.height}…")
                preview.setImageDrawable(null)
                running.set(false)
                val generation = ++restartGeneration
                worker.execute {
                    if (generation != restartGeneration) return@execute
                    closeCamera(null)
                    if (generation == restartGeneration) openCamera(device)
                }
            }
        }
        status = TextView(this).apply {
            text = "Szukam grabbera UVC…"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(4, 4, 4, 8)
        }
        root.addView(status)
        preview = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(usbReceiver, filter)

        val camera = usbManager.deviceList.values.firstOrNull(::isUvcDevice)
        if (camera == null) showStatus("Podłącz grabber UVC przez USB") else requestAccess(camera)
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        closeCamera(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun isUvcDevice(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any {
            val intf = device.getInterface(it)
            intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO &&
                intf.interfaceSubclass == 2
        }

    private fun requestAccess(device: UsbDevice) {
        if (!isUvcDevice(device)) return
        if (usbManager.hasPermission(device)) openCamera(device)
        else {
            showStatus("Oczekuję na zgodę dostępu do ${device.productName ?: "kamery USB"}…")
            val intent = PendingIntent.getBroadcast(
                this, device.deviceId, Intent(permissionAction).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, intent)
        }
    }

    @Synchronized
    private fun openCamera(device: UsbDevice) {
        if (activeDevice?.deviceId == device.deviceId && running.get()) return
        closeCamera(null)
        val usbConnection = usbManager.openDevice(device)
        if (usbConnection == null) {
            showStatus("Nie udało się otworzyć urządzenia USB")
            return
        }
        val streamInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO && it.interfaceSubclass == 2 }
        val bulkIn = streamInterface?.let { intf ->
            (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        it.direction == UsbConstants.USB_DIR_IN
                }
        }
        if (streamInterface == null || bulkIn == null || !usbConnection.claimInterface(streamInterface, true)) {
            usbConnection.close()
            showStatus("Grabber nie udostępnia wejściowego strumienia UVC bulk")
            return
        }

        val modes = findMjpegModes(usbConnection.rawDescriptors)
        if (modes.isEmpty()) {
            usbConnection.releaseInterface(streamInterface)
            usbConnection.close()
            showStatus("Nie znaleziono trybu MJPEG w deskryptorach grabbera")
            return
        }
        availableModes = modes
        val mode = selectedMode?.let { selected -> modes.firstOrNull { it == selected } }
            ?: modes.firstOrNull { it.width == 640 && it.height == 480 }
            ?: modes.minBy { it.width * it.height }
        selectedMode = mode
        updateResolutionOptions(modes, mode)
        connection = usbConnection
        activeDevice = device
        running.set(true)
        showStatus("Konfiguruję UVC ${mode.width}×${mode.height}…")
        worker.execute { configureAndRead(usbConnection, streamInterface, bulkIn, mode) }
    }

    private fun configureAndRead(
        usbConnection: UsbDeviceConnection,
        streamInterface: UsbInterface,
        endpoint: UsbEndpoint,
        mode: MjpegMode
    ) {
        try {
            val probe = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(1) // request the selected frame interval
                put(mode.formatIndex.toByte())
                put(mode.frameIndex.toByte())
                putInt(mode.frameInterval)
                putShort(0); putShort(0); putShort(0); putShort(0); putShort(0)
                putInt(mode.maxFrameSize)
                putInt(0) // device selects the bulk payload size
            }.array()

            var probeSet = -1
            for (attempt in 0 until 3) {
                if (!running.get() || connection !== usbConnection || selectedMode != mode) return
                val interfaceReady = usbConnection.setInterface(streamInterface)
                android.util.Log.i("RawUvc", "setInterface(${streamInterface.id})=$interfaceReady attempt=${attempt + 1} mode=${mode.width}x${mode.height}")
                if (!interfaceReady) Thread.sleep(150L * (attempt + 1))
                probeSet = usbConnection.controlTransfer(
                    0x21, 0x01, 0x0100, streamInterface.id, probe, probe.size, 1500
                )
                android.util.Log.i("RawUvc", "PROBE SET result=$probeSet attempt=${attempt + 1} intf=${streamInterface.id} frame=${mode.frameIndex}")
                if (probeSet >= 0) break
                Thread.sleep(200L * (attempt + 1))
            }
            check(probeSet >= 0) { "UVC PROBE SET nie powiódł się po 3 próbach" }
            val negotiated = ByteArray(26)
            val actual = usbConnection.controlTransfer(
                0xA1, 0x81, 0x0100, streamInterface.id, negotiated, negotiated.size, 1500
            )
            val negotiatedView = ByteBuffer.wrap(negotiated).order(ByteOrder.LITTLE_ENDIAN)
            android.util.Log.i("RawUvc", "PROBE GET result=$actual format=${negotiated[2].toInt() and 0xff} frame=${negotiated[3].toInt() and 0xff} interval=${negotiatedView.getInt(4)} maxFrame=${negotiatedView.getInt(18)} payload=${negotiatedView.getInt(22)}")
            if (actual >= 26) {
                ByteBuffer.wrap(negotiated).order(ByteOrder.LITTLE_ENDIAN).apply {
                    position(22)
                    val payload = int
                    if (payload > 0) Unit
                }
            } else {
                System.arraycopy(probe, 0, negotiated, 0, probe.size)
            }
            ByteBuffer.wrap(negotiated).order(ByteOrder.LITTLE_ENDIAN).putInt(22, endpoint.maxPacketSize)
            android.util.Log.i("RawUvc", "requesting ${endpoint.maxPacketSize}B UVC payload")
            val commitResult = usbConnection.controlTransfer(
                0x21, 0x01, 0x0200, streamInterface.id, negotiated, negotiated.size, 1500
            )
            android.util.Log.i("RawUvc", "COMMIT result=$commitResult")
            check(commitResult >= 0) { "UVC COMMIT nie powiódł się" }
            val committed = ByteArray(26)
            val committedLength = usbConnection.controlTransfer(
                0xA1, 0x81, 0x0200, streamInterface.id, committed, committed.size, 1500
            )
            if (committedLength >= 26) {
                System.arraycopy(committed, 0, negotiated, 0, negotiated.size)
            }
            val payloadSize = ByteBuffer.wrap(negotiated).order(ByteOrder.LITTLE_ENDIAN).getInt(22)
                .coerceIn(512, 4 * 1024 * 1024)
            android.util.Log.i("RawUvc", "COMMIT GET result=$committedLength payload=$payloadSize frame=${negotiated[3].toInt() and 0xff}")
            runOnUiThread { showStatus("Odbieram MJPEG ${mode.width}×${mode.height}") }
            val receivedFrame = readFrames(usbConnection, endpoint, payloadSize)
            if (!receivedFrame && running.get() && selectedMode == mode) {
                val device = activeDevice
                if (device != null && reconnectAttempts < 2) {
                    reconnectAttempts++
                    android.util.Log.w("RawUvc", "no frame after USB mode change; reopening connection attempt=$reconnectAttempts mode=${mode.width}x${mode.height}")
                    runOnUiThread { showStatus("Brak klatek — ponawiam połączenie USB (${reconnectAttempts}/2)…") }
                    closeCamera(null)
                    Thread.sleep(400)
                    if (selectedMode == mode) openCamera(device)
                } else {
                    running.set(false)
                    runOnUiThread { showStatus("Brak klatek z grabbera po ponowieniu połączenia") }
                }
            }
        } catch (error: Exception) {
            if (running.get() && connection === usbConnection && selectedMode == mode) runOnUiThread {
                showStatus("Błąd UVC: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun readFrames(usbConnection: UsbDeviceConnection, endpoint: UsbEndpoint, payloadSize: Int): Boolean {
        val transferBuffer = ByteArray(payloadSize)
        val jpeg = ByteArrayOutputStream(512 * 1024)
        var frameId = -1
        var reads = 0
        var failedReads = 0
        while (running.get()) {
            val count = usbConnection.bulkTransfer(endpoint, transferBuffer, transferBuffer.size, 1500)
            if (reads++ < 10) android.util.Log.i("RawUvc", "bulk read count=$count")
            if (count <= 0) {
                if (++failedReads == 10) runOnUiThread {
                    if (running.get()) showStatus("Brak danych z grabbera (błąd odczytu endpointu USB)")
                }
                if (failedReads >= 100) return false
                try { Thread.sleep(20) } catch (_: InterruptedException) { return false }
                continue
            }
            failedReads = 0
            val headerLength = transferBuffer[0].toInt() and 0xff
            if (count < 2 || headerLength < 2 || headerLength > count) continue
            val flags = transferBuffer[1].toInt() and 0xff
            if (flags and 0x40 != 0) {
                jpeg.reset()
                continue
            }
            val currentFrameId = flags and 1
            if (frameId != -1 && frameId != currentFrameId) jpeg.reset()
            frameId = currentFrameId
            if (count > headerLength) jpeg.write(transferBuffer, headerLength, count - headerLength)
            if (jpeg.size() > 8 * 1024 * 1024) jpeg.reset()
            if (flags and 0x02 != 0 && jpeg.size() > 4) {
                val bytes = jpeg.toByteArray()
                jpeg.reset()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) runOnUiThread {
                    if (running.get()) {
                        reconnectAttempts = 0
                        preview.setImageBitmap(bitmap)
                        status.text = "Obraz USB ${bitmap.width}×${bitmap.height}"
                    } else bitmap.recycle()
                }
            }
        }
        return false
    }

    private fun updateResolutionOptions(modes: List<MjpegMode>, selected: MjpegMode) {
        runOnUiThread {
            updatingResolutionList = true
            availableModes = modes
            resolution.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                modes.map { "${it.width}×${it.height}  ·  ${intervalToFps(it.frameInterval)} fps" }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            resolution.setSelection(modes.indexOf(selected), false)
            resolution.isEnabled = true
            resolution.post { updatingResolutionList = false }
        }
    }

    private fun intervalToFps(interval: Int): Int =
        if (interval > 0) (10_000_000L / interval).toInt() else 30

    private fun findMjpegModes(descriptors: ByteArray): List<MjpegMode> {
        var offset = 0
        var formatIndex = 0
        var isMjpeg = false
        val modes = mutableListOf<MjpegMode>()
        while (offset + 2 <= descriptors.size) {
            val length = descriptors[offset].toInt() and 0xff
            val type = descriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > descriptors.size) break
            if (type == 0x24 && length >= 3) {
                when (descriptors[offset + 2].toInt() and 0xff) {
                    0x06 -> {
                        isMjpeg = length >= 4
                        if (isMjpeg) formatIndex = descriptors[offset + 3].toInt() and 0xff
                    }
                    0x04, 0x10 -> isMjpeg = false
                    0x07 -> if (isMjpeg && length >= 26) {
                        val frameIndex = descriptors[offset + 3].toInt() and 0xff
                        val width = u16(descriptors, offset + 5)
                        val height = u16(descriptors, offset + 7)
                        val maxFrameSize = u32(descriptors, offset + 17)
                        val defaultInterval = selectFrameInterval(descriptors, offset, length)
                        val mode = MjpegMode(
                            formatIndex, frameIndex, width, height,
                            defaultInterval.takeIf { it > 0 } ?: 333333,
                            maxFrameSize.takeIf { it > 0 } ?: width * height * 2
                        )
                        if (width > 0 && height > 0 && mode !in modes) modes.add(mode)
                    }
                }
            }
            offset += length
        }
        return modes.sortedWith(compareBy<MjpegMode> { it.width * it.height }.thenBy { it.width })
    }

    @Synchronized
    private fun closeCamera(message: String?) {
        running.set(false)
        val oldConnection = connection
        val oldDevice = activeDevice
        connection = null
        activeDevice = null
        if (oldConnection != null) {
            val intf = oldDevice?.let { device ->
                (0 until device.interfaceCount).map { device.getInterface(it) }
                    .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO && it.interfaceSubclass == 2 }
            }
            if (intf != null) runCatching { oldConnection.releaseInterface(intf) }
            runCatching { oldConnection.close() }
        }
        if (message != null) showStatus(message)
    }

    private fun showStatus(message: String) {
        if (::status.isInitialized) runOnUiThread { status.text = message }
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun selectFrameInterval(data: ByteArray, offset: Int, length: Int): Int {
        val defaultInterval = u32(data, offset + 21)
        val intervalCount = data[offset + 25].toInt() and 0xff
        if (intervalCount > 0) {
            val intervals = (0 until intervalCount).mapNotNull { index ->
                val valueOffset = offset + 26 + index * 4
                if (valueOffset + 4 <= offset + length) u32(data, valueOffset) else null
            }.filter { it > 0 }
            return intervals.filter { it >= 333333 }.minOrNull()
                ?: intervals.minOrNull() ?: defaultInterval
        }
        return if (defaultInterval > 0) maxOf(defaultInterval, 333333) else 333333
    }

    private fun u32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)

    private data class MjpegMode(
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val frameInterval: Int,
        val maxFrameSize: Int
    )
}
