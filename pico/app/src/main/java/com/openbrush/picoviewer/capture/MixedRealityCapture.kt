package com.openbrush.picoviewer.capture

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Base64
import com.openbrush.picoviewer.domain.drawing.PaintStroke
import com.openbrush.picoviewer.domain.drawing.StrokeAppearance
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.java_websocket.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class CaptureSettings(
    val movingCamera: Boolean = true, val phonePlatform: String = "both",
    val includePalette: Boolean = true, val poseHz: Int = 30,
    val calibrationSource: String = "right_controller", val markerSizeMm: Int = 80
)
data class CaptureUiState(
    val settings: CaptureSettings, val running: Boolean = false, val listening: Boolean = false,
    val pairingCode: String = "", val serviceName: String = "", val addresses: List<String> = emptyList(),
    val peerName: String? = null, val stageReady: Boolean = false, val originId: String = "",
    val calibration: String? = null, val collecting: Boolean = false,
    val calibrated: Boolean = false, val phoneTracking: Boolean = false,
    val phoneRecording: Boolean = false, val error: String? = null
)

/** Process-scoped publisher. Listening is opt-in and never restored automatically after restart. */
class MixedRealityCapture private constructor(private val context: Context) : CaptureSocketServer.Events {
    private val prefs = context.getSharedPreferences("mixed-reality-capture", Context.MODE_PRIVATE)
    private val initial = CaptureSettings(
        prefs.getBoolean("moving", true), prefs.getString("platform", "both") ?: "both",
        prefs.getBoolean("palette", true), prefs.getInt("hz", 30).takeIf { it in setOf(15, 30, 60) } ?: 30,
        prefs.getString("source", "right_controller") ?: "right_controller", prefs.getInt("markerMm", 80))
    private val mutable = MutableStateFlow(CaptureUiState(initial))
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = Channel<CaptureFrame>(Channel.CONFLATED)
    private data class Request(val server: CaptureSocketServer, val socket: WebSocket, val text: String, val receivedAtNs: Long)
    private val requests = Channel<Request>(64)
    private var assetJob: Job? = null
    @Volatile private var server: CaptureSocketServer? = null
    @Volatile private var peer: WebSocket? = null
    @Volatile private var activeStage: String? = null
    @Volatile private var latest: CaptureFrame? = null
    @Volatile private var alignment: Alignment? = null
    @Volatile private var calibrationRequest: String? = null
    private data class Alignment(val matrix: FloatArray, val phoneWorldId: String, val originId: String)
    private val pending = ConcurrentHashMap<WebSocket, Long>()
    private val attempts = ArrayDeque<Long>()
    private var codeExpiresAt = 0L
    @Volatile private var lastPhonePoseAtNs = 0L
    private var sessionId = ""
    private var registration: NsdManager.RegistrationListener? = null
    private val resync = AtomicLong(0)
    val wantsFrames get() = peer?.isOpen == true
    val intervalMs get() = 1000L / state.value.settings.poseHz

    init {
        scope.launch {
            while(isActive) {
                delay(250)
                val now = SystemClock.elapsedRealtimeNanos()
                if(state.value.stageReady && latest?.headReceivedAtNs?.let { now - it > 500_000_000L } != false) {
                    mutable.update { it.copy(stageReady = false) }; invalidate("头显追踪超时，请重新对齐")
                }
                if(state.value.phoneTracking && now - lastPhonePoseAtNs > 1_500_000_000L) invalidate("手机位姿超时，请重新对齐")
            }
        }
        scope.launch {
            for(request in requests) {
                if(server !== request.server || !request.socket.isOpen) continue
                try { receive(request) }
                catch(e: Exception) { request.socket.close(1008, "Invalid capture control message") }
            }
        }
        scope.launch {
            var streamKey = ""
            var previous = emptyMap<String, Pair<PaintStroke, List<com.openbrush.picoviewer.domain.drawing.ArcSpan>?>>()
            var generation = -1L
            var sequence = 0L
            val resources = mutableSetOf<String>()
            for(frame in frames) try {
                val socket = peer ?: continue
                if(frame.stageId != activeStage || !state.value.stageReady) continue
                val origin = state.value.originId
                val key = "$sessionId:$origin:${resync.get()}"
                val snapshot = key != streamKey
                if(snapshot) { streamKey = key; previous = emptyMap(); generation = -1; sequence = 0; resources.clear() }
                if(snapshot || frame.generation != generation) {
                    // Publish the same color-clipped parts as the native renderer, retaining
                    // original stroke identity as metadata for the future phone companion.
                    val parts = frame.strokes.flatMap { original -> StrokeAppearance.parts(original).map {
                        it.copy(id = StrokeAppearance.partId(it)) to original
                    } }
                    val published = parts.map { it.first }
                    val publishedState = parts.associate { (part, original) ->
                        val surfaceErased = original.erased.takeIf { frame.brushes?.get(part.style.brush)?.isArcSurface == true }
                        part.id to (part to surfaceErased)
                    }
                    val changed = parts.filter { (part, _) -> snapshot || previous[part.id] != publishedState[part.id] }
                    val descriptors = JSONArray()
                    for((stroke,original) in changed) {
                        currentCoroutineContext().ensureActive()
                        if(peer !== socket || activeStage != frame.stageId || state.value.originId != origin) break
                        val (hash, bytes) = CaptureProtocol.resource(stroke,frame.brushes,original)
                        require(bytes.size <= 16 * 1024 * 1024) { "单笔拍摄网格超过16MB" }
                        if(hash !in resources) { sendResource(socket, hash, "application/vnd.openbrush.mesh", bytes); resources.add(hash) }
                        descriptors.put(CaptureProtocol.stroke(stroke, hash).put("originalStrokeId", original.id))
                    }
                    if(peer !== socket || activeStage != frame.stageId || state.value.originId != origin) continue
                    val ids = published.map { it.id }.toSet()
                    send(socket, CaptureProtocol.message(if(snapshot) "SceneSnapshot" else "SceneDelta")
                        .put("originId", origin).put("baseSequence", sequence).put("sequence", ++sequence)
                        .put("documentRevision", frame.revision).put("generation", frame.generation)
                        .put("receivedAtNs", frame.receivedAtNs.toString())
                        .put("upsert", descriptors).put("remove", JSONArray(previous.keys.filter { it !in ids }))
                        .put("order", JSONArray(published.map { it.id }))
                        .put("documentToWorld", CaptureTransform.json(frame.documentToWorld)))
                    previous = publishedState; generation = frame.generation
                }
                val config = state.value.settings
                send(socket, CaptureProtocol.message("Pose")
                    .put("originId", origin).put("receivedAtNs", frame.receivedAtNs.toString())
                    .put("timestampKind", "scene_snapshot_monotonic")
                    .put("trackingTimestampKind", "sdk_callback_received_monotonic_ms")
                    .put("headReceivedAtNs", frame.headReceivedAtNs?.toString() ?: JSONObject.NULL)
                    .put("controllersReceivedAtNs", frame.controllersReceivedAtNs?.toString() ?: JSONObject.NULL)
                    .put("head", frame.head?.json() ?: JSONObject.NULL)
                    .put("leftController", frame.leftController?.json() ?: JSONObject.NULL)
                    .put("rightController", frame.rightController?.json() ?: JSONObject.NULL)
                    .put("documentToWorld", CaptureTransform.json(frame.documentToWorld))
                    .put("paletteToWorld", if(config.includePalette) frame.paletteToWorld?.let(CaptureTransform::json) ?: JSONObject.NULL else JSONObject.NULL)
                    .put("paletteAsset", if(config.includePalette) CaptureProtocol.PALETTE_ASSET else JSONObject.NULL)
                    .put("calibrationRequestId", calibrationRequest ?: JSONObject.NULL))
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(error = "场景发布暂停：${e.message}") }; peer?.close(1011, "Scene publishing failed") }
        }
    }

    @Synchronized fun start() {
        if(server != null) return
        val random = SecureRandom()
        sessionId = UUID.randomUUID().toString()
        val code = (100000 + random.nextInt(900000)).toString()
        codeExpiresAt = SystemClock.elapsedRealtime() + 10 * 60_000L
        synchronized(attempts) { attempts.clear() }; alignment = null; calibrationRequest = null
        mutable.update { it.copy(running = true, listening = false, pairingCode = code,
            serviceName = "OpenBrush-MR-${sessionId.take(4)}", addresses = addresses(), peerName = null,
            calibrated = false, collecting = false, phoneTracking = false, phoneRecording = false, error = null) }
        val service = CaptureSocketServer(PORT, this)
        server = service
        service.start()
    }
    @Synchronized fun stop() {
        val old = server; server = null
        assetJob?.cancel(); assetJob = null
        peer?.close(1000, "Host stopped capture sharing"); peer = null
        pending.keys.forEach { it.close(1001, "Host stopped") }; pending.clear()
        unregister()
        alignment = null; calibrationRequest = null; resync.incrementAndGet()
        mutable.update { it.copy(running = false, listening = false, pairingCode = "", peerName = null,
            collecting = false, calibrated = false, calibration = null, phoneTracking = false, phoneRecording = false) }
        if(old != null) scope.launch(Dispatchers.IO) { runCatching { old.stop(1000) } }
    }
    fun updateSettings(next: CaptureSettings) {
        require(next.phonePlatform in setOf("both", "ios", "android"))
        require(next.poseHz in setOf(15, 30, 60) && next.markerSizeMm in setOf(60, 80, 100))
        require(next.calibrationSource in setOf("right_controller", "left_controller", "head"))
        val previous = state.value.settings
        prefs.edit().putBoolean("moving", next.movingCamera).putString("platform", next.phonePlatform)
            .putBoolean("palette", next.includePalette).putInt("hz", next.poseHz)
            .putString("source", next.calibrationSource).putInt("markerMm", next.markerSizeMm).apply()
        mutable.update { it.copy(settings = next) }; resync.incrementAndGet()
        if(next.movingCamera != previous.movingCamera || next.calibrationSource != previous.calibrationSource || next.markerSizeMm != previous.markerSizeMm) invalidate("拍摄或标定设置已改变")
        control(hostHello())
    }
    fun enterStage(id: String) {
        activeStage = id; latest = null; alignment = null; calibrationRequest = null
        mutable.update { it.copy(originId = UUID.randomUUID().toString(), stageReady = false,
            collecting = false, calibrated = false, calibration = "进入绘画后等待有效追踪", phoneTracking = false, phoneRecording = false) }
        resync.incrementAndGet()
        control(CaptureProtocol.message("CalibrationInvalidated").put("reason", "绘画空间会话已改变").put("originId", state.value.originId))
    }
    fun leaveStage(id: String) {
        if(activeStage != id) return
        activeStage = null; latest = null
        mutable.update { it.copy(stageReady = false) }; invalidate("绘画已暂停，请在恢复后重新对齐")
    }
    fun resetOrigin(id: String, reason: String) {
        if(activeStage != id) return
        mutable.update { it.copy(originId = UUID.randomUUID().toString()) }
        resync.incrementAndGet(); invalidate(reason)
    }
    fun offer(frame: CaptureFrame) {
        if(activeStage != frame.stageId) return
        latest = frame
        val ready = frame.head != null
        if(state.value.stageReady != ready) {
            mutable.update { it.copy(stageReady = ready) }
            if(!ready) invalidate("头显追踪中断，请重新对齐")
        }
        if(wantsFrames && ready) frames.trySend(frame)
    }
    fun beginCalibration() {
        val frame = latest
        if(peer == null || !state.value.stageReady || frame == null) { mutable.update { it.copy(error = "请先连接手机并进入绘画工作室") }; return }
        val source = state.value.settings.calibrationSource
        val pose = when(source) { "head" -> frame.head; "left_controller" -> frame.leftController; else -> frame.rightController }
        val sampledAt = if(source == "head") frame.headReceivedAtNs else frame.controllersReceivedAtNs
        if(pose == null || sampledAt == null || SystemClock.elapsedRealtimeNanos() - sampledAt > 250_000_000L) {
            mutable.update { it.copy(error = "标定设备追踪不可用；使用手柄时请先切换手柄模式") }; return
        }
        invalidate("等待手机采集标记板并返回标定结果")
        val requestId = UUID.randomUUID().toString(); calibrationRequest = requestId
        mutable.update { it.copy(collecting = true, error = null) }
        control(CaptureProtocol.message("BeginCalibration").put("requestId", requestId)
            .put("originId", state.value.originId).put("source", source)
            .put("markerFamily", "aruco_4x4_50").put("markerId", 0)
            .put("markerSizeMeters", state.value.settings.markerSizeMm / 1000.0)
            .put("solveRigidMarkerOffset", true))
    }
    fun invalidate(reason: String = "标定已清除") {
        alignment = null; calibrationRequest = null
        mutable.update { it.copy(calibrated = false, collecting = false, calibration = reason, phoneTracking = false, phoneRecording = false) }
        control(CaptureProtocol.message("CalibrationInvalidated").put("reason", reason).put("originId", state.value.originId))
    }
    fun requestRecording(start: Boolean) {
        if(start && (!state.value.calibrated || !state.value.phoneTracking || !state.value.stageReady)) {
            mutable.update { it.copy(error = "需要手机完成标定并保持有效跟踪") }; return
        }
        control(CaptureProtocol.message("CaptureRequest").put("recording", start).put("originId", state.value.originId))
    }

    override fun opened(server: CaptureSocketServer, socket: WebSocket) {
        if(this.server !== server || pending.size >= 4) { socket.close(1013, "Not accepting clients"); return }
        pending[socket] = SystemClock.elapsedRealtime()
        socket.send(CaptureProtocol.message("PairingRequired").put("sessionId", sessionId).toString())
        scope.launch { delay(5000); if(pending.remove(socket) != null) socket.close(1008, "Pairing timeout") }
    }
    override fun message(server: CaptureSocketServer, socket: WebSocket, text: String) {
        if(!requests.trySend(Request(server, socket, text, SystemClock.elapsedRealtimeNanos())).isSuccess) socket.close(1013, "Control queue full")
    }
    override fun closed(server: CaptureSocketServer, socket: WebSocket) {
        pending.remove(socket)
        if(this.server !== server || peer !== socket) return
        assetJob?.cancel(); assetJob = null
        peer = null; alignment = null; calibrationRequest = null
        mutable.update { it.copy(peerName = null, calibrated = false, collecting = false,
            calibration = "手机已断开，需要重新对齐", phoneTracking = false, phoneRecording = false) }
    }
    override fun ready(server: CaptureSocketServer) {
        if(this.server !== server) return
        mutable.update { it.copy(listening = true) }; register()
    }
    override fun failed(server: CaptureSocketServer, socket: WebSocket?, error: Exception) {
        if(this.server !== server) return
        mutable.update { it.copy(error = "混合现实连接错误：${error.message}") }
        if(socket == null) stop() else socket.close(1011, "Connection failed")
    }

    private suspend fun receive(request: Request) {
        val socket = request.socket; val json = JSONObject(request.text)
        require(json.getInt("protocolVersion") == CaptureProtocol.VERSION)
        val type = json.getString("type")
        if(peer !== socket) {
            require(type == "Hello" && pending.containsKey(socket))
            val now = SystemClock.elapsedRealtime()
            synchronized(attempts) {
                while(attempts.isNotEmpty() && now - attempts.first() > 60_000) attempts.removeFirst()
                require(attempts.size < 5 && now <= codeExpiresAt)
                attempts.addLast(now)
            }
            val code = json.getString("pairingCode"); require(code.length == 6)
            require(MessageDigest.isEqual(code.toByteArray(), state.value.pairingCode.toByteArray()))
            val platform = json.getString("platform")
            require(platform in setOf("ios", "android"))
            require(state.value.settings.phonePlatform == "both" || state.value.settings.phonePlatform == platform)
            synchronized(this) {
                require(this.server === request.server && peer == null && socket.isOpen)
                peer = socket
            }
            pending.remove(socket); resync.incrementAndGet()
            mutable.update { it.copy(peerName = json.optString("deviceName", "手机").take(64), error = null) }
            send(socket, hostHello()); return
        }
        when(type) {
            "ClockSync" -> send(socket, CaptureProtocol.message("ClockSyncReply")
                .put("clientSentNs", json.getString("clientSentNs")).put("hostReceivedNs", request.receivedAtNs.toString())
                .put("hostSentNs", SystemClock.elapsedRealtimeNanos().toString()))
            "SnapshotRequest" -> resync.incrementAndGet()
            "AssetRequest" -> {
                require(json.getString("asset") == CaptureProtocol.PALETTE_ASSET)
                require(assetJob?.isActive != true)
                assetJob = scope.launch {
                    try {
                        val bytes = context.assets.open(CaptureProtocol.PALETTE_ASSET).use { it.readBytes() }
                        sendResource(socket, CaptureProtocol.hash(bytes), "model/gltf-binary", bytes, CaptureProtocol.PALETTE_ASSET)
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { if(peer === socket) socket.close(1011, "Asset transfer failed") }
                }
            }
            "CalibrationResult" -> {
                require(state.value.collecting && json.getString("requestId") == calibrationRequest)
                require(json.getString("originId") == state.value.originId && state.value.stageReady)
                require(json.getInt("sampleCount") >= 12 && json.getDouble("reprojectionErrorPx").let { it.isFinite() && it in 0.0..3.0 })
                val world = json.getString("phoneWorldId"); require(world.isNotBlank() && world.length <= 128)
                alignment = Alignment(CaptureTransform.read(json.getJSONArray("phoneWorldToPicoWorld")), world, state.value.originId)
                calibrationRequest = null
                mutable.update { it.copy(calibrated = true, collecting = false, calibration = "手机已提交标定结果，等待跟踪", error = null) }
                send(socket, CaptureProtocol.message("CalibrationAccepted").put("originId", state.value.originId))
            }
            "PhonePose" -> {
                val transform = alignment ?: return
                require(json.getString("originId") == transform.originId && json.getString("phoneWorldId") == transform.phoneWorldId)
                val tracking = json.getString("trackingState") == "TRACKING"
                lastPhonePoseAtNs = request.receivedAtNs
                if(state.value.phoneTracking != tracking) mutable.update { it.copy(phoneTracking = tracking) }
                if(!tracking) { invalidate("手机跟踪丢失，需要重新对齐"); return }
                val camera = CaptureTransform.multiply(transform.matrix, CaptureTransform.read(json.getJSONArray("cameraToPhoneWorld")))
                val frame = latest ?: return
                val head = frame.head ?: return
                if(SystemClock.elapsedRealtimeNanos() - frame.receivedAtNs > 250_000_000L) return
                send(socket, CaptureProtocol.message("RelativePose").put("originId", transform.originId)
                    .put("clientFrameNs", json.getString("frameTimestampNs"))
                    .put("hostPoseReceivedNs", frame.headReceivedAtNs?.toString() ?: frame.receivedAtNs.toString())
                    .put("cameraToPicoWorld", CaptureTransform.json(camera))
                    .put("headToCamera", CaptureTransform.json(CaptureTransform.multiply(CaptureTransform.inverse(camera), head.matrix())))
                    .put("cameraToHead", CaptureTransform.json(CaptureTransform.multiply(CaptureTransform.inverse(head.matrix()), camera))))
            }
            "AlignmentUpdate" -> {
                val old = alignment ?: return
                require(state.value.stageReady && json.getString("originId") == old.originId)
                require(json.getString("phoneWorldId") == old.phoneWorldId)
                require(json.getDouble("reprojectionErrorPx").let { it.isFinite() && it in 0.0..3.0 })
                val next = CaptureTransform.read(json.getJSONArray("phoneWorldToPicoWorld"))
                val delta = CaptureTransform.multiply(CaptureTransform.inverse(old.matrix), next)
                val distance = kotlin.math.sqrt(delta[12]*delta[12] + delta[13]*delta[13] + delta[14]*delta[14])
                val angle = kotlin.math.acos(((delta[0]+delta[5]+delta[10]-1f)*.5f).coerceIn(-1f,1f))
                if(distance > .05f || angle > Math.toRadians(5.0)) {
                    invalidate("参考标记偏差过大，请重新标定"); return
                }
                // The phone submits gradual corrections; a large unannounced jump is rejected.
                alignment = old.copy(matrix = next)
                send(socket, CaptureProtocol.message("AlignmentAccepted").put("originId", old.originId))
            }
            "CaptureState" -> {
                val recording = json.getBoolean("recording")
                require(!recording || (state.value.calibrated && state.value.phoneTracking && state.value.stageReady))
                require(!recording || json.getString("originId") == state.value.originId)
                mutable.update { it.copy(phoneRecording = recording) }
            }
            "CalibrationInvalidated" -> invalidate("手机请求重新对齐")
            else -> error("Unknown message")
        }
    }

    private fun hostHello(): JSONObject {
        val config = state.value.settings
        return CaptureProtocol.message("HostHello").put("sessionId", sessionId).put("originId", state.value.originId)
            .put("targetDevice", "PICO Space Pro").put("deviceModel", android.os.Build.MODEL)
            .put("units", "meters").put("handedness", "right")
            .put("up", "+Y").put("cameraForward", "-Z").put("matrixLayout", "column-major")
            .put("quaternionLayout", "xyzw").put("mobileCompanionStatus", "TODO_NOT_IMPLEMENTED")
            .put("sceneScope", "drawing-stage-only").put("movingCamera", config.movingCamera)
            .put("phonePlatform", config.phonePlatform).put("poseHz", config.poseHz)
            .put("paletteAsset", if(config.includePalette) CaptureProtocol.PALETTE_ASSET else JSONObject.NULL)
    }
    private fun control(message: JSONObject) {
        val socket = peer ?: return
        if(socket.isOpen) runCatching { socket.send(message.toString()) }
    }
    private suspend fun send(socket: WebSocket, message: JSONObject) {
        val deadline = SystemClock.elapsedRealtime() + 3000
        while(socket.hasBufferedData()) {
            check(peer === socket && socket.isOpen && SystemClock.elapsedRealtime() < deadline) { "手机接收过慢或已断开" }
            delay(5)
        }
        check(peer === socket && socket.isOpen)
        socket.send(message.toString())
    }
    private suspend fun sendResource(socket: WebSocket, hash: String, mime: String, bytes: ByteArray, asset: String? = null) {
        for(offset in bytes.indices step CaptureProtocol.CHUNK_BYTES) {
            val size = minOf(CaptureProtocol.CHUNK_BYTES, bytes.size - offset)
            send(socket, CaptureProtocol.message("ResourceChunk").put("hash", hash).put("mime", mime)
                .put("asset", asset ?: JSONObject.NULL).put("offset", offset).put("totalBytes", bytes.size)
                .put("data", Base64.encodeToString(bytes, offset, size, Base64.NO_WRAP)))
        }
    }
    private fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }.map { "ws://${it.hostAddress}:$PORT/capture" }.distinct()
    }.getOrDefault(emptyList())
    @Synchronized private fun register() {
        if(server == null || registration != null) return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { mutable.update { it.copy(serviceName = info.serviceName) } }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { mutable.update { it.copy(error = "自动发现不可用，可使用连接地址；错误码 $code") } }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        runCatching {
            context.getSystemService(NsdManager::class.java).registerService(NsdServiceInfo().apply {
                serviceName = state.value.serviceName; serviceType = CaptureProtocol.SERVICE_TYPE; port = PORT
                setAttribute("protocol", CaptureProtocol.VERSION.toString()); setAttribute("app", "openbrush-native")
            }, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { mutable.update { it.copy(error = "自动发现不可用，请使用连接地址") } }
    }
    @Synchronized private fun unregister() {
        registration?.let { listener -> runCatching { context.getSystemService(NsdManager::class.java).unregisterService(listener) } }
        registration = null
    }
    companion object {
        const val PORT = 8765
        @Volatile private var instance: MixedRealityCapture? = null
        fun get(context: Context): MixedRealityCapture = instance ?: synchronized(this) {
            instance ?: MixedRealityCapture(context.applicationContext).also { instance = it }
        }
    }
}
