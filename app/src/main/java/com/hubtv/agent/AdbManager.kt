package com.hubtv.agent

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder
import java.io.ByteArrayInputStream
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
import java.util.Calendar

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

        val nome = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, "HubTV Agent")
            .addRDN(BCStyle.O, "HubTV")
            .build()

        val inicio = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        // validade longa de proposito: a identidade precisa durar tanto
        // quanto o aparelho ficar em campo
        val fim = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }

        // Assinatura pela API de BAIXO NIVEL do BouncyCastle (builders "Bc").
        // Os algoritmos sao fixados na mao para NAO passar pelo
        // DefaultSignatureNameFinder - a classe que quebrava com
        // NoClassDefFoundError (EdECObjectIdentifiers) na versao anterior.
        val algAssinatura = AlgorithmIdentifier(
            PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE
        )
        val algDigest = AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)
        val chaveParam = PrivateKeyFactory.createKey(par.private.encoded)
        val assinador = BcRSAContentSignerBuilder(algAssinatura, algDigest).build(chaveParam)

        val spki = SubjectPublicKeyInfo.getInstance(par.public.encoded)
        val holder = X509v3CertificateBuilder(
            nome,
            BigInteger.valueOf(System.currentTimeMillis()),
            inicio.time,
            fim.time,
            nome,
            spki
        ).build(assinador)

        // Converte o certificado DER usando a fabrica do proprio Android,
        // sem depender do JcaX509CertificateConverter.
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate

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
