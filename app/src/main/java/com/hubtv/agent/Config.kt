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

    /**
     * Codigo de ativacao DERIVADO do aparelho, nao sorteado.
     *
     * Antes era aleatorio e guardado em SharedPreferences: reinstalar o app
     * apagava as prefs e o aparelho aparecia no painel como se fosse outro,
     * quebrando a renovacao. Agora sai de um hash do ANDROID_ID — o mesmo
     * aparelho sempre devolve o mesmo codigo, mesmo apos reinstalar ou
     * limpar os dados.
     *
     * Depende da assinatura do APK ser estavel (o ANDROID_ID e derivado dela);
     * ver a chave fixa em app/build.gradle.kts.
     */
    fun codigoAtivacao(context: Context): String {
        val alfabeto = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // sem I, O, 0 e 1
        val id = idDispositivo(context)

        val codigo = try {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("hubtv:$id".toByteArray(Charsets.UTF_8))
            buildString {
                for (i in 0 until 8) append(alfabeto[(hash[i].toInt() and 0xFF) % alfabeto.length])
            }
        } catch (_: Exception) {
            // fallback: mantem o que ja estava guardado, se houver
            prefs(context).getString(K_CODIGO_ATIVACAO, null)
                ?: (1..8).map { alfabeto.random() }.joinToString("")
        }

        prefs(context).edit().putString(K_CODIGO_ATIVACAO, codigo).apply()
        return codigo
    }

    fun configurado(): Boolean =
        BASE_URL.startsWith("http") && !BASE_URL.contains("PLACEHOLDER")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
