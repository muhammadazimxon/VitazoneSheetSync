package com.vitazone.sync

import com.vitazone.sync.model.AppSettings
import com.vitazone.sync.odata.ODataClient
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

    suspend fun checkOneC(s: AppSettings) = withContext(Dispatchers.IO) {
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
        val repo = OneCRepository(ODataClient(s.odataUrl, s.login, s.password))
        val sheets = SheetsClient(s.scriptUrl, s.secret)

        log("Период: $period")
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
}
