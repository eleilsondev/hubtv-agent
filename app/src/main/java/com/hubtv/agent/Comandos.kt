package com.hubtv.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Comandos {

    suspend fun executar(context: Context, comandos: JSONArray) {
        if (comandos.length() == 0) return

        for (i in 0 until comandos.length()) {
            val cmd = comandos.getJSONObject(i)
            val id = cmd.optInt("id", 0)
            val tipo = cmd.optString("tipo", "")
            val payload = cmd.optJSONObject("payload")

            Registro.linha("executando comando #$id: $tipo")

            val resultado = try {
                when (tipo) {
                    "shell" -> executarShell(context, payload)
                    "instalar_app" -> instalarApp(context, payload)
                    "desinstalar_app" -> desinstalarApp(context, payload)
                    "reboot" -> reiniciar(context)
                    "bloquear" -> bloquear(context, true)
                    "desbloquear" -> bloquear(context, false)
                    "atualizar_launcher" -> atualizarLauncher(context)
                    "atualizar_agente" -> atualizarAgente(context, payload)
                    else -> ResultadoCmd(false, "tipo desconhecido: $tipo")
                }
            } catch (e: Exception) {
                ResultadoCmd(false, "erro: ${e.message}")
            }

            Registro.linha("comando #$id: ${if (resultado.sucesso) "OK" else "FALHOU"} - ${resultado.saida.take(200)}")

            if (id > 0) {
                reportar(context, id, resultado)
            }
        }
    }

    private data class ResultadoCmd(val sucesso: Boolean, val saida: String)

    private suspend fun executarShell(context: Context, payload: JSONObject?): ResultadoCmd {
        // O painel embrulha texto que nao e JSON em {"raw": "..."}. O elvis
        // antigo nunca chegava no 'raw' porque optString devolve "" e nao null
        // quando a chave falta — todo comando digitado solto morria aqui como
        // "comando vazio".
        val comando = payload?.texto("comando")?.ifBlank { payload.texto("raw") } ?: ""

        if (comando.isBlank()) return ResultadoCmd(false, "payload sem 'comando' nem 'raw'")

        return when (val r = Adb.shell(context, comando)) {
            is Adb.Resultado.Ok -> ResultadoCmd(true, r.saida)
            is Adb.Resultado.Falha -> ResultadoCmd(false, r.motivo)
        }
    }

    private suspend fun instalarApp(context: Context, payload: JSONObject?): ResultadoCmd {
        val url = payload?.texto("url") ?: ""
        if (url.isBlank()) return ResultadoCmd(false, "payload sem campo 'url'")

        val pacote = payload.texto("pacote")
        Registro.linha("baixando APK: $url")

        return when (val r = Instalador.baixarEInstalar(context, url)) {
            is Instalador.Resultado.Ok ->
                ResultadoCmd(true, "app instalado${if (pacote.isNotBlank()) ": $pacote" else ""}")
            is Instalador.Resultado.Falha -> ResultadoCmd(false, r.motivo)
        }
    }

    private suspend fun desinstalarApp(context: Context, payload: JSONObject?): ResultadoCmd {
        val pacote = payload?.texto("pacote")?.ifBlank { payload.texto("raw") } ?: ""

        if (pacote.isBlank()) return ResultadoCmd(false, "payload sem 'pacote' nem 'raw'")

        return when (val r = Adb.shell(context, "pm uninstall $pacote")) {
            is Adb.Resultado.Ok -> ResultadoCmd(true, r.saida)
            is Adb.Resultado.Falha -> ResultadoCmd(false, r.motivo)
        }
    }

    private suspend fun reiniciar(context: Context): ResultadoCmd {
        return when (val r = Adb.shell(context, "reboot")) {
            is Adb.Resultado.Ok -> ResultadoCmd(true, "reiniciando")
            is Adb.Resultado.Falha -> ResultadoCmd(false, r.motivo)
        }
    }

    private fun bloquear(context: Context, valor: Boolean): ResultadoCmd {
        context.getSharedPreferences("hubtv_agente", Context.MODE_PRIVATE)
            .edit().putBoolean("bloqueado", valor).apply()
        return ResultadoCmd(true, if (valor) "bloqueado" else "desbloqueado")
    }

    private suspend fun atualizarLauncher(context: Context): ResultadoCmd {
        return try {
            // pulso() e nao sincronizar(): ja estamos DENTRO da execucao da
            // fila, e sincronizar chamaria executar() de novo em recursao.
            CheckIn.pulso(context)
            ResultadoCmd(true, "launcher atualizado")
        } catch (e: Exception) {
            ResultadoCmd(false, "falha: ${e.message}")
        }
    }

    private suspend fun atualizarAgente(context: Context, payload: JSONObject?): ResultadoCmd {
        val url = payload?.texto("url") ?: ""
        if (url.isBlank()) return ResultadoCmd(false, "payload sem campo 'url'")

        Registro.linha("baixando nova versao do agente: $url")

        return when (val r = Instalador.baixarEInstalar(context, url, "hubtv_update.apk")) {
            is Instalador.Resultado.Ok -> ResultadoCmd(true, "agente atualizado - reiniciando")
            is Instalador.Resultado.Falha -> ResultadoCmd(false, r.motivo)
        }
    }

    private suspend fun reportar(context: Context, comandoId: Int, resultado: ResultadoCmd) {
        val token = Config.token(context) ?: return
        withContext(Dispatchers.IO) {
            try {
                val corpo = JSONObject()
                    .put("comando_id", comandoId)
                    .put("sucesso", resultado.sucesso)
                    .put("resultado", resultado.saida)

                val con = URL("${Config.BASE_URL}/api/dispositivos/reportar").openConnection() as HttpURLConnection
                con.requestMethod = "POST"
                con.connectTimeout = 15_000
                con.readTimeout = 15_000
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json")
                con.setRequestProperty("Accept", "application/json")
                con.setRequestProperty("Authorization", "Bearer $token")
                con.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }

                val codigo = con.responseCode
                if (codigo in 200..299) {
                    Registro.linha("resultado reportado ao painel")
                } else {
                    Registro.linha("report falhou: HTTP $codigo")
                }
                con.disconnect()
            } catch (e: Exception) {
                Registro.linha("erro ao reportar: ${e.message}")
            }
        }
    }
}
