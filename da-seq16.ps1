$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell input swipe 1350 1400 1350 500 300
Start-Sleep -Seconds 1
& $adb -s $ser shell screencap -p /sdcard/da-u4.png
& $adb -s $ser shell uiautomator dump /sdcard/da-u4.xml
& $adb -s $ser pull /sdcard/da-u4.png C:\Users\jonat\da\da-u4.png | Out-Null
& $adb -s $ser pull /sdcard/da-u4.xml C:\Users\jonat\da\da-u4.xml | Out-Null
for ($i = 0; $i -lt 3; $i++) {
  & $adb -s $ser shell input swipe 1350 1400 1350 500 300
  Start-Sleep -Seconds 1
}
& $adb -s $ser shell screencap -p /sdcard/da-u5.png
& $adb -s $ser shell uiautomator dump /sdcard/da-u5.xml
& $adb -s $ser pull /sdcard/da-u5.png C:\Users\jonat\da\da-u5.png | Out-Null
& $adb -s $ser pull /sdcard/da-u5.xml C:\Users\jonat\da\da-u5.xml | Out-Null
Write-Output "SEQ16_DONE"
