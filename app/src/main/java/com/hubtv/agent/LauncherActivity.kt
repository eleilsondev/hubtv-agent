package com.hubtv.agent

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LauncherActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var config: JSONObject? = null
    private var bloqueado = false
    private var bannerIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        config = carregarConfig()
        aplicarConfig()
        iniciarRelogio()
        iniciarBannerRotacao()
    }

    override fun onResume() {
        super.onResume()
        config = carregarConfig()
        aplicarConfig()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            startActivity(Intent(this, MainActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        // no launcher o back nao faz nada
    }

    private fun aplicarConfig() {
        val cfg = config
        val nome = cfg?.optString("nome", "HUB TV") ?: "HUB TV"

        findViewById<TextView>(R.id.nome_launcher).text = nome
        findViewById<TextView>(R.id.banner_placeholder).text = nome

        aplicarCores(cfg)
        aplicarLogo(cfg)
        montarCategorias(cfg)
        montarApps(cfg)
        montarAtalhos(cfg)
        montarBanner(cfg)

        val prefs = getSharedPreferences("hubtv_agente", MODE_PRIVATE)
        bloqueado = prefs.getBoolean("bloqueado", false)
        findViewById<View>(R.id.tela_bloqueio).visibility =
            if (bloqueado) View.VISIBLE else View.GONE

        val conectado = try { Adb.conectado(this) } catch (_: Exception) { false }
        val indicador = findViewById<View>(R.id.indicador_status)
        val drawable = indicador.background
        if (drawable is GradientDrawable) {
            drawable.setColor(if (conectado) Color.parseColor("#34D399") else Color.parseColor("#F87171"))
        }
    }

    private fun aplicarCores(cfg: JSONObject?) {
        val cores = cfg?.optJSONObject("cores")
        val primaria = cores?.optString("primaria", "#1a237e") ?: "#1a237e"
        val secundaria = cores?.optString("secundaria", "#0d47a1") ?: "#0d47a1"

        try {
            val gradiente = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primaria), Color.parseColor(secundaria))
            )
            findViewById<View>(R.id.fundo_gradiente).background = gradiente
        } catch (_: Exception) {}
    }

    private fun aplicarLogo(cfg: JSONObject?) {
        val logoBase64 = cfg?.optString("logo", "") ?: ""
        val logoView = findViewById<ImageView>(R.id.logo)

        if (logoBase64.isNotEmpty() && logoBase64.contains("base64,")) {
            try {
                val dados = logoBase64.substringAfter("base64,")
                val bytes = Base64.decode(dados, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                logoView.setImageBitmap(bitmap)
                logoView.visibility = View.VISIBLE
            } catch (_: Exception) {
                logoView.visibility = View.GONE
            }
        } else {
            logoView.visibility = View.GONE
        }
    }

    private fun montarCategorias(cfg: JSONObject?) {
        val container = findViewById<LinearLayout>(R.id.categorias_container)
        container.removeAllViews()

        val cats = cfg?.optJSONArray("categorias") ?: return
        if (cats.length() == 0) return

        val destaque = cfg.optJSONObject("cores")?.optString("destaque", "#00E5FF") ?: "#00E5FF"

        for (i in 0 until cats.length()) {
            val cat = cats.getJSONObject(i)
            val tv = TextView(this).apply {
                val icone = cat.optString("icone", "")
                val nome = cat.optString("nome", "")
                text = if (icone.isNotEmpty()) "$icone $nome" else nome
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundResource(R.drawable.categoria_tab_bg)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundResource(
                        if (hasFocus) R.drawable.categoria_tab_selected
                        else R.drawable.categoria_tab_bg
                    )
                    if (hasFocus) {
                        filtrarApps(cat.optInt("id", -1))
                    }
                }
                setOnClickListener {
                    filtrarApps(cat.optInt("id", -1))
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, dp(8), 0)
            container.addView(tv, params)
        }

        // tab "Todos" no inicio
        val tvTodos = TextView(this).apply {
            text = "Todos"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundResource(R.drawable.categoria_tab_selected)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundResource(
                    if (hasFocus) R.drawable.categoria_tab_selected
                    else R.drawable.categoria_tab_bg
                )
                if (hasFocus) filtrarApps(-1)
            }
            setOnClickListener { filtrarApps(-1) }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, dp(8), 0)
        container.addView(tvTodos, 0, params)
    }

    private fun montarApps(cfg: JSONObject?) {
        val grid = findViewById<RecyclerView>(R.id.apps_grid)
        grid.layoutManager = GridLayoutManager(this, 2)

        val apps = cfg?.optJSONArray("apps") ?: return
        val lista = mutableListOf<JSONObject>()

        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            if (app.optString("tipo") != "atalho") {
                lista.add(app)
            }
        }

        grid.adapter = AppAdapter(this, lista, cfg)
    }

    private fun montarAtalhos(cfg: JSONObject?) {
        val container = findViewById<LinearLayout>(R.id.atalhos_container)
        container.removeAllViews()

        val apps = cfg?.optJSONArray("apps") ?: return

        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            if (app.optString("tipo") != "atalho") continue

            val view = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundResource(R.drawable.atalho_bg)
                isFocusable = true
                isFocusableInTouchMode = true

                setOnFocusChangeListener { v, hasFocus ->
                    v.alpha = if (hasFocus) 1f else 0.7f
                    if (hasFocus) v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(100).start()
                    else v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
                alpha = 0.7f

                val pacote = app.optString("pacote", "")
                setOnClickListener { abrirApp(pacote) }
            }

            val iconeBase64 = app.optString("icone", "")
            if (iconeBase64.isNotEmpty() && iconeBase64.contains("base64,")) {
                try {
                    val dados = iconeBase64.substringAfter("base64,")
                    val bytes = Base64.decode(dados, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val iv = ImageView(this).apply {
                        setImageBitmap(bmp)
                        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    view.addView(iv)
                } catch (_: Exception) {
                    adicionarLetraFallback(view, app, cfg)
                }
            } else {
                adicionarIconeDoSistema(view, app)
            }

            val nome = TextView(this).apply {
                text = app.optString("nome", "App")
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(8), 0, 0, 0)
            }
            view.addView(nome)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, dp(10), 0)
            container.addView(view, params)
        }
    }

    private fun montarBanner(cfg: JSONObject?) {
        val banners = cfg?.optJSONArray("banners")
        val placeholder = findViewById<TextView>(R.id.banner_placeholder)
        val imagem = findViewById<ImageView>(R.id.banner_imagem)
        val overlay = findViewById<View>(R.id.banner_overlay)
        val titulo = findViewById<TextView>(R.id.banner_titulo)
        val subtitulo = findViewById<TextView>(R.id.banner_subtitulo)

        if (banners == null || banners.length() == 0) {
            placeholder.visibility = View.VISIBLE
            imagem.visibility = View.GONE
            overlay.visibility = View.GONE
            return
        }

        exibirBanner(banners.getJSONObject(0))
    }

    private fun exibirBanner(banner: JSONObject) {
        val imagem = findViewById<ImageView>(R.id.banner_imagem)
        val placeholder = findViewById<TextView>(R.id.banner_placeholder)
        val overlay = findViewById<View>(R.id.banner_overlay)
        val titulo = findViewById<TextView>(R.id.banner_titulo)
        val subtitulo = findViewById<TextView>(R.id.banner_subtitulo)

        val imgBase64 = banner.optString("imagem", "")
        if (imgBase64.isNotEmpty() && imgBase64.contains("base64,")) {
            try {
                val dados = imgBase64.substringAfter("base64,")
                val bytes = Base64.decode(dados, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                imagem.setImageBitmap(bmp)
                imagem.visibility = View.VISIBLE
                placeholder.visibility = View.GONE
            } catch (_: Exception) {
                imagem.visibility = View.GONE
                placeholder.visibility = View.VISIBLE
            }
        }

        val tit = banner.optString("titulo", "")
        val sub = banner.optString("subtitulo", "")
        if (tit.isNotEmpty() || sub.isNotEmpty()) {
            titulo.text = tit
            subtitulo.text = sub
            overlay.visibility = View.VISIBLE
        } else {
            overlay.visibility = View.GONE
        }

        val pacote = banner.optString("pacote_alvo", "")
        val bannerContainer = findViewById<View>(R.id.banner_container)
        if (pacote.isNotEmpty()) {
            bannerContainer.isFocusable = true
            bannerContainer.setOnClickListener { abrirApp(pacote) }
        }
    }

    private fun iniciarBannerRotacao() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val banners = config?.optJSONArray("banners")
                if (banners != null && banners.length() > 1) {
                    bannerIndex = (bannerIndex + 1) % banners.length()
                    exibirBanner(banners.getJSONObject(bannerIndex))
                }
                handler.postDelayed(this, 8000)
            }
        }, 8000)
    }

    private fun iniciarRelogio() {
        val fmt = SimpleDateFormat("HH:mm", Locale.ROOT)
        val fmtData = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
        val relogio = findViewById<TextView>(R.id.relogio)
        val data = findViewById<TextView>(R.id.data)

        handler.post(object : Runnable {
            override fun run() {
                val agora = Date()
                relogio.text = fmt.format(agora)
                data.text = fmtData.format(agora)
                handler.postDelayed(this, 30_000)
            }
        })
    }

    private fun filtrarApps(categoriaId: Int) {
        val grid = findViewById<RecyclerView>(R.id.apps_grid)
        val apps = config?.optJSONArray("apps") ?: return
        val lista = mutableListOf<JSONObject>()

        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            if (app.optString("tipo") == "atalho") continue
            if (categoriaId == -1 || app.optInt("categoria_id", -1) == categoriaId) {
                lista.add(app)
            }
        }

        grid.adapter = AppAdapter(this, lista, config)
    }

    private fun abrirApp(pacote: String) {
        if (pacote.isEmpty()) return
        try {
            val intent = packageManager.getLaunchIntentForPackage(pacote)
            if (intent != null) {
                startActivity(intent)
            } else {
                Registro.linha("app nao encontrado: $pacote")
            }
        } catch (e: Exception) {
            Registro.linha("erro ao abrir $pacote: ${e.message}")
        }
    }

    private fun adicionarLetraFallback(parent: LinearLayout, app: JSONObject, cfg: JSONObject?) {
        val destaque = cfg?.optJSONObject("cores")?.optString("destaque", "#00E5FF") ?: "#00E5FF"
        val tv = TextView(this).apply {
            val nome = app.optString("nome", "?")
            text = nome.take(1).uppercase()
            setTextColor(Color.BLACK)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor(destaque))
                cornerRadius = dp(6).toFloat()
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        parent.addView(tv)
    }

    private fun adicionarIconeDoSistema(parent: LinearLayout, app: JSONObject) {
        val pacote = app.optString("pacote", "")
        try {
            val icon = packageManager.getApplicationIcon(pacote)
            val iv = ImageView(this).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            parent.addView(iv)
        } catch (_: Exception) {
            adicionarLetraFallback(parent, app, config)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun carregarConfig(): JSONObject? {
        val prefs = getSharedPreferences("hubtv_launcher", MODE_PRIVATE)
        val json = prefs.getString("config", null) ?: return null
        return try { JSONObject(json) } catch (_: Exception) { null }
    }

    companion object {
        fun salvarConfig(context: Context, config: JSONObject) {
            context.getSharedPreferences("hubtv_launcher", Context.MODE_PRIVATE)
                .edit()
                .putString("config", config.toString())
                .apply()
        }
    }

    // -----------------------------------------------------------------

    class AppAdapter(
        private val ctx: LauncherActivity,
        private val apps: List<JSONObject>,
        private val cfg: JSONObject?
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icone: ImageView = v.findViewById(R.id.app_icone)
            val letra: TextView = v.findViewById(R.id.app_icone_letra)
            val nome: TextView = v.findViewById(R.id.app_nome)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return VH(v)
        }

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            holder.nome.text = app.optString("nome", "App")

            val iconeBase64 = app.optString("icone", "")
            if (iconeBase64.isNotEmpty() && iconeBase64.contains("base64,")) {
                try {
                    val dados = iconeBase64.substringAfter("base64,")
                    val bytes = Base64.decode(dados, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    holder.icone.setImageBitmap(bmp)
                    holder.icone.visibility = View.VISIBLE
                    holder.letra.visibility = View.GONE
                } catch (_: Exception) {
                    mostrarLetra(holder, app)
                }
            } else {
                val pacote = app.optString("pacote", "")
                try {
                    val icon = ctx.packageManager.getApplicationIcon(pacote)
                    holder.icone.setImageDrawable(icon)
                    holder.icone.visibility = View.VISIBLE
                    holder.letra.visibility = View.GONE
                } catch (_: Exception) {
                    mostrarLetra(holder, app)
                }
            }

            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundResource(
                    if (hasFocus) R.drawable.app_card_bg_focused
                    else R.drawable.app_card_bg
                )
                if (hasFocus) v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                else v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }

            val pacote = app.optString("pacote", "")
            holder.itemView.setOnClickListener { ctx.abrirApp(pacote) }
        }

        private fun mostrarLetra(holder: VH, app: JSONObject) {
            holder.icone.visibility = View.GONE
            holder.letra.visibility = View.VISIBLE
            val nome = app.optString("nome", "?")
            holder.letra.text = nome.take(1).uppercase()
            val destaque = cfg?.optJSONObject("cores")?.optString("destaque", "#00E5FF") ?: "#00E5FF"
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor(destaque))
                cornerRadius = ctx.dp(10).toFloat()
            }
            holder.letra.background = bg
        }
    }
}
