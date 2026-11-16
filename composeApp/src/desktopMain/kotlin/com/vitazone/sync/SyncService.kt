package com.vitazone.sync

import com.vitazone.sync.model.AppSettings
import com.vitazone.sync.odata.ODataClient
import com.vitazone.sync.onec.ComReports
import com.vitazone.sync.onec.OneCComClient
import com.vitazone.sync.onec.OneCQueries
import com.vitazone.sync.odata.OneCRepository
import com.vitazone.sync.report.ReportBuilder
import com.vitazone.sync.sheets.SheetsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

data class SyncPeriod(val from: LocalDate?, val to: LocalDate) {
    override fun toString() = (from?.format(DMY) ?: "начало базы") + " – " + to.format(DMY)

    companion object {
        fun of(s: AppSettings): SyncPeriod {
            fun parse(v: String, name: String): LocalDate? = v.trim().takeIf { it.isNotEmpty() }?.let {
                try { LocalDate.parse(it, DMY) } catch (_: Exception) {
                    throw IllegalArgumentException("Поле «$name»: дата должна быть в формате дд.мм.гггг")
                }
            }
            val from = parse(s.periodFrom, "Период с")
            val to = parse(s.periodTo, "Период по") ?: LocalDate.now()
            require(from == null || !from.isAfter(to)) { "Дата «с» позже даты «по»" }
            return SyncPeriod(from, to)
        }
    }
}

/** Полный цикл: 1С (OData) → расчёт как в отчётах → Google Sheets */
class SyncService(private val log: (String) -> Unit) {

    suspend fun registerCom() = withContext(Dispatchers.IO) {
        val dlls = OneCComClient.registerCom()
        log("COM 1С зарегистрирован ✔ ${dlls.joinToString()}")
    }

    suspend fun checkOneC(s: AppSettings) = withContext(Dispatchers.IO) {
        if (s.source == "COM") {
            val com = OneCComClient(s.basePath, s.login, s.password)
            val t = com.run(listOf(OneCComClient.Query("check", OneCQueries.CHECK)), log).getValue("check")
            val n = t.rows.firstOrNull()?.let { t.str(it, "Количество") } ?: "?"
            log("1С: база «${com.baseDir}» открыта ✔ Номенклатура: $n позиций")
            return@withContext
        }
        val repo = OneCRepository(ODataClient(s.odataUrl, s.login, s.password))
        val missing = repo.missingEntities()
        if (missing.isEmpty()) log("1С: связь есть, все нужные объекты опубликованы в OData ✔")
        else log("1С: связь есть, но НЕ опубликованы в OData: ${missing.joinToString()}")
    }

    suspend fun checkGoogle(s: AppSettings) = withContext(Dispatchers.IO) {
        val r = SheetsClient(s.scriptUrl, s.secret).ping()
        log("Google: связь есть ✔ ${r["sheets"]?.toString() ?: ""}")
    }

    suspend fun run(s: AppSettings) = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val period = SyncPeriod.of(s)
        val sheets = SheetsClient(s.scriptUrl, s.secret)

        log("Период: $period")
        if (s.source == "COM") { runCom(s, period, sheets, started); return@withContext }
        val repo = OneCRepository(ODataClient(s.odataUrl, s.login, s.password))
        log("Загрузка справочников из 1С…")
        val items = repo.items()
        val counterparties = repo.counterparties()
        val contracts = repo.contracts()
        log("  номенклатура: ${items.size}, контрагенты: ${counterparties.size}, договоры: ${contracts.size}")

        // Повторяющиеся баркоды (КОД у 2+ товаров) — не выгружаем нигде
        val excluded = ReportBuilder.duplicateCodeItems(items)
        if (excluded.isNotEmpty()) {
            val codes = excluded.mapNotNull { items[it]?.code }.distinct().sorted()
            log("  исключены повторяющиеся баркоды: ${codes.size} кодов / ${excluded.size} товаров: " +
                codes.take(30).joinToString() + if (codes.size > 30) " …" else "")
        }

        // ---- Продажа отчети (Номенклатура-Куратор) ----
        log("Продажа: чтение оборотов регистра «Остатки»…")
        val turnovers = repo.salesTurnovers(period.from, period.to)
        val sales = ReportBuilder.sales(turnovers, items, counterparties, contracts, excluded)
        log("  строк продаж: ${sales.size}")
        sheets.upload(
            sheet = s.salesSheet,
            header = listOf("Контрагенты", "ИНН", "Номенклатура", "Док номер", "Дата", "Кол-во", "Реализация", "Доля в продаже", "КОД"),
            rows = sales.map { listOf(it.counterparty, it.inn, it.item, it.docNumber, it.date, it.qty, it.sum, it.share, it.code) },
            textCols = listOf(3, 8),
            dateCols = listOf(4),
            progress = log,
        )

        // ---- Остатки2 (Вариант2), Итого ----
        log("Остатки: чтение регистра «Склады»…")
        val stock = ReportBuilder.stock(
            repo.stockTotals(period.from, period.to), items,
            excludedItems = excluded,
        )
        log("  строк остатков: ${stock.size}")
        sheets.upload(
            sheet = s.stockSheet,
            header = listOf("Код, Номенклатура", "Кол во начальный", "Кол во Приход", "Кол во Расход", "Кол во конечный", "Сумма реализации", "Сумма конечный"),
            rows = stock.map { listOf(it.label, it.qtyOpen, it.qtyIn, it.qtyOut, it.qtyClose, it.saleSum, it.sumClose) },
            textCols = listOf(0),
            progress = log,
        )

        // ---- Остатки2 (Вариант2), по дням ----
        if (s.includeDaily) {
            val from = period.from ?: repo.firstStockMoveDate()
            if (from == null) {
                log("Остатки по дням: движений нет")
            } else {
                log("Остатки по дням: чтение движений с ${from.format(DMY)}…")
                val openings = repo.stockOpenings(period.from)
                val moves = repo.stockMoves(from, period.to, log)
                val daily = ReportBuilder.daily(openings, moves, items, excluded)
                log("  строк по дням: ${daily.size}")
                sheets.upload(
                    sheet = s.dailySheet,
                    header = listOf("Дата", "Код, Номенклатура", "Кол во начальный", "Кол во Приход", "Кол во Расход", "Кол во конечный", "Сумма реализации", "Сумма конечный"),
                    rows = daily.map { d ->
                        val r = d.row
                        listOf(d.date, r.label, r.qtyOpen, r.qtyIn, r.qtyOut, r.qtyClose, r.saleSum, r.sumClose)
                    },
                    textCols = listOf(1),
                    dateCols = listOf(0),
                    progress = log,
                )
            }
        }

        val sec = (System.currentTimeMillis() - started) / 1000
        log("Готово ✔ за ${sec / 60} мин ${sec % 60} сек")
    }

    /** Офлайн файловая база 1С → Google Sheets */
    private fun runCom(s: AppSettings, period: SyncPeriod, sheets: SheetsClient, started: Long) {
        val com = OneCComClient(s.basePath, s.login, s.password)
        log("Подключение к базе 1С: ${com.baseDir}…")
        val r = ComReports.build(com.run(OneCQueries.all(period.from, period.to, s.includeDaily), log))
        if (r.duplicateCodes.isNotEmpty()) {
            val codes = r.duplicateCodes.sorted()
            log("  исключены повторяющиеся баркоды: ${codes.size}: " + codes.take(30).joinToString() + if (codes.size > 30) " …" else "")
        }
        log("  строк продаж: ${r.sales.size}, остатков: ${r.stock.size}" + if (s.includeDaily) ", по дням: ${r.daily.size}" else "")
        uploadAll(s, sheets, r.sales, r.stock, if (s.includeDaily) r.daily else null)
        val sec = (System.currentTimeMillis() - started) / 1000
        log("Готово ✔ за ${sec / 60} мин ${sec % 60} сек")
    }

    private fun uploadAll(
        s: AppSettings,
        sheets: SheetsClient,
        sales: List<com.vitazone.sync.model.SalesRow>,
        stock: List<com.vitazone.sync.model.StockRow>,
        daily: List<com.vitazone.sync.model.DailyRow>?,
    ) {
        sheets.upload(
            sheet = s.salesSheet,
            header = listOf("Контрагенты", "ИНН", "Номенклатура", "Док номер", "Дата", "Кол-во", "Реализация", "Доля в продаже", "КОД"),
            rows = sales.map { listOf(it.counterparty, it.inn, it.item, it.docNumber, it.date, it.qty, it.sum, it.share, it.code) },
            textCols = listOf(3, 8), dateCols = listOf(4), progress = log,
        )
        sheets.upload(
            sheet = s.stockSheet,
            header = listOf("Код, Номенклатура", "Кол во начальный", "Кол во Приход", "Кол во Расход", "Кол во конечный", "Сумма реализации", "Сумма конечный"),
            rows = stock.map { listOf(it.label, it.qtyOpen, it.qtyIn, it.qtyOut, it.qtyClose, it.saleSum, it.sumClose) },
            textCols = listOf(0), progress = log,
        )
        if (daily != null) sheets.upload(
            sheet = s.dailySheet,
            header = listOf("Дата", "Код, Номенклатура", "Кол во начальный", "Кол во Приход", "Кол во Расход", "Кол во конечный", "Сумма реализации", "Сумма конечный"),
            rows = daily.map { d -> val x = d.row; listOf(d.date, x.label, x.qtyOpen, x.qtyIn, x.qtyOut, x.qtyClose, x.saleSum, x.sumClose) },
            textCols = listOf(1), dateCols = listOf(0), progress = log,
        )
    }
}
