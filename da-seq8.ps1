$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell settings put system screen_off_timeout 1800000
& $adb -s $ser shell input keyevent 224
Start-Sleep -Seconds 1
& $adb -s $ser shell input swipe 1350 1500 1350 600 200
Start-Sleep -Seconds 2
& $adb -s $ser shell am start -n com.jonathancruzate.metamusedeviceassistance/.MainActivity
Start-Sleep -Seconds 2
& $adb -s $ser shell uiautomator dump /sdcard/da-after-unlock.xml
& $adb -s $ser pull /sdcard/da-after-unlock.xml C:\Users\jonat\da\da-after-unlock.xml | Out-Null
& $adb -s $ser shell input swipe 1350 1400 1350 500 300
Start-Sleep -Seconds 1
& $adb -s $ser shell uiautomator dump /sdcard/da-cal1.xml
& $adb -s $ser pull /sdcard/da-cal1.xml C:\Users\jonat\da\da-cal1.xml | Out-Null
Write-Output "SEQ8_DONE"
