package com.vitazone.sync.report

import com.vitazone.sync.model.*
import kotlin.math.round

/**
 * Повторяет логику СКД 1С:
 *  - Отчет.ПродажаОтчети, вариант «Номенклатура-Куратор»
 *  - Отчет.Остатки2, вариант «Вариант2»
 */
object ReportBuilder {

    fun r2(x: Double): Double = round(x * 100.0) / 100.0
    fun r3(x: Double): Double = round(x * 1000.0) / 1000.0

    /**
     * Ключи товаров с повторяющимся баркодом (КОД): один и тот же код у 2+ товаров
     * (помеченные на удаление не считаются). Такие товары не выгружаются вообще.
     */
    fun duplicateCodeItems(items: Map<String, Item>): Set<String> =
        items.entries
            .filter { !it.value.deleted && it.value.code.isNotBlank() }
            .groupBy { it.value.code }
            .filterValues { it.size > 1 }
            .values.flatten().map { it.key }.toSet()

    /**
     * Продажа отчети / Номенклатура-Куратор.
     * Группировки в 1С: ДокНомер.МП.Куратор → ДокНомер.МП → (Контрагенты, Номенклатура, ДокНомер).
     * Детальные записи: Контрагенты и Номенклатура заполнены.
     * Группы МП/Куратор с СуммаРасход = 0 скрыты.
     * «Доля в продаже» = СуммаРасход.ПроцентВГруппе (процент от итога группы МП).
     */
    fun sales(
        turnovers: List<SalesTurnover>,
        items: Map<String, Item>,
        counterparties: Map<String, Counterparty>,
        contracts: Map<String, Contract>,
        excludedItems: Set<String> = emptySet(),
    ): List<SalesRow> {
        fun managerOf(t: SalesTurnover) = contracts[t.contractKey]?.managerKey ?: EMPTY_REF

        val groupTotals = HashMap<String, Double>()
        for (t in turnovers) {
            val mp = managerOf(t)
            groupTotals[mp] = (groupTotals[mp] ?: 0.0) + t.sumExpense
        }

        return turnovers
            .asSequence()
            .filter { it.itemKey != EMPTY_REF && it.counterpartyKey != EMPTY_REF }
            .filter { it.itemKey !in excludedItems }
            .filter { it.qtyExpense != 0.0 || it.sumExpense != 0.0 }
            .filter { r2(groupTotals[managerOf(it)] ?: 0.0) != 0.0 }
            .map { t ->
                val total = groupTotals[managerOf(t)] ?: 0.0
                val item = items[t.itemKey]
                val cp = counterparties[t.counterpartyKey]
                val contract = contracts[t.contractKey]
                SalesRow(
                    itemKey = t.itemKey,
                    counterparty = cp?.name ?: "",
                    inn = cp?.inn,
                    item = item?.name ?: "",
                    docNumber = contract?.number ?: "",
                    date = contract?.date ?: "",
                    qty = r3(t.qtyExpense),
                    sum = r2(t.sumExpense),
                    share = if (total != 0.0) r2(t.sumExpense / total * 100.0) else 0.0,
                    code = item?.code ?: "",
                )
            }
            .sortedWith(compareBy({ it.counterparty }, { it.item }, { it.docNumber }))
            .toList()
    }

    /**
     * Остатки2 / Вариант2 — колонка «Итого». Все колонки — из регистра «Склады», как в 1С.
     * Сумма реализации = СуммаРасход + ПрибыльПриход (вычисляемое поле в СКД).
     */
    fun stock(
        totals: List<StockTotal>,
        items: Map<String, Item>,
        excludedItems: Set<String> = emptySet(),
    ): List<StockRow> =
        totals
            .filter { it.itemKey != EMPTY_REF && it.itemKey !in excludedItems }
            .map { t ->
                StockRow(
                    label = items[t.itemKey]?.label ?: t.itemKey,
                    qtyOpen = r3(t.qtyOpen),
                    qtyIn = r3(t.qtyIn),
                    qtyOut = r3(t.qtyOut),
                    qtyClose = r3(t.qtyClose),
                    saleSum = r2(t.sumExpense + t.profitReceipt),
                    sumClose = r2(t.sumClose),
                )
            }
            .filter { it.qtyOpen != 0.0 || it.qtyIn != 0.0 || it.qtyOut != 0.0 || it.qtyClose != 0.0 || it.saleSum != 0.0 || it.sumClose != 0.0 }
            .sortedBy { it.label }

    /**
     * Остатки2 / Вариант2 — разбивка по дням (Период.КонецДня).
     * Только дни, в которые было движение по товару (как в 1С).
     */
    fun daily(
        openings: Map<String, Opening>,
        moves: List<StockMove>,
        items: Map<String, Item>,
        excludedItems: Set<String> = emptySet(),
    ): List<DailyRow> {
        val result = ArrayList<DailyRow>()
        for ((itemKey, list) in moves.groupBy { it.itemKey }) {
            if (itemKey == EMPTY_REF || itemKey in excludedItems) continue
            val label = items[itemKey]?.label ?: itemKey
            var qty = openings[itemKey]?.qty ?: 0.0
            var sum = openings[itemKey]?.sum ?: 0.0
            for ((date, day) in list.groupBy { it.date }.entries.sortedBy { it.key }) {
                var qIn = 0.0; var qOut = 0.0; var sIn = 0.0; var sOut = 0.0; var profitIn = 0.0
                for (m in day) {
                    if (m.receipt) { qIn += m.qty; sIn += m.sum; profitIn += m.profit }
                    else { qOut += m.qty; sOut += m.sum }
                }
                val qOpen = qty
                qty += qIn - qOut
                sum += sIn - sOut
                result += DailyRow(
                    date = date,
                    row = StockRow(
                        label = label,
                        qtyOpen = r3(qOpen),
                        qtyIn = r3(qIn),
                        qtyOut = r3(qOut),
                        qtyClose = r3(qty),
                        saleSum = r2(sOut + profitIn),
                        sumClose = r2(sum),
                    ),
                )
            }
        }
        return result.sortedWith(compareBy({ it.row.label }, { it.date }))
    }
}
