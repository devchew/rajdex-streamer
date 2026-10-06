package com.devchew.rajdex_streamer

import android.Manifest
import android.app.PendingIntent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.os.Environment
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var globalStatus: TextView
    private lateinit var slots: List<PreviewSlot>
    private var cameraClient: MultiCameraClient? = null
    private val slotByDeviceId = mutableMapOf<Int, PreviewSlot>()
    private val rawPermissionPending = mutableSetOf<Int>()
    private val libraryPermissionPending = mutableSetOf<Int>()
    private lateinit var recordingButton: Button
    private lateinit var audioRecordingCheckbox: CheckBox
    private var recordingRequested = false
    private var recordingActive = false
    private var audioRecorder: UsbWavRecorder? = null
    private val rawPermissionAction by lazy { "$packageName.RAW_USB_PERMISSION" }

    private inner class PreviewSlot(val index: Int) {
        lateinit var title: TextView
        lateinit var status: TextView
        lateinit var resolution: Spinner
        lateinit var image: ImageView
        lateinit var texture: AspectRatioTextureView
        var device: UsbDevice? = null
        var raw = false
        var rawConnection: UsbDeviceConnection? = null
        var rawInterface: UsbInterface? = null
        var rawEndpoint: UsbEndpoint? = null
        var rawRunning: AtomicBoolean? = null
        var rawGeneration = 0
        var rawReconnects = 0
        var rawWorker = Executors.newSingleThreadExecutor()
        var libraryCamera: CameraUVC? = null
        var libraryControlBlock: USBMonitor.UsbControlBlock? = null
        var options: List<ResolutionOption> = emptyList()
        var selectedOption: ResolutionOption? = null
        var changingOptions = false
        lateinit var recordingCheckbox: CheckBox
        lateinit var encodeMjpegCheckbox: CheckBox
        var recordingWriter: MjpegAviRecorder? = null
        var mp4RecordingWriter: MjpegMp4RecordingWorker? = null
        var recordingFile: File? = null
    }

    private data class ResolutionOption(
        val width: Int,
        val height: Int,
        val fps: Int,
        val rawMode: MjpegMode? = null
    ) {
        override fun toString() = "$width×$height  ·  $fps fps"
    }

    private data class MjpegMode(
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val frameInterval: Int,
        val maxFrameSize: Int
    )

    private val rawPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != rawPermissionAction) return
            val device = intent.usbDeviceExtra() ?: return
            rawPermissionPending.remove(device.deviceId)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                slotByDeviceId[device.deviceId]?.let { startRaw(it, device, it.selectedOption?.rawMode) }
            } else {
                slotByDeviceId[device.deviceId]?.let { setSlotStatus(it, "Brak zgody na dostęp USB") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(12, 15, 20)
        usbManager = getSystemService(UsbManager::class.java)
        slots = listOf(PreviewSlot(0), PreviewSlot(1))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.rgb(12, 15, 20))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12), dp(8) + bars.top, dp(12), dp(8) + bars.bottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = "PODGLĄD KAMER USB"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(4, 4, 4, 4)
        })
        globalStatus = TextView(this).apply {
            text = "Inicjalizuję kamery USB…"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(4, 0, 4, 6)
        }
        root.addView(globalStatus)

        audioRecordingCheckbox = CheckBox(this).apply {
            text = "Nagrywaj dźwięk USB"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(audioRecordingCheckbox)

        recordingButton = Button(this).apply {
            text = "NAGRAJ ZAZNACZONE"
            isEnabled = false
            setOnClickListener {
                if (recordingRequested) stopRecordingAndRestorePreview()
                else requestOrStartRecording()
            }
        }
        root.addView(recordingButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this)
        val panelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        slots.forEach { slot -> panelList.addView(createSlotView(slot)) }
        refreshRecordingControls()
        scroll.addView(panelList)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        val filter = IntentFilter(rawPermissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rawPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(rawPermissionReceiver, filter)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraClient()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            globalStatus.text = "Zezwól na uprawnienie Kamera, aby uruchomić UVC"
        }
    }

    private fun createSlotView(slot: PreviewSlot): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 6, 8, 8)
            setBackgroundColor(Color.rgb(25, 30, 38))
        }
        slot.title = TextView(this).apply {
            text = "KAMERA USB ${slot.index + 1}"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(2, 2, 2, 2)
        }
        card.addView(slot.title)

        val recordingOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        slot.recordingCheckbox = CheckBox(this).apply {
            text = "Nagrywaj tę kamerę"
            setTextColor(Color.WHITE)
            isChecked = true
            setOnCheckedChangeListener { _, _ -> refreshRecordingControls() }
        }
        recordingOptions.addView(slot.recordingCheckbox)
        slot.encodeMjpegCheckbox = CheckBox(this).apply {
            text = "Koduj MJPEG do MP4 (H.264)"
            setTextColor(Color.WHITE)
            isChecked = false
            visibility = View.GONE
            setOnCheckedChangeListener { _, _ -> refreshRecordingControls() }
        }
        recordingOptions.addView(slot.encodeMjpegCheckbox)
        card.addView(recordingOptions)

        slot.resolution = Spinner(this).apply {
            isEnabled = false
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (slot.changingOptions || position !in slot.options.indices) return
                    val option = slot.options[position]
                    if (option == slot.selectedOption) return
                    slot.selectedOption = option
                    if (slot.raw) {
                        slot.rawReconnects = 0
                        slot.device?.let { startRaw(slot, it, option.rawMode) }
                    } else {
                        slot.texture.setAspectRatio(option.width, option.height)
                        val device = slot.device
                        val ctrlBlock = slot.libraryControlBlock
                        if (device != null && ctrlBlock != null) {
                            restartLibraryCamera(slot, device, ctrlBlock, option.width, option.height)
                        }
                    }
                }
            }
        }
        card.addView(slot.resolution)

        slot.status = TextView(this).apply {
            text = "Oczekuję na kamerę UVC…"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(2, 2, 2, 5)
        }
        card.addView(slot.status)

        val previewArea = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        slot.image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        slot.texture = AspectRatioTextureView(this).apply { visibility = View.GONE }
        previewArea.addView(slot.image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        previewArea.addView(slot.texture, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        card.addView(previewArea, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { height = dp(230) })
        return card
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun startCameraClient() {
        if (cameraClient != null) return
        val client = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device?.let(::handleAttach)
            }

            override fun onDetachDec(device: UsbDevice?) {
                device?.let(::handleDetach)
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                if (device == null || ctrlBlock == null) return
                libraryPermissionPending.remove(device.deviceId)
                val slot = slotByDeviceId[device.deviceId] ?: return
                slot.libraryControlBlock = ctrlBlock
                if (hasBulkEndpoint(device)) return
                val selected = slot.selectedOption
                openLibraryCamera(
                    slot, device, ctrlBlock,
                    selected?.width ?: DEFAULT_WIDTH,
                    selected?.height ?: DEFAULT_HEIGHT
                )
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device?.let { slotByDeviceId[it.deviceId]?.let { slot -> stopLibraryCamera(slot) } }
            }

            override fun onCancelDev(device: UsbDevice?) {
                device?.let {
                    libraryPermissionPending.remove(it.deviceId)
                    slotByDeviceId[it.deviceId]?.let { slot -> setSlotStatus(slot, "Brak zgody na kamerę USB") }
                }
            }
        })
        cameraClient = client
        client.openDebug(true)
        client.register()
        globalStatus.text = "Szukam kamer UVC…"
        usbManager.deviceList.values.filter(::isUvcDevice).forEach(::handleAttach)
    }

    private fun handleAttach(device: UsbDevice) {
        if (!isUvcDevice(device)) return
        val slot = slotByDeviceId[device.deviceId] ?: slots.firstOrNull { it.device == null }
        if (slot == null) {
            globalStatus.text = "Podłączono więcej niż dwie kamery UVC; pomiń ${device.productName ?: device.deviceName}"
            return
        }
        if (slot.device?.deviceId == device.deviceId) return
        slot.device = device
        slotByDeviceId[device.deviceId] = slot
        slot.raw = hasBulkEndpoint(device)
        slot.title.text = "KAMERA USB ${slot.index + 1}: ${device.productName ?: "UVC"}"
        refreshRecordingControls(slot)
        updateGlobalStatus()
        slot.texture.visibility = if (slot.raw) View.GONE else View.VISIBLE
        slot.image.visibility = if (slot.raw) View.VISIBLE else View.GONE
        setSlotStatus(slot, "Proszę o dostęp do urządzenia…")
        if (slot.raw) {
            requestRawPermission(device)
        } else if (libraryPermissionPending.add(device.deviceId)) {
            cameraClient?.requestPermission(device)
        }
    }

    private fun handleDetach(device: UsbDevice) {
        val slot = slotByDeviceId.remove(device.deviceId) ?: return
        slot.recordingWriter?.let { runCatching { it.close() }; slot.recordingWriter = null }
        rawPermissionPending.remove(device.deviceId)
        libraryPermissionPending.remove(device.deviceId)
        stopRaw(slot)
        slot.mp4RecordingWriter?.let { writer ->
            slot.mp4RecordingWriter = null
            val file = slot.recordingFile
            slot.recordingFile = null
            Thread({
                if (runCatching { writer.close() }.isSuccess && file != null) {
                    publishRecordingAsync(file, "video/mp4") {
                        setSlotStatus(slot, "Zapisano MP4: DCIM/Rajdex/${file.name}")
                    }
                } else file?.delete()
            }, "MP4-detach-finalize").start()
        }
        stopLibraryCamera(slot)
        slot.libraryControlBlock = null
        slot.device = null
        slot.raw = false
        refreshRecordingControls(slot)
        slot.options = emptyList()
        slot.selectedOption = null
        slot.resolution.adapter = null
        slot.resolution.isEnabled = false
        slot.image.setImageDrawable(null)
        slot.texture.visibility = View.GONE
        slot.image.visibility = View.VISIBLE
        slot.title.text = "KAMERA USB ${slot.index + 1}"
        setSlotStatus(slot, "Odłączono — oczekuję na kamerę UVC")
        updateGlobalStatus()
    }

    private fun requestRawPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            slotByDeviceId[device.deviceId]?.let { startRaw(it, device, it.selectedOption?.rawMode) }
            return
        }
        if (!rawPermissionPending.add(device.deviceId)) return
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            Intent(rawPermissionAction).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun startRaw(slot: PreviewSlot, device: UsbDevice, requestedMode: MjpegMode?) {
        val generation = ++slot.rawGeneration
        slot.rawRunning?.set(false)
        slot.rawConnection?.let { old ->
            runCatching { slot.rawInterface?.let(old::releaseInterface) }
            runCatching { old.close() }
        }
        slot.rawConnection = null
        slot.rawRunning = null
        slot.rawWorker.execute {
            if (generation != slot.rawGeneration || slot.device?.deviceId != device.deviceId) return@execute
            try {
                // Let Android finish USB/audio enumeration before opening a bulk stream.
                Thread.sleep(if (requestedMode == null) 1_200L else 400L)
            } catch (_: InterruptedException) {
                return@execute
            }
            if (generation != slot.rawGeneration || slot.device?.deviceId != device.deviceId) return@execute
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                setSlotStatus(slot, "Nie udało się otworzyć USB")
                return@execute
            }
            val streamInterface = findBulkInterface(device)
            val endpoint = streamInterface?.let(::findBulkInEndpoint)
            if (streamInterface == null || endpoint == null || !connection.claimInterface(streamInterface, true)) {
                connection.close()
                setSlotStatus(slot, "Nie udało się uruchomić strumienia bulk")
                return@execute
            }
            slot.rawConnection = connection
            slot.rawInterface = streamInterface
            slot.rawEndpoint = endpoint

            val descriptors = connection.rawDescriptors
            logUvcFormats(device, descriptors)
            val modes = findMjpegModes(descriptors)
            if (modes.isEmpty()) {
                stopRaw(slot)
                setSlotStatus(slot, "Brak trybów MJPEG w deskryptorze urządzenia")
                return@execute
            }
            val mode = requestedMode?.let { requested -> modes.firstOrNull { it == requested } }
                ?: modes.firstOrNull { it.width == 640 && it.height == 480 }
                ?: modes.minBy { it.width * it.height }
            slot.selectedOption = ResolutionOption(mode.width, mode.height, intervalToFps(mode.frameInterval), mode)
            slot.rawRunning = AtomicBoolean(true)
            runOnUiThread {
                slot.image.visibility = View.VISIBLE
                slot.texture.visibility = View.GONE
                slot.image.setImageDrawable(null)
            }
            updateOptions(slot, modes.map {
                ResolutionOption(it.width, it.height, intervalToFps(it.frameInterval), it)
            }, slot.selectedOption!!)
            setSlotStatus(slot, "Konfiguruję ${mode.width}×${mode.height}…")
            configureAndRead(slot, device, connection, streamInterface, endpoint, mode, generation)
        }
    }

    private fun configureAndRead(
        slot: PreviewSlot,
        device: UsbDevice,
        connection: UsbDeviceConnection,
        streamInterface: UsbInterface,
        endpoint: UsbEndpoint,
        mode: MjpegMode,
        generation: Int
    ) {
        try {
            val probe = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(1)
                put(mode.formatIndex.toByte())
                put(mode.frameIndex.toByte())
                putInt(mode.frameInterval)
                putShort(0); putShort(0); putShort(0); putShort(0); putShort(0)
                putInt(mode.maxFrameSize)
                putInt(0)
            }.array()

            var probeResult = -1
            for (attempt in 0 until 3) {
                if (!isCurrentRaw(slot, connection, generation, mode)) return
                val interfaceReady = connection.setInterface(streamInterface)
                android.util.Log.i(TAG, "${device.productName}: setInterface=$interfaceReady attempt=${attempt + 1} mode=${mode.width}x${mode.height}")
                if (!interfaceReady) Thread.sleep(150L * (attempt + 1))
                val endpointReset = connection.controlTransfer(
                    0x02, 0x01, 0, endpoint.address, ByteArray(0), 0, 1000
                )
                android.util.Log.i(TAG, "${device.productName}: clear endpoint halt=${endpointReset >= 0}")
                probeResult = connection.controlTransfer(
                    0x21, 0x01, 0x0100, streamInterface.id, probe, probe.size, 1500
                )
                android.util.Log.i(TAG, "${device.productName}: PROBE=$probeResult attempt=${attempt + 1} frame=${mode.frameIndex}")
                if (probeResult >= 0) break
                Thread.sleep(200L * (attempt + 1))
            }
            check(probeResult >= 0) { "UVC PROBE SET failed after three attempts" }

            val negotiated = ByteArray(26)
            val probeLength = connection.controlTransfer(
                0xA1, 0x81, 0x0100, streamInterface.id, negotiated, negotiated.size, 1500
            )
            if (probeLength < 26) System.arraycopy(probe, 0, negotiated, 0, probe.size)
            ByteBuffer.wrap(negotiated).order(ByteOrder.LITTLE_ENDIAN).putInt(22, endpoint.maxPacketSize)
            check(connection.controlTransfer(
                0x21, 0x01, 0x0200, streamInterface.id, negotiated, negotiated.size, 1500
            ) >= 0) { "UVC COMMIT failed" }

            val committed = ByteArray(26)
            val committedLength = connection.controlTransfer(
                0xA1, 0x81, 0x0200, streamInterface.id, committed, committed.size, 1500
            )
            if (committedLength >= 26) System.arraycopy(committed, 0, negotiated, 0, negotiated.size)
            val payloadSize = ByteBuffer.wrap(negotiated).order(ByteOrder.LITTLE_ENDIAN).getInt(22)
                .coerceIn(512, 4 * 1024 * 1024)
            android.util.Log.i(TAG, "${device.productName}: COMMIT frame=${negotiated[3].toInt() and 0xff} payload=$payloadSize")
            setSlotStatus(slot, "Odbieram MJPEG ${mode.width}×${mode.height}")

            val gotFrame = readRawFrames(slot, connection, endpoint, payloadSize, mode, generation)
            if (!gotFrame && isCurrentRaw(slot, connection, generation, mode)) {
                if (slot.rawReconnects < 4) {
                    slot.rawReconnects++
                    setSlotStatus(slot, "Brak klatek — ponawiam połączenie (${slot.rawReconnects}/4)…")
                    android.util.Log.w(TAG, "${device.productName}: reopening USB after missing frames, attempt=${slot.rawReconnects}")
                    startRaw(slot, device, mode)
                } else {
                    stopRaw(slot)
                    setSlotStatus(slot, "Brak obrazu USB po ponowieniu połączenia")
                }
            }
        } catch (error: Exception) {
            if (isCurrentRaw(slot, connection, generation, mode)) {
                android.util.Log.e(TAG, "${device.productName}: stream configuration failed", error)
                slot.rawRunning?.set(false)
                setSlotStatus(slot, "Błąd UVC: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun readRawFrames(
        slot: PreviewSlot,
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        payloadSize: Int,
        mode: MjpegMode,
        generation: Int
    ): Boolean {
        val transferBuffer = ByteArray(payloadSize)
        val jpeg = ByteArrayOutputStream(512 * 1024)
        var frameId = -1
        var failedReads = 0
        var reads = 0
        var lastPreviewUpdateMs = 0L
        while (isCurrentRaw(slot, connection, generation, mode)) {
            val count = connection.bulkTransfer(endpoint, transferBuffer, transferBuffer.size, 1500)
            if (reads++ < 8) android.util.Log.i(TAG, "KAMERA USB ${slot.index + 1}: bulk read=$count")
            if (count <= 0) {
                failedReads++
                if (failedReads == 10) setSlotStatus(slot, "Brak danych z grabbera USB")
                if (failedReads >= 100) return false
                try { Thread.sleep(20) } catch (_: InterruptedException) { return false }
                continue
            }
            failedReads = 0
            if (count < 2) continue
            val headerLength = transferBuffer[0].toInt() and 0xff
            if (headerLength < 2 || headerLength > count) continue
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
                val data = jpeg.toByteArray()
                val capturedAtNs = System.nanoTime()
                jpeg.reset()
                slot.recordingWriter?.let { writer ->
                    runCatching { writer.writeFrame(data) }.onFailure { error ->
                        runOnUiThread { failRecording("Blad zapisu AVI: ${error.message ?: "plik"}") }
                    }
                }
                slot.mp4RecordingWriter?.let { writer ->
                    writer.offerFrame(data, capturedAtNs)
                }
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if ((slot.recordingWriter != null || slot.mp4RecordingWriter != null) && nowMs - lastPreviewUpdateMs < 33L) continue
                lastPreviewUpdateMs = nowMs
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) {
                    slot.rawReconnects = 0
                    runOnUiThread {
                        if (isCurrentRaw(slot, connection, generation, mode)) {
                            slot.image.setImageBitmap(bitmap)
                            slot.status.text = "Obraz USB ${bitmap.width}×${bitmap.height}"
                        } else bitmap.recycle()
                    }
                }
            }
        }
        return false
    }

    private fun isCurrentRaw(slot: PreviewSlot, connection: UsbDeviceConnection, generation: Int, mode: MjpegMode) =
        slot.rawGeneration == generation && slot.rawConnection === connection &&
            slot.rawRunning?.get() == true && slot.selectedOption?.rawMode == mode

    private fun openLibraryCamera(
        slot: PreviewSlot,
        device: UsbDevice,
        ctrlBlock: USBMonitor.UsbControlBlock,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ) {
        if (slot.libraryCamera?.getUsbDevice()?.deviceId == device.deviceId) return
        stopLibraryCamera(slot)
        slot.texture.visibility = View.VISIBLE
        slot.image.visibility = View.GONE
        setSlotStatus(slot, "Uruchamiam UVC przez libuvc…")
        val camera = CameraUVC(this, device)
        slot.libraryCamera = camera
        camera.setUsbControlBlock(ctrlBlock)
        camera.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
                when (code) {
                    ICameraStateCallBack.State.OPENED -> {
                        val sizes = self.getAllPreviewSizes().distinctBy { "${it.width}x${it.height}" }
                            .sortedWith(compareBy<PreviewSize> { it.width * it.height }.thenBy { it.width })
                        val options = sizes.map { ResolutionOption(it.width, it.height, 30) }
                        val current = self.getCameraRequest()
                        val selected = options.firstOrNull {
                            it.width == current?.previewWidth && it.height == current.previewHeight
                        } ?: options.firstOrNull { it.width == 640 && it.height == 480 } ?: options.firstOrNull()
                        if (selected != null) {
                            slot.selectedOption = selected
                            updateOptions(slot, options, selected)
                        }
                        setSlotStatus(slot, "Kamera działa przez libuvc")
                    }
                    ICameraStateCallBack.State.CLOSED -> setSlotStatus(slot, "Strumień kamery zatrzymany")
                    ICameraStateCallBack.State.ERROR -> {
                        setSlotStatus(slot, "Błąd kamery: ${msg ?: "libuvc"}")
                    }
                }
            }
        })
        slot.texture.setAspectRatio(width, height)
        val request = CameraRequest.Builder()
            .setPreviewWidth(width)
            .setPreviewHeight(height)
            .setRenderMode(CameraRequest.RenderMode.NORMAL)
            .setAspectRatioShow(true)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .create()
        var previewStarted = false
        fun startPreviewWhenReady() {
            if (previewStarted || slot.libraryCamera !== camera || !slot.texture.isAvailable) return
            previewStarted = true
            camera.openCamera(slot.texture, request)
        }
        slot.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                startPreviewWhenReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                camera.setRenderSize(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                if (slot.libraryCamera === camera) camera.closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
        }
        slot.texture.post { startPreviewWhenReady() }
    }

    private fun restartLibraryCamera(
        slot: PreviewSlot,
        device: UsbDevice,
        ctrlBlock: USBMonitor.UsbControlBlock,
        width: Int,
        height: Int
    ) {
        slot.libraryCamera?.let { oldCamera ->
            oldCamera.setCameraStateCallBack(null)
            runCatching { oldCamera.closeCamera() }
        }
        slot.libraryCamera = null
        slot.libraryControlBlock = ctrlBlock
        slot.texture.postDelayed({
            if (slot.device?.deviceId == device.deviceId && slot.libraryControlBlock === ctrlBlock) {
                openLibraryCamera(slot, device, ctrlBlock, width, height)
            }
        }, 500)
    }

    private fun stopLibraryCamera(slot: PreviewSlot) {
        slot.libraryCamera?.let { camera ->
            camera.setCameraStateCallBack(null)
            runCatching { camera.closeCamera() }
            runCatching { camera.setUsbControlBlock(null) }
        }
        slot.libraryCamera = null
    }

    private fun stopRaw(slot: PreviewSlot) {
        slot.rawGeneration++
        slot.rawRunning?.set(false)
        slot.rawConnection?.let { connection ->
            runCatching { slot.rawInterface?.let(connection::releaseInterface) }
            runCatching { connection.close() }
        }
        slot.rawConnection = null
        slot.rawInterface = null
        slot.rawEndpoint = null
        slot.rawRunning = null
    }

    private fun updateOptions(slot: PreviewSlot, options: List<ResolutionOption>, selected: ResolutionOption) {
        runOnUiThread {
            slot.changingOptions = true
            slot.options = options
            slot.resolution.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                options
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            slot.resolution.setSelection(options.indexOf(selected), false)
            slot.resolution.isEnabled = options.isNotEmpty()
            slot.resolution.post { slot.changingOptions = false }
        }
    }

    private fun requestOrStartRecording() {
        val selectedSlots = slots.filter { it.device != null && it.recordingCheckbox.isChecked }
        if (selectedSlots.isEmpty()) {
            globalStatus.text = "Zaznacz co najmniej jedną podłączoną kamerę"
            return
        }
        if (audioRecordingCheckbox.isChecked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION)
            globalStatus.text = "Zezwól na dostęp do audio USB"
            return
        }
        startRecording()
    }

    private fun startRecording() {
        if (recordingRequested) return
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Rajdex")
        if (!directory.exists() && !directory.mkdirs()) {
            globalStatus.text = "Nie mozna utworzyc katalogu nagran"
            return
        }
        val selectedSlots = slots.filter { it.device != null && it.recordingCheckbox.isChecked }
        if (selectedSlots.isEmpty()) {
            globalStatus.text = "Zaznacz co najmniej jedną podłączoną kamerę"
            return
        }
        val audioInput = if (audioRecordingCheckbox.isChecked) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE && it.productName.toString().contains("UGREEN", true)
            }.also {
                if (it == null) globalStatus.text = "Nie wykryto wejścia audio USB UGREEN"
            } ?: return
        } else null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        try {
            audioInput?.let {
                val audioFile = File(directory, "${stamp}_audio_UGREEN.wav")
                audioRecorder = UsbWavRecorder.create(audioFile, it).also { recorder -> recorder.start() }
            }
            recordingRequested = true
            recordingActive = true
            recordingButton.text = "ZATRZYMAJ NAGRYWANIE"
            refreshRecordingControls()

            selectedSlots.forEach { slot ->
                val device = requireNotNull(slot.device)
                val safeName = (device.productName ?: "USB_camera_${slot.index + 1}")
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
                if (slot.raw) {
                    val mode = slot.selectedOption ?: error("Brak rozdzielczosci dla ${device.productName}")
                    if (slot.encodeMjpegCheckbox.isChecked) {
                        slot.recordingFile = File(directory, "${stamp}_${slot.index + 1}_${safeName}.mp4")
                        slot.mp4RecordingWriter = MjpegMp4RecordingWorker(
                            slot.recordingFile!!, mode.width, mode.height, 30
                        ) { error ->
                            runOnUiThread { failRecording("Blad kodowania MP4: ${error.message ?: "encoder"}") }
                        }
                        setSlotStatus(slot, "Nagrywam MP4 H.264 ${mode.width}x${mode.height}")
                    } else {
                        slot.recordingFile = File(directory, "${stamp}_${slot.index + 1}_${safeName}.avi")
                        slot.recordingWriter = MjpegAviRecorder(
                            slot.recordingFile!!,
                            mode.width, mode.height, mode.fps.coerceAtLeast(1)
                        )
                        setSlotStatus(slot, "Nagrywam MJPEG ${mode.width}x${mode.height}")
                    }
                } else {
                    val camera = slot.libraryCamera ?: error("Kamera ${slot.index + 1} nie jest gotowa")
                    val path = File(directory, "${stamp}_${slot.index + 1}_${safeName}").absolutePath
                    camera.captureVideoStart(object : ICaptureCallBack {
                        override fun onBegin() = setSlotStatus(slot, "Nagrywam MP4 H.264")
                        override fun onError(error: String?) {
                            setSlotStatus(slot, "Blad nagrywania: ${error ?: "encoder"}")
                            runOnUiThread { failRecording("Blad zapisu MP4") }
                        }
                        override fun onComplete(path: String?) {
                            path?.let { savedPath ->
                                publishRecordingAsync(File(savedPath), "video/mp4") { published ->
                                    setSlotStatus(slot, "Zapisano: DCIM/Rajdex/${File(savedPath).name}")
                                }
                            }
                        }
                    }, path)
                }
            }
            val tracks = buildList {
                if (selectedSlots.isNotEmpty()) add("${selectedSlots.size} kamer")
                if (audioRecorder != null) add("audio USB")
            }
            globalStatus.text = "Nagrywanie: ${tracks.joinToString(" + ")}"
        } catch (error: Exception) {
            failRecording("Nie mozna uruchomic nagrywania: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun stopRecordingAndRestorePreview() {
        if (!recordingRequested) return
        recordingRequested = false
        recordingActive = false
        recordingButton.text = "NAGRAJ ZAZNACZONE"
        slots.forEach { slot ->
            slot.mp4RecordingWriter?.let { writer ->
                slot.mp4RecordingWriter = null
                val recordingFile = slot.recordingFile
                slot.recordingFile = null
                Thread({
                    val saved = runCatching { writer.close() }.isSuccess
                    if (saved && recordingFile != null) {
                        publishRecordingAsync(recordingFile, "video/mp4") {
                            setSlotStatus(slot, "Zapisano MP4: DCIM/Rajdex/${recordingFile.name}")
                        }
                    } else {
                        recordingFile?.delete()
                        runOnUiThread { setSlotStatus(slot, "Brak klatek MP4 — plik nie został zapisany") }
                    }
                }, "MP4-finalize").start()
            }
            slot.recordingWriter?.let { writer ->
                slot.recordingWriter = null
                val recordingFile = slot.recordingFile
                slot.recordingFile = null
                Thread({
                    val saved = runCatching { writer.close() }.isSuccess
                    if (saved) {
                        if (recordingFile != null) {
                            publishRecordingAsync(recordingFile, "video/x-msvideo") {
                                setSlotStatus(slot, "Zapisano AVI: DCIM/Rajdex/${recordingFile.name}")
                            }
                        } else {
                            runOnUiThread { setSlotStatus(slot, "Blad finalizacji AVI") }
                        }
                    } else {
                        runOnUiThread { setSlotStatus(slot, "Blad finalizacji AVI") }
                    }
                }, "AVI-finalize").start()
            }
            slot.libraryCamera?.let { camera ->
                if (camera.isRecording()) camera.captureVideoStop()
            }
        }
        audioRecorder?.let { recorder ->
            audioRecorder = null
            Thread({
                val file = runCatching { recorder.stop() }.getOrNull()
                if (file != null) {
                    publishRecordingAsync(file, "audio/wav") {
                        runOnUiThread { globalStatus.text = "Nagrania zapisane w DCIM/Rajdex" }
                    }
                } else {
                    runOnUiThread { globalStatus.text = "Blad finalizacji pliku WAV" }
                }
            }, "USB-audio-finalize").start()
        }
        refreshRecordingControls()
        updateGlobalStatus()
    }

    private fun failRecording(message: String) {
        globalStatus.text = message
        if (recordingRequested) stopRecordingAndRestorePreview()
    }

    private fun publishRecordingAsync(file: File, mimeType: String, onPublished: (android.net.Uri) -> Unit) {
        Thread({
            runCatching { RecordingPublisher.publish(contentResolver, file, mimeType) }
                .onSuccess { uri -> runOnUiThread { onPublished(uri) } }
                .onFailure { error ->
                    runOnUiThread {
                        globalStatus.text = "Blad przenoszenia nagrania: ${error.message ?: file.absolutePath}"
                    }
                }
        }, "Recording-publisher").start()
    }

    private fun setSlotStatus(slot: PreviewSlot, message: String) {
        if (::slots.isInitialized) runOnUiThread { slot.status.text = message }
    }

    private fun updateGlobalStatus() {
        if (!::globalStatus.isInitialized) return
        val count = slotByDeviceId.size
        refreshRecordingControls()
        globalStatus.text = when (count) {
            0 -> "Podłącz jedną lub dwie kamery UVC przez USB"
            1 -> "1 kamera UVC połączona · druga może zostać podłączona"
            else -> "Obie kamery UVC są połączone"
        }
    }

    private fun refreshRecordingControls(slot: PreviewSlot? = null) {
        if (!::slots.isInitialized) return
        val targets = slot?.let(::listOf) ?: slots
        targets.forEach { preview ->
            if (recordingRequested) {
                preview.recordingCheckbox.isEnabled = false
                preview.encodeMjpegCheckbox.isEnabled = false
            } else {
                preview.recordingCheckbox.isEnabled = preview.device != null
                preview.encodeMjpegCheckbox.visibility = if (preview.raw) View.VISIBLE else View.GONE
                preview.encodeMjpegCheckbox.isEnabled = preview.raw && preview.device != null
            }
        }
        if (::audioRecordingCheckbox.isInitialized) audioRecordingCheckbox.isEnabled = !recordingRequested
        if (::recordingButton.isInitialized) {
            val hasSelection = slots.any { it.device != null && it.recordingCheckbox.isChecked }
            recordingButton.isEnabled = recordingRequested || hasSelection
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any {
            val intf = device.getInterface(it)
            intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO && intf.interfaceSubclass == 2
        }

    private fun hasBulkEndpoint(device: UsbDevice): Boolean = findBulkInterface(device) != null

    private fun findBulkInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { intf ->
                intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO && intf.interfaceSubclass == 2 &&
                    findBulkInEndpoint(intf) != null
            }

    private fun findBulkInEndpoint(intf: UsbInterface): UsbEndpoint? =
        (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
        }

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
                        val interval = selectFrameInterval(descriptors, offset, length)
                        val mode = MjpegMode(
                            formatIndex, frameIndex, width, height,
                            interval.takeIf { it > 0 } ?: 333333,
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

    private fun logUvcFormats(device: UsbDevice, descriptors: ByteArray) {
        var offset = 0
        val formats = mutableListOf<String>()
        while (offset + 2 <= descriptors.size) {
            val length = descriptors[offset].toInt() and 0xff
            val type = descriptors[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > descriptors.size) break
            if (type == 0x24 && length >= 4) {
                val subtype = descriptors[offset + 2].toInt() and 0xff
                when (subtype) {
                    0x04 -> if (length >= 21) {
                        val index = descriptors[offset + 3].toInt() and 0xff
                        val fourcc = String(descriptors, offset + 5, 4, Charsets.US_ASCII)
                        formats.add("uncompressed[$index]=$fourcc")
                    }
                    0x06 -> if (length >= 5) {
                        formats.add("mjpeg[${descriptors[offset + 3].toInt() and 0xff}]")
                    }
                    0x10 -> if (length >= 21) {
                        val index = descriptors[offset + 3].toInt() and 0xff
                        val fourcc = String(descriptors, offset + 5, 4, Charsets.US_ASCII)
                        formats.add("frame-based[$index]=$fourcc")
                    }
                }
            }
            offset += length
        }
        android.util.Log.i(TAG, "${device.productName}: UVC stream formats=${formats.ifEmpty { listOf("none found") }}")
    }

    private fun selectFrameInterval(data: ByteArray, offset: Int, length: Int): Int {
        val defaultInterval = u32(data, offset + 21)
        val intervalCount = data[offset + 25].toInt() and 0xff
        if (intervalCount > 0) {
            val intervals = (0 until intervalCount).mapNotNull { index ->
                val valueOffset = offset + 26 + index * 4
                if (valueOffset + 4 <= offset + length) u32(data, valueOffset) else null
            }.filter { it > 0 }
            return intervals.filter { it >= 333333 }.minOrNull() ?: intervals.minOrNull() ?: defaultInterval
        }
        return if (defaultInterval > 0) maxOf(defaultInterval, 333333) else 333333
    }

    private fun intervalToFps(interval: Int): Int = if (interval > 0) (10_000_000L / interval).toInt() else 30

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
            else globalStatus.text = "Uprawnienie do audio jest potrzebne do zapisu dzwieku USB"
        } else if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCameraClient()
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            globalStatus.text = "Uprawnienie Kamera jest potrzebne do dostępu UVC"
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(rawPermissionReceiver) }
        runCatching { audioRecorder?.stop() }
        audioRecorder = null
        slots.forEach { slot ->
            slot.recordingWriter?.let { runCatching { it.close() }; slot.recordingWriter = null }
            slot.mp4RecordingWriter?.let { runCatching { it.close() }; slot.mp4RecordingWriter = null }
            slot.libraryCamera?.let { camera ->
                if (camera.isRecording()) camera.captureVideoStop()
            }
            stopRaw(slot)
            slot.rawWorker.shutdownNow()
            stopLibraryCamera(slot)
        }
        cameraClient?.destroy()
        cameraClient = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DualUvc"
        private const val DEFAULT_WIDTH = 640
        private const val DEFAULT_HEIGHT = 480
        private const val REQUEST_CAMERA_PERMISSION = 40
        private const val REQUEST_AUDIO_PERMISSION = 41
    }
}
