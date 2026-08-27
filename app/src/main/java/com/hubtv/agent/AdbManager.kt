package com.hubtv.agent

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * A identidade do agente perante o adbd.
 *
 * O app se apresenta como se fosse um computador: gera um par de chaves RSA
 * e assina o handshake do ADB com ele. Quando alguem marca "sempre permitir"
 * no dialogo da TV, o Android grava a chave publica em /data/misc/adb/adb_keys.
 *
 * Esse arquivo sobrevive ao reboot. Por isso a chave e gerada UMA vez e
 * guardada no armazenamento privado do app - regerar significaria perder a
 * autorizacao e precisar de um PC outra vez.
 *
 * O certificado e criado com sun.security.x509 (via a lib sun-security-android),
 * que e o caminho recomendado pela propria libadb. Tentar isso com BouncyCastle
 * no Android quebra por classes que faltam no classpath - foi o que derrubou
 * as versoes anteriores.
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

        val agora = System.currentTimeMillis()
        val inicio = Date(agora - 24L * 60 * 60 * 1000)          // ontem
        val fim = Date(agora + 30L * 365 * 24 * 60 * 60 * 1000)  // ~30 anos
        val dono = X500Name("CN=HubTV Agent, O=HubTV")

        val info = X509CertInfo()
        info.set(X509CertInfo.VERSION, CertificateVersion(CertificateVersion.V3))
        info.set(X509CertInfo.SERIAL_NUMBER, CertificateSerialNumber(BigInteger.valueOf(agora)))
        info.set(X509CertInfo.SUBJECT, dono)
        info.set(X509CertInfo.ISSUER, dono)
        info.set(X509CertInfo.KEY, CertificateX509Key(par.public))
        info.set(X509CertInfo.VALIDITY, CertificateValidity(inicio, fim))
        info.set(X509CertInfo.ALGORITHM_ID, CertificateAlgorithmId(AlgorithmId.get("SHA256withRSA")))

        // Assina, le o algoritmo real de volta e reassina - idioma padrao do
        // sun.security para que o algoritmo entre corretamente no certificado.
        var cert = X509CertImpl(info)
        cert.sign(par.private, "SHA256withRSA")
        val algReal = cert.get(X509CertImpl.SIG_ALG) as AlgorithmId
        info.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, algReal)
        cert = X509CertImpl(info)
        cert.sign(par.private, "SHA256withRSA")

        arquivoChave.writeBytes(par.private.encoded)
        arquivoCert.writeBytes(cert.encoded)
        return Pair(par.private, cert)
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
