/**
 * Приёмник данных из приложения VitazoneSheetSync (1С → Google Sheets).
 *
 * Установка:
 *  1. Откройте Google-таблицу → Расширения → Apps Script.
 *  2. Вставьте этот код вместо содержимого Code.gs, задайте SECRET (тот же — в приложении).
 *  3. Развернуть → Новое развертывание → тип «Веб-приложение»:
 *       Выполнять от имени: «Я»;  У кого есть доступ: «Все».
 *  4. Скопируйте URL вида https://script.google.com/macros/s/.../exec в приложение.
 *
 * Пишутся только колонки с данными (A..I для продаж, A..G для остатков).
 * Колонки правее («Ключ (авто)», «Наименование (авто)» и т.д.) НЕ трогаются.
 */
const SECRET = 'vz-n9rWj8kwxUIYFOlD3IOIFSui3g4qbxmc';

/** Проверка из браузера: откройте URL .../exec — должно показать {"ok":true,...} */
function doGet() {
  return out_({ ok: true, message: 'VitazoneSheetSync: веб-приложение работает' });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(300000);
  try {
    const p = JSON.parse(e.postData.contents);
    if (SECRET && p.secret !== SECRET) return out_({ ok: false, error: 'Неверный секрет' });

    const ss = SpreadsheetApp.getActiveSpreadsheet();
    if (p.ping) return out_({ ok: true, sheets: ss.getSheets().map(function (s) { return s.getName(); }) });

    const nCols = p.header.length;
    let sh = ss.getSheetByName(p.sheet);
    if (!sh) {
      sh = ss.insertSheet(p.sheet);
      sh.getRange(1, 1, 1, nCols).setValues([p.header]).setFontWeight('bold');
      sh.setFrozenRows(1);
    }

    const textCols = p.textCols || [];
    const dateCols = p.dateCols || [];

    if (p.mode === 'replace') {
      // очистить старые данные только в своих колонках
      const last = sh.getLastRow();
      if (last >= 2) sh.getRange(2, 1, last - 1, nCols).clearContent();
      const need = 1 + Math.max(p.total, 1);
      if (sh.getMaxRows() < need) sh.insertRowsAfter(sh.getMaxRows(), need - sh.getMaxRows());
      const n = Math.max(p.total, 1);
      textCols.forEach(function (c) { sh.getRange(2, c + 1, n, 1).setNumberFormat('@'); });
      dateCols.forEach(function (c) { sh.getRange(2, c + 1, n, 1).setNumberFormat('dd.MM.yyyy'); });
    }

    const rows = p.rows.map(function (r) {
      return r.map(function (v, i) {
        if (v === null || v === undefined) return '';
        if (dateCols.indexOf(i) >= 0 && v) return toDate_(v);
        if (textCols.indexOf(i) >= 0) return String(v);
        return v;
      });
    });
    if (rows.length) sh.getRange(2 + p.offset, 1, rows.length, nCols).setValues(rows);

    return out_({ ok: true, written: rows.length });
  } catch (err) {
    return out_({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function toDate_(s) {
  const a = String(s).split('-').map(Number);
  return new Date(a[0], a[1] - 1, a[2]);
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
