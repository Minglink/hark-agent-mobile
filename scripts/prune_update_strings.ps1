# UTF-8-safe scan + prune of leftover update/GitHub string keys across all locales.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = "C:\Users\Administrator\Desktop\OpenMinis-main\src\android\app\src\main\res"
$deadPattern = 'name="(update_[^"]*|check_update[^"]*)"'

$files = Get-ChildItem $root -Recurse -Filter strings.xml
foreach ($f in $files) {
    $lines = [System.IO.File]::ReadAllLines($f.FullName, $utf8)
    $out = New-Object System.Collections.Generic.List[string]
    $removed = 0
    foreach ($line in $lines) {
        if ($line -match $deadPattern) { $removed++; continue }
        $out.Add($line)
    }
    if ($removed -gt 0) {
        [System.IO.File]::WriteAllLines($f.FullName, $out, $utf8)
        Write-Host ("{0}: removed {1}" -f $f.Directory.Name, $removed)
    }
}
# Verify zero remaining (UTF-8 safe)
$remaining = 0
foreach ($f in $files) {
    $t = [System.IO.File]::ReadAllText($f.FullName, $utf8)
    if ($t -match 'update_error|check_update|GitHub Releases|github\.com/OpenMinis|openminis\.app|dev@openminis') { $remaining++ }
    # ALSO verify XML well-formedness via .NET with correct encoding
    try { [xml]$null = $t } catch { Write-Host ("XML BROKEN: {0}: {1}" -f $f.Directory.Name, $_.Exception.Message) }
}
Write-Host "files with residue: $remaining"
Write-Host "SCAN-DONE"
