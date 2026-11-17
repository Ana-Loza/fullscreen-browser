package com.fullscreen.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** Archivo esperando el permiso de almacenamiento (solo Android 9 o anterior). */
    private var pendingSave: PendingFile? = null

    private class PendingFile(val bytes: ByteArray, val name: String, val mime: String)

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
        // Pantalla completa ANTES de cargar la pagina, para que el editor (p. ej. Konva)
        // calcule el tamano del lienzo con el tamano final.
        enterImmersive()

        // Por defecto, como Chrome: el teclado NO achica la pagina. Si la pagina se achica,
        // editores como Konva recalculan el lienzo mas pequeno mientras escribes y el texto
        // queda cortado. Se puede activar desde el menu oculto si un campo queda tapado.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val keyboard = if (!resizeForKeyboard) 0
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, keyboard)
            insets
        }
        applyKeyboardMode()

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            // Esperamos a que la vista tenga su tamano de pantalla completa.
            root.post {
                if (!handleIntent(intent)) {
                    val home = homeUrl
                    if (home.isNullOrBlank()) showUrlDialog(firstRun = true) else webView.loadUrl(home)
                }
            }
        }
    }

    private var resizeForKeyboard: Boolean
        get() = prefs.getBoolean(KEY_RESIZE_KEYBOARD, false)
        set(value) = prefs.edit().putBoolean(KEY_RESIZE_KEYBOARD, value).apply()

    private fun applyKeyboardMode() {
        window.setSoftInputMode(
            if (resizeForKeyboard) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        root.requestApplyInsets()
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
            // Ignora el tamano de letra del sistema: si no, el WebView agranda el texto
            // y en el editor las cajas de texto se desbordan y salen cortadas (Chrome no lo hace).
            textZoom = 100
            minimumFontSize = 1
            minimumLogicalFontSize = 1
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

            override fun onPageFinished(view: WebView, url: String?) {
                // Engancha las descargas de la pagina (a[download] con data: o blob:)
                // para guardarlas completas desde la app.
                if (url != null && url.startsWith("http")) view.evaluateJavascript(DOWNLOAD_JS, null)
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

        webView.addJavascriptInterface(DownloadBridge(), "AndroidDownload")

        // Respaldo por si la pagina descarga de una forma que el script no engancho.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            when {
                url.startsWith("data:") -> saveDataUrl(url, "", mimeType ?: "")
                url.startsWith("blob:") -> webView.evaluateJavascript(
                    DOWNLOAD_JS + ";window.__fsSaveUrl(" + JSONObject.quote(url) + ",'')", null
                )
                else -> downloadHttp(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    // ---------- Descargas ----------

    /** Recibe desde JavaScript el archivo completo como data: URL. */
    private inner class DownloadBridge {
        @JavascriptInterface
        fun save(dataUrl: String, name: String, type: String) {
            runOnUiThread { saveDataUrl(dataUrl, name, type) }
        }

        @JavascriptInterface
        fun error(message: String) {
            runOnUiThread { toast("No se pudo descargar: $message") }
        }
    }

    /**
     * Antes la data: URL se pasaba a otra app con Uri.parse(), que corta todo lo que
     * va despues del primer '#' (p. ej. fill="#ff0000" en un SVG). Ahora se decodifica
     * aqui entera y se guarda en Descargas.
     */
    private fun saveDataUrl(dataUrl: String, name: String, type: String) {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) {
            toast("No se pudo descargar: formato no valido"); return
        }
        val meta = dataUrl.substring(5, comma).split(';').map { it.trim() }
        val payload = dataUrl.substring(comma + 1)
        val bytes = try {
            if (meta.any { it.equals("base64", ignoreCase = true) }) {
                Base64.decode(Uri.decode(payload), Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }
        } catch (e: IllegalArgumentException) {
            toast("No se pudo descargar: datos danados"); return
        }
        val mime = type.ifBlank { meta.firstOrNull().orEmpty() }.ifBlank { "application/octet-stream" }
        saveBytes(bytes, fileNameFor(name, mime), mime)
    }

    private fun fileNameFor(requested: String, mime: String): String {
        var name = requested.substringAfterLast('/').replace(BAD_NAME_CHARS, "_").trim()
        if (name.isEmpty() || name == "." || name == "..") {
            name = "descarga-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        }
        if (!name.contains('.')) {
            val ext = if (mime.contains("svg")) "svg"
            else MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';'))
            if (ext != null) name += ".$ext"
        }
        return name
    }

    private fun saveBytes(bytes: ByteArray, name: String, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("no se pudo crear el archivo")
                (contentResolver.openOutputStream(uri) ?: throw IOException("no se pudo escribir"))
                    .use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingSave = PendingFile(bytes, name, mime)
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
                    return
                }
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val base = name.substringBeforeLast('.')
                val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
                var file = File(dir, name)
                var n = 1
                while (file.exists()) {
                    file = File(dir, "$base ($n)$ext")
                    n++
                }
                file.writeBytes(bytes)
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mime), null)
            }
            toast("Guardado en Descargas: $name")
        } catch (e: Exception) {
            toast("No se pudo guardar: ${e.message}")
        }
    }

    private fun downloadHttp(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast("Descargando: $name")
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_STORAGE) {
            val file = pendingSave
            pendingSave = null
            if (file != null) {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    saveBytes(file.bytes, file.name, file.mime)
                } else {
                    toast("Sin permiso de almacenamiento no se puede guardar")
                }
            }
            enterImmersive()
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
            if (resizeForKeyboard) "Teclado: achicar la pagina (activado)"
            else "Teclado: achicar la pagina (desactivado)",
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
                    5 -> {
                        resizeForKeyboard = !resizeForKeyboard
                        applyKeyboardMode()
                    }
                    6 -> finishAndRemoveTask()
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
        private const val KEY_RESIZE_KEYBOARD = "resize_for_keyboard"
        private const val REQ_FILE = 1001
        private const val REQ_STORAGE = 1002

        private val BAD_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1f]")
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

        /**
         * Se inyecta en cada pagina. Guarda los Blob que crea la pagina (y retrasa su
         * borrado) para poder leerlos enteros, e intercepta los clics en <a download>
         * con data: o blob: para mandar el archivo completo a la app.
         */
        private val DOWNLOAD_JS = """
            (function(){
              if (window.__fsSaveUrl) return;
              var blobs = {}, order = [];
              var origCreate = URL.createObjectURL, origRevoke = URL.revokeObjectURL;
              URL.createObjectURL = function(o){
                var u = origCreate.apply(URL, arguments);
                try { if (o instanceof Blob) { blobs[u] = o; order.push(u);
                  if (order.length > 30) delete blobs[order.shift()]; } } catch(e) {}
                return u;
              };
              URL.revokeObjectURL = function(u){
                setTimeout(function(){ origRevoke.call(URL, u); delete blobs[u]; }, 60000);
              };
              function send(blob, name){
                var r = new FileReader();
                r.onload = function(){ AndroidDownload.save(String(r.result), name || '', blob.type || ''); };
                r.onerror = function(){ AndroidDownload.error('no se pudo leer el archivo'); };
                r.readAsDataURL(blob);
              }
              window.__fsSaveUrl = function(href, name){
                if (href.indexOf('data:') === 0) { AndroidDownload.save(href, name || '', ''); return true; }
                if (href.indexOf('blob:') === 0) {
                  if (blobs[href]) { send(blobs[href], name); return true; }
                  fetch(href).then(function(r){ return r.blob(); })
                    .then(function(b){ send(b, name); })
                    .catch(function(e){ AndroidDownload.error(String(e)); });
                  return true;
                }
                return false;
              };
              function handle(a){
                if (!a || !a.hasAttribute || !a.hasAttribute('download')) return false;
                var h = a.href || '';
                if (h.indexOf('data:') !== 0 && h.indexOf('blob:') !== 0) return false;
                return window.__fsSaveUrl(h, a.getAttribute('download'));
              }
              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function(){
                if (handle(this)) return;
                return origClick.apply(this, arguments);
              };
              var origDispatch = EventTarget.prototype.dispatchEvent;
              EventTarget.prototype.dispatchEvent = function(ev){
                if (ev && ev.type === 'click' && this instanceof HTMLAnchorElement && handle(this)) return false;
                return origDispatch.apply(this, arguments);
              };
              window.addEventListener('click', function(ev){
                if (ev.defaultPrevented) return;
                var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
                if (a && handle(a)) ev.preventDefault();
              }, false);
            })();
        """.trimIndent()

        private fun String.htmlEscape() =
            replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
