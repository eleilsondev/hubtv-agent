package com.hubtv.agent

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hubtv.agent.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Tela de teste da Etapa 1.
 *
 * Ela existe para responder a uma pergunta e nada mais: o app consegue
 * virar cliente ADB de si mesmo e manter isso depois de um reboot?
 * Quando a resposta for sim em campo, esta tela vira apenas diagnostico.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var v: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        v = ActivityMainBinding.inflate(layoutInflater)
        setContentView(v.root)

        v.registro.text = Registro.tudo().joinToString("\n")
        Registro.observar { runOnUiThread { acrescentar(it) } }

        v.btnLigarDepuracao.setOnClickListener {
            val ok = Adb.ligarDepuracaoSemFio(this)
            Registro.linha(
                if (ok) "depuracao sem fio ligada pela configuracao do sistema"
                else "nao consegui ligar - falta WRITE_SECURE_SETTINGS"
            )
            atualizarEstado()
        }

        v.btnParear.setOnClickListener {
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
        }

        v.btnConectar.setOnClickListener {
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
        }

        v.btnTestar.setOnClickListener {
            lifecycleScope.launch {
                ocupado(true)
                testarPoderes()
                ocupado(false)
            }
        }

        v.btnFixarPorta.setOnClickListener {
            lifecycleScope.launch {
                ocupado(true)
                when (val r = Adb.fixarPorta5555(this@MainActivity)) {
                    is Adb.Resultado.Ok -> Registro.linha("porta fixa: reconexao apos boot fica direta")
                    is Adb.Resultado.Falha -> Registro.linha(r.motivo)
                }
                ocupado(false)
            }
        }

        v.btnLimpar.setOnClickListener {
            Registro.limpar()
            v.registro.text = ""
        }

        if (AdbManager.get(this).identidadeNova) {
            Registro.linha("identidade RSA criada - esta e a primeira execucao")
        } else {
            Registro.linha("identidade RSA carregada do disco")
        }
        atualizarEstado()
        AgentService.iniciar(this)
    }

    override fun onResume() {
        super.onResume()
        atualizarEstado()
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
        val depuracao = Adb.depuracaoSemFioLigada(this)
        val conectado = Adb.conectado(this)

        v.estadoDepuracao.text = if (depuracao) "ligada" else "desligada"
        v.estadoDepuracao.setTextColor(cor(depuracao))

        v.estadoConexao.text = if (conectado) "conectado ao adbd" else "sem conexao"
        v.estadoConexao.setTextColor(cor(conectado))

        v.btnTestar.isEnabled = conectado
        v.btnFixarPorta.isEnabled = conectado
    }

    private fun cor(bom: Boolean) =
        getColor(if (bom) R.color.ok else R.color.fraco)

    private fun ocupado(sim: Boolean) {
        v.progresso.visibility = if (sim) View.VISIBLE else View.INVISIBLE
        v.btnConectar.isEnabled = !sim
        v.btnParear.isEnabled = !sim
    }

    private fun acrescentar(linha: String) {
        v.registro.append(if (v.registro.text.isEmpty()) linha else "\n$linha")
        v.rolagem.post { v.rolagem.fullScroll(View.FOCUS_DOWN) }
    }
}
