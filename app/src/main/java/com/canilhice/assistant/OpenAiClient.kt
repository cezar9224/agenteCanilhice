package com.canilhice.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OpenAiClient(private val apiKey: String) {
    private var previousResponseId: String? = null

    fun ask(question: String): String {
        val body = JSONObject().apply {
            put("model", "gpt-5.4-mini")
            put("instructions", "Você é Canilhice, um assistente pessoal de voz. Responda em português do Brasil, de forma clara, útil, cordial e breve, ideal para ser ouvida. Não use markdown.")
            put("input", question)
            put("max_output_tokens", 500)
            previousResponseId?.let { put("previous_response_id", it) }
        }
        val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) {
            val message = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault("Erro ${connection.responseCode}")
            val friendlyMessage = when {
                connection.responseCode == 401 -> "A chave da OpenAI é inválida ou foi revogada."
                connection.responseCode == 429 && message.contains("quota", ignoreCase = true) ->
                    "Sua conta da API da OpenAI está sem créditos. Verifique o faturamento da API."
                connection.responseCode == 429 -> "Muitas solicitações foram feitas. Aguarde um pouco e tente novamente."
                connection.responseCode >= 500 -> "A OpenAI está temporariamente indisponível. Tente novamente em alguns minutos."
                else -> message.substringBefore("For more information").trim()
            }
            throw IllegalStateException(friendlyMessage)
        }
        val json = JSONObject(raw)
        previousResponseId = json.optString("id").ifBlank { null }
        val output = json.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.optJSONObject(j) ?: continue
                if (item.optString("type") == "output_text") return item.optString("text")
            }
        }
        return "Não consegui formular uma resposta agora. Tente novamente."
    }
}
