package com.hubtv.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * O servico que mantem a conexao de pe.
 *
 * Na Etapa 1 ele so garante a reconexao apos o boot. Na Etapa 2 e daqui
 * que sai o check-in periodico para o painel; na Etapa 3, e aqui que a
 * fila de comandos e consumida.
 */
class AgentService : Service() {

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var laco: Job? = null
    private var lacoCheckin: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        criarCanal()
        startForeground(ID_AVISO, montarAviso("iniciando"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (laco?.isActive != true) {
            laco = escopo.launch { manterConexao() }
        }
        if (lacoCheckin?.isActive != true) {
            lacoCheckin = escopo.launch { manterCheckin() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        escopo.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------

    private suspend fun manterConexao() {
        // Depois do boot o sistema ainda esta subindo servicos. Tentar cedo
        // demais falha por motivo bobo, entao o agente espera assentar.
        Registro.linha("aguardando o sistema assentar (${ESPERA_BOOT_MS / 1000}s)")
        delay(ESPERA_BOOT_MS)

        var tentativa = 0
        while (true) {
            if (Adb.conectado(this)) {
                atualizarAviso("conectado ao adbd")
                tentativa = 0
                delay(INTERVALO_VERIFICACAO_MS)
                continue
            }

            tentativa++
            Registro.linha("reconexao: tentativa $tentativa")
            atualizarAviso("reconectando ($tentativa)")

            if (!Adb.depuracaoSemFioLigada(this)) {
                Adb.ligarDepuracaoSemFio(this)
                delay(3_000)
            }

            when (val r = Adb.conectar(this)) {
                is Adb.Resultado.Ok -> {
                    Registro.linha("de pe outra vez, sem PC e sem dialogo")
                    atualizarAviso("conectado ao adbd")
                    tentativa = 0
                }
                is Adb.Resultado.Falha -> {
                    Registro.linha("ainda nao: ${r.motivo}")
                    atualizarAviso("sem conexao")
                }
            }

            // recuo progressivo, com teto - nao adianta martelar
            val espera = minOf(ESPERA_BASE_MS * tentativa, ESPERA_MAXIMA_MS)
            delay(espera)
        }
    }

    /**
     * O laco do check-in (Etapa 2): manda um retrato ao painel de tempos em
     * tempos. Independe da conexao ADB - so precisa de rede - por isso corre
     * num laco proprio. Enquanto Config.BASE_URL for o placeholder, ele so
     * dorme, sem barulho.
     */
    private suspend fun manterCheckin() {
        delay(ESPERA_BOOT_MS)
        while (true) {
            if (Config.configurado()) {
                when (val r = CheckIn.pulso(this)) {
                    is CheckIn.Resultado.Ok -> { /* comandos: consumidos na Etapa 3 */ }
                    is CheckIn.Resultado.Falha -> Registro.linha("check-in: ${r.motivo}")
                }
            }
            delay(INTERVALO_CHECKIN_MS)
        }
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun montarAviso(estado: String): Notification =
        NotificationCompat.Builder(this, CANAL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(estado)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun atualizarAviso(estado: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(ID_AVISO, montarAviso(estado))
        } catch (e: Exception) {
            // TV sem area de notificacao: seguir sem barulho
        }
    }

    companion object {
        private const val CANAL = "hubtv_agente"
        private const val ID_AVISO = 42

        private const val ESPERA_BOOT_MS = 45_000L
        private const val ESPERA_BASE_MS = 10_000L
        private const val ESPERA_MAXIMA_MS = 120_000L
        private const val INTERVALO_VERIFICACAO_MS = 60_000L
        private const val INTERVALO_CHECKIN_MS = 5 * 60_000L   // 5 min (teste); producao ~15

        fun iniciar(context: Context) {
            val i = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
