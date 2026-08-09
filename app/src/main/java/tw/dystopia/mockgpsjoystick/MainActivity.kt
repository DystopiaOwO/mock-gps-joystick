package tw.dystopia.mockgpsjoystick

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val speedValues = doubleArrayOf(1.0, 3.0, 5.0, 10.0, 15.0, 20.0)
    private var resumed = false

    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText
    private lateinit var speedSpinner: Spinner
    private lateinit var permissionStatus: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var resetButton: Button
    private lateinit var notificationPermissionButton: Button

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            if (resumed) handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.root))
        bindViews()
        restoreSettings()
        bindActions()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        RuntimeState.setActivityVisible(true)
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        saveValidSettings()
        resumed = false
        RuntimeState.setActivityVisible(false)
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (isFinishing && !isChangingConfigurations) {
            stopService(Intent(this, MockLocationService::class.java))
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun bindViews() {
        latitudeInput = findViewById(R.id.latitudeInput)
        longitudeInput = findViewById(R.id.longitudeInput)
        speedSpinner = findViewById(R.id.speedSpinner)
        permissionStatus = findViewById(R.id.permissionStatus)
        runtimeStatus = findViewById(R.id.runtimeStatus)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        resetButton = findViewById(R.id.resetButton)
        notificationPermissionButton = findViewById(R.id.notificationPermissionButton)

        speedSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            speedValues.map { "${it.toInt()} km/h" },
        )
        notificationPermissionButton.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
    }

    private fun restoreSettings() {
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val latitude = preferences.getString(KEY_LATITUDE, "25.0330") ?: "25.0330"
        val longitude = preferences.getString(KEY_LONGITUDE, "121.5654") ?: "121.5654"
        val speed = preferences.getFloat(KEY_SPEED, 5f).toDouble()
        latitudeInput.setText(latitude)
        longitudeInput.setText(longitude)
        speedSpinner.setSelection(speedValues.indexOfFirst { it == speed }.coerceAtLeast(0))
    }

    private fun bindActions() {
        findViewById<Button>(R.id.locationPermissionButton).setOnClickListener {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_LOCATION,
            )
        }
        notificationPermissionButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
            }
        }
        findViewById<Button>(R.id.overlaySettingsButton).setOnClickListener {
            openSettings(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
        findViewById<Button>(R.id.developerSettingsButton).setOnClickListener {
            openSettings(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.locationSettingsButton).setOnClickListener {
            openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshStatus() }
        findViewById<Button>(R.id.mapPickerButton).setOnClickListener { openMapPicker() }
        startButton.setOnClickListener { startMockLocation() }
        stopButton.setOnClickListener {
            stopService(Intent(this, MockLocationService::class.java))
            RuntimeState.markStopped()
            refreshStatus()
        }
        resetButton.setOnClickListener {
            if (!RuntimeState.resetToStart()) {
                toast("服務尚未執行。")
            }
            refreshStatus()
        }
    }

    private fun startMockLocation() {
        if (!resumed || !hasWindowFocus()) {
            toast("只能從目前可見的主畫面啟動。")
            return
        }

        val latitude = latitudeInput.text.toString().toDoubleOrNull()
        val longitude = longitudeInput.text.toString().toDoubleOrNull()
        if (latitude == null || !latitude.isFinite() || latitude !in -90.0..90.0) {
            latitudeInput.error = "緯度必須介於 -90 到 90。"
            return
        }
        if (longitude == null || !longitude.isFinite() || longitude !in -180.0..180.0) {
            longitudeInput.error = "經度必須介於 -180 到 180。"
            return
        }
        if (!hasLocationPermission()) {
            toast("請先授予精確定位權限。")
            return
        }
        if (!hasNotificationPermission()) {
            toast("請先授予通知權限。")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("請先開啟「顯示在其他應用程式上層」權限。")
            return
        }
        if (!isLocationEnabled()) {
            toast("請先開啟手機定位服務。")
            return
        }

        val speed = speedValues[speedSpinner.selectedItemPosition]
        val start = Coordinate(latitude, longitude)
        saveSettings(start, speed)
        RuntimeState.prepareStart(start, speed)

        val intent = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LATITUDE, latitude)
            .putExtra(MockLocationService.EXTRA_LONGITUDE, longitude)
            .putExtra(MockLocationService.EXTRA_SPEED_KMH, speed)
        try {
            startForegroundService(intent)
        } catch (error: RuntimeException) {
            RuntimeState.fail("服務啟動失敗：${error.message ?: error.javaClass.simpleName}")
            toast("服務啟動失敗，請確認權限與系統設定。")
        }
        refreshStatus()
    }

    @Suppress("DEPRECATION")
    private fun openMapPicker() {
        val latitude = latitudeInput.text.toString().toDoubleOrNull()
        val longitude = longitudeInput.text.toString().toDoubleOrNull()
        val initial = if (latitude != null && latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude != null && longitude.isFinite() && longitude in -180.0..180.0
        ) {
            Coordinate(latitude, longitude)
        } else {
            RuntimeState.snapshot().currentCoordinate
        }
        startActivityForResult(
            Intent(this, MapPickerActivity::class.java)
                .putExtra(MapPickerActivity.EXTRA_INITIAL_LATITUDE, initial.latitude)
                .putExtra(MapPickerActivity.EXTRA_INITIAL_LONGITUDE, initial.longitude)
                .putExtra(
                    MapPickerActivity.EXTRA_SERVICE_RUNNING,
                    RuntimeState.snapshot().serviceRunning,
                ),
            REQUEST_MAP,
        )
    }

    @Deprecated("Framework callback retained because this project intentionally has no AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MAP || resultCode != RESULT_OK || data == null) return
        val latitude = data.getDoubleExtra(MapPickerActivity.RESULT_LATITUDE, Double.NaN)
        val longitude = data.getDoubleExtra(MapPickerActivity.RESULT_LONGITUDE, Double.NaN)
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) {
            toast("地圖回傳的座標無效。")
            return
        }
        val selected = Coordinate(latitude, longitude)
        latitudeInput.setText(String.format(Locale.US, "%.6f", latitude))
        longitudeInput.setText(String.format(Locale.US, "%.6f", longitude))
        saveSettings(selected, speedValues[speedSpinner.selectedItemPosition])
        var moved = false
        if (RuntimeState.snapshot().serviceRunning) {
            if (data.getBooleanExtra(MapPickerActivity.RESULT_MOVE_NOW, false)) {
                moved = RuntimeState.jumpTo(selected)
            } else {
                RuntimeState.updateStartCoordinate(selected, moveCurrent = false)
            }
        }
        toast(if (moved) "已傳送到地圖座標。" else "已套用地圖座標。")
        refreshStatus()
    }

    private fun refreshStatus() {
        val snapshot = RuntimeState.snapshot()
        val mockAppSelected = hasMockLocationAppSelection()
        val mockStatus = when {
            snapshot.serviceRunning -> "已驗證"
            snapshot.error?.startsWith("Mock Location") == true -> "失敗"
            mockAppSelected -> "已選取"
            else -> "未選取"
        }
        permissionStatus.text = buildString {
            appendLine("定位權限：${yesNo(hasLocationPermission())}")
            appendLine("通知權限：${yesNo(hasNotificationPermission())}")
            appendLine("懸浮窗權限：${yesNo(Settings.canDrawOverlays(this@MainActivity))}")
            appendLine("系統定位：${yesNo(isLocationEnabled())}")
            append("Mock Location App：$mockStatus")
        }
        val lastPush = if (snapshot.lastPushMillis == 0L) {
            "尚未推送"
        } else {
            android.text.format.DateFormat.getTimeFormat(this).format(Date(snapshot.lastPushMillis))
        }
        runtimeStatus.text = buildString {
            appendLine("目前座標：${format(snapshot.currentCoordinate.latitude)}, ${format(snapshot.currentCoordinate.longitude)}")
            appendLine("目前速度：${format(snapshot.actualSpeedMps * 3.6)} km/h")
            appendLine("方向角：${format(snapshot.bearing)}°")
            appendLine("搖桿強度：${format(snapshot.joystickStrength)}")
            appendLine("服務：${yesNo(snapshot.serviceRunning)}")
            appendLine("懸浮搖桿：${yesNo(snapshot.overlayVisible)}")
            appendLine("最近推送：$lastPush")
            append("最近錯誤：${snapshot.error ?: "無"}")
        }
        if (snapshot.serviceRunning) {
            val speedIndex = speedValues.indexOfFirst { it == snapshot.speedKmh }
            if (speedIndex >= 0 && speedSpinner.selectedItemPosition != speedIndex) {
                speedSpinner.setSelection(speedIndex)
            }
        }
        startButton.isEnabled = resumed && !snapshot.starting && !snapshot.serviceRunning
        stopButton.isEnabled = snapshot.starting || snapshot.serviceRunning
        resetButton.isEnabled = snapshot.serviceRunning
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun hasMockLocationAppSelection(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    applicationInfo.uid,
                    packageName,
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    applicationInfo.uid,
                    packageName,
                )
            }
        } catch (_: RuntimeException) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(LocationManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun saveValidSettings() {
        val latitude = latitudeInput.text.toString().toDoubleOrNull()
        val longitude = longitudeInput.text.toString().toDoubleOrNull()
        if (latitude != null && latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude != null && longitude.isFinite() && longitude in -180.0..180.0
        ) {
            saveSettings(
                Coordinate(latitude, longitude),
                speedValues[speedSpinner.selectedItemPosition],
            )
        }
    }

    private fun saveSettings(coordinate: Coordinate, speed: Double) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_LATITUDE, coordinate.latitude.toString())
            .putString(KEY_LONGITUDE, coordinate.longitude.toString())
            .putFloat(KEY_SPEED, speed.toFloat())
            .apply()
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("此裝置沒有對應的設定頁，請從系統設定手動開啟。")
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarInsets(root: ScrollView) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                left = insets.left
                top = insets.top
                right = insets.right
                bottom = insets.bottom
            } else {
                val legacyInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    legacyInsetsWithCutout(windowInsets)
                } else {
                    intArrayOf(
                        windowInsets.systemWindowInsetLeft,
                        windowInsets.systemWindowInsetTop,
                        windowInsets.systemWindowInsetRight,
                        windowInsets.systemWindowInsetBottom,
                    )
                }
                left = legacyInsets[0]
                top = legacyInsets[1]
                right = legacyInsets[2]
                bottom = legacyInsets[3]
            }
            view.setPadding(
                initialLeft + left,
                initialTop + top,
                initialRight + right,
                initialBottom + bottom,
            )
            windowInsets
        }
        root.requestApplyInsets()
    }

    @TargetApi(Build.VERSION_CODES.P)
    @Suppress("DEPRECATION")
    private fun legacyInsetsWithCutout(windowInsets: WindowInsets): IntArray {
        val cutout = windowInsets.displayCutout
        return intArrayOf(
            max(windowInsets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
            max(windowInsets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
            max(windowInsets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
            max(windowInsets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0),
        )
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    private fun format(value: Number): String = String.format(Locale.US, "%.6f", value.toDouble())

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_LOCATION = 100
        private const val REQUEST_NOTIFICATION = 101
        private const val REQUEST_MAP = 102
        private const val PREFS_NAME = "mock_gps_settings"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_SPEED = "speed"
    }
}
