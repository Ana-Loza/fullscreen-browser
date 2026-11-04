package com.fullscreen.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Navegador a pantalla completa: muestra cualquier pagina web sin la barra de Chrome
 * ni las barras del sistema.
 *
 * Menu oculto: 3 toques rapidos en la esquina superior derecha, o el boton Atras
 * cuando ya no hay paginas anteriores.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastFailedUrl: String? = null
    private lateinit var chromeClient: WebChromeClient

    private var cornerTaps = 0
    private var firstCornerTapAt = 0L

    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    private var homeUrl: String?
        get() = prefs.getString(KEY_HOME_URL, null)
        set(value) = prefs.edit().putString(KEY_HOME_URL, value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)

        root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        // Como dibujamos detras de las barras, el teclado no redimensiona solo:
        // dejamos espacio abajo igual a la altura del teclado.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val keyboard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, keyboard)
            insets
        }

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else if (!handleIntent(intent)) {
            val home = homeUrl
            if (home.isNullOrBlank()) showUrlDialog(firstRun = true) else webView.loadUrl(home)
        }
        enterImmersive()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            // false = los enlaces que abren ventana nueva (target=_blank) se cargan aqui mismo.
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Algunas webs (p. ej. login de Google) bloquean WebViews; nos presentamos como Chrome.
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") return false
                // La pagina de error navega a retry://now para reintentar.
                if (uri.scheme == "retry") {
                    lastFailedUrl?.let { view.loadUrl(it) }
                    return true
                }
                // tel:, mailto:, intent:, etc. se abren con otra app.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && url.startsWith("http")) lastFailedUrl = null
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    lastFailedUrl = request.url.toString()
                    showErrorPage(error.description?.toString() ?: "Error de conexion")
                }
            }
        }

        chromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            // Video u otro elemento pidiendo pantalla completa (Fullscreen API).
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden(); return
                }
                customView = view
                customViewCallback = callback
                root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                webView.visibility = View.GONE
                enterImmersive()
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                enterImmersive()
            }
        }

        webView.webChromeClient = chromeClient

        webView.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun showErrorPage(reason: String) {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{background:#111;color:#eee;font-family:sans-serif;display:flex;
            flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0}
            p{opacity:.7}</style></head><body>
            <h2>Sin conexion</h2><p>${reason.htmlEscape()}</p>
            <p>Reintentando en <span id="s">10</span> s</p>
            <script>var s=10;setInterval(function(){s--;document.getElementById('s').textContent=s;
            if(s<=0){location.href='retry://now';}},1000);</script>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    /** Abre la URL que llega desde "Compartir" o desde un enlace. Devuelve true si cargo algo. */
    private fun handleIntent(intent: Intent?): Boolean {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { findUrl(it) }
            else -> null
        } ?: return false
        webView.loadUrl(url)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------- Menu oculto ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val corner = CORNER_DP * resources.displayMetrics.density
            if (ev.x > root.width - corner && ev.y < corner) {
                val now = System.currentTimeMillis()
                if (now - firstCornerTapAt > TAP_WINDOW_MS) {
                    firstCornerTapAt = now
                    cornerTaps = 0
                }
                cornerTaps++
                if (cornerTaps >= 3) {
                    cornerTaps = 0
                    showMenu()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showMenu() {
        val options = arrayOf(
            "Cambiar pagina de inicio",
            "Ir a la pagina de inicio",
            "Usar esta pagina como inicio",
            "Recargar",
            "Atras",
            "Cerrar la app"
        )
        AlertDialog.Builder(this)
            .setTitle(webView.url ?: getString(R.string.app_name))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showUrlDialog(firstRun = false)
                    1 -> homeUrl?.let { webView.loadUrl(it) }
                    2 -> webView.url?.takeIf { it.startsWith("http") }?.let { homeUrl = it }
                    3 -> webView.reload()
                    4 -> if (webView.canGoBack()) webView.goBack()
                    5 -> finishAndRemoveTask()
                }
            }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    private fun showUrlDialog(firstRun: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://mi-editor.com"
            setText(homeUrl ?: "")
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Pagina de inicio")
            .setMessage(
                if (firstRun) "Escribe la direccion que se abrira a pantalla completa. " +
                    "Para volver a este menu: 3 toques en la esquina superior derecha."
                else null
            )
            .setView(box)
            .setCancelable(!firstRun)
            .setPositiveButton("Abrir") { _, _ ->
                val url = normalizeUrl(input.text.toString())
                if (url != null) {
                    homeUrl = url
                    webView.loadUrl(url)
                } else if (firstRun) {
                    showUrlDialog(true)
                }
            }
            .apply { if (!firstRun) setNegativeButton("Cancelar", null) }
            .setOnDismissListener { enterImmersive() }
            .show()
    }

    // ---------- Pantalla completa ----------

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        enterImmersive()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> chromeClient.onHideCustomView()
            webView.canGoBack() -> webView.goBack()
            else -> showMenu()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val KEY_HOME_URL = "home_url"
        private const val REQ_FILE = 1001
        private const val CORNER_DP = 80
        private const val TAP_WINDOW_MS = 1500L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        private val URL_REGEX = Regex("""https?://\S+""")

        fun findUrl(text: String): String? = URL_REGEX.find(text)?.value

        fun normalizeUrl(raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
        }

        private fun String.htmlEscape() =
            replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
