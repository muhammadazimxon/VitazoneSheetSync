package com.vitazone.sync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitazone.sync.SettingsStore
import com.vitazone.sync.SyncService
import com.vitazone.sync.model.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

@Composable
fun App(store: SettingsStore) {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(store.load()) }
    var saved by remember { mutableStateOf(s) }
    val logs = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var lastRun by remember { mutableStateOf("—") }
    var nextRun by remember { mutableStateOf("—") }
    var showPassword by remember { mutableStateOf(false) }
    val mutex = remember { Mutex() }

    fun log(msg: String) {
        logs.add(0, "${LocalDateTime.now().format(TIME)}  $msg")
        if (logs.size > 1000) logs.removeAt(logs.lastIndex)
    }

    // лог из фоновых потоков → в UI-поток
    val service = remember { SyncService { m -> scope.launch(Dispatchers.Main) { log(m) } } }

    suspend fun guarded(title: String, block: suspend () -> Unit) {
        if (!mutex.tryLock()) { log("Уже выполняется другая операция, подождите"); return }
        running = true
        try {
            log("▶ $title")
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("✖ ОШИБКА: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            running = false
            mutex.unlock()
        }
    }

    fun saveSettings() {
        store.save(s); saved = s
        log("Настройки сохранены (${store.path})")
    }

    // Авто-выгрузка по сохранённым настройкам
    LaunchedEffect(saved.autoEnabled, saved.autoIntervalMinutes) {
        if (!saved.autoEnabled) { nextRun = "—"; return@LaunchedEffect }
        val minutes = saved.autoIntervalMinutes.coerceAtLeast(1)
        log("Авто-выгрузка включена: каждые $minutes мин")
        while (isActive) {
            nextRun = LocalDateTime.now().plusMinutes(minutes.toLong()).format(TIME)
            delay(minutes * 60_000L)
            guarded("Авто-выгрузка") { service.run(saved) }
            lastRun = LocalDateTime.now().format(TIME)
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("1С → Google Sheets", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Отчёты: «Продажа отчети» (Номенклатура-Куратор) и «Остатки2» (Вариант2)",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Section("1С (OData)") {
                        Field("Адрес OData", s.odataUrl, "http://localhost/optom/odata/standard.odata/") { s = s.copy(odataUrl = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Логин", s.login, modifier = Modifier.weight(1f)) { s = s.copy(login = it) }
                            OutlinedTextField(
                                value = s.password,
                                onValueChange = { s = s.copy(password = it) },
                                label = { Text("Пароль") },
                                singleLine = true,
                                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "скрыть" else "показать") }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Section("Google Sheets (Apps Script doPost)") {
                        Field("URL веб-приложения", s.scriptUrl, "https://script.google.com/macros/s/…/exec") { s = s.copy(scriptUrl = it) }
                        Field("Секрет (как в Code.gs)", s.secret) { s = s.copy(secret = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Лист продаж", s.salesSheet, modifier = Modifier.weight(1f)) { s = s.copy(salesSheet = it) }
                            Field("Лист остатков", s.stockSheet, modifier = Modifier.weight(1f)) { s = s.copy(stockSheet = it) }
                            Field("Лист по дням", s.dailySheet, modifier = Modifier.weight(1f)) { s = s.copy(dailySheet = it) }
                        }
                    }

                    Section("Период и режим") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Field("С (дд.мм.гггг), пусто = весь период", s.periodFrom, modifier = Modifier.weight(1f)) { s = s.copy(periodFrom = it) }
                            Field("По (дд.мм.гггг), пусто = сегодня", s.periodTo, modifier = Modifier.weight(1f)) { s = s.copy(periodTo = it) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = s.includeDaily, onCheckedChange = { s = s.copy(includeDaily = it) })
                            Text("Выгружать остатки по дням (отдельный лист)")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Switch(checked = s.autoEnabled, onCheckedChange = { s = s.copy(autoEnabled = it) })
                            Text("Авто-выгрузка каждые")
                            OutlinedTextField(
                                value = s.autoIntervalMinutes.toString(),
                                onValueChange = { v -> v.filter(Char::isDigit).take(5).toIntOrNull()?.let { s = s.copy(autoIntervalMinutes = it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(100.dp),
                            )
                            Text("мин")
                        }
                        Text(
                            "Последняя авто-выгрузка: $lastRun   •   Следующая: $nextRun",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { saveSettings() }, enabled = s != saved) { Text("Сохранить") }
                        OutlinedButton(onClick = { scope.launch { guarded("Проверка 1С") { service.checkOneC(s) } } }, enabled = !running) { Text("Проверить 1С") }
                        OutlinedButton(onClick = { scope.launch { guarded("Проверка Google") { service.checkGoogle(s) } } }, enabled = !running) { Text("Проверить Google") }
                        Spacer(Modifier.weight(1f))
                        if (running) CircularProgressIndicator(Modifier.size(28.dp))
                        Button(
                            onClick = {
                                if (s != saved) saveSettings()
                                scope.launch { guarded("Выгрузка") { service.run(s) } }
                            },
                            enabled = !running,
                        ) { Text("Выгрузить сейчас") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Журнал", fontWeight = FontWeight.SemiBold)
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                ) {
                    LazyColumn(Modifier.padding(8.dp)) {
                        items(logs) { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if ("ОШИБКА" in line || "НЕ опубликованы" in line) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String = "",
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        modifier = modifier,
    )
}
