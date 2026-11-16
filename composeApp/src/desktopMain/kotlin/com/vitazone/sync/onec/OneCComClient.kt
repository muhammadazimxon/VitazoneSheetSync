package com.vitazone.sync.onec

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class ComException(message: String) : Exception(message)

/** Результат запроса: заголовки колонок + строки (все значения — строки) */
class Table(val columns: List<String>, val rows: List<List<String>>) {
    private val index = columns.withIndex().associate { it.value to it.index }
    fun str(row: List<String>, col: String): String = index[col]?.let { row.getOrNull(it) } ?: ""
    fun num(row: List<String>, col: String): Double = str(row, col).toDoubleOrNull() ?: 0.0
}

/**
 * Прямое подключение к ФАЙЛОВОЙ (офлайн) базе 1С через COM-соединение V83.COMConnector.
 * Веб-сервер и публикация не нужны. Нужна установленная платформа 1С на этом ПК
 * и один раз зарегистрированный comcntr.dll (кнопка «Зарегистрировать COM 1С»).
 */
class OneCComClient(
    basePath: String,
    private val login: String,
    private val password: String,
) {
    data class Query(val name: String, val text: String)

    /** Папка базы (где лежит 1Cv8.1CD) */
    val baseDir: String = basePath.trim().trim('"').let {
        if (it.endsWith(".1CD", ignoreCase = true)) File(it).parent ?: it else it
    }.trimEnd('\\', '/')

    private fun connectionString(): String {
        fun q(s: String) = s.replace("\"", "\"\"")
        return "File=\"${q(baseDir)}\";Usr=\"${q(login)}\";Pwd=\"${q(password)}\";"
    }

    fun run(queries: List<Query>, log: (String) -> Unit): Map<String, Table> {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            throw ComException("Подключение к файловой базе 1С работает только на Windows (на ПК, где установлена 1С)")
        }
        if (baseDir.isBlank()) throw ComException("Укажите путь к папке базы 1С (где лежит файл 1Cv8.1CD)")
        if (!File(baseDir, "1Cv8.1CD").exists()) {
            throw ComException("В папке «$baseDir» нет файла 1Cv8.1CD. Укажите папку файловой базы 1С.")
        }

        val dir = Files.createTempDirectory("vz1c").toFile()
        try {
            val script = File(dir, "run1c.ps1")
            val res = OneCComClient::class.java.getResourceAsStream("/run1c.ps1")
                ?: throw ComException("Не найден встроенный скрипт run1c.ps1")
            // UTF-8 с BOM — чтобы Windows PowerShell 5 точно прочитал как UTF-8
            script.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + res.readBytes())

            val errFile = File(dir, "error.txt")
            val outFiles = queries.associate { it.name to File(dir, "${it.name}.tsv") }
            val job = buildJsonObject {
                put("connection", connectionString())
                put("errFile", errFile.absolutePath)
                putJsonArray("queries") {
                    queries.forEach { q ->
                        addJsonObject {
                            put("name", q.name)
                            put("text", q.text)
                            put("out", outFiles.getValue(q.name).absolutePath)
                        }
                    }
                }
            }
            val jobFile = File(dir, "job.json").apply { writeText(job.toString(), Charsets.UTF_8) }

            // Сначала 64-битный PowerShell, если COM не найден — 32-битный (для 32-битной 1С)
            val root = System.getenv("SystemRoot") ?: "C:\\Windows"
            val shells = listOf(
                "$root\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "$root\\SysWOW64\\WindowsPowerShell\\v1.0\\powershell.exe",
            ).filter { File(it).exists() }.ifEmpty { listOf("powershell.exe") }

            var code = -1
            var err = ""
            for (ps in shells) {
                errFile.delete()
                val p = ProcessBuilder(
                    ps, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath, "-job", jobFile.absolutePath,
                ).redirectErrorStream(true).start()
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    when {
                        line.startsWith("START ") -> log("  1С: запрос «${line.removePrefix("START ")}»…")
                        line.startsWith("PROGRESS ") || line.startsWith("DONE ") -> {
                            val parts = line.split(' ')
                            if (parts.size >= 3) log("  1С: «${parts[1]}» строк: ${parts[2]}" + if (line.startsWith("DONE")) " ✔" else "")
                        }
                    }
                }
                code = p.waitFor()
                err = if (errFile.exists()) errFile.readText(Charsets.UTF_8).trim() else ""
                if (code == 0) break
                if (code != 3) break // 3 = COM не зарегистрирован в этой разрядности — пробуем другую
            }

            if (code != 0) {
                throw ComException(
                    when (code) {
                        3 -> "Компонента 1С V83.COMConnector не зарегистрирована. Нажмите «Зарегистрировать COM 1С» " +
                            "(Windows попросит права администратора) и повторите. Подробности: $err"
                        4 -> "Не удалось открыть базу 1С «$baseDir»: $err. Проверьте путь, логин и пароль."
                        else -> "Ошибка 1С: ${err.ifBlank { "код $code" }}"
                    }
                )
            }

            return outFiles.mapValues { (_, f) ->
                val lines = f.readLines(Charsets.UTF_8).let { l ->
                    if (l.isNotEmpty() && l[0].startsWith("\uFEFF")) listOf(l[0].substring(1)) + l.drop(1) else l
                }
                if (lines.isEmpty()) Table(emptyList(), emptyList())
                else Table(lines[0].split('\t'), lines.drop(1).filter { it.isNotEmpty() }.map { it.split('\t') })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    companion object {
        /** Литерал даты для языка запросов 1С */
        fun dateLiteral(d: LocalDate?, endOfDay: Boolean): String =
            if (d == null) "ДАТАВРЕМЯ(1, 1, 1)"
            else if (endOfDay) "ДАТАВРЕМЯ(${d.year}, ${d.monthValue}, ${d.dayOfMonth}, 23, 59, 59)"
            else "ДАТАВРЕМЯ(${d.year}, ${d.monthValue}, ${d.dayOfMonth})"

        /**
         * Регистрирует comcntr.dll установленной платформы 1С (нужны права администратора —
         * Windows покажет запрос UAC). Возвращает список зарегистрированных файлов.
         */
        fun registerCom(): List<String> {
            val roots = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
                .map { File(it, "1cv8") }.filter { it.isDirectory }
            // последняя версия платформы в каждой папке (64 и 32 бит)
            val dlls = roots.mapNotNull { root ->
                root.listFiles()?.filter { File(it, "bin/comcntr.dll").exists() }
                    ?.maxWithOrNull(compareBy<File>({ v -> v.name.split('.').map { it.toIntOrNull() ?: 0 }.joinToString(".") { it.toString().padStart(6, '0') } }))
                    ?.let { File(it, "bin/comcntr.dll").absolutePath }
            }
            if (dlls.isEmpty()) throw ComException("Не найдена платформа 1С (comcntr.dll) в Program Files\\1cv8")
            for (dll in dlls) {
                val cmd = "Start-Process -FilePath regsvr32.exe -ArgumentList '/s','\"$dll\"' -Verb RunAs -Wait"
                val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", cmd)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                if (p.waitFor() != 0) throw ComException("Не удалось зарегистрировать $dll: $out")
            }
            return dlls
        }
    }
}
