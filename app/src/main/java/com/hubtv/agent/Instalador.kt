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

        val resultado = instalarArquivo(context, arquivo)
        arquivo.delete()
        return resultado
    }

    /**
     * O `pm install` roda como usuario **shell**, que nao consegue entrar em
     * /data/user/0/<pacote>/ — dai o "Unable to open file / Consider using a
     * file under /data/local/tmp/". Por isso o APK e baixado no cache EXTERNO
     * (que o shell le) e copiado para /data/local/tmp antes de instalar.
     */
    private suspend fun instalarArquivo(context: Context, arquivo: File): Resultado {
        val destino = "/data/local/tmp/${arquivo.name}"

        val copia = Adb.shell(context, "cp '${arquivo.absolutePath}' '$destino' && chmod 644 '$destino'")
        val copiou = copia is Adb.Resultado.Ok &&
            !copia.saida.contains("No such file", true) &&
            !copia.saida.contains("Permission denied", true) &&
            !copia.saida.contains("can't open", true)

        // Se a copia falhar, ainda vale tentar direto do caminho externo.
        val caminho = if (copiou) destino else arquivo.absolutePath
        if (!copiou) {
            Registro.linha("cp para /data/local/tmp falhou, tentando direto: ${(copia as? Adb.Resultado.Ok)?.saida ?: ""}")
        }

        val r = Adb.shell(context, "pm install -r '$caminho'")
        if (copiou) Adb.shell(context, "rm -f '$destino'")

        return when (r) {
            is Adb.Resultado.Ok ->
                if (r.saida.contains("Success", ignoreCase = true)) {
                    Resultado.Ok(r.saida.trim().ifEmpty { "instalado" })
                } else {
                    Resultado.Falha("pm install: ${r.saida.trim().take(400)}")
                }
            is Adb.Resultado.Falha -> Resultado.Falha(r.motivo)
        }
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
            // Cache EXTERNO de proposito: o cache interno fica em
            // /data/user/0/<pacote>/, onde o usuario shell do `pm install`
            // nao consegue entrar. Ver instalarArquivo().
            val pasta = context.externalCacheDir ?: context.cacheDir
            val arquivo = File(pasta, nomeArquivo)

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
