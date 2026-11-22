$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell input tap 72 182
Start-Sleep -Seconds 2
& $adb -s $ser shell screencap -p /sdcard/da-final-off.png
& $adb -s $ser shell uiautomator dump /sdcard/da-final-off.xml
& $adb -s $ser pull /sdcard/da-final-off.png C:\Users\jonat\da\da-final-off.png | Out-Null
& $adb -s $ser pull /sdcard/da-final-off.xml C:\Users\jonat\da\da-final-off.xml | Out-Null
& $adb -s $ser shell logcat -d -v time > C:\Users\jonat\da\logcat2.txt 2>$null
& $adb -s $ser shell settings put system screen_off_timeout 60000
& $adb -s $ser shell settings get system screen_off_timeout
Write-Output "SEQ18_DONE"
