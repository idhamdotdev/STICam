Add-Type -AssemblyName System.Drawing

$srcPath = "sticam_media/icon_big.png"
$resDir = "android/app/src/main/res"

$sizes = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}

if (-not (Test-Path $srcPath)) {
    Write-Error "Source icon not found at $srcPath"
    exit 1
}

$srcImg = [System.Drawing.Image]::FromFile((Resolve-Path $srcPath))

foreach ($folder in $sizes.Keys) {
    $size = $sizes[$folder]
    $destFolder = Join-Path $resDir $folder
    
    if (-not (Test-Path $destFolder)) {
        New-Item -ItemType Directory -Path $destFolder -Force | Out-Null
    }
    
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    
    $g.DrawImage($srcImg, 0, 0, $size, $size)
    
    $launcherPath = Join-Path $destFolder "ic_launcher.png"
    $roundPath = Join-Path $destFolder "ic_launcher_round.png"
    
    if (Test-Path $launcherPath) { Remove-Item $launcherPath -Force }
    if (Test-Path $roundPath) { Remove-Item $roundPath -Force }
    
    $bmp.Save($launcherPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Save($roundPath, [System.Drawing.Imaging.ImageFormat]::Png)
    
    $g.Dispose()
    $bmp.Dispose()
    
    Write-Host "Generated $size x $size icons in $folder"
}

$srcImg.Dispose()
Write-Host "Launcher icons successfully updated!"
