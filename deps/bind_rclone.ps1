$env:ANDROID_NDK_HOME = "C:\Users\Administrator\AppData\Local\Android\Sdk\ndk\27.0.12077973"
$env:ANDROID_HOME = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:Path = "C:\Users\Administrator\go\bin;$env:Path"
Push-Location rclone-mobile
& go get -tool golang.org/x/mobile/cmd/gobind
if ($LASTEXITCODE -ne 0) { Write-Error "go get gobind failed: $LASTEXITCODE"; exit $LASTEXITCODE }
New-Item -ItemType Directory -Force -Path "..\build\rclone" | Out-Null
# PS-native arg splitting mangles `-javapkg=com.openminis.rclone` into two
# tokens (`-javapkg=com` + `.openminis.rclone`) — the classic PowerShell
# pre-7.3 native-arg quirk. Route through cmd with %PACK% expansion so the
# string reaches gomobile intact; escape the inner quotes for cmd.
$cmd = 'gomobile bind -v -target=android/arm64 -androidapi 24 "-javapkg=com.openminis.rclone" "-ldflags=-s -w" "-o=..\build\rclone\rclone.aar" ".\gomobile"'
cmd /c $cmd
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { Write-Error "gomobile bind failed: $code"; exit $code }
Write-Host "BIND OK"
