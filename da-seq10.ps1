$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
for ($i = 1; $i -le 4; $i++) {
  & $adb -s $ser shell input swipe 25 1400 25 500 300
  Start-Sleep -Seconds 1
  & $adb -s $ser shell screencap -p /sdcard/da-p$i.png
  & $adb -s $ser shell uiautomator dump /sdcard/da-p$i.xml
  & $adb -s $ser pull /sdcard/da-p$i.png C:\Users\jonat\da\da-p$i.png | Out-Null
  & $adb -s $ser pull /sdcard/da-p$i.xml C:\Users\jonat\da\da-p$i.xml | Out-Null
  Write-Output "P_$i"
}
Write-Output "SEQ10_DONE"
