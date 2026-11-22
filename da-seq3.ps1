$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell input keyevent 224
& $adb -s $ser shell input tap 72 1188
Start-Sleep -Seconds 2
& $adb -s $ser shell screencap -p /sdcard/da-debug-on.png
& $adb -s $ser shell uiautomator dump /sdcard/da-debug-on.xml
& $adb -s $ser pull /sdcard/da-debug-on.png C:\Users\jonat\da\da-debug-on.png
& $adb -s $ser pull /sdcard/da-debug-on.xml C:\Users\jonat\da\da-debug-on.xml
Write-Output "SEQ3_DONE"
