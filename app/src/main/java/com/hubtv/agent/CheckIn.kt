package com.hubtv.agent

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * O agente telefona pra casa: manda um retrato do aparelho ao painel.
 *
 * Fluxo: se ainda nao ha token, faz a INSCRICAO uma vez com a chave
 * compartilhada e guarda o token devolvido; a partir dai, so CHECK-IN
 * periodico. A resposta do check-in ja traz uma lista de comandos - a
 * ponte para a Etapa 3. Por enquanto ela chega vazia.
 *
 * Rede em Kotlin puro (HttpURLConnection + org.json), sem nenhuma
 * dependencia nova - a lição das bibliotecas de cripto vale aqui tambem.
 */
object CheckIn {

    sealed class Resultado {
        data class Ok(val comandos: JSONArray) : Resultado()
        data class Falha(val motivo: String) : Resultado()
    }

    /** Um pulso completo: inscreve se preciso e faz o check-in. */
    suspend fun pulso(context: Context): Resultado = withContext(Dispatchers.IO) {
        if (!Config.configurado()) {
            return@withContext Resultado.Falha("URL do painel ainda nao configurada (Config.BASE_URL)")
        }
        try {
            if (!Config.inscrito(context)) {
                when (val r = inscrever(context)) {
                    is Resultado.Falha -> return@withContext r
                    is Resultado.Ok -> { /* segue para o check-in */ }
                }
            }
            checkin(context)
        } catch (e: Throwable) {
            Resultado.Falha("erro de rede: ${e.message}")
        }
    }

    // ------------------------------------------------------------------

    private fun inscrever(context: Context): Resultado {
        val resp = postar(
            "${Config.BASE_URL}/api/dispositivos/registrar",
            retrato(context),
            mapOf("X-Enroll-Key" to Config.CHAVE_INSCRICAO)
        ) ?: return Resultado.Falha("sem resposta na inscricao")

        val token = resp.optString("token", "")
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
        context.getSharedPreferences("hubtv_agente", Context.MODE_PRIVATE)
            .edit().putBoolean("bloqueado", bloqueado).apply()

        val launcher = resp.optJSONObject("launcher")
        if (launcher != null) {
            LauncherActivity.salvarConfig(context, launcher)
            Registro.linha("config do launcher atualizada")
        }

        return Resultado.Ok(comandos)
    }

    /** O retrato do aparelho que vai no corpo do POST. */
    private fun retrato(context: Context): JSONObject {
        val apps = JSONArray()
        val pm = context.packageManager
        for (pkg in ALVOS) {
            try {
                val info = pm.getPackageInfo(pkg, 0)
                apps.put(JSONObject().put("pkg", pkg).put("versao", info.versionName ?: "?"))
            } catch (_: Exception) { /* app nao instalado - ignora */ }
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

    /** Apps da HUB TV cuja versao instalada interessa ao relatorio. */
    private val ALVOS = listOf(
        "org.smarttube.stable",
        "com.global.unitviptv",
        "dev.vodik7.tvquickactions",
        "com.rightside.launcher"
    )
}
