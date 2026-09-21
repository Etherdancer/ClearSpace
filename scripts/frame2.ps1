Add-Type -AssemblyName System.Drawing
$outDir = "C:\Users\Tomek\.gemini\antigravity\scratch\ClearSpace\fastlane\metadata\android\en-US\images\phoneScreenshots"

function Add-PhoneFrame {
    param($inFile, $outFile)
    $img = [System.Drawing.Image]::FromFile($inFile)
    
    $targetW = 1080
    $targetH = 1920
    $ratio = [math]::Max($targetW / $img.Width, $targetH / $img.Height)
    $drawW = $img.Width * $ratio
    $drawH = $img.Height * $ratio
    $posX = ($targetW - $drawW) / 2
    $posY = ($targetH - $drawH) / 2
    
    $bmpScreen = New-Object System.Drawing.Bitmap $targetW, $targetH
    $gScreen = [System.Drawing.Graphics]::FromImage($bmpScreen)
    $gScreen.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gScreen.Clear([System.Drawing.Color]::Black)
    $gScreen.DrawImage($img, [float]$posX, [float]$posY, [float]$drawW, [float]$drawH)
    
    $canvasW = 1260
    $canvasH = 2240
    $bmpFrame = New-Object System.Drawing.Bitmap $canvasW, $canvasH
    $gFrame = [System.Drawing.Graphics]::FromImage($bmpFrame)
    $gFrame.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $gFrame.Clear([System.Drawing.ColorTranslator]::FromHtml("#E5E7EB"))
    
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $radius = 120
    $rect = New-Object System.Drawing.Rectangle 50, 110, 1160, 2020
    $path.AddArc($rect.X, $rect.Y, $radius, $radius, 180, 90)
    $path.AddArc($rect.Right - $radius, $rect.Y, $radius, $radius, 270, 90)
    $path.AddArc($rect.Right - $radius, $rect.Bottom - $radius, $radius, $radius, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $radius, $radius, $radius, 90, 90)
    $path.CloseFigure()
    
    $brush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#111827"))
    $gFrame.FillPath($brush, $path)
    
    $gFrame.DrawImage($bmpScreen, 90, 160, 1080, 1920)
    
    $notchW = 400
    $notchH = 70
    $notchX = ($canvasW - $notchW) / 2
    $notchY = 160
    $notchPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $nRadius = 40
    $nRect = New-Object System.Drawing.Rectangle $notchX, $notchY, $notchW, $notchH
    $notchPath.AddArc($nRect.X, $nRect.Y, $nRadius, $nRadius, 180, 90)
    $notchPath.AddArc($nRect.Right - $nRadius, $nRect.Y, $nRadius, $nRadius, 270, 90)
    $notchPath.AddArc($nRect.Right - $nRadius, $nRect.Bottom - $nRadius, $nRadius, $nRadius, 0, 90)
    $notchPath.AddArc($nRect.X, $nRect.Bottom - $nRadius, $nRadius, $nRadius, 90, 90)
    $notchPath.CloseFigure()
    
    $gFrame.FillPath($brush, $notchPath)
    
    $gScreen.Dispose()
    $bmpScreen.Dispose()
    $gFrame.Dispose()
    $img.Dispose()
    
    $bmpFrame.Save($outFile, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmpFrame.Dispose()
}

Add-PhoneFrame "C:\Users\Tomek\.gemini\antigravity\brain\12ea3876-739d-4d27-915f-0e5602de09d7\screenshot_nearby_1780619495646.png" "$outDir\screenshot_framed_3.png"
Add-PhoneFrame "C:\Users\Tomek\.gemini\antigravity\brain\12ea3876-739d-4d27-915f-0e5602de09d7\screenshot_editor_1780619505569.png" "$outDir\screenshot_framed_4.png"
Write-Host "Framed images created!"
