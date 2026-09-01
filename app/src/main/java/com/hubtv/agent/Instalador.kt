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
     * O `pm install <caminho>` roda como usuario **shell**, que nao consegue
     * entrar em /data/user/0/<pacote>/ — dai o "Unable to open file /
     * Consider using a file under /data/local/tmp/".
     *
     * Sao tres tentativas, da mais confiavel para a menos:
     *  1. streaming: o APK vai pela entrada padrao do `pm install -S`, sem
     *     arquivo nenhum para o shell abrir. E o que o `adb install` faz.
     *  2. copiar para /data/local/tmp e instalar de la.
     *  3. instalar direto do caminho do arquivo (funciona se ele estiver no
     *     armazenamento externo, que o shell enxerga).
     *
     * A saida diz qual caminho funcionou, para o Diagnostico nao virar
     * adivinhacao quando algum firmware bloquear um deles.
     */
    private suspend fun instalarArquivo(context: Context, arquivo: File): Resultado {
        val tentativas = mutableListOf<String>()

        // 1) streaming pelo proprio canal do adb
        val stream = Adb.shellEnviando(
            context, "pm install -r -S ${arquivo.length()}", arquivo
        )
        if (stream is Adb.Resultado.Ok && stream.saida.contains("Success", true)) {
            return Resultado.Ok("instalado via streaming")
        }
        tentativas += "streaming: " + resumo(stream)

        // 2) /data/local/tmp
        val destino = "/data/local/tmp/${arquivo.name}"
        val copia = Adb.shell(context, "cp '${arquivo.absolutePath}' '$destino' 2>&1 && chmod 644 '$destino' 2>&1")
        val copiou = copia is Adb.Resultado.Ok &&
            !copia.saida.contains("denied", true) &&
            !copia.saida.contains("No such file", true) &&
            !copia.saida.contains("can't open", true)

        if (copiou) {
            val r = Adb.shell(context, "pm install -r '$destino'")
            Adb.shell(context, "rm -f '$destino'")
            if (r is Adb.Resultado.Ok && r.saida.contains("Success", true)) {
                return Resultado.Ok("instalado via /data/local/tmp")
            }
            tentativas += "tmp: " + resumo(r)
        } else {
            tentativas += "cp para tmp: " + resumo(copia)
        }

        // 3) direto do caminho onde o arquivo esta
        val direto = Adb.shell(context, "pm install -r '${arquivo.absolutePath}'")
        if (direto is Adb.Resultado.Ok && direto.saida.contains("Success", true)) {
            return Resultado.Ok("instalado direto do arquivo")
        }
        tentativas += "direto: " + resumo(direto)

        val relato = tentativas.joinToString(" | ")
        Registro.linha("as 3 formas de instalar falharam -> $relato")
        return Resultado.Falha(relato.take(600))
    }

    private fun resumo(r: Adb.Resultado): String = when (r) {
        is Adb.Resultado.Ok -> r.saida.trim().replace("\n", " ").take(150).ifEmpty { "sem saida" }
        is Adb.Resultado.Falha -> r.motivo.take(150)
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
