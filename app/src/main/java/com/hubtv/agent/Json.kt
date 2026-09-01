package com.hubtv.agent

import org.json.JSONObject

/**
 * O `optString` do org.json devolve a STRING "null" quando o campo veio como
 * null no JSON — e era literalmente isso que aparecia escrito na TV
 * ("Venc: null", "null" em cima do banner). Sempre usar `texto()` no lugar.
 */
fun JSONObject.texto(chave: String, padrao: String = ""): String {
    if (isNull(chave)) return padrao
    val v = optString(chave, padrao)
    return if (v == "null" || v.isBlank()) padrao else v
}
