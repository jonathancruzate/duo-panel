$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
for ($i = 1; $i -le 8; $i++) {
  & $adb -s $ser shell input swipe 1350 1400 1350 500 300
  Start-Sleep -Seconds 1
  & $adb -s $ser shell screencap -p /sdcard/da-s$i.png
  & $adb -s $ser shell uiautomator dump /sdcard/da-s$i.xml
  & $adb -s $ser pull /sdcard/da-s$i.png C:\Users\jonat\da\da-s$i.png | Out-Null
  & $adb -s $ser pull /sdcard/da-s$i.xml C:\Users\jonat\da\da-s$i.xml | Out-Null
  Write-Output "SCROLL_$i"
}
Write-Output "SEQ5_DONE"
