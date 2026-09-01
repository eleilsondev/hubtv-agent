package com.hubtv.agent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hubtv.agent.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var v: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            v = ActivityMainBinding.inflate(layoutInflater)
            setContentView(v.root)
        } catch (e: Throwable) {
            mostrarErroSimples("Falha ao montar a tela:\n${e.stackTraceToString()}")
            return
        }

        try {
            configurar()
        } catch (e: Throwable) {
            Registro.linha("ERRO ao iniciar a tela: ${e.message}")
            Registro.linha(e.stackTraceToString())
        }
    }

    private fun configurar() {
        v.registro.text = Registro.tudo().joinToString("\n")
        Registro.observar { runOnUiThread { acrescentar(it) } }

        AgentApp.lerUltimoErro(this)?.let { erro ->
            Registro.linha("=== ERRO DA EXECUCAO ANTERIOR ===")
            erro.lines().take(12).forEach { Registro.linha("  $it") }
            Registro.linha("=== fim do erro anterior ===")
            AgentApp.limparUltimoErro(this)
        }

        v.btnLigarDepuracao.setOnClickListener { comProtecao {
            val ok = Adb.ligarDepuracaoSemFio(this)
            Registro.linha(
                if (ok) "depuracao sem fio ligada pela configuracao do sistema"
                else "nao consegui ligar - falta WRITE_SECURE_SETTINGS"
            )
            atualizarEstado()
        } }

        v.btnParear.setOnClickListener { comProtecao {
            val porta = v.campoPorta.text.toString().trim().toIntOrNull()
            val codigo = v.campoCodigo.text.toString().trim()
            when {
                porta == null -> Registro.linha("informe a porta de PAREAMENTO")
                codigo.length < 6 -> Registro.linha("o codigo tem 6 digitos")
                else -> lifecycleScope.launch {
                    ocupado(true)
                    when (val r = Adb.parear(this@MainActivity, porta, codigo)) {
                        is Adb.Resultado.Ok -> {
                            Registro.linha("agora conecte - a autorizacao ja vale")
                            Adb.conectar(this@MainActivity)
                            marcarAdbConfigurado()
                        }
                        is Adb.Resultado.Falha -> Registro.linha(r.motivo)
                    }
                    ocupado(false)
                    atualizarEstado()
                }
            }
        } }

        v.btnConectar.setOnClickListener { comProtecao {
            lifecycleScope.launch {
                ocupado(true)
                when (val r = Adb.conectar(this@MainActivity)) {
                    is Adb.Resultado.Ok -> {
                        Registro.linha("conexao estabelecida")
                        marcarAdbConfigurado()
                    }
                    is Adb.Resultado.Falha -> {
                        Registro.linha(r.motivo)
                        Registro.linha("se for a primeira vez, pareie antes (porta + codigo)")
                    }
                }
                ocupado(false)
                atualizarEstado()
            }
        } }

        v.btnTestar.setOnClickListener { comProtecao {
            lifecycleScope.launch {
                ocupado(true)
                testarPoderes()
                ocupado(false)
            }
        } }

        v.btnFixarPorta.setOnClickListener { comProtecao {
            lifecycleScope.launch {
                ocupado(true)
                when (val r = Adb.fixarPorta5555(this@MainActivity)) {
                    is Adb.Resultado.Ok -> {
                        Registro.linha("porta fixa: reconexao apos boot fica direta")
                        marcarAdbConfigurado()
                    }
                    is Adb.Resultado.Falha -> Registro.linha(r.motivo)
                }
                ocupado(false)
            }
        } }

        val codigoAtivacao = Config.codigoAtivacao(this)
        v.campoCodigoInscricao.setText(codigoAtivacao)
        v.campoCodigoInscricao.isEnabled = false

        v.btnInscrever.setOnClickListener { comProtecao {
            lifecycleScope.launch {
                ocupado(true)
                when (val r = CheckIn.sincronizar(this@MainActivity)) {
                    is CheckIn.Resultado.Ok -> {
                        Registro.linha("dispositivo registrado com sucesso")
                        atualizarEstadoInscricao()
                    }
                    is CheckIn.Resultado.Falha ->
                        Registro.linha("registro falhou: ${r.motivo}")
                }
                ocupado(false)
            }
        } }

        v.btnCheckin.setOnClickListener { comProtecao {
            lifecycleScope.launch {
                ocupado(true)
                if (!Config.configurado()) {
                    Registro.linha("configure Config.BASE_URL (VPS) antes de testar o check-in")
                } else if (!Config.inscrito(this@MainActivity)) {
                    Registro.linha("inscreva o dispositivo primeiro (digite o codigo)")
                } else {
                    when (val r = CheckIn.sincronizar(this@MainActivity)) {
                        is CheckIn.Resultado.Ok ->
                            Registro.linha("check-in enviado - painel respondeu ok")
                        is CheckIn.Resultado.Falha ->
                            Registro.linha("check-in falhou: ${r.motivo}")
                    }
                }
                ocupado(false)
            }
        } }

        v.btnLauncher.setOnClickListener { comProtecao {
            if (!adbConfigurado()) {
                Toast.makeText(this, "Configure o ADB primeiro (conecte e fixe a porta)", Toast.LENGTH_LONG).show()
                Registro.linha("ADB ainda nao configurado - conecte e fixe a porta antes")
            } else {
                marcarAdbConfigurado()
                val intent = Intent(this, LauncherActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            }
        } }

        v.btnLimpar.setOnClickListener {
            Registro.limpar()
            v.registro.text = ""
        }

        atualizarBotaoLauncher()
        atualizarEstadoInscricao()

        Registro.linha("app iniciado - preparando identidade em segundo plano...")
        Registro.linha("fluxo: 1) CONECTAR o ADB  2) FIXAR PORTA 5555  3) REGISTRAR  4) ENTRAR NO MODO LAUNCHER")
        Registro.linha("codigo de ativacao: $codigoAtivacao")
        if (Config.inscrito(this)) {
            Registro.linha("dispositivo ja registrado no painel")
        } else {
            Registro.linha("toque REGISTRAR para enviar o dispositivo ao painel")
        }

        lifecycleScope.launch {
            val nova = try {
                withContext(Dispatchers.IO) { AdbManager.get(this@MainActivity).identidadeNova }
            } catch (e: Throwable) {
                Registro.linha("ERRO ao preparar a identidade ADB:")
                Registro.linha(e.message ?: e.toString())
                Registro.linha(e.stackTraceToString().lines().take(8).joinToString("\n"))
                null
            }
            when (nova) {
                true  -> Registro.linha("identidade RSA criada - primeira execucao")
                false -> Registro.linha("identidade RSA carregada do disco")
                null  -> Registro.linha("identidade indisponivel - veja o erro acima")
            }
            atualizarEstado()
            try { AgentService.iniciar(this@MainActivity) }
            catch (e: Throwable) { Registro.linha("aviso: servico nao iniciou: ${e.message}") }
        }
    }

    override fun onResume() {
        super.onResume()
        comProtecao {
            atualizarEstado()
            atualizarBotaoLauncher()
        }
    }

    override fun onDestroy() {
        Registro.observar(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------

    private fun adbConfigurado(): Boolean {
        val arquivo = java.io.File(filesDir, "adb_key.pk8")
        val jaConectou = getSharedPreferences("hubtv_agente", MODE_PRIVATE)
            .getBoolean("adb_configurado", false)
        return arquivo.exists() && jaConectou
    }

    private fun marcarAdbConfigurado() {
        LauncherActivity.marcarAdbConfigurado(this)
        atualizarBotaoLauncher()
    }

    private fun atualizarEstadoInscricao() {
        val inscrito = Config.inscrito(this)
        v.campoCodigoInscricao.isEnabled = false
        v.btnInscrever.isEnabled = !inscrito
        v.btnInscrever.text = if (inscrito) "Registrado" else "Registrar"
        v.btnCheckin.isEnabled = inscrito
    }

    private fun atualizarBotaoLauncher() {
        val pronto = adbConfigurado()
        v.btnLauncher.isEnabled = true
        v.btnLauncher.text = if (pronto) "Entrar no modo Launcher" else "Entrar no modo Launcher (configure ADB primeiro)"
    }

    private suspend fun testarPoderes() {
        Registro.linha("--- teste de poderes de shell ---")
        val comandos = listOf(
            "modelo" to "getprop ro.product.model",
            "android" to "getprop ro.build.version.release",
            "identidade do shell" to "id",
            "tela inicial" to "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
            "apps de usuario" to "pm list packages -3"
        )
        for ((rotulo, comando) in comandos) {
            when (val r = Adb.shell(this, comando)) {
                is Adb.Resultado.Ok -> {
                    val saida = r.saida.lines().take(6).joinToString("\n    ")
                    Registro.linha("$rotulo: $saida")
                }
                is Adb.Resultado.Falha -> Registro.linha("$rotulo: FALHOU - ${r.motivo}")
            }
        }
        Registro.linha("--- fim do teste ---")
    }

    private fun atualizarEstado() {
        lifecycleScope.launch {
            val depuracao = try {
                withContext(Dispatchers.IO) { Adb.depuracaoSemFioLigada(this@MainActivity) }
            } catch (e: Throwable) { false }
            val conectado = try {
                withContext(Dispatchers.IO) { Adb.conectado(this@MainActivity) }
            } catch (e: Throwable) { false }

            v.estadoDepuracao.text = if (depuracao) "ligada" else "desligada"
            v.estadoDepuracao.setTextColor(cor(depuracao))
            v.estadoConexao.text = if (conectado) "conectado ao adbd" else "sem conexao"
            v.estadoConexao.setTextColor(cor(conectado))
            v.btnTestar.isEnabled = conectado
            v.btnFixarPorta.isEnabled = conectado
        }
    }

    private fun cor(bom: Boolean) = getColor(if (bom) R.color.ok else R.color.fraco)

    private fun ocupado(sim: Boolean) {
        v.progresso.visibility = if (sim) View.VISIBLE else View.INVISIBLE
        v.btnConectar.isEnabled = !sim
        v.btnParear.isEnabled = !sim
    }

    private fun acrescentar(linha: String) {
        v.registro.append(if (v.registro.text.isEmpty()) linha else "\n$linha")
        v.rolagem.post { v.rolagem.fullScroll(View.FOCUS_DOWN) }
    }

    private inline fun comProtecao(bloco: () -> Unit) {
        try { bloco() } catch (e: Throwable) {
            Registro.linha("ERRO: ${e.message}")
            Registro.linha(e.stackTraceToString().lines().take(6).joinToString("\n"))
        }
    }

    private fun mostrarErroSimples(texto: String) {
        val tv = TextView(this).apply {
            text = texto
            setTextColor(0xFFF87171.toInt())
            setBackgroundColor(0xFF0E1116.toInt())
            setPadding(32, 32, 32, 32)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }
}
