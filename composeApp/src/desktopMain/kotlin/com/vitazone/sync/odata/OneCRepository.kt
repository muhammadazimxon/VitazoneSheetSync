package com.vitazone.sync.odata

import com.vitazone.sync.model.*
import java.time.LocalDate
import java.time.YearMonth

/** Чтение нужных данных конфигурации «Optom» через OData */
class OneCRepository(private val od: ODataClient) {

    companion object {
        /** Объекты, которые должны быть включены в состав OData */
        val REQUIRED = listOf(
            "Catalog_Номенклатура",
            "Catalog_Контрагенты",
            "Catalog_Договор",
            "AccumulationRegister_Остатки",
            "AccumulationRegister_Склады",
        )

        private fun dt(d: LocalDate, endOfDay: Boolean) =
            "datetime'${d}T" + (if (endOfDay) "23:59:59" else "00:00:00") + "'"

        private fun periodParams(from: LocalDate?, to: LocalDate): List<String> =
            listOfNotNull(
                from?.let { "StartPeriod=${dt(it, false)}" },
                "EndPeriod=${dt(to, true)}",
            )
    }

    /** Проверка: какие из нужных объектов НЕ опубликованы */
    fun missingEntities(): List<String> {
        val published = od.entitySets().toSet()
        return REQUIRED.filter { it !in published }
    }

    // ---------- Справочники ----------
    fun items(): Map<String, Item> =
        od.values("Catalog_Номенклатура?\$format=json&\$select=Ref_Key,Code,Description,DeletionMark")
            .associate { it.str("Ref_Key") to Item(it.str("Code").trim(), it.str("Description").trim(), it.bool("DeletionMark")) }

    fun counterparties(): Map<String, Counterparty> =
        od.values("Catalog_Контрагенты?\$format=json&\$select=Ref_Key,Description,инн")
            .associate {
                val inn = it.num("инн").toLong().takeIf { v -> v != 0L }
                it.str("Ref_Key") to Counterparty(it.str("Description").trim(), inn)
            }

    fun contracts(): Map<String, Contract> =
        od.values("Catalog_Договор?\$format=json&\$select=Ref_Key,Description,ДатаСоставления,МП_Key")
            .associate {
                it.str("Ref_Key") to Contract(
                    number = it.str("Description").trim(),
                    date = it.date("ДатаСоставления"),
                    managerKey = it.str("МП_Key").ifBlank { EMPTY_REF },
                )
            }

    // ---------- Продажа отчети ----------
    /** РегистрНакопления.Остатки.Обороты(Начало, Конец) в разрезе Номенклатура, Контрагенты, ДокНомер */
    fun salesTurnovers(from: LocalDate?, to: LocalDate): List<SalesTurnover> {
        val params = (periodParams(from, to) + "Dimensions='Номенклатура,Контрагенты,ДокНомер'").joinToString(",")
        return od.values("AccumulationRegister_Остатки/Turnovers($params)?\$format=json").map {
            SalesTurnover(
                itemKey = it.str("Номенклатура_Key").ifBlank { EMPTY_REF },
                counterpartyKey = it.str("Контрагенты_Key").ifBlank { EMPTY_REF },
                contractKey = it.str("ДокНомер_Key").ifBlank { EMPTY_REF },
                qtyExpense = it.num("КолвоExpense"),
                sumExpense = it.num("СуммаExpense"),
            )
        }
    }

    // ---------- Остатки2 ----------
    /** РегистрНакопления.Склады.ОстаткиИОбороты(Начало, Конец) в разрезе Номенклатура */
    fun stockTotals(from: LocalDate?, to: LocalDate): List<StockTotal> {
        val params = (periodParams(from, to) + "Dimensions='Номенклатура'").joinToString(",")
        return od.values("AccumulationRegister_Склады/BalanceAndTurnovers($params)?\$format=json").map {
            StockTotal(
                itemKey = it.str("Номенклатура_Key").ifBlank { EMPTY_REF },
                qtyOpen = it.num("КолВоOpeningBalance"),
                qtyIn = it.num("КолВоReceipt"),
                qtyOut = it.num("КолВоExpense"),
                qtyClose = it.num("КолВоClosingBalance"),
                sumExpense = it.num("СуммаExpense"),
                profitReceipt = it.num("ПрибыльReceipt"),
                sumClose = it.num("СуммаClosingBalance"),
            )
        }
    }

    /** Остатки на начало периода (для разбивки по дням) */
    fun stockOpenings(from: LocalDate?): Map<String, Opening> {
        if (from == null) return emptyMap()
        return od.values("AccumulationRegister_Склады/Balance(Period=${dt(from, false)},Dimensions='Номенклатура')?\$format=json")
            .associate { it.str("Номенклатура_Key") to Opening(it.num("КолВоBalance"), it.num("СуммаBalance")) }
    }

    /** Дата самого первого движения по Складам (для «весь период») */
    fun firstStockMoveDate(): LocalDate? {
        val first = od.values("AccumulationRegister_Склады_RecordType?\$format=json&\$top=1&\$orderby=Period&\$select=Period")
            .firstOrNull() ?: return null
        val d = first.date("Period")
        return if (d.isBlank()) null else LocalDate.parse(d)
    }

    /** Движения регистра Склады за период, загружаются по месяцам */
    fun stockMoves(from: LocalDate, to: LocalDate, progress: (String) -> Unit): List<StockMove> {
        val result = ArrayList<StockMove>()
        var month = YearMonth.from(from)
        val last = YearMonth.from(to)
        while (!month.isAfter(last)) {
            val start: LocalDate = if (month.atDay(1).isBefore(from)) from else month.atDay(1)
            val end: LocalDate = if (month.atEndOfMonth().isAfter(to)) to else month.atEndOfMonth()
            val filter = "Active eq true and Period ge ${dt(start, false)} and Period le ${dt(end, true)}"
            val rows = od.values(
                "AccumulationRegister_Склады_RecordType?\$format=json" +
                    "&\$filter=$filter" +
                    "&\$select=Period,RecordType,Номенклатура_Key,КолВо,Сумма,Прибыль"
            )
            rows.mapTo(result) {
                StockMove(
                    date = it.date("Period"),
                    itemKey = it.str("Номенклатура_Key").ifBlank { EMPTY_REF },
                    receipt = it.str("RecordType") == "Receipt",
                    qty = it.num("КолВо"),
                    sum = it.num("Сумма"),
                    profit = it.num("Прибыль"),
                )
            }
            progress("  движения $month: ${rows.size}")
            month = month.plusMonths(1)
        }
        return result
    }
}
