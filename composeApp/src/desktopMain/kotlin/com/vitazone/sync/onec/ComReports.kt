package com.vitazone.sync.onec

import com.vitazone.sync.model.DailyRow
import com.vitazone.sync.model.SalesRow
import com.vitazone.sync.model.StockRow
import com.vitazone.sync.report.ReportBuilder.r2
import com.vitazone.sync.report.ReportBuilder.r3

/** Результат чтения офлайн-базы 1С, готовый к выгрузке */
class ComReports(
    val duplicateCodes: Set<String>,
    val sales: List<SalesRow>,
    val stock: List<StockRow>,
    val daily: List<DailyRow>,
) {
    companion object {
        private fun label(code: String, name: String) = if (code.isBlank()) name else "$code, $name"

        fun build(t: Map<String, Table>): ComReports {
            val dup = t.getValue("dup").let { tb -> tb.rows.map { tb.str(it, "Код").trim() }.filter { it.isNotEmpty() }.toSet() }

            // ---- Продажа отчети / Номенклатура-Куратор ----
            val s = t.getValue("sales")
            data class Raw(val code: String, val name: String, val cp: String, val inn: Long?, val doc: String,
                           val date: String, val mp: String, val qty: Double, val sum: Double)
            val raw = s.rows.map { r ->
                Raw(
                    code = s.str(r, "Код").trim(),
                    name = s.str(r, "Номенклатура"),
                    cp = s.str(r, "Контрагент"),
                    inn = s.str(r, "ИНН").toDoubleOrNull()?.toLong()?.takeIf { it != 0L },
                    doc = s.str(r, "ДокНомер"),
                    date = s.str(r, "Дата"),
                    mp = s.str(r, "МП"),
                    qty = s.num(r, "Кол"),
                    sum = s.num(r, "Сумма"),
                )
            }
            // «Доля в продаже» = процент от итога группы ДокНомер.МП (как ПроцентВГруппе в 1С)
            val groupTotal = raw.groupBy { it.mp }.mapValues { (_, l) -> l.sumOf { it.sum } }
            val sales = raw
                .filter { it.cp.isNotBlank() && (it.code.isNotBlank() || it.name.isNotBlank()) }
                .filter { it.code !in dup }
                .filter { it.qty != 0.0 || it.sum != 0.0 }
                .filter { r2(groupTotal[it.mp] ?: 0.0) != 0.0 }
                .map {
                    val total = groupTotal[it.mp] ?: 0.0
                    SalesRow(
                        itemKey = it.code, counterparty = it.cp, inn = it.inn, item = it.name,
                        docNumber = it.doc, date = it.date, qty = r3(it.qty), sum = r2(it.sum),
                        share = if (total != 0.0) r2(it.sum / total * 100.0) else 0.0, code = it.code,
                    )
                }
                .sortedWith(compareBy({ it.counterparty }, { it.item }, { it.docNumber }))

            // ---- Остатки2 / Вариант2 ----
            fun stockRow(tb: Table, r: List<String>) = StockRow(
                label = label(tb.str(r, "Код").trim(), tb.str(r, "Номенклатура")),
                qtyOpen = r3(tb.num(r, "КолНач")),
                qtyIn = r3(tb.num(r, "КолПриход")),
                qtyOut = r3(tb.num(r, "КолРасход")),
                qtyClose = r3(tb.num(r, "КолКон")),
                saleSum = r2(tb.num(r, "СуммаРеализации")),
                sumClose = r2(tb.num(r, "СуммаКон")),
            )
            fun StockRow.nonZero() = qtyOpen != 0.0 || qtyIn != 0.0 || qtyOut != 0.0 || qtyClose != 0.0 || saleSum != 0.0 || sumClose != 0.0
            fun ok(tb: Table, r: List<String>): Boolean {
                val code = tb.str(r, "Код").trim()
                return code !in dup && (code.isNotBlank() || tb.str(r, "Номенклатура").isNotBlank())
            }

            val st = t.getValue("stock")
            val stock = st.rows.filter { ok(st, it) }.map { stockRow(st, it) }.filter { it.nonZero() }.sortedBy { it.label }

            val daily = t["daily"]?.let { d ->
                d.rows.filter { ok(d, it) }
                    .map { DailyRow(date = d.str(it, "Дата"), row = stockRow(d, it)) }
                    .filter { it.date.isNotBlank() && it.row.nonZero() }
                    .sortedWith(compareBy({ it.row.label }, { it.date }))
            } ?: emptyList()

            return ComReports(dup, sales, stock, daily)
        }
    }
}
