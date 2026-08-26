package com.hubtv.agent

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro simples e observavel.
 *
 * Na Etapa 1 ele so alimenta a tela de teste. Quando o backend entrar,
 * as mesmas linhas viram o corpo do relatorio enviado no check-in.
 */
object Registro {

    private const val TAG = "HubTVAgent"
    private const val LIMITE = 300

    private val relogio = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val linhas = ArrayDeque<String>()
    private var ouvinte: ((String) -> Unit)? = null

    @Synchronized
    fun linha(texto: String) {
        val marcada = "${relogio.format(Date())}  $texto"
        linhas.addLast(marcada)
        while (linhas.size > LIMITE) linhas.removeFirst()
        Log.i(TAG, texto)
        ouvinte?.invoke(marcada)
    }

    @Synchronized
    fun tudo(): List<String> = linhas.toList()

    @Synchronized
    fun observar(bloco: ((String) -> Unit)?) {
        ouvinte = bloco
    }

    @Synchronized
    fun limpar() {
        linhas.clear()
    }
}
