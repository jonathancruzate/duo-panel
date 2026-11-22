$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell input swipe 1350 500 1350 850 300
Start-Sleep -Seconds 1
& $adb -s $ser shell screencap -p /sdcard/da-u6.png
& $adb -s $ser shell uiautomator dump /sdcard/da-u6.xml
& $adb -s $ser pull /sdcard/da-u6.png C:\Users\jonat\da\da-u6.png | Out-Null
& $adb -s $ser pull /sdcard/da-u6.xml C:\Users\jonat\da\da-u6.xml | Out-Null
Write-Output "SEQ17_DONE"
