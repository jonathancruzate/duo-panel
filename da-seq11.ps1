$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
for ($i = 1; $i -le 5; $i++) {
  & $adb -s $ser shell input swipe 25 1400 25 500 300
  Start-Sleep -Seconds 1
  & $adb -s $ser shell screencap -p /sdcard/da-q$i.png
  & $adb -s $ser shell uiautomator dump /sdcard/da-q$i.xml
  & $adb -s $ser pull /sdcard/da-q$i.png C:\Users\jonat\da\da-q$i.png | Out-Null
  & $adb -s $ser pull /sdcard/da-q$i.xml C:\Users\jonat\da\da-q$i.xml | Out-Null
  Write-Output "Q_$i"
}
Write-Output "SEQ11_DONE"
