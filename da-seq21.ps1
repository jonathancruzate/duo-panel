$adb = "C:\Users\jonat\platform-tools\adb.exe"
$ser = "900008402667"
& $adb -s $ser shell input keyevent 224
& $adb -s $ser shell am start -n com.jonathancruzate.metamusedeviceassistance/.MainActivity
Start-Sleep -Seconds 2
$done = $false
for ($i = 0; $i -lt 9; $i++) {
  & $adb -s $ser shell uiautomator dump /sdcard/da-w.xml
  & $adb -s $ser pull /sdcard/da-w.xml C:\Users\jonat\da\da-w.xml | Out-Null
  $xml = Get-Content -Raw C:\Users\jonat\da\da-w.xml
  if ($xml -match 'Duo debug mode') {
    $m = [regex]::Match($xml, 'text="Duo debug mode[^"]*"[^>]*checked="(true|false)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { $m = [regex]::Match($xml, 'text="Duo debug mode[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*checked="(true|false)"') }
    Write-Output "FOUND iter=$i raw=$($m.Value.Substring(0, [Math]::Min(160, $m.Value.Length)))"
    $s = [regex]::Match($xml, 'Debug mode: [^&]*')
    Write-Output "STATUS_BEFORE=$($s.Value)"
    if ($m.Value -match 'checked="true"') {
      $mm = [regex]::Match($xml, 'text="Duo debug mode[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
      $x = [int]$mm.Groups[1].Value + 30
      $y = [int](([int]$mm.Groups[2].Value + [int]$mm.Groups[4].Value) / 2)
      Write-Output "TAPPING_OFF $x $y"
      & $adb -s $ser shell input tap $x $y
      Start-Sleep -Seconds 2
      & $adb -s $ser shell screencap -p /sdcard/da-final-off.png
      & $adb -s $ser shell uiautomator dump /sdcard/da-final-off.xml
      & $adb -s $ser pull /sdcard/da-final-off.png C:\Users\jonat\da\da-final-off.png | Out-Null
      & $adb -s $ser pull /sdcard/da-final-off.xml C:\Users\jonat\da\da-final-off.xml | Out-Null
      $xml2 = Get-Content -Raw C:\Users\jonat\da\da-final-off.xml
      $m2 = [regex]::Match($xml2, 'text="Duo debug mode[^"]*"[^>]*checked="(true|false)"')
      $s2 = [regex]::Match($xml2, 'Debug mode: [^&]*')
      Write-Output "AFTER_CHECKED=$($m2.Groups[1].Value)"
      Write-Output "STATUS_AFTER=$($s2.Value)"
    } else {
      Write-Output "ALREADY_OFF"
      & $adb -s $ser shell screencap -p /sdcard/da-final-off.png
      & $adb -s $ser pull /sdcard/da-final-off.png C:\Users\jonat\da\da-final-off.png | Out-Null
    }
    $done = $true
    break
  }
  & $adb -s $ser shell input swipe 1350 1400 1350 500 300
  Start-Sleep -Seconds 1
}
if (-not $done) { Write-Output "NOT_FOUND" }
& $adb -s $ser shell settings put system screen_off_timeout 60000
& $adb -s $ser shell settings get system screen_off_timeout
Write-Output "SEQ21_DONE"
