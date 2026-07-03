package tw.dystopia.mockgpsjoystick

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.max

class MapPickerActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var coordinateText: TextView
    private lateinit var moveNowCheckbox: CheckBox
    private var selected = Coordinate(25.0330, 121.5654)
    private var pageReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_picker)
        applySystemBarInsets(findViewById(R.id.mapPickerRoot))

        selected = Coordinate(
            intent.getDoubleExtra(EXTRA_INITIAL_LATITUDE, 25.0330)
                .takeIf { it.isFinite() && it in -90.0..90.0 } ?: 25.0330,
            intent.getDoubleExtra(EXTRA_INITIAL_LONGITUDE, 121.5654)
                .takeIf { it.isFinite() && it in -180.0..180.0 } ?: 121.5654,
        )
        coordinateText = findViewById(R.id.selectedCoordinate)
        moveNowCheckbox = findViewById(R.id.mapResetCheckbox)
        val serviceRunning = intent.getBooleanExtra(EXTRA_SERVICE_RUNNING, false)
        moveNowCheckbox.isEnabled = serviceRunning
        moveNowCheckbox.isChecked = serviceRunning
        updateCoordinateText()

        findViewById<Button>(R.id.mapCancelButton).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        val applyButton = findViewById<Button>(R.id.mapApplyButton)
        fun updateApplyLabel() {
            applyButton.setText(if (moveNowCheckbox.isChecked) R.string.map_move else R.string.map_apply)
        }
        moveNowCheckbox.setOnCheckedChangeListener { _, _ -> updateApplyLabel() }
        updateApplyLabel()
        applyButton.setOnClickListener {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(RESULT_LATITUDE, selected.latitude)
                    .putExtra(RESULT_LONGITUDE, selected.longitude)
                    .putExtra(RESULT_MOVE_NOW, moveNowCheckbox.isChecked),
            )
            finish()
        }

        webView = findViewById(R.id.mapWebView)
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
                    sendInitialCoordinate()
                }
            }
        }
        webView.loadUrl(MAP_URL)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidMap")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
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
            WebResourceResponse(mimeType, "UTF-8", assets.open(assetPath))
        } catch (_: IOException) {
            null
        }
    }

    private fun isOpenStreetMapTile(uri: Uri): Boolean {
        return uri.scheme == "https" &&
            uri.host == "tile.openstreetmap.org" &&
            OSM_TILE_PATH.matches(uri.path.orEmpty())
    }

    private fun sendInitialCoordinate() {
        if (!pageReady) return
        webView.evaluateJavascript(
            "window.setInitialLocation(${selected.latitude}, ${selected.longitude});",
            null,
        )
    }

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
            runOnUiThread {
                selected = Coordinate(latitude, longitude)
                updateCoordinateText()
            }
        }

        @JavascriptInterface
        fun onTileError() {
            runOnUiThread {
                coordinateText.text = "圖磚載入失敗；請確認網路連線。"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarInsets(root: LinearLayout) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view: View, windowInsets: WindowInsets ->
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

    companion object {
        const val EXTRA_INITIAL_LATITUDE = "initial_latitude"
        const val EXTRA_INITIAL_LONGITUDE = "initial_longitude"
        const val EXTRA_SERVICE_RUNNING = "service_running"
        const val RESULT_LATITUDE = "selected_latitude"
        const val RESULT_LONGITUDE = "selected_longitude"
        const val RESULT_MOVE_NOW = "move_now"

        private const val LOCAL_ASSET_HOST = "appassets.androidplatform.net"
        private const val MAP_URL = "https://appassets.androidplatform.net/map/index.html"
        private val OSM_TILE_PATH = Regex("""/\d+/\d+/\d+\.png""")
    }
}
