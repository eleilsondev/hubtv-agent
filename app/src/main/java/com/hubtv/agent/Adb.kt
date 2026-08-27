package com.hubtv.agent

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Tudo que o agente faz com o adbd local passa por aqui.
 *
 * O truque central: a conexao e para 127.0.0.1. O aparelho e, ao mesmo
 * tempo, o "PC" e o alvo - por isso funciona em qualquer rede, com qualquer
 * IP, sem nada aberto para fora.
 */
object Adb {

    sealed class Resultado {
        data class Ok(val saida: String) : Resultado()
        data class Falha(val motivo: String, val causa: Throwable? = null) : Resultado()
    }

    /**
     * Liga a depuracao sem fio pela configuracao do sistema.
     * So funciona com WRITE_SECURE_SETTINGS concedida - e o que faz o
     * agente se reerguer sozinho depois de cada boot.
     */
    fun ligarDepuracaoSemFio(context: Context): Boolean = try {
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        true
    } catch (e: SecurityException) {
        Registro.linha("sem WRITE_SECURE_SETTINGS - nao consigo religar a depuracao")
        false
    } catch (e: Exception) {
        Registro.linha("falha ao ligar a depuracao sem fio: ${e.message}")
        false
    }

    fun depuracaoSemFioLigada(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    } catch (e: Exception) {
        false
    }

    fun conectado(context: Context): Boolean = try {
        AdbManager.get(context).isConnected
    } catch (e: Exception) {
        false
    }

    /**
     * Pareamento: so na primeira vez, com a porta e o codigo que a TV mostra
     * na tela "Parear dispositivo por Wi-Fi". Depois disso a chave fica
     * autorizada e este passo nunca mais e necessario.
     */
    suspend fun parear(context: Context, porta: Int, codigo: String): Resultado =
        withContext(Dispatchers.IO) {
            try {
                Registro.linha("pareando na porta $porta...")
                val ok = AdbManager.get(context).pair(HOST, porta, codigo)
                if (ok) {
                    Registro.linha("pareado - a chave do agente foi aceita")
                    Resultado.Ok("pareado")
                } else {
                    Resultado.Falha("o pareamento foi recusado; confira porta e codigo")
                }
            } catch (e: Throwable) {
                Resultado.Falha("erro no pareamento: ${e.message}", e)
            }
        }

    /**
     * Conecta ao adbd local. A porta da depuracao sem fio muda a cada
     * ativacao, entao a descoberta fica por conta da biblioteca (mDNS
     * dentro do proprio aparelho, que funciona bem).
     */
    suspend fun conectar(context: Context, tempoLimiteMs: Long = 15_000): Resultado =
        withContext(Dispatchers.IO) {
            try {
                val gerente = AdbManager.get(context)
                if (gerente.isConnected) return@withContext Resultado.Ok("ja conectado")

                // 1) caminho legado: porta TCP fixa (tcpip 5555). Se o adbd
                //    estiver nesse modo, conectar aqui dispara o dialogo
                //    classico "sempre permitir" - UM toque, SEM codigo.
                try {
                    if (gerente.connect(HOST, 5555)) {
                        Registro.linha("conectado ao adbd em $HOST:5555 (modo TCP)")
                        return@withContext Resultado.Ok("conectado")
                    }
                } catch (_: Throwable) { /* 5555 fechado: segue para o mDNS */ }

                // 2) depuracao sem fio (TLS): porta muda a cada boot, descoberta
                //    por mDNS interno. Precisa de pareamento previo uma vez.
                Registro.linha("procurando o adbd (depuracao sem fio)...")
                val ok = gerente.autoConnect(context, tempoLimiteMs)
                if (ok) {
                    Registro.linha("conectado ao adbd em $HOST")
                    Resultado.Ok("conectado")
                } else {
                    Resultado.Falha("nenhuma porta do adbd respondeu (pareie uma vez, ou ative o modo TCP pelo HubTV)")
                }
            } catch (e: Throwable) {
                Resultado.Falha("erro ao conectar: ${e.message}", e)
            }
        }

    /** Tentativa direta numa porta conhecida - util quando a porta esta fixa em 5555. */
    suspend fun conectarNaPorta(context: Context, porta: Int): Resultado =
        withContext(Dispatchers.IO) {
            try {
                val ok = AdbManager.get(context).connect(HOST, porta)
                if (ok) Resultado.Ok("conectado em $HOST:$porta")
                else Resultado.Falha("a porta $porta nao respondeu")
            } catch (e: Throwable) {
                Resultado.Falha("erro ao conectar em $porta: ${e.message}", e)
            }
        }

    /**
     * Executa um comando de shell e devolve a saida.
     * E o mesmo `adb shell <cmd>` que o HubTV manda hoje pelo Wi-Fi,
     * so que disparado de dentro do aparelho.
     */
    suspend fun shell(context: Context, comando: String): Resultado =
        withContext(Dispatchers.IO) {
            try {
                val gerente = AdbManager.get(context)
                if (!gerente.isConnected) return@withContext Resultado.Falha("sem conexao com o adbd")

                gerente.openStream("shell:$comando").use { fluxo ->
                    val saida = ByteArrayOutputStream()
                    fluxo.openInputStream().use { entrada ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val lidos = entrada.read(buffer)
                            if (lidos < 0) break
                            saida.write(buffer, 0, lidos)
                        }
                    }
                    Resultado.Ok(saida.toString("UTF-8").trim())
                }
            } catch (e: Throwable) {
                Resultado.Falha("erro no comando: ${e.message}", e)
            }
        }

    fun desconectar(context: Context) {
        try {
            AdbManager.get(context).disconnect()
        } catch (e: Throwable) {
            // desconectar nunca deve derrubar o app
        }
    }

    /**
     * Fixa a porta do adbd em 5555 de forma PERSISTENTE e reinicia o servico.
     *
     * O ponto critico do desligamento total (nao um simples reboot): a
     * propriedade de runtime `service.adb.tcp.port` some no boot frio. Quem
     * sobrevive e a propriedade persistente `persist.adb.tcp.port`, que o
     * init restaura a cada boot nos firmwares que a respeitam. Com ela + a
     * chave ja gravada em adb_keys, a reconexao apos o boot fica direta:
     * 127.0.0.1:5555, sem dialogo e sem codigo.
     *
     * Setamos as duas: a persistente (para o proximo boot) e a de runtime
     * (para valer ja nesta sessao). Nem todo firmware aceita; o retorno diz.
     */
    suspend fun fixarPorta5555(context: Context): Resultado =
        withContext(Dispatchers.IO) {
            // 1) persistente - e o que faz a porta voltar apos desligar 100%
            shell(context, "setprop persist.adb.tcp.port 5555")
            // 2) runtime - vale para a sessao atual
            when (val r = shell(context, "setprop service.adb.tcp.port 5555")) {
                is Resultado.Falha -> r
                is Resultado.Ok -> {
                    shell(context, "stop adbd")
                    shell(context, "start adbd")

                    // confere se a persistente pegou (alguns firmwares ignoram)
                    val persist = when (val p = shell(context, "getprop persist.adb.tcp.port")) {
                        is Resultado.Ok -> p.saida.trim()
                        is Resultado.Falha -> ""
                    }
                    if (persist == "5555") {
                        Registro.linha("porta 5555 fixada de forma PERSISTENTE - sobrevive ao desligamento")
                    } else {
                        Registro.linha("porta 5555 fixada, mas este firmware nao guardou a persistente")
                        Registro.linha("apos desligar 100% a reconexao pode depender de pareamento")
                    }
                    Resultado.Ok("porta fixada")
                }
            }
        }

    const val HOST = "127.0.0.1"
}
