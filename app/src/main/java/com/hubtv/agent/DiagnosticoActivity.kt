package com.hubtv.agent

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tela de devolutiva no proprio aparelho.
 *
 * Responde, sem precisar do painel: o ADB esta conectado? o check-in esta
 * acontecendo? quais comandos chegaram aqui e o que cada um respondeu?
 */
class DiagnosticoActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostico)

        findViewById<Button>(R.id.diag_btn_atualizar).setOnClickListener {
            buscarAgora()
        }
    }

    override fun onResume() {
        super.onResume()
        desenhar()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buscarAgora() {
        val botao = findViewById<Button>(R.id.diag_btn_atualizar)
        botao.isEnabled = false
        botao.text = "Buscando..."

        CoroutineScope(Dispatchers.IO).launch {
            val r = try {
                CheckIn.sincronizar(this@DiagnosticoActivity)
            } catch (e: Exception) {
                CheckIn.Resultado.Falha(e.message ?: "erro")
            }
            handler.post {
                botao.isEnabled = true
                botao.text = "Buscar agora"
                if (r is CheckIn.Resultado.Falha) {
                    android.widget.Toast
                        .makeText(this@DiagnosticoActivity, r.motivo, android.widget.Toast.LENGTH_LONG)
                        .show()
                }
                desenhar()
            }
        }
    }

    private fun desenhar() {
        val prefs = getSharedPreferences("hubtv_agente", MODE_PRIVATE)

        val conectado = try { Adb.conectado(this) } catch (_: Exception) { false }
        pintarEstado(R.id.diag_adb, if (conectado) "Conectado" else "Sem conexao", conectado)

        val checkin = Historico.ultimoCheckin(this)
        pintarEstado(
            R.id.diag_checkin,
            if (checkin.isBlank()) "Nunca" else checkin,
            checkin.isNotBlank()
        )

        val ativado = prefs.getBoolean("ativado", false)
        val expira = prefs.getString("expira_em", "") ?: ""
        val bloqueado = prefs.getBoolean("bloqueado", false)
        val textoLicenca = when {
            bloqueado -> "Bloqueado"
            !ativado -> "Nao ativado"
            expira.isBlank() -> "Ativo"
            else -> "Ate ${dataBonita(expira)}"
        }
        pintarEstado(R.id.diag_licenca, textoLicenca, ativado && !bloqueado)

        montarLista()
    }

    private fun pintarEstado(id: Int, texto: String, bom: Boolean) {
        findViewById<TextView>(id).apply {
            text = texto
            setTextColor(Color.parseColor(if (bom) "#34D399" else "#F87171"))
        }
    }

    private fun montarLista() {
        val lista = findViewById<LinearLayout>(R.id.diag_lista)
        lista.removeAllViews()

        val entradas = Historico.ler(this)
        findViewById<TextView>(R.id.diag_vazio).visibility =
            if (entradas.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        for (e in entradas) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }

            val cabecalho = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            cabecalho.addView(TextView(this).apply {
                text = if (e.sucesso) "OK" else "FALHOU"
                setTextColor(Color.BLACK)
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor(if (e.sucesso) "#34D399" else "#F87171"))
                }
            })

            cabecalho.addView(TextView(this).apply {
                text = e.tipo
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(10)
                layoutParams = lp
            })

            cabecalho.addView(TextView(this).apply {
                text = e.quando
                setTextColor(Color.parseColor("#70FFFFFF"))
                textSize = 12f
            })

            card.addView(cabecalho)

            if (e.saida.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = e.saida.take(600)
                    setTextColor(Color.parseColor("#A0FFFFFF"))
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, dp(8), 0, 0)
                })
            }

            lista.addView(card)
        }
    }

    private fun dataBonita(iso: String): String {
        val p = iso.take(10).split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}" else iso
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}
