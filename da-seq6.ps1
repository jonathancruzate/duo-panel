$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell logcat -d -v time > C:\Users\jonat\da\logcat.txt 2>$null
for ($i = 0; $i -lt 4; $i++) {
  & $adb -s $ser shell input swipe 1350 500 1350 1400 300
  Start-Sleep -Milliseconds 600
}
& $adb -s $ser shell input tap 72 1188
Start-Sleep -Seconds 2
& $adb -s $ser shell screencap -p /sdcard/da-debug-off.png
& $adb -s $ser shell uiautomator dump /sdcard/da-debug-off.xml
& $adb -s $ser pull /sdcard/da-debug-off.png C:\Users\jonat\da\da-debug-off.png | Out-Null
& $adb -s $ser pull /sdcard/da-debug-off.xml C:\Users\jonat\da\da-debug-off.xml | Out-Null
& $adb -s $ser shell settings put system screen_off_timeout 60000
& $adb -s $ser shell settings get system screen_off_timeout
Write-Output "SEQ6_DONE"
