package com.hubtv.agent

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hubtv.agent.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela de teste da Etapa 1.
 *
 * Regra de ouro daqui: NADA pesado ou arriscado roda na thread principal
 * dentro do onCreate. A geracao da chave RSA, a conexao, o servico - tudo
 * vai para corrotina com try/catch. Um erro vira texto na tela, nunca uma
 * "tela preta que fecha".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var v: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Se a UI em si falhar ao inflar, cai num painel de texto simples
        // mostrando o erro - melhor isso do que uma tela preta.
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

        // Mostra o crash da execucao anterior, se houve.
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
                    is Adb.Resultado.Ok -> Registro.linha("conexao estabelecida")
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
                    is Adb.Resultado.Ok -> Registro.linha("porta fixa: reconexao apos boot fica direta")
                    is Adb.Resultado.Falha -> Registro.linha(r.motivo)
                }
                ocupado(false)
            }
        } }

        v.btnLimpar.setOnClickListener {
            Registro.limpar()
            v.registro.text = ""
        }

        Registro.linha("app iniciado - preparando identidade em segundo plano...")
        Registro.linha("fluxo: no HubTV rode 'adb tcpip 5555', depois toque CONECTAR aqui")
        Registro.linha("um dialogo 'sempre permitir' aparece - um toque e pronto, sem codigo")
        Registro.linha("PARA SOBREVIVER AO DESLIGAMENTO 100%: depois de conectar, toque FIXAR PORTA 5555")

        // A criptografia (chave RSA + BouncyCastle) NUNCA na thread principal.
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
            // servico so depois da identidade pronta, e protegido
            try { AgentService.iniciar(this@MainActivity) }
            catch (e: Throwable) { Registro.linha("aviso: servico nao iniciou: ${e.message}") }
        }
    }

    override fun onResume() {
        super.onResume()
        comProtecao { atualizarEstado() }
    }

    override fun onDestroy() {
        Registro.observar(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------

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
        // ambos consultam o AdbManager -> podem tocar cripto -> em corrotina
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

    /** Envolve um clique para que nenhuma falha derrube o app. */
    private inline fun comProtecao(bloco: () -> Unit) {
        try { bloco() } catch (e: Throwable) {
            Registro.linha("ERRO: ${e.message}")
            Registro.linha(e.stackTraceToString().lines().take(6).joinToString("\n"))
        }
    }

    /** Ultimo recurso: um TextView rolavel com o erro, sem depender do layout. */
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
