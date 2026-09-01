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
import androidx.recyclerview.widget.LinearLayoutManager
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
    /** qual das duas ImageViews do banner esta na frente (crossfade) */
    private var bannerNoA = false
    private var rotacaoBanner: Runnable? = null
    private var instalando = false

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
        verificarAtivacao()
        verificarNotificacoes()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
                CheckIn.sincronizar(this@LauncherActivity)
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
        // INFO (ou o botao amarelo) abre o diagnostico direto, sem passar
        // pela tela de config — e o caminho rapido para ver se os comandos
        // do painel estao chegando neste aparelho.
        if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_PROG_YELLOW) {
            startActivity(Intent(this, DiagnosticoActivity::class.java))
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
        val senha = config?.texto("senha_config", "") ?: ""
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
                    try { CheckIn.sincronizar(this@LauncherActivity) } catch (_: Exception) {}
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
        val titulo = n.texto("titulo", "Notificacao")
        val mensagem = n.texto("mensagem", "")
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
        val nome = cfg?.texto("nome", "HUB TV") ?: "HUB TV"

        findViewById<TextView>(R.id.nome_launcher).text = nome
        findViewById<TextView>(R.id.banner_placeholder).text = nome

        aplicarCores(cfg)
        aplicarFundo(cfg)
        aplicarLogo(cfg)
        aplicarRelogio(cfg)
        aplicarTamanhoBanner(cfg)
        montarAtalhosDoSistema(cfg)
        reposicionarTopbar(cfg)
        montarApps(cfg)
        montarDestaques(cfg)
        montarAtalhosApps(cfg)
        montarBanner(cfg)

        val prefs = getSharedPreferences("hubtv_agente", MODE_PRIVATE)
        bloqueado = prefs.getBoolean("bloqueado", false)
        montarTelaBloqueio(cfg, prefs.getString("expira_em", "") ?: "")

        val conectado = try { Adb.conectado(this) } catch (_: Exception) { false }
        val indicador = findViewById<View>(R.id.indicador_status)
        val drawable = indicador.background
        if (drawable is GradientDrawable) {
            drawable.setColor(if (conectado) Color.parseColor("#34D399") else Color.parseColor("#F87171"))
        }
    }

    private fun aplicarCores(cfg: JSONObject?) {
        val cores = cfg?.optJSONObject("cores")
        val primaria = cores?.texto("primaria", "#1a237e") ?: "#1a237e"
        val secundaria = cores?.texto("secundaria", "#0d47a1") ?: "#0d47a1"

        try {
            val gradiente = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primaria), Color.parseColor(secundaria))
            )
            findViewById<View>(R.id.fundo_gradiente).background = gradiente
        } catch (_: Exception) {}
    }

    private fun aplicarFundo(cfg: JSONObject?) {
        val fundoBase64 = cfg?.texto("fundo", "") ?: ""
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
        val logoView = findViewById<ImageView>(R.id.logo)
        val nomeView = findViewById<TextView>(R.id.nome_launcher)

        val logo = decodificarBase64(cfg?.texto("logo", "") ?: "")
        if (logo != null) {
            logoView.setImageBitmap(logo)
            logoView.visibility = View.VISIBLE
        } else {
            logoView.visibility = View.GONE
        }

        // O painel ja resolveu a regra: no modo automatico o nome escrito
        // some quando existe uma logo, para nao duplicar a marca.
        val exibirNome = cfg?.optBoolean("exibir_nome", true) ?: true
        nomeView.visibility = if (exibirNome) View.VISIBLE else View.GONE
        (nomeView.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginStart = if (logoView.visibility == View.VISIBLE) dp(10) else 0
            nomeView.layoutParams = it
        }
    }

    /**
     * Move cada bloco da barra superior (identidade, atalhos do sistema e
     * relogio) para o slot escolhido no painel. Blocos que caem no mesmo
     * slot ficam lado a lado, nesta ordem.
     */
    private fun reposicionarTopbar(cfg: JSONObject?) {
        val slots = mapOf(
            "esquerda" to findViewById<LinearLayout>(R.id.slot_esquerda),
            "centro" to findViewById<LinearLayout>(R.id.slot_centro),
            "direita" to findViewById<LinearLayout>(R.id.slot_direita)
        )

        fun mover(bloco: View, posicao: String, margem: Int) {
            val destino = slots[posicao] ?: slots.getValue("esquerda")
            (bloco.parent as? ViewGroup)?.removeView(bloco)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // so afasta do vizinho se ja houver alguem no slot
            lp.marginStart = if (destino.childCount > 0) dp(margem) else 0
            bloco.layoutParams = lp
            destino.addView(bloco)
        }

        mover(findViewById(R.id.bloco_identidade), posicaoValida(cfg, "posicao_logo", "esquerda"), 0)
        mover(findViewById(R.id.sistema_atalhos), posicaoValida(cfg, "posicao_atalhos", "esquerda"), 16)
        mover(findViewById(R.id.relogio_container), posicaoValida(cfg, "posicao_relogio", "direita"), 16)
    }

    /** "canto" e o valor legado do painel antigo e equivale a esquerda. */
    private fun posicaoValida(cfg: JSONObject?, chave: String, padrao: String): String =
        when (cfg?.texto(chave, padrao) ?: padrao) {
            "centro" -> "centro"
            "direita" -> "direita"
            else -> "esquerda"
        }

    private fun decodificarBase64(dataUri: String): android.graphics.Bitmap? {
        if (dataUri.isEmpty() || !dataUri.contains("base64,")) return null
        return try {
            val bytes = Base64.decode(dataUri.substringAfter("base64,"), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun aplicarRelogio(cfg: JSONObject?) {
        val mostrar = cfg?.optBoolean("mostrar_relogio", true) ?: true
        findViewById<View>(R.id.relogio_container).visibility =
            if (mostrar) View.VISIBLE else View.GONE
    }

    /**
     * Banner com tamanho FIXO em dp — sem proporcao. O painel recorta a arte
     * exatamente nessa medida, entao a imagem nunca estica nem achata.
     */
    private fun aplicarTamanhoBanner(cfg: JSONObject?) {
        val larguraPedida = (cfg?.optInt("largura_banner", 480) ?: 480).coerceIn(200, 1920)
        val alturaPedida = (cfg?.optInt("altura_banner", 160) ?: 160).coerceIn(80, 800)

        val telaLargura = resources.displayMetrics.widthPixels
        val telaAltura = resources.displayMetrics.heightPixels

        // Travas de seguranca: um banner configurado grande demais espremia a
        // fileira de apps a ponto de sumir com os nomes. Sobra sempre metade
        // da tela para o resto, e espaco lateral para a coluna de destaques.
        val alturaMax = (telaAltura * 0.45f).toInt()
        val larguraMax = telaLargura - dp(40) - dp(230)

        val banner = findViewById<View>(R.id.banner_container)
        val params = banner.layoutParams
        params.width = minOf(dp(larguraPedida), maxOf(larguraMax, dp(200)))
        params.height = minOf(dp(alturaPedida), alturaMax)
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
                    a.texto("icone", "settings"),
                    a.texto("nome", "Config"),
                    a.texto("intent", "android.settings.SETTINGS"),
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

        val senhaGlobal = cfg?.texto("senha_config", "") ?: ""

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

    // --- Tela de bloqueio (licenca vencida) ---

    private fun montarTelaBloqueio(cfg: JSONObject?, expiraEm: String) {
        val tela = findViewById<View>(R.id.tela_bloqueio)
        tela.visibility = if (bloqueado) View.VISIBLE else View.GONE
        if (!bloqueado) return

        // A mesma logo do topo, para o cliente reconhecer de quem comprou.
        val logoView = findViewById<ImageView>(R.id.bloqueio_logo)
        val logo = decodificarBase64(cfg?.texto("logo") ?: "")
        if (logo != null) {
            logoView.setImageBitmap(logo)
            logoView.visibility = View.VISIBLE
        } else {
            logoView.visibility = View.GONE
        }

        val mensagem = cfg?.texto("mensagem_bloqueio") ?: ""
        if (mensagem.isNotEmpty()) {
            findViewById<TextView>(R.id.bloqueio_mensagem).text = mensagem
        }

        val contato = cfg?.texto("contato_suporte") ?: ""
        val card = findViewById<View>(R.id.bloqueio_contato_card)
        if (contato.isNotEmpty()) {
            findViewById<TextView>(R.id.bloqueio_contato).text = contato
            card.visibility = View.VISIBLE
        } else {
            card.visibility = View.GONE
        }

        val venceu = findViewById<TextView>(R.id.bloqueio_venceu_em)
        val data = dataBonita(expiraEm)
        if (data.isNotEmpty()) {
            venceu.text = "Assinatura vencida em $data"
            venceu.visibility = View.VISIBLE
        } else {
            venceu.visibility = View.GONE
        }
    }

    /** "2026-09-01" -> "01/09/2026". Devolve vazio se nao der para ler. */
    private fun dataBonita(iso: String): String {
        if (iso.isBlank()) return ""
        val p = iso.take(10).split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
    }

    // --- Apps (carrossel horizontal, sem categorias) ---

    private fun montarApps(cfg: JSONObject?) {
        val grid = findViewById<RecyclerView>(R.id.apps_grid)
        // Fileira horizontal: com muitos apps a lista rola para a direita
        // conforme o foco anda, em vez de espremer tudo numa grade fixa.
        grid.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        val apps = cfg?.optJSONArray("apps") ?: return
        val lista = mutableListOf<JSONObject>()

        // "atalho" vai para a barra de baixo e "destaque" para o lado do
        // banner — no carrossel fica so o tipo "app".
        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            val tipo = app.texto("tipo", "app")
            if (tipo != "atalho" && tipo != "destaque") {
                lista.add(app)
            }
        }

        grid.adapter = AppAdapter(this, lista, cfg)
    }

    // --- Destaques (ate 2, empilhados ao lado do banner) ---

    private fun montarDestaques(cfg: JSONObject?) {
        val container = findViewById<LinearLayout>(R.id.destaques_container)
        container.removeAllViews()

        val apps = cfg?.optJSONArray("apps") ?: return
        val destaques = mutableListOf<JSONObject>()
        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            if (app.texto("tipo", "app") == "destaque") destaques.add(app)
        }

        val lista = destaques.take(2)
        container.visibility = if (lista.isEmpty()) View.GONE else View.VISIBLE
        if (lista.isEmpty()) return

        for ((indice, app) in lista.withIndex()) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundResource(R.drawable.destaque_bg)
                isFocusable = true
                isFocusableInTouchMode = true

                setOnFocusChangeListener { v, tem ->
                    v.setBackgroundResource(
                        if (tem) R.drawable.destaque_bg_focused else R.drawable.destaque_bg
                    )
                }
                setOnClickListener { abrirApp(app) }
            }

            card.addView(iconeDoApp(app, dp(44), 20f))

            val textos = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(12)
                layoutParams = lp
            }
            textos.addView(TextView(this).apply {
                text = app.texto("nome", "App")
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textos.addView(TextView(this).apply {
                text = if (instalado(app.texto("pacote"))) "Abrir" else "Instalar"
                setTextColor(Color.parseColor("#80FFFFFF"))
                textSize = 12f
            })
            card.addView(textos)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            lp.topMargin = if (indice > 0) dp(12) else 0
            container.addView(card, lp)
        }
    }

    private fun instalado(pacote: String): Boolean = try {
        pacote.isNotEmpty() && packageManager.getLaunchIntentForPackage(pacote) != null
    } catch (_: Exception) {
        false
    }

    /**
     * Icone do app na ordem: imagem do painel > icone instalado no aparelho >
     * circulo neutro com a inicial. Nunca um quadrado colorido.
     */
    private fun iconeDoApp(app: JSONObject, lado: Int, tamanhoLetra: Float): View {
        val bmp = decodificarBase64(app.texto("icone"))
        if (bmp != null) {
            return ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(lado, lado)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }

        try {
            val doSistema = packageManager.getApplicationIcon(app.texto("pacote"))
            return ImageView(this).apply {
                setImageDrawable(doSistema)
                layoutParams = LinearLayout.LayoutParams(lado, lado)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        } catch (_: Exception) {
        }

        return TextView(this).apply {
            text = app.texto("nome", "?").take(1).uppercase()
            setTextColor(Color.WHITE)
            textSize = tamanhoLetra
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(lado, lado)
        }
    }

    // --- Atalhos de apps (barra inferior) ---

    private fun montarAtalhosApps(cfg: JSONObject?) {
        val container = findViewById<LinearLayout>(R.id.atalhos_container)
        container.removeAllViews()

        val apps = cfg?.optJSONArray("apps") ?: return

        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            if (app.texto("tipo") != "atalho") continue

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

                setOnClickListener { abrirApp(app) }
            }

            val iconeBase64 = app.texto("icone", "")
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
                text = app.texto("nome", "App")
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
        val a = findViewById<ImageView>(R.id.banner_imagem_a)
        val b = findViewById<ImageView>(R.id.banner_imagem_b)
        val overlay = findViewById<View>(R.id.banner_overlay)
        val pontos = findViewById<LinearLayout>(R.id.banner_pontos)

        // Vitrine pura: o banner nao abre app nenhum e nao pega foco.
        findViewById<View>(R.id.banner_container).apply {
            isFocusable = false
            isClickable = false
            setOnClickListener(null)
        }

        rotacaoBanner?.let { handler.removeCallbacks(it) }
        rotacaoBanner = null

        if (banners == null || banners.length() == 0) {
            placeholder.visibility = View.VISIBLE
            a.visibility = View.GONE
            b.visibility = View.GONE
            overlay.visibility = View.GONE
            pontos.visibility = View.GONE
            return
        }

        placeholder.visibility = View.GONE
        bannerIndex = 0
        bannerNoA = false
        montarPontos(banners.length())
        exibirBanner(banners.getJSONObject(0), animar = false)
        iniciarRotacaoBanner(cfg)
    }

    /** bolinhas indicando quantos banners existem e qual esta na tela */
    private fun montarPontos(quantidade: Int) {
        val pontos = findViewById<LinearLayout>(R.id.banner_pontos)
        pontos.removeAllViews()

        if (quantidade <= 1) {
            pontos.visibility = View.GONE
            return
        }

        pontos.visibility = View.VISIBLE
        repeat(quantidade) {
            val ponto = View(this)
            val lp = LinearLayout.LayoutParams(dp(6), dp(6))
            lp.marginStart = if (it > 0) dp(5) else 0
            ponto.layoutParams = lp
            pontos.addView(ponto)
        }
        pintarPontos()
    }

    private fun pintarPontos() {
        val pontos = findViewById<LinearLayout>(R.id.banner_pontos)
        val destaque = try {
            Color.parseColor(config?.optJSONObject("cores")?.texto("destaque", "#00E5FF") ?: "#00E5FF")
        } catch (_: Exception) {
            Color.parseColor("#00E5FF")
        }

        for (i in 0 until pontos.childCount) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == bannerIndex) destaque else Color.parseColor("#60FFFFFF"))
            }
            pontos.getChildAt(i).background = bg
        }
    }

    /**
     * Troca o banner com crossfade entre duas ImageViews sobrepostas — na TV
     * nao ha deslizar com o dedo, entao a transicao e por tempo.
     */
    private fun exibirBanner(banner: JSONObject, animar: Boolean) {
        val a = findViewById<ImageView>(R.id.banner_imagem_a)
        val b = findViewById<ImageView>(R.id.banner_imagem_b)
        val placeholder = findViewById<TextView>(R.id.banner_placeholder)
        val overlay = findViewById<View>(R.id.banner_overlay)
        val titulo = findViewById<TextView>(R.id.banner_titulo)
        val subtitulo = findViewById<TextView>(R.id.banner_subtitulo)

        val bmp = decodificarBase64(banner.texto("imagem", ""))
        if (bmp == null) {
            placeholder.visibility = View.VISIBLE
            return
        }

        val entra = if (bannerNoA) b else a
        val sai = if (bannerNoA) a else b

        entra.setImageBitmap(bmp)
        entra.alpha = if (animar) 0f else 1f
        entra.visibility = View.VISIBLE
        entra.bringToFront()

        if (animar) {
            entra.animate().alpha(1f).setDuration(450).start()
            sai.animate().alpha(0f).setDuration(450)
                .withEndAction { sai.visibility = View.GONE }.start()
        } else {
            sai.visibility = View.GONE
        }
        bannerNoA = !bannerNoA

        val tit = banner.texto("titulo", "")
        val sub = banner.texto("subtitulo", "")
        if (tit.isNotEmpty() || sub.isNotEmpty()) {
            titulo.text = tit
            subtitulo.text = sub
            overlay.visibility = View.VISIBLE
            overlay.bringToFront()
        } else {
            overlay.visibility = View.GONE
        }

        findViewById<LinearLayout>(R.id.banner_pontos).bringToFront()
        pintarPontos()
    }

    private fun iniciarRotacaoBanner(cfg: JSONObject?) {
        val banners = cfg?.optJSONArray("banners") ?: return
        if (banners.length() <= 1) return

        val intervalo = ((cfg?.optInt("banner_intervalo", 8) ?: 8).coerceIn(3, 60)) * 1000L
        val tarefa = object : Runnable {
            override fun run() {
                val lista = config?.optJSONArray("banners")
                if (lista != null && lista.length() > 1) {
                    bannerIndex = (bannerIndex + 1) % lista.length()
                    exibirBanner(lista.getJSONObject(bannerIndex), animar = true)
                }
                handler.postDelayed(this, intervalo)
            }
        }
        rotacaoBanner = tarefa
        handler.postDelayed(tarefa, intervalo)
    }

    private fun iniciarRelogio() {
        val fmt = SimpleDateFormat("HH:mm", Locale.ROOT)
        val relogio = findViewById<TextView>(R.id.relogio)
        val data = findViewById<TextView>(R.id.data)

        val prefs = getSharedPreferences("hubtv_agente", MODE_PRIVATE)
        val expiraEm = prefs.getString("expira_em", "") ?: ""

        if (expiraEm.isNotBlank()) {
            try {
                val partes = expiraEm.split("-")
                data.text = "Venc: ${partes[2]}/${partes[1]}/${partes[0]}"
            } catch (_: Exception) {
                data.text = "Venc: $expiraEm"
            }
        } else {
            data.text = "Sem licenca"
        }

        handler.post(object : Runnable {
            override fun run() {
                relogio.text = fmt.format(Date())
                handler.postDelayed(this, 30_000)
            }
        })
    }

    /**
     * Abre o app. Se ele ainda nao estiver no aparelho e o painel tiver
     * mandado um `apk_url`, baixa e instala na hora — e so entao abre. Era
     * esse o "app nao instalado" que aparecia mesmo com o APK no servidor.
     */
    fun abrirApp(app: JSONObject) {
        val pacote = app.texto("pacote")
        if (pacote.isEmpty()) return

        if (bloqueado) {
            Toast.makeText(this, "Dispositivo bloqueado", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = try { packageManager.getLaunchIntentForPackage(pacote) } catch (_: Exception) { null }
        if (intent != null) {
            startActivity(intent)
            return
        }

        val apkUrl = app.texto("apk_url")
        if (apkUrl.isEmpty()) {
            Toast.makeText(this, "App nao instalado e sem APK no painel", Toast.LENGTH_LONG).show()
            Registro.linha("app sem apk_url: $pacote")
            return
        }

        instalarEAbrir(app.texto("nome", "App"), pacote, apkUrl)
    }

    /** compatibilidade: abrir so pelo pacote, sem instalacao automatica */
    fun abrirApp(pacote: String) {
        abrirApp(JSONObject().put("pacote", pacote))
    }

    private fun instalarEAbrir(nome: String, pacote: String, apkUrl: String) {
        if (instalando) {
            Toast.makeText(this, "Ja tem uma instalacao em andamento", Toast.LENGTH_SHORT).show()
            return
        }
        instalando = true

        val painel = findViewById<View>(R.id.tela_instalacao)
        val titulo = findViewById<TextView>(R.id.instalacao_titulo)
        val detalhe = findViewById<TextView>(R.id.instalacao_detalhe)
        val barra = findViewById<android.widget.ProgressBar>(R.id.instalacao_barra)

        titulo.text = "Instalando $nome"
        detalhe.text = "Baixando..."
        barra.isIndeterminate = true
        barra.progress = 0
        painel.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val r = Instalador.baixarEInstalar(this@LauncherActivity, apkUrl, "app_$pacote.apk") { pct ->
                handler.post {
                    if (pct >= 0) {
                        barra.isIndeterminate = false
                        barra.progress = pct
                        detalhe.text = "Baixando... $pct%"
                    } else {
                        detalhe.text = "Baixando..."
                    }
                }
            }

            // entra no Diagnostico igual aos comandos do painel, senao a
            // unica pista seria um Toast que some em 3 segundos
            Historico.registrar(
                this@LauncherActivity, 0, "instalar ($nome)",
                r is Instalador.Resultado.Ok,
                when (r) {
                    is Instalador.Resultado.Ok -> r.detalhe
                    is Instalador.Resultado.Falha -> r.motivo
                }
            )

            handler.post {
                when (r) {
                    is Instalador.Resultado.Ok -> {
                        detalhe.text = "Pronto! Abrindo..."
                        barra.isIndeterminate = false
                        barra.progress = 100
                        handler.postDelayed({
                            painel.visibility = View.GONE
                            instalando = false
                            val i = try { packageManager.getLaunchIntentForPackage(pacote) } catch (_: Exception) { null }
                            if (i != null) startActivity(i)
                            else Toast.makeText(this@LauncherActivity, "$nome instalado", Toast.LENGTH_SHORT).show()
                        }, 900)
                    }
                    is Instalador.Resultado.Falha -> {
                        painel.visibility = View.GONE
                        instalando = false
                        Toast.makeText(
                            this@LauncherActivity,
                            "Nao deu para instalar $nome: ${r.motivo}",
                            Toast.LENGTH_LONG
                        ).show()
                        Registro.linha("instalacao de $pacote falhou: ${r.motivo}")
                    }
                }
            }
        }
    }

    private fun adicionarIconeDoSistema(parent: LinearLayout, app: JSONObject) {
        val pacote = app.texto("pacote", "")
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
        parent.addView(TextView(this).apply {
            text = app.texto("nome", "?").take(1).uppercase()
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
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
            holder.nome.text = app.texto("nome", "App")

            val iconeBase64 = app.texto("icone", "")
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

            holder.itemView.setOnClickListener { ctx.abrirApp(app) }
        }

        private fun mostrarIconeSistema(holder: VH, app: JSONObject) {
            val pacote = app.texto("pacote", "")
            try {
                val icon = ctx.packageManager.getApplicationIcon(pacote)
                holder.icone.setImageDrawable(icon)
                holder.icone.visibility = View.VISIBLE
                holder.letra.visibility = View.GONE
            } catch (_: Exception) {
                mostrarLetra(holder, app)
            }
        }

        /** circulo discreto com a inicial — nada de quadrado colorido */
        private fun mostrarLetra(holder: VH, app: JSONObject) {
            holder.icone.visibility = View.GONE
            holder.letra.visibility = View.VISIBLE
            holder.letra.text = app.texto("nome", "?").take(1).uppercase()
            holder.letra.setTextColor(Color.WHITE)
            holder.letra.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
        }
    }
}
