Add-Type -AssemblyName System.Drawing
$outDir = "C:\Users\Tomek\.gemini\antigravity\scratch\ClearSpace\fastlane\metadata\android\en-US\images\phoneScreenshots"
if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

function Resize-Image {
    param($inFile, $outFile)
    $img = [System.Drawing.Image]::FromFile($inFile)
    $targetW = 1080
    $targetH = 1920
    $bmp = New-Object System.Drawing.Bitmap $targetW, $targetH
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::Black)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    
    $ratio = [math]::Min($targetW / $img.Width, $targetH / $img.Height)
    $drawW = $img.Width * $ratio
    $drawH = $img.Height * $ratio
    $posX = ($targetW - $drawW) / 2
    $posY = ($targetH - $drawH) / 2
    
    $g.DrawImage($img, [float]$posX, [float]$posY, [float]$drawW, [float]$drawH)
    $g.Dispose()
    $img.Dispose()
    
    $bmp.Save($outFile, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

Resize-Image "C:\Users\Tomek\.gemini\antigravity\brain\12ea3876-739d-4d27-915f-0e5602de09d7\screenshot_feed_1780618735947.png" "$outDir\screenshot_1.png"
Resize-Image "C:\Users\Tomek\.gemini\antigravity\brain\12ea3876-739d-4d27-915f-0e5602de09d7\screenshot_vault_1780618745135.png" "$outDir\screenshot_2.png"
Write-Host "Done!"
