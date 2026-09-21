Start-Sleep -Seconds 10
Write-Host "Taking screenshot 1..."
& "C:\Users\Tomek\AppData\Local\Android\Sdk\platform-tools\adb.exe" exec-out screencap -p > "C:\Users\Tomek\.gemini\antigravity\scratch\ClearSpace\fastlane\metadata\android\en-US\images\phoneScreenshots\real_1.png"
Start-Sleep -Seconds 5
Write-Host "Taking screenshot 2..."
& "C:\Users\Tomek\AppData\Local\Android\Sdk\platform-tools\adb.exe" exec-out screencap -p > "C:\Users\Tomek\.gemini\antigravity\scratch\ClearSpace\fastlane\metadata\android\en-US\images\phoneScreenshots\real_2.png"
Start-Sleep -Seconds 5
Write-Host "Taking screenshot 3..."
& "C:\Users\Tomek\AppData\Local\Android\Sdk\platform-tools\adb.exe" exec-out screencap -p > "C:\Users\Tomek\.gemini\antigravity\scratch\ClearSpace\fastlane\metadata\android\en-US\images\phoneScreenshots\real_3.png"
Start-Sleep -Seconds 5
Write-Host "Taking screenshot 4..."
& "C:\Users\Tomek\AppData\Local\Android\Sdk\platform-tools\adb.exe" exec-out screencap -p > "C:\Users\Tomek\.gemini\antigravity\scratch\ClearSpace\fastlane\metadata\android\en-US\images\phoneScreenshots\real_4.png"
Write-Host "All screenshots taken!"
