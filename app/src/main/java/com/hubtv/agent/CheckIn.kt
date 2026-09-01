package com.hubtv.agent

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CheckIn {

    sealed class Resultado {
        data class Ok(val comandos: JSONArray) : Resultado()
        data class Falha(val motivo: String) : Resultado()
    }

    /**
     * Check-in + execucao da fila, numa chamada so.
     *
     * O painel marca os comandos como "enviado" assim que os entrega, entao
     * quem baixa a fila TEM que executa-la. Antes, o launcher e a MainActivity
     * chamavam `pulso()` e jogavam os comandos fora: eles saiam da fila, nunca
     * rodavam e ficavam presos em "enviado" para sempre, sem resultado nenhum
     * no painel. Use esta funcao — nao `pulso()` — em todo lugar.
     */
    suspend fun sincronizar(context: Context): Resultado {
        val r = pulso(context)
        if (r is Resultado.Ok && r.comandos.length() > 0) {
            Registro.linha("executando ${r.comandos.length()} comando(s)")
            Comandos.executar(context, r.comandos)
        }
        return r
    }

    suspend fun pulso(context: Context): Resultado = withContext(Dispatchers.IO) {
        if (!Config.configurado()) {
            return@withContext Resultado.Falha("URL do painel ainda nao configurada (Config.BASE_URL)")
        }
        try {
            if (!Config.inscrito(context)) {
                when (val r = inscrever(context)) {
                    is Resultado.Falha -> return@withContext r
                    is Resultado.Ok -> { }
                }
            }
            checkin(context)
        } catch (e: Throwable) {
            Resultado.Falha("erro de rede: ${e.message}")
        }
    }

    private fun inscrever(context: Context): Resultado {
        val corpo = retrato(context)
        corpo.put("codigo_ativacao", Config.codigoAtivacao(context))

        val resp = postar(
            "${Config.BASE_URL}/api/dispositivos/registrar",
            corpo,
            emptyMap()
        ) ?: return Resultado.Falha("sem resposta na inscricao")

        val erro = resp.texto("erro", "")
        if (erro.isNotBlank()) return Resultado.Falha(erro)

        val token = resp.texto("token", "")
        if (token.isBlank()) return Resultado.Falha("o painel nao devolveu token")

        Config.guardarToken(context, token)
        Registro.linha("inscrito no painel - token recebido")
        return Resultado.Ok(JSONArray())
    }

    private fun checkin(context: Context): Resultado {
        val token = Config.token(context) ?: return Resultado.Falha("sem token")
        val resp = postar(
            "${Config.BASE_URL}/api/dispositivos/checkin",
            retrato(context),
            mapOf("Authorization" to "Bearer $token")
        ) ?: return Resultado.Falha("sem resposta no check-in")

        val comandos = resp.optJSONArray("comandos") ?: JSONArray()
        Registro.linha("check-in ok - ${comandos.length()} comando(s) na fila")

        val bloqueado = resp.optBoolean("bloqueado", false)
        val ativado = resp.optBoolean("ativado", false)
        val expiraEm = resp.texto("expira_em", "")

        val prefs = context.getSharedPreferences("hubtv_agente", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("bloqueado", bloqueado)
            .putBoolean("ativado", ativado)
            .putString("expira_em", expiraEm)
            .apply()

        val launcher = resp.optJSONObject("launcher")
        if (launcher != null) {
            LauncherActivity.salvarConfig(context, launcher)
            Registro.linha("config do launcher atualizada")
        }

        val notificacoes = resp.optJSONArray("notificacoes")
        if (notificacoes != null && notificacoes.length() > 0) {
            salvarNotificacoes(context, notificacoes)
        }

        return Resultado.Ok(comandos)
    }

    private fun salvarNotificacoes(context: Context, notificacoes: JSONArray) {
        val prefs = context.getSharedPreferences("hubtv_notificacoes", Context.MODE_PRIVATE)
        prefs.edit().putString("pendentes", notificacoes.toString()).apply()
    }

    fun lerNotificacoes(context: Context): JSONArray {
        val prefs = context.getSharedPreferences("hubtv_notificacoes", Context.MODE_PRIVATE)
        val json = prefs.getString("pendentes", null) ?: return JSONArray()
        return try { JSONArray(json) } catch (_: Exception) { JSONArray() }
    }

    fun limparNotificacoes(context: Context) {
        context.getSharedPreferences("hubtv_notificacoes", Context.MODE_PRIVATE)
            .edit().remove("pendentes").apply()
    }

    fun marcarLida(context: Context, notificacaoId: Int) {
        val token = Config.token(context) ?: return
        try {
            postar(
                "${Config.BASE_URL}/api/notificacoes/lida",
                JSONObject().put("id", notificacaoId),
                mapOf("Authorization" to "Bearer $token")
            )
        } catch (_: Exception) {}
    }

    private fun retrato(context: Context): JSONObject {
        val apps = JSONArray()
        val pm = context.packageManager
        for (pkg in ALVOS) {
            try {
                val info = pm.getPackageInfo(pkg, 0)
                apps.put(JSONObject().put("pkg", pkg).put("versao", info.versionName ?: "?"))
            } catch (_: Exception) { }
        }
        return JSONObject()
            .put("device_id", Config.idDispositivo(context))
            .put("modelo", Build.MODEL)
            .put("fabricante", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("adb_ok", Adb.conectado(context))
            .put("apps", apps)
    }

    private fun postar(
        url: String,
        corpo: JSONObject,
        cabecalhos: Map<String, String>
    ): JSONObject? {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15_000
            con.readTimeout = 15_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("Accept", "application/json")
            for ((k, v) in cabecalhos) con.setRequestProperty(k, v)

            con.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }

            val codigo = con.responseCode
            val fonte = if (codigo in 200..299) con.inputStream else con.errorStream
            val texto = fonte?.bufferedReader()?.use { it.readText() } ?: ""

            if (codigo !in 200..299) {
                Registro.linha("painel respondeu $codigo: ${texto.take(120)}")
                return null
            }
            return if (texto.isBlank()) JSONObject() else JSONObject(texto)
        } finally {
            con.disconnect()
        }
    }

    private val ALVOS = listOf(
        "org.smarttube.stable",
        "com.global.unitviptv",
        "dev.vodik7.tvquickactions",
        "com.rightside.launcher"
    )
}
