$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell input swipe 2692 900 2692 1550 600
Start-Sleep -Seconds 1
& $adb -s $ser shell screencap -p /sdcard/da-r1.png
& $adb -s $ser shell uiautomator dump /sdcard/da-r1.xml
& $adb -s $ser pull /sdcard/da-r1.png C:\Users\jonat\da\da-r1.png | Out-Null
& $adb -s $ser pull /sdcard/da-r1.xml C:\Users\jonat\da\da-r1.xml | Out-Null
Write-Output "SEQ12_DONE"
