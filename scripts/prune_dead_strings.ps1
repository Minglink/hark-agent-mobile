# Remove now-dead string resources (update checker / external links / MinisSkills
# browser entry) from every values*/strings.xml.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = "C:\Users\Administrator\Desktop\OpenMinis-main\src\android\app\src\main\res"

$deadKeys = @(
  'check_update_section_header','check_update_current_version','check_update_checking',
  'check_update_check_button','check_update_up_to_date','check_update_no_release',
  'check_update_no_apk_asset','check_update_error','check_update_available_title',
  'check_update_available_subtitle','check_update_changelog_header','check_update_install_hint',
  'check_update_downloading','check_update_download_failed','check_update_install_perm_required',
  'check_update_open_install_settings','check_update_download_button','check_update_install_launch_failed',
  'about_links','about_github_repository','settings_privacy_policy','settings_feedback',
  'settings_feedback_telegram','settings_feedback_email','settings_submit_github_issues',
  'skill_minis_skills_modal'
)

$files = Get-ChildItem $root -Recurse -File -Filter strings.xml
foreach ($f in $files) {
  $lines = [System.IO.File]::ReadAllLines($f.FullName, $utf8)
  $out = New-Object System.Collections.Generic.List[string]
  $removed = 0
  foreach ($line in $lines) {
    $hit = $false
    foreach ($k in $deadKeys) {
      if ($line -match ('name="' + [regex]::Escape($k) + '"')) { $hit = $true; break }
    }
    if ($hit) { $removed++ } else { $out.Add($line) }
  }
  if ($removed -gt 0) {
    [System.IO.File]::WriteAllLines($f.FullName, $out, $utf8)
    Write-Host ("{0}: removed {1}" -f $f.Directory.Name, $removed)
  }
}
Write-Host DONE
