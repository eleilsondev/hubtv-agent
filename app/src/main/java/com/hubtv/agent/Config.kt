package com.hubtv.agent

import android.content.Context
import android.provider.Settings

object Config {

    const val BASE_URL = "https://hub.tv.br"
    const val CHAVE_INSCRICAO = "troque-esta-chave-compartilhada"

    private const val PREFS = "hubtv_agente"
    private const val K_TOKEN = "token"
    private const val K_CODIGO = "codigo_inscricao"
    private const val K_CODIGO_ATIVACAO = "codigo_ativacao"

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

    fun codigoAtivacao(context: Context): String {
        val existente = prefs(context).getString(K_CODIGO_ATIVACAO, null)
        if (!existente.isNullOrBlank()) return existente

        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val codigo = (1..8).map { chars.random() }.joinToString("")
        prefs(context).edit().putString(K_CODIGO_ATIVACAO, codigo).apply()
        return codigo
    }

    fun configurado(): Boolean =
        BASE_URL.startsWith("http") && !BASE_URL.contains("PLACEHOLDER")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
