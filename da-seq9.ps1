$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell am start -n com.jonathancruzate.metamusedeviceassistance/.MainActivity
Start-Sleep -Seconds 2
& $adb -s $ser shell uiautomator dump /sdcard/da-now.xml
& $adb -s $ser pull /sdcard/da-now.xml C:\Users\jonat\da\da-now.xml | Out-Null
for ($i = 1; $i -le 3; $i++) {
  & $adb -s $ser shell input swipe 1350 1400 1350 500 300
  Start-Sleep -Seconds 1
  & $adb -s $ser shell uiautomator dump /sdcard/da-n$i.xml
  & $adb -s $ser pull /sdcard/da-n$i.xml C:\Users\jonat\da\da-n$i.xml | Out-Null
  Write-Output "N_$i"
}
Write-Output "SEQ9_DONE"
