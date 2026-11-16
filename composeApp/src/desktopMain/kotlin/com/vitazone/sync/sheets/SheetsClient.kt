package com.vitazone.sync.sheets

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

class SheetsException(message: String) : Exception(message)

/**
 * Отправка данных в Google Sheets через веб-приложение Apps Script (doPost).
 *
 * Формат запроса (JSON):
 * {
 *   "secret": "...",
 *   "sheet": "Продажа",
 *   "mode": "replace" | "append",   // replace — первая порция: очистить старые данные
 *   "offset": 0,                     // с какой строки данных писать (0 = строка 2 листа)
 *   "total": 12345,                  // всего строк в выгрузке
 *   "header": ["Контрагенты", ...],  // заголовок (для нового листа)
 *   "textCols": [3, 8],              // колонки (с 0), которые писать как текст (коды с ведущими нулями)
 *   "dateCols": [4],                 // колонки-даты, значения "yyyy-MM-dd"
 *   "rows": [[...], ...]
 * }
 * Ответ: {"ok": true, "written": N} или {"ok": false, "error": "..."}
 */
class SheetsClient(private val scriptUrl: String, private val secret: String) {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        // Apps Script отвечает 302 на script.googleusercontent.com — переходим GET'ом
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun ping(): JsonObject = post(buildJsonObject {
        put("secret", secret)
        put("ping", true)
    })

    fun upload(
        sheet: String,
        header: List<String>,
        rows: List<List<Any?>>,
        textCols: List<Int> = emptyList(),
        dateCols: List<Int> = emptyList(),
        chunkSize: Int = 4000,
        progress: (String) -> Unit = {},
    ) {
        val chunks = if (rows.isEmpty()) listOf(emptyList()) else rows.chunked(chunkSize)
        chunks.forEachIndexed { i, part ->
            val payload = buildJsonObject {
                put("secret", secret)
                put("sheet", sheet)
                put("mode", if (i == 0) "replace" else "append")
                put("offset", i * chunkSize)
                put("total", rows.size)
                putJsonArray("header") { header.forEach { add(it) } }
                putJsonArray("textCols") { textCols.forEach { add(it) } }
                putJsonArray("dateCols") { dateCols.forEach { add(it) } }
                putJsonArray("rows") {
                    part.forEach { r ->
                        addJsonArray {
                            r.forEach { v ->
                                when (v) {
                                    null -> add(JsonNull)
                                    is Number -> add(v)
                                    is Boolean -> add(v)
                                    else -> add(v.toString())
                                }
                            }
                        }
                    }
                }
            }
            post(payload)
            progress("  «$sheet»: ${minOf((i + 1) * chunkSize, rows.size)} / ${rows.size}")
        }
    }

    private fun post(payload: JsonObject): JsonObject {
        if (scriptUrl.isBlank()) throw SheetsException("Не указан URL Google Apps Script")
        val request = HttpRequest.newBuilder(URI.create(scriptUrl.trim()))
            .header("Content-Type", "application/json; charset=utf-8")
            .timeout(Duration.ofMinutes(6))
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), UTF_8))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        } catch (e: Exception) {
            throw SheetsException("Нет связи с Google: ${e.message ?: e.javaClass.simpleName}")
        }
        val body = response.body()
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw SheetsException(
                "Google вернул не JSON (HTTP ${response.statusCode()}). " +
                    "Проверьте, что веб-приложение развернуто с доступом «Все» (Anyone). Ответ: ${body.take(200)}"
            )
        }
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw SheetsException("Google: ${obj["error"]?.jsonPrimitive?.contentOrNull ?: body.take(300)}")
        }
        return obj
    }
}
