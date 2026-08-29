package com.hubtv.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
        val comando = payload?.optString("comando", "")
            ?: payload?.optString("raw", "")
            ?: return ResultadoCmd(false, "payload sem campo 'comando'")

        if (comando.isBlank()) return ResultadoCmd(false, "comando vazio")

        return when (val r = Adb.shell(context, comando)) {
            is Adb.Resultado.Ok -> ResultadoCmd(true, r.saida)
            is Adb.Resultado.Falha -> ResultadoCmd(false, r.motivo)
        }
    }

    private suspend fun instalarApp(context: Context, payload: JSONObject?): ResultadoCmd {
        val url = payload?.optString("url", "")
            ?: return ResultadoCmd(false, "payload sem campo 'url'")
        if (url.isBlank()) return ResultadoCmd(false, "url vazia")

        val pacote = payload.optString("pacote", "")
        Registro.linha("baixando APK: $url")

        val arquivo = baixarArquivo(context, url, "install_temp.apk")
            ?: return ResultadoCmd(false, "falha no download")

        Registro.linha("instalando APK: ${arquivo.absolutePath} (${arquivo.length() / 1024}KB)")

        val resultado = when (val r = Adb.shell(context, "pm install -r ${arquivo.absolutePath}")) {
            is Adb.Resultado.Ok -> {
                if (r.saida.contains("Success", ignoreCase = true)) {
                    ResultadoCmd(true, "app instalado${if (pacote.isNotBlank()) ": $pacote" else ""}")
                } else {
                    ResultadoCmd(false, "pm install: ${r.saida}")
                }
            }
            is Adb.Resultado.Falha -> ResultadoCmd(false, r.motivo)
        }

        arquivo.delete()
        return resultado
    }

    private suspend fun desinstalarApp(context: Context, payload: JSONObject?): ResultadoCmd {
        val pacote = payload?.optString("pacote", "")
            ?: payload?.optString("raw", "")
            ?: return ResultadoCmd(false, "payload sem campo 'pacote'")

        if (pacote.isBlank()) return ResultadoCmd(false, "pacote vazio")

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
            CheckIn.pulso(context)
            ResultadoCmd(true, "launcher atualizado")
        } catch (e: Exception) {
            ResultadoCmd(false, "falha: ${e.message}")
        }
    }

    private suspend fun atualizarAgente(context: Context, payload: JSONObject?): ResultadoCmd {
        val url = payload?.optString("url", "")
            ?: return ResultadoCmd(false, "payload sem campo 'url'")
        if (url.isBlank()) return ResultadoCmd(false, "url vazia")

        Registro.linha("baixando nova versao do agente: $url")

        val arquivo = baixarArquivo(context, url, "hubtv_update.apk")
            ?: return ResultadoCmd(false, "falha no download da atualizacao")

        Registro.linha("instalando atualizacao: ${arquivo.length() / 1024}KB")

        val resultado = when (val r = Adb.shell(context, "pm install -r ${arquivo.absolutePath}")) {
            is Adb.Resultado.Ok -> {
                if (r.saida.contains("Success", ignoreCase = true)) {
                    ResultadoCmd(true, "agente atualizado - reiniciando")
                } else {
                    ResultadoCmd(false, "pm install: ${r.saida}")
                }
            }
            is Adb.Resultado.Falha -> ResultadoCmd(false, r.motivo)
        }

        arquivo.delete()
        return resultado
    }

    private suspend fun baixarArquivo(context: Context, url: String, nomeArquivo: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val con = URL(url).openConnection() as HttpURLConnection
                con.connectTimeout = 30_000
                con.readTimeout = 60_000
                con.instanceFollowRedirects = true

                if (con.responseCode !in 200..299) {
                    Registro.linha("download falhou: HTTP ${con.responseCode}")
                    con.disconnect()
                    return@withContext null
                }

                val arquivo = File(context.cacheDir, nomeArquivo)
                FileOutputStream(arquivo).use { fos ->
                    con.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val lidos = input.read(buffer)
                            if (lidos < 0) break
                            fos.write(buffer, 0, lidos)
                            total += lidos
                        }
                        Registro.linha("download completo: ${total / 1024}KB")
                    }
                }
                con.disconnect()
                arquivo
            } catch (e: Exception) {
                Registro.linha("erro no download: ${e.message}")
                null
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
