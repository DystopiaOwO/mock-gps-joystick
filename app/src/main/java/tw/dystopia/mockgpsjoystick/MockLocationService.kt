package tw.dystopia.mockgpsjoystick

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class MockLocationService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var windowManager: WindowManager
    private var providerAdded = false
    private var overlayAdded = false
    private var overlayView: JoystickOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var savedPanelAdded = false
    private var savedPanelView: View? = null
    private var mapPanelAdded = false
    private var mapPanelView: FloatingMapPanel? = null
    private var shuttingDown = false
    private var lastTickNanos = 0L
    private var lastNotificationMillis = 0L

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (shuttingDown || !RuntimeState.snapshot().serviceRunning) return

            if (!advanceAndPushLocation()) {
                shutdown(stopSelfAfter = true)
                return
            }

            val nowMillis = System.currentTimeMillis()
            if (nowMillis - lastNotificationMillis >= 1_000L) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
                lastNotificationMillis = nowMillis
            }
            mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
        if (BuildConfig.DEBUG) MovementMath.debugSelfCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFromVisibleActivity(intent)
            ACTION_STOP -> shutdown(stopSelfAfter = true)
            else -> shutdown(stopSelfAfter = true)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown(stopSelfAfter = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runOnMain { clampAndUpdateOverlay() }
    }

    override fun onDestroy() {
        shutdown(stopSelfAfter = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startFromVisibleActivity(intent: Intent) {
        if (shuttingDown || RuntimeState.snapshot().serviceRunning) return
        if (!RuntimeState.activityVisible) {
            RuntimeState.fail("只能從目前可見的主畫面啟動。")
            stopSelf()
            return
        }

        val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
        val speed = intent.getDoubleExtra(EXTRA_SPEED_KMH, Double.NaN)
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            ALLOWED_SPEEDS.none { it == speed }
        ) {
            RuntimeState.fail("啟動參數無效。")
            stopSelf()
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            !Settings.canDrawOverlays(this)
        ) {
            RuntimeState.fail("服務啟動失敗，請確認定位與懸浮窗權限。")
            stopSelf()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (error: RuntimeException) {
            RuntimeState.fail("Foreground Service 啟動失敗：${error.message ?: error.javaClass.simpleName}")
            shutdown(stopSelfAfter = true)
            return
        }

        if (!addTestProvider() || !enableTestProvider()) {
            shutdown(stopSelfAfter = true)
            return
        }
        val initialResult = MovementResult(Coordinate(latitude, longitude), 0f, 0f)
        if (!setTestProviderLocation(initialResult) || !addOverlay()) {
            shutdown(stopSelfAfter = true)
            return
        }

        RuntimeState.markRunning(overlayVisible = true)
        lastTickNanos = SystemClock.elapsedRealtimeNanos()
        lastNotificationMillis = 0L
        mainHandler.removeCallbacks(updateRunnable)
        mainHandler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS)
    }

    private fun advanceAndPushLocation(): Boolean {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        // ponytail: cap scheduler stalls; route playback can replace this if exact catch-up is ever needed.
        val deltaSeconds = ((nowNanos - lastTickNanos) / 1_000_000_000.0).coerceIn(0.0, 1.0)
        lastTickNanos = nowNanos
        val snapshot = RuntimeState.snapshot()
        return setTestProviderLocation(
            MovementMath.next(
                current = snapshot.currentCoordinate,
                screenAngleDegrees = snapshot.joystickAngle,
                strength = snapshot.joystickStrength,
                speedKmh = snapshot.speedKmh,
                deltaSeconds = deltaSeconds,
            ),
        )
    }

    private fun handleJoystick(angle: Double, strength: Double) {
        val previousStrength = RuntimeState.snapshot().joystickStrength
        if (strength == 0.0 && previousStrength > 0.0 && !advanceAndPushLocation()) {
            shutdown(stopSelfAfter = true)
            return
        }
        RuntimeState.setJoystick(angle, strength)
    }

    private fun addTestProvider(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val properties = ProviderProperties.Builder()
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .build()
                locationManager.addTestProvider(LocationManager.GPS_PROVIDER, properties)
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false,
                    true,
                    false,
                    false,
                    true,
                    true,
                    true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE,
                )
            }
            providerAdded = true
            true
        } catch (_: SecurityException) {
            RuntimeState.fail(MOCK_PERMISSION_ERROR)
            false
        } catch (error: RuntimeException) {
            RuntimeState.fail("Mock provider 建立失敗：${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun enableTestProvider(): Boolean {
        return try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            true
        } catch (_: SecurityException) {
            RuntimeState.fail(MOCK_PERMISSION_ERROR)
            false
        } catch (error: RuntimeException) {
            RuntimeState.fail("Mock provider 啟用失敗：${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    private fun setTestProviderLocation(result: MovementResult): Boolean {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = result.coordinate.latitude
            longitude = result.coordinate.longitude
            accuracy = ACCURACY_METERS
            altitude = 0.0
            speed = result.actualSpeedMps
            bearing = result.bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        return try {
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
            RuntimeState.updateLocation(result, location.time)
            true
        } catch (_: SecurityException) {
            RuntimeState.fail(MOCK_PERMISSION_ERROR)
            false
        } catch (error: RuntimeException) {
            RuntimeState.fail("Mock 位置推送失敗：${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun disableTestProvider() {
        if (!providerAdded) return
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        } catch (error: RuntimeException) {
            RuntimeState.recordErrorIfEmpty("Mock provider 停用失敗：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun removeTestProvider() {
        if (!providerAdded) return
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (error: RuntimeException) {
            RuntimeState.recordErrorIfEmpty("Mock provider 移除失敗：${error.message ?: error.javaClass.simpleName}")
        } finally {
            providerAdded = false
        }
    }

    private fun addOverlay(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            RuntimeState.fail("Overlay 建立失敗：操作不在 main thread。")
            return false
        }
        if (overlayAdded || overlayView?.isAttachedToWindow == true) return true
        if (!Settings.canDrawOverlays(this)) {
            RuntimeState.fail("懸浮搖桿建立失敗，請確認懸浮窗權限是否已開啟。")
            return false
        }

        val size = dp(EXPANDED_SIZE_DP)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = (screenHeight() - size - dp(64)).coerceAtLeast(0)
        }
        val view = JoystickOverlayView(
            context = this,
            initialSpeedKmh = RuntimeState.snapshot().speedKmh,
            onJoystick = ::handleJoystick,
            onDrag = ::moveOverlay,
            onSpeedChange = ::cycleSpeed,
            onMap = ::showFloatingMap,
            onSavedLocations = ::showSavedLocations,
            onCollapsedChanged = ::setOverlayCollapsed,
        )
        return try {
            windowManager.addView(view, params)
            overlayView = view
            overlayParams = params
            overlayAdded = true
            true
        } catch (error: RuntimeException) {
            RuntimeState.fail("懸浮搖桿建立失敗：${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    private fun moveOverlay(deltaX: Int, deltaY: Int) = runOnMain {
        val params = overlayParams ?: return@runOnMain
        params.x += deltaX
        params.y += deltaY
        clampAndUpdateOverlay()
    }

    private fun setOverlayCollapsed(collapsed: Boolean) = runOnMain {
        val params = overlayParams ?: return@runOnMain
        val size = dp(if (collapsed) COLLAPSED_SIZE_DP else EXPANDED_SIZE_DP)
        params.width = size
        params.height = size
        RuntimeState.setJoystick(-90.0, 0.0)
        clampAndUpdateOverlay()
    }

    private fun clampAndUpdateOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper() || !overlayAdded) return
        val view = overlayView ?: return
        val params = overlayParams ?: return
        params.x = params.x.coerceIn(0, (screenWidth() - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight() - params.height).coerceAtLeast(0))
        try {
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params)
        } catch (error: RuntimeException) {
            RuntimeState.recordErrorIfEmpty("懸浮搖桿更新失敗：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun removeOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::removeOverlay)
            return
        }
        val view = overlayView
        try {
            if (overlayAdded && view?.isAttachedToWindow == true) {
                windowManager.removeView(view)
            }
        } catch (error: RuntimeException) {
            RuntimeState.recordErrorIfEmpty("懸浮搖桿移除失敗：${error.message ?: error.javaClass.simpleName}")
        } finally {
            overlayAdded = false
            overlayView = null
            overlayParams = null
        }
    }

    private fun cycleSpeed(): Double {
        val speeds = ALLOWED_SPEEDS
        val current = RuntimeState.snapshot().speedKmh
        val index = speeds.indexOfFirst { it == current }
        val next = speeds[(index + 1).mod(speeds.size)]
        RuntimeState.setSpeed(next)
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
            .putFloat(PREFERENCE_SPEED, next.toFloat())
            .apply()
        return next
    }

    private fun showSavedLocations() = runOnMain {
        if (savedPanelAdded || shuttingDown) return@runOnMain
        val view = SavedLocationsPanel(
            context = this,
            currentCoordinate = RuntimeState.snapshot().currentCoordinate,
            savedPlaces = loadSavedPlaces(),
            onClose = ::removeSavedLocations,
            onSave = { place ->
                savePlace(place)
                refreshSavedLocations()
            },
            onUse = { place ->
                RuntimeState.jumpTo(place.coordinate)
                removeSavedLocations()
            },
            onDelete = { place ->
                deletePlace(place.name)
                refreshSavedLocations()
            },
        )
        val params = WindowManager.LayoutParams(
            dp(320),
            dp(440),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            windowManager.addView(view, params)
            savedPanelView = view
            savedPanelAdded = true
        } catch (error: RuntimeException) {
            RuntimeState.recordErrorIfEmpty("座標面板建立失敗：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun showFloatingMap() = runOnMain {
        if (mapPanelAdded || shuttingDown) return@runOnMain
        removeSavedLocations()
        val view = FloatingMapPanel(
            context = this,
            initialCoordinate = RuntimeState.snapshot().currentCoordinate,
            onClose = ::removeFloatingMap,
            onMove = { coordinate ->
                if (RuntimeState.jumpTo(coordinate) && advanceAndPushLocation()) {
                    removeFloatingMap()
                } else {
                    shutdown(stopSelfAfter = true)
                }
            },
        )
        val params = WindowManager.LayoutParams(
            (screenWidth() - dp(24)).coerceAtLeast(dp(280)),
            dp(560).coerceAtMost((screenHeight() - dp(48)).coerceAtLeast(dp(360))),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            windowManager.addView(view, params)
            mapPanelView = view
            mapPanelAdded = true
            overlayView?.visibility = View.GONE
        } catch (error: RuntimeException) {
            view.destroy()
            RuntimeState.recordErrorIfEmpty("懸浮地圖建立失敗：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun removeFloatingMap() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::removeFloatingMap)
            return
        }
        val view = mapPanelView
        try {
            if (mapPanelAdded && view?.isAttachedToWindow == true) {
                windowManager.removeView(view)
            }
        } catch (error: RuntimeException) {
            RuntimeState.recordErrorIfEmpty("懸浮地圖移除失敗：${error.message ?: error.javaClass.simpleName}")
        } finally {
            view?.destroy()
            mapPanelAdded = false
            mapPanelView = null
            if (overlayAdded) overlayView?.visibility = View.VISIBLE
        }
    }

    private fun refreshSavedLocations() {
        removeSavedLocations()
        mainHandler.post(::showSavedLocations)
    }

    private fun removeSavedLocations() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::removeSavedLocations)
            return
        }
        val view = savedPanelView
        try {
            if (savedPanelAdded && view?.isAttachedToWindow == true) {
                windowManager.removeView(view)
            }
        } catch (error: RuntimeException) {
            RuntimeState.recordErrorIfEmpty("座標面板移除失敗：${error.message ?: error.javaClass.simpleName}")
        } finally {
            savedPanelAdded = false
            savedPanelView = null
        }
    }

    private fun loadSavedPlaces(): List<SavedPlace> {
        val raw = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(PREFERENCE_SAVED_PLACES, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    val latitude = item.optDouble("latitude", Double.NaN)
                    val longitude = item.optDouble("longitude", Double.NaN)
                    if (name.isNotEmpty() && latitude.isFinite() && latitude in -90.0..90.0 &&
                        longitude.isFinite() && longitude in -180.0..180.0
                    ) {
                        add(SavedPlace(name, Coordinate(latitude, longitude)))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePlace(place: SavedPlace) {
        // ponytail: 100 local places is enough for MVP; move to a database only if this ceiling becomes real.
        val updated = (loadSavedPlaces().filterNot { it.name == place.name } + place).takeLast(100)
        val array = JSONArray()
        updated.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("latitude", it.coordinate.latitude)
                    .put("longitude", it.coordinate.longitude),
            )
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
            .putString(PREFERENCE_SAVED_PLACES, array.toString())
            .apply()
    }

    private fun deletePlace(name: String) {
        val array = JSONArray()
        loadSavedPlaces().filterNot { it.name == name }.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("latitude", it.coordinate.latitude)
                    .put("longitude", it.coordinate.longitude),
            )
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
            .putString(PREFERENCE_SAVED_PLACES, array.toString())
            .apply()
    }

    private fun shutdown(stopSelfAfter: Boolean) {
        if (shuttingDown) {
            if (stopSelfAfter) stopSelf()
            return
        }
        shuttingDown = true
        mainHandler.removeCallbacks(updateRunnable)
        RuntimeState.setJoystick(-90.0, 0.0)
        disableTestProvider()
        removeTestProvider()
        removeFloatingMap()
        removeSavedLocations()
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        RuntimeState.markStopped(preserveError = true)
        if (stopSelfAfter) stopSelf()
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snapshot = RuntimeState.snapshot()
        val coordinate = snapshot.currentCoordinate
        val content = String.format(
            Locale.US,
            "%.6f, %.6f · %.1f km/h",
            coordinate.latitude,
            coordinate.longitude,
            snapshot.actualSpeedMps * 3.6,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(getString(R.string.notification_running))
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_location,
                    getString(R.string.notification_stop),
                    stopPendingIntent,
                ).build(),
            )
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "顯示 Mock GPS 目前座標與停止操作。"
                setShowBadge(false)
            },
        )
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            resources.displayMetrics.widthPixels
        }

    @Suppress("DEPRECATION")
    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            resources.displayMetrics.heightPixels
        }

    companion object {
        const val ACTION_START = "tw.dystopia.mockgpsjoystick.action.START"
        const val ACTION_STOP = "tw.dystopia.mockgpsjoystick.action.STOP"
        const val EXTRA_LATITUDE = "start_latitude"
        const val EXTRA_LONGITUDE = "start_longitude"
        const val EXTRA_SPEED_KMH = "speed_kmh"

        private const val NOTIFICATION_CHANNEL_ID = "mock_gps_status"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL_MS = 500L
        private const val ACCURACY_METERS = 1f
        private const val EXPANDED_SIZE_DP = 144
        private const val COLLAPSED_SIZE_DP = 40
        private const val PREFERENCES_NAME = "mock_gps_settings"
        private const val PREFERENCE_SPEED = "speed"
        private const val PREFERENCE_SAVED_PLACES = "saved_places"
        private val ALLOWED_SPEEDS = doubleArrayOf(1.0, 3.0, 5.0, 10.0, 15.0, 20.0)
        private const val MOCK_PERMISSION_ERROR =
            "Mock Location 啟動失敗。請到「開發人員選項」→「選取模擬位置應用程式」→ 選擇本 App。"
    }
}

private data class SavedPlace(
    val name: String,
    val coordinate: Coordinate,
)

@android.annotation.SuppressLint("ViewConstructor")
private class SavedLocationsPanel(
    context: android.content.Context,
    private val currentCoordinate: Coordinate,
    savedPlaces: List<SavedPlace>,
    private val onClose: () -> Unit,
    private val onSave: (SavedPlace) -> Unit,
    onUse: (SavedPlace) -> Unit,
    onDelete: (SavedPlace) -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val nameInput = EditText(context)
    private val coordinateInput = EditText(context)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = panelBackground()

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "已儲存座標"
            setTextColor(Color.rgb(20, 22, 26))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(panelButton("關閉", onClose))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(nameInput.apply {
            hint = "名稱"
            setTextColor(Color.rgb(20, 22, 26))
            setHintTextColor(Color.rgb(105, 108, 115))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            background = inputBackground()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }, spacedParams())

        addView(coordinateInput.apply {
            hint = "緯度, 經度"
            setText(
                String.format(
                    Locale.US,
                    "%.6f, %.6f",
                    currentCoordinate.latitude,
                    currentCoordinate.longitude,
                ),
            )
            setTextColor(Color.rgb(20, 22, 26))
            setHintTextColor(Color.rgb(105, 108, 115))
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSelectAllOnFocus(true)
            background = inputBackground()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }, spacedParams())

        addView(
            panelButton("移動到輸入座標") {
                parseCoordinate()?.let { coordinate -> onUse(SavedPlace("", coordinate)) }
            },
            spacedParams(),
        )

        val saveRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        saveRow.addView(
            panelButton("儲存輸入座標") {
                validName()?.let { name ->
                    parseCoordinate()?.let { coordinate -> onSave(SavedPlace(name, coordinate)) }
                }
            },
            LayoutParams(0, dp(44), 1f),
        )
        saveRow.addView(
            panelButton("儲存目前座標") {
                validName()?.let { name -> onSave(SavedPlace(name, currentCoordinate)) }
            },
            LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) },
        )
        addView(saveRow, spacedParams())

        addView(TextView(context).apply {
            text = "座標清單"
            setTextColor(Color.rgb(20, 22, 26))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }, spacedParams(top = 12))

        val list = LinearLayout(context).apply { orientation = VERTICAL }
        if (savedPlaces.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "尚無儲存座標"
                setTextColor(Color.rgb(105, 108, 115))
                setPadding(0, dp(12), 0, dp(12))
            })
        } else {
            savedPlaces.forEach { place ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                row.addView(
                    TextView(context).apply {
                        text = "${place.name}\n${formatCoordinate(place.coordinate)}"
                        setTextColor(Color.rgb(20, 22, 26))
                        textSize = 13f
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), 0, dp(10), 0)
                        background = inputBackground()
                    },
                    LayoutParams(0, dp(58), 1f),
                )
                row.addView(
                    panelButton("移動") { onUse(place) },
                    LayoutParams(dp(64), dp(58)).apply { marginStart = dp(8) },
                )
                row.addView(
                    panelButton("刪除") { onDelete(place) },
                    LayoutParams(dp(64), dp(58)).apply { marginStart = dp(8) },
                )
                list.addView(
                    row,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    },
                )
            }
        }
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            event.action == android.view.KeyEvent.ACTION_UP
        ) {
            onClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun validName(): String? {
        val name = nameInput.text.toString().trim()
        return when {
            name.isEmpty() -> {
                nameInput.error = "請輸入名稱。"
                null
            }
            name.length > 40 -> {
                nameInput.error = "名稱最多 40 個字。"
                null
            }
            else -> name
        }
    }

    private fun parseCoordinate(): Coordinate? {
        val values = coordinateInput.text.toString()
            .trim()
            .split(Regex("[,，\\s]+"))
            .filter(String::isNotEmpty)
        val latitude = values.getOrNull(0)?.toDoubleOrNull()
        val longitude = values.getOrNull(1)?.toDoubleOrNull()
        if (values.size != 2 || latitude == null || longitude == null ||
            !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) {
            coordinateInput.error = "格式應為：25.033000, 121.565400"
            return null
        }
        return Coordinate(latitude, longitude)
    }

    private fun panelButton(label: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTextColor(Color.rgb(20, 22, 26))
            textSize = 13f
            isAllCaps = false
            background = buttonBackground()
            setOnClickListener { action() }
        }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(Color.rgb(247, 247, 248))
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), Color.rgb(180, 183, 190))
    }

    private fun inputBackground() = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(5).toFloat()
        setStroke(dp(1), Color.rgb(180, 183, 190))
    }

    private fun buttonBackground() = GradientDrawable().apply {
        setColor(Color.rgb(233, 235, 240))
        cornerRadius = dp(5).toFloat()
        setStroke(dp(1), Color.rgb(180, 183, 190))
    }

    private fun spacedParams(top: Int = 8) =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun formatCoordinate(coordinate: Coordinate): String =
        String.format(Locale.US, "%.6f, %.6f", coordinate.latitude, coordinate.longitude)
}

@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
private class FloatingMapPanel(
    context: android.content.Context,
    initialCoordinate: Coordinate,
    private val onClose: () -> Unit,
    private val onMove: (Coordinate) -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val coordinateText = TextView(context)
    private val webView = WebView(context)
    private var selected = initialCoordinate
    private var pageReady = false

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = panelBackground()
        isFocusableInTouchMode = true
        requestFocus()

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "地圖傳送"
            setTextColor(Color.rgb(20, 22, 26))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(panelButton("關閉", onClose))
        header.addView(
            panelButton("傳送") { onMove(selected) },
            LayoutParams(dp(72), dp(48)).apply { marginStart = dp(6) },
        )
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(coordinateText.apply {
            setTextColor(Color.rgb(0, 47, 167))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        updateCoordinateText()

        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.setBackgroundColor(Color.rgb(247, 247, 248))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString MockGpsJoystick/0.2.0 (family-use; tw.dystopia.mockgpsjoystick)"
            safeBrowsingEnabled = false
        }
        webView.addJavascriptInterface(MapBridge(), "AndroidMap")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (uri.scheme == "https" && uri.host == LOCAL_ASSET_HOST &&
                    uri.path?.startsWith("/map/") == true
                ) {
                    return localAssetResponse(uri)
                }
                if (request.method == "GET" && isOpenStreetMapTile(uri)) {
                    return super.shouldInterceptRequest(view, request)
                }
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    ByteArrayInputStream(ByteArray(0)),
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return true
                return uri.scheme != "https" || uri.host != LOCAL_ASSET_HOST
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == MAP_URL) {
                    pageReady = true
                    webView.evaluateJavascript(
                        "window.setInitialLocation(${selected.latitude}, ${selected.longitude});",
                        null,
                    )
                }
            }
        }
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        webView.loadUrl(MAP_URL)
    }

    fun destroy() {
        webView.removeJavascriptInterface("AndroidMap")
        webView.stopLoading()
        webView.destroy()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            event.action == android.view.KeyEvent.ACTION_UP
        ) {
            onClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun localAssetResponse(uri: Uri): WebResourceResponse? {
        val assetPath = uri.path?.removePrefix("/") ?: return null
        val mimeType = when {
            assetPath.endsWith(".html") -> "text/html"
            assetPath.endsWith(".css") -> "text/css"
            assetPath.endsWith(".js") -> "application/javascript"
            else -> return null
        }
        return try {
            WebResourceResponse(mimeType, "UTF-8", context.assets.open(assetPath))
        } catch (_: IOException) {
            null
        }
    }

    private fun isOpenStreetMapTile(uri: Uri): Boolean =
        uri.scheme == "https" &&
            uri.host == "tile.openstreetmap.org" &&
            OSM_TILE_PATH.matches(uri.path.orEmpty())

    private fun updateCoordinateText() {
        coordinateText.text = String.format(
            Locale.US,
            "%.6f, %.6f",
            selected.latitude,
            selected.longitude,
        )
    }

    private inner class MapBridge {
        @JavascriptInterface
        fun onCoordinateSelected(latitude: Double, longitude: Double) {
            if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0
            ) {
                return
            }
            webView.post {
                selected = Coordinate(latitude, longitude)
                updateCoordinateText()
            }
        }

        @JavascriptInterface
        fun onTileError() {
            webView.post { coordinateText.text = "圖磚載入失敗；請確認網路連線。" }
        }
    }

    private fun panelButton(label: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTextColor(Color.rgb(20, 22, 26))
            textSize = 13f
            isAllCaps = false
            background = buttonBackground()
            setOnClickListener { action() }
        }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(Color.rgb(247, 247, 248))
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), Color.rgb(180, 183, 190))
    }

    private fun buttonBackground() = GradientDrawable().apply {
        setColor(Color.rgb(233, 235, 240))
        cornerRadius = dp(5).toFloat()
        setStroke(dp(1), Color.rgb(180, 183, 190))
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    companion object {
        private const val LOCAL_ASSET_HOST = "appassets.androidplatform.net"
        private const val MAP_URL = "https://appassets.androidplatform.net/map/index.html"
        private val OSM_TILE_PATH = Regex("""/\d+/\d+/\d+\.png""")
    }
}

private class JoystickOverlayView(
    context: android.content.Context,
    initialSpeedKmh: Double,
    private val onJoystick: (Double, Double) -> Unit,
    private val onDrag: (Int, Int) -> Unit,
    private val onSpeedChange: () -> Double,
    private val onMap: () -> Unit,
    private val onSavedLocations: () -> Unit,
    private val onCollapsedChanged: (Boolean) -> Unit,
) : View(context) {
    private enum class TouchMode { NONE, JOYSTICK, DRAG, STRAIGHT, SPEED, MAP, SAVED, COLLAPSE }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(145, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 47, 167)
        style = Paint.Style.FILL
    }
    private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val speedHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        textAlign = Paint.Align.CENTER
        textSize = 10f * scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 10f * scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }
    private var collapsed = false
    private var mode = TouchMode.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var moved = false
    private var knobX = 0f
    private var knobY = 0f
    private var speedKmh = initialSpeedKmh
    private var straightMode = false
    private var lastJoystickAngle = -90.0

    init {
        contentDescription = context.getString(R.string.overlay_description)
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (collapsed) {
            val radius = min(width, height) / 2f
            canvas.drawCircle(width / 2f, height / 2f, radius - 3f * density, haloPaint)
            canvas.drawCircle(width / 2f, height / 2f, radius - 3f * density, linePaint)
            canvas.drawCircle(width / 2f, height / 2f, 6f * density, accentPaint)
            return
        }

        val handleHeight = handleHeight()
        drawStraightControl(canvas)
        drawSpeedControl(canvas)
        drawMapControl(canvas)
        drawSavedControl(canvas)
        val dragY = dragHandleHeight() / 2f
        canvas.drawLine(width / 2f - 12f * density, dragY, width / 2f + 12f * density, dragY, haloPaint)
        canvas.drawLine(width / 2f - 12f * density, dragY, width / 2f + 12f * density, dragY, controlPaint)
        val controlY = controlCenterY()
        canvas.drawLine(
            width - 24f * density,
            controlY,
            width - 12f * density,
            controlY,
            haloPaint,
        )
        canvas.drawLine(
            width - 24f * density,
            controlY,
            width - 12f * density,
            controlY,
            controlPaint,
        )

        val centerX = width / 2f
        val centerY = handleHeight + (height - handleHeight) / 2f
        val radius = min(width.toFloat(), height - handleHeight) * 0.42f
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, haloPaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, haloPaint)
        canvas.drawCircle(centerX, centerY, radius, haloPaint)
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, linePaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, linePaint)
        canvas.drawCircle(centerX, centerY, radius, linePaint)
        val northY = centerY - radius + 12f * density
        canvas.drawText("N", centerX, northY, speedHaloPaint)
        canvas.drawText("N", centerX, northY, speedPaint)
        val drawKnobX = if (knobX == 0f) centerX else knobX
        val drawKnobY = if (knobY == 0f) centerY else knobY
        canvas.drawCircle(drawKnobX, drawKnobY, radius * 0.29f, accentPaint)
        canvas.drawCircle(drawKnobX, drawKnobY, radius * 0.29f, linePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                moved = false
                mode = when {
                    collapsed -> TouchMode.DRAG
                    event.y <= dragHandleHeight() -> TouchMode.DRAG
                    event.y <= handleHeight() && event.x >= width - 28f * density -> TouchMode.COLLAPSE
                    event.y <= handleHeight() && event.x >= width - 56f * density -> TouchMode.SAVED
                    event.y <= handleHeight() && event.x >= width - 84f * density -> TouchMode.MAP
                    event.y <= handleHeight() && event.x >= width - 112f * density -> TouchMode.SPEED
                    event.y <= handleHeight() && event.x >= width - 140f * density -> TouchMode.STRAIGHT
                    event.y <= handleHeight() -> TouchMode.DRAG
                    else -> TouchMode.JOYSTICK
                }
                if (mode == TouchMode.JOYSTICK) updateJoystick(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop) moved = true
                when (mode) {
                    TouchMode.DRAG -> {
                        onDrag((event.rawX - lastRawX).roundToInt(), (event.rawY - lastRawY).roundToInt())
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    TouchMode.JOYSTICK -> updateJoystick(event.x, event.y)
                    else -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    TouchMode.JOYSTICK -> releaseJoystick()
                    TouchMode.STRAIGHT -> if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        straightMode = !straightMode
                        if (straightMode) showStraightDirection() else {
                            knobX = 0f
                            knobY = 0f
                        }
                        onJoystick(lastJoystickAngle, if (straightMode) 1.0 else 0.0)
                        invalidate()
                    }
                    TouchMode.SPEED -> if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        speedKmh = onSpeedChange()
                        invalidate()
                    }
                    TouchMode.MAP -> if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        onMap()
                    }
                    TouchMode.SAVED -> if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        onSavedLocations()
                    }
                    TouchMode.COLLAPSE -> if (!moved && event.actionMasked == MotionEvent.ACTION_UP) setCollapsed(true)
                    TouchMode.DRAG -> if (collapsed && !moved && event.actionMasked == MotionEvent.ACTION_UP) setCollapsed(false)
                    else -> Unit
                }
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) performClick()
                mode = TouchMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateJoystick(x: Float, y: Float) {
        val centerX = width / 2f
        val centerY = handleHeight() + (height - handleHeight()) / 2f
        val radius = min(width.toFloat(), height - handleHeight()) * 0.42f
        val deltaX = x - centerX
        val deltaY = y - centerY
        val distance = hypot(deltaX, deltaY)
        val scale = if (distance > radius) radius / distance else 1f
        knobX = centerX + deltaX * scale
        knobY = centerY + deltaY * scale
        val strength = (distance / radius).coerceIn(0f, 1f).toDouble()
        val angle = Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble()))
        lastJoystickAngle = angle
        onJoystick(angle, strength)
        invalidate()
    }

    private fun releaseJoystick() {
        if (straightMode) showStraightDirection() else {
            knobX = 0f
            knobY = 0f
        }
        onJoystick(lastJoystickAngle, if (straightMode) 1.0 else 0.0)
        invalidate()
    }

    private fun showStraightDirection() {
        val centerX = width / 2f
        val centerY = handleHeight() + (height - handleHeight()) / 2f
        val radius = min(width.toFloat(), height - handleHeight()) * 0.42f
        val angle = Math.toRadians(lastJoystickAngle)
        knobX = centerX + cos(angle).toFloat() * radius
        knobY = centerY + sin(angle).toFloat() * radius
    }

    private fun setCollapsed(value: Boolean) {
        releaseJoystick()
        collapsed = value
        onCollapsedChanged(value)
        invalidate()
    }

    private fun drawSpeedControl(canvas: Canvas) {
        val label = speedKmh.toInt().toString()
        val x = width - 102f * density
        val y = controlCenterY() - (speedPaint.ascent() + speedPaint.descent()) / 2f
        canvas.drawText(label, x, y, speedHaloPaint)
        canvas.drawText(label, x, y, speedPaint)
    }

    private fun drawStraightControl(canvas: Canvas) {
        val x = width - 130f * density
        val y = controlCenterY()
        if (straightMode) canvas.drawCircle(x, y, 10f * density, accentPaint)
        val textY = y - (speedPaint.ascent() + speedPaint.descent()) / 2f
        canvas.drawText("直", x, textY, speedHaloPaint)
        canvas.drawText("直", x, textY, speedPaint)
    }

    private fun drawMapControl(canvas: Canvas) {
        val centerX = width - 74f * density
        val centerY = controlCenterY()
        val pin = android.graphics.Path().apply {
            moveTo(centerX, centerY + 8f * density)
            cubicTo(
                centerX - 10f * density,
                centerY,
                centerX - 7f * density,
                centerY - 8f * density,
                centerX,
                centerY - 8f * density,
            )
            cubicTo(
                centerX + 7f * density,
                centerY - 8f * density,
                centerX + 10f * density,
                centerY,
                centerX,
                centerY + 8f * density,
            )
            close()
        }
        canvas.drawPath(pin, haloPaint)
        canvas.drawPath(pin, controlPaint)
        canvas.drawCircle(centerX, centerY - 2f * density, 2f * density, controlPaint)
    }

    private fun drawSavedControl(canvas: Canvas) {
        val centerX = width - 46f * density
        val centerY = controlCenterY()
        val bookmark = android.graphics.Path().apply {
            moveTo(centerX - 5f * density, centerY - 7f * density)
            lineTo(centerX + 5f * density, centerY - 7f * density)
            lineTo(centerX + 5f * density, centerY + 7f * density)
            lineTo(centerX, centerY + 3f * density)
            lineTo(centerX - 5f * density, centerY + 7f * density)
            close()
        }
        canvas.drawPath(bookmark, haloPaint)
        canvas.drawPath(bookmark, controlPaint)
    }

    private fun dragHandleHeight(): Float = 16f * density

    private fun controlCenterY(): Float = dragHandleHeight() + 12f * density

    private fun handleHeight(): Float = dragHandleHeight() + 24f * density
}
