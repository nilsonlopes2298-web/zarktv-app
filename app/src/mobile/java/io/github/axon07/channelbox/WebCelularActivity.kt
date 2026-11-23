package io.github.axon07.channelbox

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * ZarkTV Mobile: o APK abre o app web do celular (o mesmo que já roda bem no navegador).
 * - Uso normal em pé; quando o vídeo vai para tela cheia, gira para a horizontal.
 * - Botão voltar: sai da tela cheia, volta a página, e só fecha com dois toques.
 * - Links de WhatsApp, Pix e outros apps abrem fora.
 */
class WebCelularActivity : Activity() {

    private val endereco = "https://zark.zarktvqx.cfd/celular"
    private lateinit var raiz: FrameLayout
    private lateinit var web: WebView
    private lateinit var telaErro: LinearLayout
    private var telaCheia: View? = null
    private var telaCheiaCallback: WebChromeClient.CustomViewCallback? = null
    private var ultimoVoltar = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // Os servidores de vídeo são http; o site é https.
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "$userAgentString ZarkTVApp/${BuildConfig.VERSION_NAME}"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Ponte(), "ZarkApp")
            webViewClient = Cliente()
            webChromeClient = Chrome()
        }
        raiz.addView(web, FrameLayout.LayoutParams(-1, -1))
        telaErro = montarTelaErro()
        raiz.addView(telaErro, FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(endereco)
    }

    private fun montarTelaErro(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(TextView(context).apply {
            text = "Sem conexão com o ZarkTV"
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Confira a internet do aparelho e toque em Tentar de novo."
            setTextColor(0xAAFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })
        addView(Button(context).apply {
            text = "Tentar de novo"
            setTextColor(Color.WHITE); setBackgroundColor(0xFFE53935.toInt())
            setOnClickListener { telaErro.visibility = View.GONE; web.reload() }
        })
    }

    private inner class Cliente : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val esquema = uri.scheme?.lowercase()
            val host = uri.host?.lowercase().orEmpty()
            val fora = esquema !in listOf("http", "https") ||
                host == "wa.me" || host.endsWith("whatsapp.com") || host == "t.me" || host.endsWith("mercadopago.com.br") && uri.path?.contains("checkout") == false
            if (!fora) return false
            abrirFora(uri)
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) telaErro.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush()
        }
    }

    private fun abrirFora(uri: Uri) {
        try {
            val i = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, uri)
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app para abrir este link.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (telaCheia != null) { callback.onCustomViewHidden(); return }
            telaCheia = view
            telaCheiaCallback = callback
            raiz.addView(view, FrameLayout.LayoutParams(-1, -1))
            entrarTelaCheia()
        }

        override fun onHideCustomView() {
            val v = telaCheia ?: return
            raiz.removeView(v)
            telaCheia = null
            telaCheiaCallback = null
            sairTelaCheia()
        }

        override fun getDefaultVideoPoster() = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** O site pode chamar ZarkApp.telaCheia(true/false) quando usa tela cheia própria (sem a do navegador). */
    private inner class Ponte {
        @JavascriptInterface fun telaCheia(ligar: Boolean) = runOnUiThread { if (ligar) entrarTelaCheia() else sairTelaCheia() }
        @JavascriptInterface fun ehApp(): Boolean = true
        @JavascriptInterface fun versao(): String = BuildConfig.VERSION_NAME
    }

    private fun entrarTelaCheia() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        esconderBarras(true)
    }

    private fun sairTelaCheia() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        esconderBarras(false)
    }

    @Suppress("DEPRECATION")
    private fun esconderBarras(esconder: Boolean) {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (esconder) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        window.decorView.systemUiVisibility = if (esconder)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        else View.SYSTEM_UI_FLAG_VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            telaCheia != null -> telaCheiaCallback?.onCustomViewHidden() ?: (web.webChromeClient as? Chrome)?.onHideCustomView()
            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> {
                web.evaluateJavascript("document.exitFullscreen&&document.fullscreenElement&&document.exitFullscreen()", null)
                sairTelaCheia()
            }
            web.canGoBack() -> web.goBack()
            System.currentTimeMillis() - ultimoVoltar < 2000 -> finish()
            else -> {
                ultimoVoltar = System.currentTimeMillis()
                Toast.makeText(this, "Toque de novo em voltar para sair", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() { super.onResume(); web.onResume() }
    override fun onPause() { web.onPause(); CookieManager.getInstance().flush(); super.onPause() }
    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
