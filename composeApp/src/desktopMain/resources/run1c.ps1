param([string]$job)
# Выполняет запросы к файловой базе 1С через COM (V83.COMConnector)
# и пишет результаты в TSV-файлы (UTF-8). Вызывается из VitazoneSheetSync.
$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$cfg = [System.IO.File]::ReadAllText($job, [System.Text.Encoding]::UTF8) | ConvertFrom-Json

function Fail([int]$code, [string]$msg) {
    [System.IO.File]::WriteAllText($cfg.errFile, $msg, $enc)
    exit $code
}

function Fmt($v) {
    if ($null -eq $v -or $v -is [System.DBNull]) { return '' }
    if ($v -is [DateTime]) {
        if ($v.Year -le 1) { return '' }
        return $v.ToString('yyyy-MM-dd', [System.Globalization.CultureInfo]::InvariantCulture)
    }
    if ($v -is [bool]) { if ($v) { return '1' } else { return '0' } }
    if ($v -is [string]) { return ($v -replace "[`t`r`n]", ' ').Trim() }
    if ($v -is [System.IFormattable]) { return $v.ToString($null, [System.Globalization.CultureInfo]::InvariantCulture) }
    return ([string]$v -replace "[`t`r`n]", ' ').Trim()
}

try {
    $connector = New-Object -ComObject V83.COMConnector
} catch {
    Fail 3 $_.Exception.Message
}

try {
    $conn = $connector.Connect($cfg.connection)
} catch {
    Fail 4 $_.Exception.Message
}

try {
    foreach ($t in $cfg.queries) {
        [Console]::Out.WriteLine('START ' + $t.name); [Console]::Out.Flush()
        $q = $conn.NewObject('Query')
        $q.Text = $t.text
        $res = $q.Execute()
        $cols = $res.Columns
        $n = $cols.Count()
        $names = New-Object string[] $n
        for ($i = 0; $i -lt $n; $i++) { $names[$i] = $cols.Get($i).Name }
        $w = New-Object System.IO.StreamWriter($t.out, $false, $enc)
        $w.WriteLine([string]::Join("`t", $names))
        $sel = $res.Select()
        $count = 0
        $vals = New-Object string[] $n
        while ($sel.Next()) {
            for ($i = 0; $i -lt $n; $i++) { $vals[$i] = Fmt ($sel.Get($i)) }
            $w.WriteLine([string]::Join("`t", $vals))
            $count++
            if ($count % 20000 -eq 0) { [Console]::Out.WriteLine('PROGRESS ' + $t.name + ' ' + $count); [Console]::Out.Flush() }
        }
        $w.Close()
        [Console]::Out.WriteLine('DONE ' + $t.name + ' ' + $count); [Console]::Out.Flush()
    }
} catch {
    Fail 1 $_.Exception.Message
}
exit 0
