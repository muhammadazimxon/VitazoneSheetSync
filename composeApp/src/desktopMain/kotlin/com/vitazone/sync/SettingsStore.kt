package com.vitazone.sync

import com.vitazone.sync.model.AppSettings
import kotlinx.serialization.json.Json
import java.io.File

/** Настройки хранятся в %APPDATA%\VitazoneSheetSync\settings.json */
class SettingsStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val file: File = run {
        val root = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        File(root, "VitazoneSheetSync").apply { mkdirs() }.resolve("settings.json")
    }

    fun load(): AppSettings = try {
        if (file.exists()) json.decodeFromString(AppSettings.serializer(), file.readText(Charsets.UTF_8)) else AppSettings()
    } catch (_: Exception) {
        AppSettings()
    }

    fun save(s: AppSettings) {
        file.writeText(json.encodeToString(AppSettings.serializer(), s), Charsets.UTF_8)
    }

    val path: String get() = file.absolutePath
}
