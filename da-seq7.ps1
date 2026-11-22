$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell input swipe 1350 500 1350 1400 300
Start-Sleep -Seconds 1
& $adb -s $ser shell uiautomator dump /sdcard/da-dir1.xml
& $adb -s $ser pull /sdcard/da-dir1.xml C:\Users\jonat\da\da-dir1.xml | Out-Null
& $adb -s $ser shell input swipe 1350 1400 1350 500 300
Start-Sleep -Seconds 1
& $adb -s $ser shell uiautomator dump /sdcard/da-dir2.xml
& $adb -s $ser pull /sdcard/da-dir2.xml C:\Users\jonat\da\da-dir2.xml | Out-Null
Write-Output "SEQ7_DONE"
