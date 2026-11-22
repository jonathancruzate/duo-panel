$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell settings put system screen_off_timeout 1800000
& $adb -s $ser shell input keyevent 224
& $adb -s $ser shell am start -n com.jonathancruzate.metamusedeviceassistance/.MainActivity
Start-Sleep -Seconds 2
& $adb -s $ser shell input swipe 1350 500 1350 1100 300
Start-Sleep -Seconds 2
& $adb -s $ser shell uiautomator dump /sdcard/da-t1.xml
& $adb -s $ser pull /sdcard/da-t1.xml C:\Users\jonat\da\da-t1.xml | Out-Null
$xml = Get-Content -Raw C:\Users\jonat\da\da-t1.xml
$m = [regex]::Match($xml, 'text="Duo debug mode[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $m.Success) { Write-Output "TAP_ABORT_NO_MATCH"; exit 0 }
$x = [int]$m.Groups[1].Value + 30
$y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
Write-Output "TAPPING $x $y"
& $adb -s $ser shell input tap $x $y
Start-Sleep -Seconds 2
& $adb -s $ser shell screencap -p /sdcard/da-final-off.png
& $adb -s $ser shell uiautomator dump /sdcard/da-final-off.xml
& $adb -s $ser pull /sdcard/da-final-off.png C:\Users\jonat\da\da-final-off.png | Out-Null
& $adb -s $ser pull /sdcard/da-final-off.xml C:\Users\jonat\da\da-final-off.xml | Out-Null
$xml2 = Get-Content -Raw C:\Users\jonat\da\da-final-off.xml
$m2 = [regex]::Match($xml2, 'text="Duo debug mode[^"]*"[^>]*checked="(true|false)"')
Write-Output "AFTER_CHECKED=$($m2.Groups[1].Value)"
$m3 = [regex]::Match($xml2, 'Debug mode: [^&]*')
Write-Output "STATUSLINE=$($m3.Value)"
& $adb -s $ser shell settings put system screen_off_timeout 60000
& $adb -s $ser shell settings get system screen_off_timeout
Write-Output "SEQ19_DONE"
