$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell settings put system screen_off_timeout 1800000
& $adb -s $ser shell input keyevent 224
& $adb -s $ser shell am start -n com.jonathancruzate.metamusedeviceassistance/.MainActivity
Start-Sleep -Seconds 2
$found = $false
for ($i = 0; $i -lt 7; $i++) {
  & $adb -s $ser shell uiautomator dump /sdcard/da-v.xml
  & $adb -s $ser pull /sdcard/da-v.xml C:\Users\jonat\da\da-v.xml | Out-Null
  $xml = Get-Content -Raw C:\Users\jonat\da\da-v.xml
  if ($xml -match 'Duo debug mode') {
    $m = [regex]::Match($xml, 'text="Duo debug mode[^"]*"[^>]*checked="(true|false)"')
    $s = [regex]::Match($xml, 'Debug mode: [^&]*')
    Write-Output "FOUND iter=$i CHECKED=$($m.Groups[1].Value)"
    Write-Output "STATUS=$($s.Value)"
    $found = $true
    break
  }
  & $adb -s $ser shell input swipe 1350 500 1350 1400 300
  Start-Sleep -Seconds 1
}
if (-not $found) { Write-Output "NOT_FOUND" }
Write-Output "SEQ20_DONE"
