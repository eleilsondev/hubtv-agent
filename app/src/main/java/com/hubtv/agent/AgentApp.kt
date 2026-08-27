package com.hubtv.agent

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captura QUALQUER crash e grava num arquivo.
 *
 * Num aparelho de TV nao ha como ler o logcat com facilidade: um erro nao
 * tratado vira "tela preta e fecha", sem pista nenhuma. Aqui a excecao e
 * salva em arquivo e a MainActivity a mostra na tela ao reabrir - o crash
 * deixa de ser invisivel.
 */
class AgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, erro ->
            try {
                gravarErro(erro)
            } catch (_: Throwable) {
                // nunca deixe o proprio handler derrubar de novo
            }
            anterior?.uncaughtException(thread, erro)
        }
    }

    private fun gravarErro(erro: Throwable) {
        val sw = StringWriter()
        erro.printStackTrace(PrintWriter(sw))
        val quando = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date())
        arquivoErro(this).writeText("[$quando]\n$sw")
    }

    companion object {
        fun arquivoErro(app: Application): File = File(app.filesDir, "ultimo_erro.txt")

        fun lerUltimoErro(ctx: android.content.Context): String? {
            val f = File(ctx.filesDir, "ultimo_erro.txt")
            return if (f.exists()) f.readText() else null
        }

        fun limparUltimoErro(ctx: android.content.Context) {
            val f = File(ctx.filesDir, "ultimo_erro.txt")
            if (f.exists()) f.delete()
        }
    }
}
