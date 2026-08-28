package com.hubtv.agent

import android.content.Context
import android.provider.Settings

/**
 * Configuracao do agente para falar com o painel (Etapa 2).
 *
 * A URL do servidor e a chave de inscricao sao FIXAS para toda a frota, por
 * isso ficam embutidas no APK como constantes: assim nao ha NADA para digitar
 * no controle da TV (mesmo motivo pelo qual evitamos o codigo de pareamento).
 * O token, esse sim, e por aparelho e nasce sozinho na primeira inscricao.
 */
object Config {

    // >>> AJUSTE ANTES DE COMPILAR A BUILD DE PRODUCAO <<<
    // Aponte para o seu VPS (de preferencia https). Sem a barra no fim.
    const val BASE_URL = "https://hub.tv.br"

    // Segredo compartilhado que autoriza a PRIMEIRA inscricao de um aparelho.
    // Deve ser o mesmo valor guardado no .env do painel (HUBTV_ENROLL_KEY).
    const val CHAVE_INSCRICAO = "troque-esta-chave-compartilhada"

    private const val PREFS = "hubtv_agente"
    private const val K_TOKEN = "token"
    private const val K_CODIGO = "codigo_inscricao"

    /** Identificador estavel do aparelho (sobrevive a reboot). */
    fun idDispositivo(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "desconhecido"

    fun token(context: Context): String? = prefs(context).getString(K_TOKEN, null)

    fun guardarToken(context: Context, token: String) {
        prefs(context).edit().putString(K_TOKEN, token).apply()
    }

    fun inscrito(context: Context): Boolean = token(context) != null

    fun codigoInscricao(context: Context): String? = prefs(context).getString(K_CODIGO, null)

    fun guardarCodigo(context: Context, codigo: String) {
        prefs(context).edit().putString(K_CODIGO, codigo).apply()
    }

    /** true quando a URL ja foi trocada pela real (nao e mais o placeholder). */
    fun configurado(): Boolean =
        BASE_URL.startsWith("http") && !BASE_URL.contains("PLACEHOLDER")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
