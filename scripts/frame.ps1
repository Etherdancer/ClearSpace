Add-Type -AssemblyName System.Drawing
$outDir = "C:\Users\Tomek\.gemini\antigravity\scratch\ClearSpace\fastlane\metadata\android\en-US\images\phoneScreenshots"

function Add-PhoneFrame {
    param($inFile, $outFile)
    $img = [System.Drawing.Image]::FromFile($inFile)
    
    $canvasW = 1260
    $canvasH = 2240
    $bmp = New-Object System.Drawing.Bitmap $canvasW, $canvasH
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.ColorTranslator]::FromHtml("#E5E7EB"))
    
    # Draw phone body (black rounded rect)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $radius = 120
    $rect = New-Object System.Drawing.Rectangle 50, 110, 1160, 2020
    $path.AddArc($rect.X, $rect.Y, $radius, $radius, 180, 90)
    $path.AddArc($rect.Right - $radius, $rect.Y, $radius, $radius, 270, 90)
    $path.AddArc($rect.Right - $radius, $rect.Bottom - $radius, $radius, $radius, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $radius, $radius, $radius, 90, 90)
    $path.CloseFigure()
    
    $brush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#111827"))
    $g.FillPath($brush, $path)
    
    # Draw screen (the 1080x1920 image)
    $g.DrawImage($img, 90, 160, 1080, 1920)
    
    # Draw notch
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
    
    $g.FillPath($brush, $notchPath)
    
    $g.Dispose()
    $img.Dispose()
    
    $bmp.Save($outFile, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

Add-PhoneFrame "$outDir\screenshot_1.png" "$outDir\screenshot_framed_1.png"
Add-PhoneFrame "$outDir\screenshot_2.png" "$outDir\screenshot_framed_2.png"
Write-Host "Framed images created!"
