$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell settings put system screen_off_timeout 1800000
& $adb -s $ser shell input keyevent 224
Start-Sleep -Seconds 1
& $adb -s $ser shell input swipe 1350 1500 1350 600 200
Start-Sleep -Seconds 1
& $adb -s $ser shell am start -n com.jonathancruzate.metamusedeviceassistance/.MainActivity
Start-Sleep -Seconds 2
& $adb -s $ser shell screencap -p /sdcard/da-state.png
& $adb -s $ser shell uiautomator dump /sdcard/da-state.xml
& $adb -s $ser pull /sdcard/da-state.png C:\Users\jonat\da\da-state.png
& $adb -s $ser pull /sdcard/da-state.xml C:\Users\jonat\da\da-state.xml
Write-Output "SEQ2_DONE"
