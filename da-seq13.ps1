$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell input swipe 1350 500 1350 1400 300
Start-Sleep -Seconds 1
& $adb -s $ser shell input swipe 1350 500 1350 1400 300
Start-Sleep -Seconds 1
& $adb -s $ser shell screencap -p /sdcard/da-u1.png
& $adb -s $ser shell uiautomator dump /sdcard/da-u1.xml
& $adb -s $ser pull /sdcard/da-u1.png C:\Users\jonat\da\da-u1.png | Out-Null
& $adb -s $ser pull /sdcard/da-u1.xml C:\Users\jonat\da\da-u1.xml | Out-Null
Write-Output "SEQ13_DONE"
