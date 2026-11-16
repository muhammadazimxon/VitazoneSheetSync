package com.vitazone.sync.model

import kotlinx.serialization.Serializable

/** Настройки приложения (хранятся в %APPDATA%\VitazoneSheetSync\settings.json). */
@Serializable
data class AppSettings(
    /** Источник: "COM" — офлайн файловая база 1С напрямую; "ODATA" — веб-публикация */
    val source: String = "COM",
    /** Папка файловой базы 1С (где лежит 1Cv8.1CD), например D:\\1C\\Optom */
    val basePath: String = "",
    /** Адрес публикации OData, например http://localhost/optom/odata/standard.odata/ */
    val odataUrl: String = "http://localhost/optom/odata/standard.odata/",
    val login: String = "Админ",
    val password: String = "",
    /** URL веб-приложения Google Apps Script (doPost) */
    val scriptUrl: String = "https://script.google.com/macros/s/AKfycbwm0AYpJrZbO9kFoN67g1SE9mCxhPXfVcbfYoHuELnksz_SHfHwoXoQRG_cZxzYaZlrAA/exec",
    /** Секрет, который проверяет doPost (необязательно) */
    val secret: String = "vz-n9rWj8kwxUIYFOlD3IOIFSui3g4qbxmc",
    val salesSheet: String = "Продажа",
    val stockSheet: String = "Остатка",
    val dailySheet: String = "Остатки по дням",
    /** dd.MM.yyyy; пусто = весь период (с самого начала базы) */
    val periodFrom: String = "",
    /** dd.MM.yyyy; пусто = сегодня */
    val periodTo: String = "",
    val includeDaily: Boolean = true,
    val autoEnabled: Boolean = false,
    val autoIntervalMinutes: Int = 60,
)

/** Пустая ссылка 1С */
const val EMPTY_REF = "00000000-0000-0000-0000-000000000000"

// ---------- Справочники ----------
data class Item(val code: String, val name: String, val deleted: Boolean = false) {
    /** Как в отчёте Остатки2: «Код, Номенклатура» */
    val label: String get() = if (code.isBlank()) name else "$code, $name"
}

data class Counterparty(val name: String, val inn: Long?)

/** Справочник «Договор» (это поле «ДокНомер» в регистрах) */
data class Contract(val number: String, val date: String, val managerKey: String)

// ---------- Сырые данные регистров ----------
/** РегистрНакопления.Остатки.Обороты по Номенклатура, Контрагенты, ДокНомер */
data class SalesTurnover(
    val itemKey: String,
    val counterpartyKey: String,
    val contractKey: String,
    val qtyExpense: Double,
    val sumExpense: Double,
)

/** РегистрНакопления.Склады.ОстаткиИОбороты по Номенклатура */
data class StockTotal(
    val itemKey: String,
    val qtyOpen: Double,
    val qtyIn: Double,
    val qtyOut: Double,
    val qtyClose: Double,
    val sumExpense: Double,
    val profitReceipt: Double,
    val sumClose: Double,
)

/** Движение регистра Склады (для разбивки по дням) */
data class StockMove(
    val date: String, // yyyy-MM-dd
    val itemKey: String,
    val receipt: Boolean,
    val qty: Double,
    val sum: Double,
    val profit: Double,
)

data class Opening(val qty: Double, val sum: Double)

// ---------- Строки для Google Sheets ----------
/** Контрагенты | ИНН | Номенклатура | Док номер | Дата | Кол-во | Реализация | Доля в продаже | КОД */
data class SalesRow(
    val itemKey: String,
    val counterparty: String,
    val inn: Long?,
    val item: String,
    val docNumber: String,
    val date: String,
    val qty: Double,
    val sum: Double,
    val share: Double,
    val code: String,
)

/** Код, Номенклатура | Кол во начальный | Приход | Расход | конечный | Сумма реализации | Сумма конечный */
data class StockRow(
    val label: String,
    val qtyOpen: Double,
    val qtyIn: Double,
    val qtyOut: Double,
    val qtyClose: Double,
    val saleSum: Double,
    val sumClose: Double,
)

data class DailyRow(val date: String, val row: StockRow)
