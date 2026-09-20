# Hark rebrand — ordered bulk replacement (longest/most-specific first).
# Scope: src/android/app/src (kt/xml/assets) + build.gradle.kts + proguard.
# UTF-8 no-BOM read/write via .NET to preserve encodings exactly.
$ErrorActionPreference = 'Stop'
$root = "C:\Users\Administrator\Desktop\OpenMinis-main\src\android\app"

$files = @()
$files += Get-ChildItem "$root\src" -Recurse -File -Include *.kt, *.xml
$files += Get-ChildItem "$root\src\main\assets\default_mount" -Recurse -File
$files += Get-ChildItem "$root" -File -Include build.gradle.kts, proguard-rules.pro
$files = $files | Where-Object { $_.FullName -notmatch '\\build\\' } | Select-Object -Unique FullName

# Ordered: specific CLI names -> scheme/paths -> host dirs -> quoted leftovers.
$replacements = [ordered]@{
  'minis-config'      = 'hark-config'
  'minis-mcp-cli'     = 'hark-mcp-cli'
  'minis-open'        = 'hark-open'
  'minis-model-use'   = 'hark-model-use'
  'minis-sessions-cli'= 'hark-sessions-cli'
  'minis-scheduled'   = 'hark-scheduled'
  'minis-debug'       = 'hark-debug'
  'minis-browser-use' = 'hark-browser-use'
  'minis://'          = 'hark://'
  '/var/minis'        = '/var/hark'
  'minis-global'      = 'hark-global'
  'minis-sessions'    = 'hark-sessions'
  '"minis-'           = '"hark-'
  'scheme="minis"'    = 'scheme="hark"'
  'com.openminis.app/.accessibility' = 'com.hark.app/.accessibility'
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
$changed = 0; $skipped = 0
$perPattern = @{}
foreach ($f in $files) {
  try { $text = [System.IO.File]::ReadAllText($f.FullName, $utf8) } catch { $skipped++; continue }
  $orig = $text
  foreach ($k in $replacements.Keys) {
    if ($text.Contains($k)) {
      $count = ([regex]::Matches($text, [regex]::Escape($k))).Count
      $prev = 0; if ($perPattern.ContainsKey($k)) { $prev = $perPattern[$k] }
      $perPattern[$k] = $prev + $count
      $text = $text.Replace($k, $replacements[$k])
    }
  }
  if ($text -ne $orig) {
    [System.IO.File]::WriteAllText($f.FullName, $text, $utf8)
    $changed++
  }
}
Write-Host "files changed: $changed, skipped(binary): $skipped"
$perPattern.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { Write-Host ("{0,5}  {1}" -f $_.Value, $_.Key) }

# Strings + display literals: Minis -> Hark (strings.xml all locales + kt literals)
$stringFiles = Get-ChildItem "$root\src" -Recurse -File -Include strings.xml
$c2 = 0
foreach ($f in $stringFiles) {
  $text = [System.IO.File]::ReadAllText($f.FullName, $utf8)
  if ($text.Contains('Minis')) {
    $n = ([regex]::Matches($text, 'Minis')).Count
    $text = $text.Replace('Minis', 'Hark')
    [System.IO.File]::WriteAllText($f.FullName, $text, $utf8)
    $c2++; Write-Host ("strings: {0} ({1} hits)" -f $f.FullName.Split('\')[-2], $n)
  }
}
$ktFiles = Get-ChildItem "$root\src" -Recurse -File -Include *.kt
$c3 = 0
foreach ($f in $ktFiles) {
  $text = [System.IO.File]::ReadAllText($f.FullName, $utf8)
  $orig = $text
  $text = $text.Replace('"Minis ', '"Hark ').Replace('"Minis/"', '"Hark/"').Replace('"Minis"', '"Hark"')
  if ($text -ne $orig) {
    [System.IO.File]::WriteAllText($f.FullName, $text, $utf8); $c3++
  }
}
Write-Host "strings.xml files: $c2, kt literal files: $c3"
