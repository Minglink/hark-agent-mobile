param([string]$file)
[xml]$x = Get-Content $file -Raw
$nodes = $x.SelectNodes('//*[@clickable="true" or @class="android.widget.EditText"]')
foreach ($n in $nodes) {
    if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $cx = [math]::Round(([int]$Matches[1] + [int]$Matches[3]) / 2)
        $cy = [math]::Round(([int]$Matches[2] + [int]$Matches[4]) / 2)
        $cls = $n.class -replace 'android.widget.', ''
        $t = $n.text; if ($t.Length -gt 24) { $t = $t.Substring(0, 24) }
        Write-Host ("[$cls] text='{0}' desc='{1}' tap=({2},{3})" -f $t, $n.GetAttribute('content-desc'), $cx, $cy)
    }
}
