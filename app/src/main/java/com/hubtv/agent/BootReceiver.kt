package com.hubtv.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * O momento decisivo da arquitetura.
 *
 * A depuracao sem fio desliga a cada boot. A chave RSA, nao - ela ficou
 * gravada em /data/misc/adb/adb_keys quando alguem marcou "sempre permitir".
 * Entao basta religar a depuracao e reconectar: nenhum dialogo aparece,
 * nenhum PC e necessario.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val acao = intent.action ?: return
        Registro.linha("boot detectado ($acao)")
        AgentService.iniciar(context)
    }
}
