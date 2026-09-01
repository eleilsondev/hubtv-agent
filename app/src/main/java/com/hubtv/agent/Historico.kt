package com.hubtv.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Historico dos comandos executados NO APARELHO, gravado em disco.
 *
 * O Registro vive so na memoria e some quando o processo morre — nao servia
 * para responder "esse comando chegou aqui?". Isto sobrevive a reinicio e
 * alimenta a tela de Diagnostico.
 */
object Historico {

    private const val PREFS = "hubtv_historico"
    private const val CHAVE = "comandos"
    private const val LIMITE = 40

    private val relogio = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT)

    data class Entrada(
        val id: Int,
        val tipo: String,
        val sucesso: Boolean,
        val saida: String,
        val quando: String
    )

    @Synchronized
    fun registrar(context: Context, id: Int, tipo: String, sucesso: Boolean, saida: String) {
        val lista = lerBruto(context)

        lista.put(
            JSONObject()
                .put("id", id)
                .put("tipo", tipo)
                .put("sucesso", sucesso)
                .put("saida", saida.take(1500))
                .put("quando", relogio.format(Date()))
        )

        // mantem apenas os mais recentes
        val cortada = JSONArray()
        val inicio = maxOf(0, lista.length() - LIMITE)
        for (i in inicio until lista.length()) cortada.put(lista.get(i))

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CHAVE, cortada.toString()).apply()
    }

    /** mais recentes primeiro */
    @Synchronized
    fun ler(context: Context): List<Entrada> {
        val bruto = lerBruto(context)
        val saida = mutableListOf<Entrada>()
        for (i in bruto.length() - 1 downTo 0) {
            val o = bruto.optJSONObject(i) ?: continue
            saida.add(
                Entrada(
                    id = o.optInt("id", 0),
                    tipo = o.texto("tipo", "?"),
                    sucesso = o.optBoolean("sucesso", false),
                    saida = o.texto("saida"),
                    quando = o.texto("quando")
                )
            )
        }
        return saida
    }

    @Synchronized
    fun limpar(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CHAVE).apply()
    }

    private fun lerBruto(context: Context): JSONArray {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CHAVE, null) ?: return JSONArray()
        return try { JSONArray(json) } catch (_: Exception) { JSONArray() }
    }

    /** carimbo do ultimo check-in bem sucedido, para a tela de Diagnostico */
    fun marcarCheckin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("ultimo_checkin", relogio.format(Date())).apply()
    }

    fun ultimoCheckin(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("ultimo_checkin", "") ?: ""
}
