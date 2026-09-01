package com.hubtv.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Download e instalacao de APK. Usado tanto pela fila de comandos do painel
 * quanto pelo launcher, quando o cliente clica num app que ainda nao esta no
 * aparelho e o painel forneceu o arquivo.
 */
object Instalador {

    sealed class Resultado {
        data class Ok(val detalhe: String) : Resultado()
        data class Falha(val motivo: String) : Resultado()
    }

    /**
     * Baixa e instala. `aoProgredir` recebe o percentual (0..100) ou -1 quando
     * o servidor nao informa o tamanho total.
     */
    suspend fun baixarEInstalar(
        context: Context,
        url: String,
        nomeArquivo: String = "install_temp.apk",
        aoProgredir: (Int) -> Unit = {}
    ): Resultado {
        if (url.isBlank()) return Resultado.Falha("sem URL do APK")

        val arquivo = baixar(context, url, nomeArquivo, aoProgredir)
            ?: return Resultado.Falha("falha no download")

        Registro.linha("instalando ${arquivo.name} (${arquivo.length() / 1024}KB)")

        val resultado = when (val r = Adb.shell(context, "pm install -r ${arquivo.absolutePath}")) {
            is Adb.Resultado.Ok ->
                if (r.saida.contains("Success", ignoreCase = true)) {
                    Resultado.Ok(r.saida.trim().ifEmpty { "instalado" })
                } else {
                    Resultado.Falha("pm install: ${r.saida.trim()}")
                }
            is Adb.Resultado.Falha -> Resultado.Falha(r.motivo)
        }

        arquivo.delete()
        return resultado
    }

    suspend fun baixar(
        context: Context,
        url: String,
        nomeArquivo: String,
        aoProgredir: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val con = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Registro.linha("erro ao abrir $url: ${e.message}")
            return@withContext null
        }

        try {
            con.connectTimeout = 30_000
            con.readTimeout = 60_000
            con.instanceFollowRedirects = true

            if (con.responseCode !in 200..299) {
                Registro.linha("download falhou: HTTP ${con.responseCode}")
                return@withContext null
            }

            val total = con.contentLength.toLong()
            val arquivo = File(context.cacheDir, nomeArquivo)

            FileOutputStream(arquivo).use { saida ->
                con.inputStream.use { entrada ->
                    val buffer = ByteArray(16 * 1024)
                    var baixado = 0L
                    var ultimoPct = -1

                    while (true) {
                        val lidos = entrada.read(buffer)
                        if (lidos < 0) break
                        saida.write(buffer, 0, lidos)
                        baixado += lidos

                        if (total > 0) {
                            val pct = ((baixado * 100) / total).toInt()
                            if (pct != ultimoPct) {
                                ultimoPct = pct
                                aoProgredir(pct)
                            }
                        } else {
                            aoProgredir(-1)
                        }
                    }
                    Registro.linha("download completo: ${baixado / 1024}KB")
                }
            }
            arquivo
        } catch (e: Exception) {
            Registro.linha("erro no download: ${e.message}")
            null
        } finally {
            con.disconnect()
        }
    }
}
