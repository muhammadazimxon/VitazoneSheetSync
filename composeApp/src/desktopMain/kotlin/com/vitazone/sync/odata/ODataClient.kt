package com.vitazone.sync.odata

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64

class ODataException(message: String) : Exception(message)

/**
 * Клиент стандартного интерфейса OData 1С.
 * baseUrl: http://localhost/<имя_публикации>/odata/standard.odata/
 */
class ODataClient(baseUrl: String, login: String, password: String) {

    private val base: String = run {
        val u = baseUrl.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            throw ODataException(
                "«Адрес OData» должен быть веб-адресом публикации 1С, например " +
                    "http://localhost/optom/odata/standard.odata/ — а не путём к папке базы ($u). " +
                    "Опубликуйте базу на веб-сервере (см. ИНСТРУКЦИЯ.md, раздел 1)."
            )
        }
        u.trimEnd('/') + "/"
    }
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$login:$password".toByteArray(UTF_8))
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** GET относительного пути, ответ — JSON-объект */
    fun get(path: String): JsonObject {
        val url = encodeUrl(base + path)
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .timeout(Duration.ofMinutes(15))
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        } catch (e: Exception) {
            throw ODataException("Нет связи с 1С ($base): ${e.message ?: e.javaClass.simpleName}")
        }
        val body = response.body()
        when (response.statusCode()) {
            in 200..299 -> {}
            401 -> throw ODataException("1С: неверный логин или пароль (401)")
            404 -> throw ODataException("1С: не найдено (404). Проверьте адрес публикации и состав OData. ${extractError(body)}")
            else -> throw ODataException("1С: HTTP ${response.statusCode()} ${extractError(body)}")
        }
        return try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw ODataException("1С вернула не JSON: ${body.take(300)}")
        }
    }

    /** Массив value из ответа */
    fun values(path: String): List<JsonObject> =
        get(path)["value"]?.jsonArray?.map { it.jsonObject } ?: emptyList()

    /** Список опубликованных сущностей (служебный документ OData) */
    fun entitySets(): List<String> =
        values("?\$format=json").mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull ?: it["url"]?.jsonPrimitive?.contentOrNull }

    private fun extractError(body: String): String = try {
        val o = json.parseToJsonElement(body).jsonObject
        o["odata.error"]?.jsonObject?.get("message")?.jsonObject?.get("value")?.jsonPrimitive?.content ?: body.take(300)
    } catch (_: Exception) {
        body.take(300)
    }

    companion object {
        private const val SAFE = "-._~:/?#[]@!$&'()*+,;=%"

        /** Кодирует кириллицу и пробелы в URL (остальное оставляет как есть) */
        fun encodeUrl(s: String): String {
            val sb = StringBuilder()
            for (ch in s) {
                if ((ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || SAFE.indexOf(ch) >= 0) {
                    sb.append(ch)
                } else {
                    for (b in ch.toString().toByteArray(UTF_8)) {
                        sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
                    }
                }
            }
            return sb.toString()
        }
    }
}

// ---------- помощники чтения полей ----------
fun JsonObject.str(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: ""

fun JsonObject.num(name: String): Double {
    val p = this[name] as? JsonPrimitive ?: return 0.0
    return p.contentOrNull?.toDoubleOrNull() ?: 0.0
}

fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: false

/** "2026-09-14T00:00:00" → "2026-09-14", пустая дата 1С → "" */
fun JsonObject.date(name: String): String {
    val s = str(name)
    if (s.length < 10 || s.startsWith("0001")) return ""
    return s.substring(0, 10)
}
