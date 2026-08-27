package com.hubtv.agent

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A identidade do agente perante o adbd.
 *
 * O app se apresenta como se fosse um computador: gera um par de chaves RSA
 * e assina o handshake do ADB com ele. Quando alguem marca "sempre permitir"
 * no dialogo da TV, o Android grava a chave publica em /data/misc/adb/adb_keys.
 * Esse arquivo sobrevive ao reboot, entao a chave e gerada UMA vez.
 *
 * O certificado X.509 e montado aqui na mao (DER puro). Isso evita de vez as
 * bibliotecas que quebravam no Android: o BouncyCastle some classes de ASN.1
 * do classpath, e o sun-security-android e descartado pelo AGP por trazer
 * classes no namespace 'sun.*'. Com DER manual so usamos java.security.
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val arquivoChave = File(context.filesDir, "adb_key.pk8")
    private val arquivoCert = File(context.filesDir, "adb_cert.der")

    private val chavePrivada: PrivateKey
    private val certificado: X509Certificate

    /** true quando a identidade acabou de nascer nesta execucao. */
    val identidadeNova: Boolean

    init {
        api = Build.VERSION.SDK_INT

        if (arquivoChave.exists() && arquivoCert.exists()) {
            chavePrivada = lerChave()
            certificado = lerCertificado()
            identidadeNova = false
        } else {
            val gerada = gerarIdentidade()
            chavePrivada = gerada.first
            certificado = gerada.second
            identidadeNova = true
        }
    }

    override fun getPrivateKey(): PrivateKey = chavePrivada
    override fun getCertificate(): X509Certificate = certificado
    override fun getDeviceName(): String = NOME_NO_DIALOGO

    // -----------------------------------------------------------------

    private fun lerChave(): PrivateKey {
        val bytes = arquivoChave.readBytes()
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun lerCertificado(): X509Certificate {
        arquivoCert.inputStream().use { entrada ->
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(entrada) as X509Certificate
        }
    }

    private fun gerarIdentidade(): Pair<PrivateKey, X509Certificate> {
        val gerador = KeyPairGenerator.getInstance("RSA")
        gerador.initialize(2048, SecureRandom())
        val par: KeyPair = gerador.generateKeyPair()

        val certDer = montarCertificado(par)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        arquivoChave.writeBytes(par.private.encoded)
        arquivoCert.writeBytes(certDer)
        return Pair(par.private, cert)
    }

    /**
     * Monta um certificado X.509 v1 auto-assinado, em DER, sem bibliotecas.
     * O adbd so precisa de um certificado bem-formado; no fluxo TCP legado
     * (tcpip 5555) o que vale mesmo e a chave publica.
     */
    private fun montarCertificado(par: KeyPair): ByteArray {
        val agora = System.currentTimeMillis()
        val inicio = Date(agora - 24L * 60 * 60 * 1000)          // ontem
        val fim = Date(agora + 30L * 365 * 24 * 60 * 60 * 1000)  // ~30 anos

        // AlgorithmIdentifier: sha256WithRSAEncryption (1.2.840.113549.1.1.11) + NULL
        val algId = seq(cat(
            oid(bytes(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B)),
            nulo()
        ))

        // Name = SEQUENCE OF SET OF { OID(CN=2.5.4.3), UTF8String "HubTV Agent" }
        val nome = seq(set(seq(cat(
            oid(bytes(0x55, 0x04, 0x03)),
            tlv(0x0C, "HubTV Agent".toByteArray(Charsets.UTF_8))
        ))))

        val serial = tlv(0x02, BigInteger.valueOf(agora).toByteArray())
        val validade = seq(cat(gtime(inicio), gtime(fim)))
        val spki = par.public.encoded  // ja e o SubjectPublicKeyInfo em DER

        // TBSCertificate (v1: sem campo version)
        val tbs = seq(cat(serial, algId, nome, validade, nome, spki))

        val assinatura = Signature.getInstance("SHA256withRSA").run {
            initSign(par.private)
            update(tbs)
            sign()
        }
        val bitSig = tlv(0x03, cat(bytes(0x00), assinatura))  // BIT STRING, 0 bits nao usados

        return seq(cat(tbs, algId, bitSig))
    }

    // ---- codificador DER minimo ----
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun cat(vararg partes: ByteArray): ByteArray {
        var total = 0; for (p in partes) total += p.size
        val out = ByteArray(total); var o = 0
        for (p in partes) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }
    private fun tlv(tag: Int, conteudo: ByteArray): ByteArray {
        val comp = if (conteudo.size < 0x80) {
            byteArrayOf(conteudo.size.toByte())
        } else {
            var v = conteudo.size
            val tmp = ArrayList<Byte>()
            while (v > 0) { tmp.add(0, (v and 0xFF).toByte()); v = v ushr 8 }
            val b = ByteArray(tmp.size + 1)
            b[0] = (0x80 or tmp.size).toByte()
            for (i in tmp.indices) b[i + 1] = tmp[i]
            b
        }
        return cat(byteArrayOf(tag.toByte()), comp, conteudo)
    }
    private fun seq(c: ByteArray) = tlv(0x30, c)
    private fun set(c: ByteArray) = tlv(0x31, c)
    private fun oid(c: ByteArray) = tlv(0x06, c)
    private fun nulo() = tlv(0x05, ByteArray(0))
    private fun gtime(d: Date): ByteArray {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.ROOT)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x18, fmt.format(d).toByteArray(Charsets.US_ASCII))  // GeneralizedTime
    }

    companion object {
        /** Aparece no dialogo de autorizacao da TV. */
        const val NOME_NO_DIALOGO = "HubTV-Agent"

        @Volatile
        private var instancia: AdbManager? = null

        @JvmStatic
        fun get(context: Context): AdbManager =
            instancia ?: synchronized(this) {
                instancia ?: AdbManager(context.applicationContext).also { instancia = it }
            }
    }
}
