package com.hubtv.agent

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

        if (!adbJaConfigurado()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_launcher)
        config = carregarConfig()
        aplicarConfig()
        iniciarRelogio()
        iniciarBannerRotacao()
        verificarAtivacao()
        verificarNotificacoes()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        atualizarDoServidor()
    }

    override fun onResume() {
        super.onResume()
        if (!adbJaConfigurado()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        config = carregarConfig()
        aplicarConfig()
        verificarAtivacao()
    }

    private fun atualizarDoServidor() {
        if (!Config.inscrito(this)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CheckIn.pulso(this@LauncherActivity)
            } catch (_: Exception) {}
            handler.post {
                config = carregarConfig()
                aplicarConfig()
                verificarAtivacao()
                verificarNotificacoes()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            abrirConfigAdb()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
    }

    private fun adbJaConfigurado(): Boolean {
        val arquivo = java.io.File(filesDir, "adb_key.pk8")
        val jaConectou = getSharedPreferences("hubtv_agente", MODE_PRIVATE)
            .getBoolean("adb_configurado", false)
        return arquivo.exists() && jaConectou
    }

    private fun abrirConfigAdb() {
        val senha = config?.optString("senha_config", "") ?: ""
        if (senha.isNotEmpty()) {
            pedirSenha(senha) {
                startActivity(Intent(this, MainActivity::class.java))
            }
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    // --- Ativacao ---

    private fun verificarAtivacao() {
        val prefs = getSharedPreferences("hubtv_agente", MODE_PRIVATE)
        val ativado = prefs.getBoolean("ativado", false)
        val telaAtivacao = findViewById<View>(R.id.tela_ativacao)
        val codigoView = findViewById<TextView>(R.id.codigo_ativacao_display)
        val statusView = findViewById<TextView>(R.id.ativacao_status_texto)

        if (!ativado) {
            telaAtivacao.visibility = View.VISIBLE
            codigoView.text = Config.codigoAtivacao(this)
            statusView.text = "Aguardando ativacao pelo revendedor..."

            if (!Config.inscrito(this)) {
                CoroutineScope(Dispatchers.IO).launch {
                    try { CheckIn.pulso(this@LauncherActivity) } catch (_: Exception) {}
                    handler.post { verificarAtivacao() }
                }
            }
        } else {
            telaAtivacao.visibility = View.GONE
            val expiraEm = prefs.getString("expira_em", "")
            if (!expiraEm.isNullOrBlank()) {
                Registro.linha("licenca ativa ate $expiraEm")
            }
        }
    }

    // --- Notificacoes ---

    private fun verificarNotificacoes() {
        val notificacoes = CheckIn.lerNotificacoes(this)
        if (notificacoes.length() == 0) return

        val n = notificacoes.getJSONObject(0)
        val titulo = n.optString("titulo", "Notificacao")
        val mensagem = n.optString("mensagem", "")
        val id = n.optInt("id", 0)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(titulo)
            .setMessage(mensagem)
            .setPositiveButton("OK") { _, _ ->
                if (id > 0) {
                    CoroutineScope(Dispatchers.IO).launch {
                        CheckIn.marcarLida(this@LauncherActivity, id)
                    }
                }
                val restantes = org.json.JSONArray()
                for (i in 1 until notificacoes.length()) {
                    restantes.put(notificacoes.getJSONObject(i))
                }
                if (restantes.length() > 0) {
                    getSharedPreferences("hubtv_notificacoes", MODE_PRIVATE)
                        .edit().putString("pendentes", restantes.toString()).apply()
                    handler.postDelayed({ verificarNotificacoes() }, 500)
                } else {
                    CheckIn.limparNotificacoes(this)
                }
            }
            .setCancelable(false)
            .show()
    }

    // --- Config ---

    private fun aplicarConfig() {
        val cfg = config
        val nome = cfg?.optString("nome", "HUB TV") ?: "HUB TV"

        findViewById<TextView>(R.id.nome_launcher).text = nome
        findViewById<TextView>(R.id.banner_placeholder).text = nome

        aplicarCores(cfg)
        aplicarFundo(cfg)
        aplicarLogo(cfg)
        aplicarRelogio(cfg)
        aplicarAlturaBanner(cfg)
        montarAtalhosDoSistema(cfg)
        montarApps(cfg)
        montarAtalhosApps(cfg)
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

    private fun aplicarFundo(cfg: JSONObject?) {
        val fundoBase64 = cfg?.optString("fundo", "") ?: ""
        val fundoView = findViewById<ImageView>(R.id.fundo_imagem)

        if (fundoBase64.isNotEmpty() && fundoBase64.contains("base64,")) {
            try {
                val dados = fundoBase64.substringAfter("base64,")
                val bytes = Base64.decode(dados, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                fundoView.setImageBitmap(bitmap)
                fundoView.visibility = View.VISIBLE
                findViewById<View>(R.id.fundo_gradiente).visibility = View.GONE
            } catch (_: Exception) {
                fundoView.visibility = View.GONE
            }
        } else {
            fundoView.visibility = View.GONE
            findViewById<View>(R.id.fundo_gradiente).visibility = View.VISIBLE
        }
    }

    private fun aplicarLogo(cfg: JSONObject?) {
        val logoBase64 = cfg?.optString("logo", "") ?: ""
        val logoView = findViewById<ImageView>(R.id.logo)
        val nomeView = findViewById<TextView>(R.id.nome_launcher)
        val posicao = cfg?.optString("posicao_logo", "canto") ?: "canto"

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

        if (posicao == "centro") {
            nomeView.gravity = android.view.Gravity.CENTER
        } else {
            nomeView.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
    }

    private fun aplicarRelogio(cfg: JSONObject?) {
        val mostrar = cfg?.optBoolean("mostrar_relogio", true) ?: true
        findViewById<View>(R.id.relogio_container).visibility =
            if (mostrar) View.VISIBLE else View.GONE
    }

    private fun aplicarAlturaBanner(cfg: JSONObject?) {
        val altura = cfg?.optInt("altura_banner", 160) ?: 160
        val banner = findViewById<View>(R.id.banner_container)
        val params = banner.layoutParams
        params.height = dp(altura)
        banner.layoutParams = params
    }

    // --- Atalhos do sistema (WiFi, Bluetooth, Config) com icones SVG ---

    private val ICONE_MAP = mapOf(
        "wifi" to R.drawable.ic_wifi,
        "bluetooth" to R.drawable.ic_bluetooth,
        "config" to R.drawable.ic_settings,
        "settings" to R.drawable.ic_settings,
        "wrench" to R.drawable.ic_wrench
    )

    private fun resolverIconeDrawable(iconeStr: String, intent: String): Int? {
        val lower = iconeStr.lowercase()
        for ((key, res) in ICONE_MAP) {
            if (lower.contains(key)) return res
        }
        if (intent.contains("WIFI")) return R.drawable.ic_wifi
        if (intent.contains("BLUETOOTH")) return R.drawable.ic_bluetooth
        return R.drawable.ic_settings
    }

    private fun montarAtalhosDoSistema(cfg: JSONObject?) {
        val container = findViewById<LinearLayout>(R.id.sistema_atalhos)
        container.removeAllViews()

        val atalhos = cfg?.optJSONArray("atalhos_sistema")

        data class AtalhoSistema(val icone: String, val nome: String, val intent: String, val senha: Boolean)

        val lista = if (atalhos != null && atalhos.length() > 0) {
            (0 until atalhos.length()).map { i ->
                val a = atalhos.getJSONObject(i)
                AtalhoSistema(
                    a.optString("icone", "settings"),
                    a.optString("nome", "Config"),
                    a.optString("intent", "android.settings.SETTINGS"),
                    a.optBoolean("senha", false)
                )
            }
        } else {
            listOf(
                AtalhoSistema("wifi", "WiFi", "android.settings.WIFI_SETTINGS", false),
                AtalhoSistema("bluetooth", "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, false),
                AtalhoSistema("settings", "Config", "android.settings.SETTINGS", true)
            )
        }

        val senhaGlobal = cfg?.optString("senha_config", "") ?: ""

        for (atalho in lista) {
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundResource(R.drawable.atalho_bg)
                isFocusable = true
                isFocusableInTouchMode = true
                alpha = 0.8f

                setOnFocusChangeListener { v, hasFocus ->
                    v.alpha = if (hasFocus) 1f else 0.8f
                    if (hasFocus) v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start()
                    else v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }

                setOnClickListener {
                    val acao = {
                        try {
                            startActivity(Intent(atalho.intent))
                        } catch (_: Exception) {
                            try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                            catch (_: Exception) {}
                        }
                    }

                    if (atalho.senha && senhaGlobal.isNotEmpty()) {
                        pedirSenha(senhaGlobal, acao)
                    } else {
                        acao()
                    }
                }
            }

            val iconRes = resolverIconeDrawable(atalho.icone, atalho.intent)
            if (iconRes != null) {
                val iv = ImageView(this).apply {
                    setImageDrawable(ContextCompat.getDrawable(context, iconRes))
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                btn.addView(iv)
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, dp(6), 0)
            container.addView(btn, params)
        }

        val btnAgente = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            alpha = 0.4f
            isFocusable = true
            isFocusableInTouchMode = true

            setOnFocusChangeListener { v, hasFocus ->
                v.alpha = if (hasFocus) 0.9f else 0.4f
            }
            setOnClickListener { abrirConfigAdb() }
        }
        val ivWrench = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_wrench))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        btnAgente.addView(ivWrench)

        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(dp(12), 0, 0, 0)
        container.addView(btnAgente, p)
    }

    private fun pedirSenha(senhaCorreta: String, aoSucesso: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Senha"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Acesso protegido")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == senhaCorreta) {
                    aoSucesso()
                } else {
                    Toast.makeText(this, "Senha incorreta", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- Apps (grid de largura total, sem categorias) ---

    private fun montarApps(cfg: JSONObject?) {
        val grid = findViewById<RecyclerView>(R.id.apps_grid)
        grid.layoutManager = GridLayoutManager(this, 5)

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

    // --- Atalhos de apps (barra inferior) ---

    private fun montarAtalhosApps(cfg: JSONObject?) {
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
                alpha = 0.7f

                setOnFocusChangeListener { v, hasFocus ->
                    v.alpha = if (hasFocus) 1f else 0.7f
                    if (hasFocus) v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(100).start()
                    else v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }

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
                    adicionarIconeDoSistema(view, app)
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

    // --- Banners ---

    private fun montarBanner(cfg: JSONObject?) {
        val banners = cfg?.optJSONArray("banners")
        val placeholder = findViewById<TextView>(R.id.banner_placeholder)
        val imagem = findViewById<ImageView>(R.id.banner_imagem)
        val overlay = findViewById<View>(R.id.banner_overlay)

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

    fun abrirApp(pacote: String) {
        if (pacote.isEmpty()) return

        if (bloqueado) {
            Toast.makeText(this, "Dispositivo bloqueado", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = packageManager.getLaunchIntentForPackage(pacote)
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "App nao instalado: $pacote", Toast.LENGTH_SHORT).show()
                Registro.linha("app nao encontrado: $pacote")
            }
        } catch (e: Exception) {
            Registro.linha("erro ao abrir $pacote: ${e.message}")
        }
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
            adicionarLetraFallback(parent, app)
        }
    }

    private fun adicionarLetraFallback(parent: LinearLayout, app: JSONObject) {
        val destaque = config?.optJSONObject("cores")?.optString("destaque", "#00E5FF") ?: "#00E5FF"
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

        fun marcarAdbConfigurado(context: Context) {
            context.getSharedPreferences("hubtv_agente", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("adb_configurado", true)
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
                    mostrarIconeSistema(holder, app)
                }
            } else {
                mostrarIconeSistema(holder, app)
            }

            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundResource(
                    if (hasFocus) R.drawable.app_card_bg_focused else R.drawable.app_card_bg
                )
                if (hasFocus) v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                else v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }

            val pacote = app.optString("pacote", "")
            holder.itemView.setOnClickListener { ctx.abrirApp(pacote) }
        }

        private fun mostrarIconeSistema(holder: VH, app: JSONObject) {
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
