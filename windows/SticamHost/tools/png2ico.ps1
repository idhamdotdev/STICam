param(
    [string]$PngPath,
    [string]$IcoPath
)

if (-not (Test-Path $PngPath)) {
    Write-Error "Source file $PngPath does not exist"
    exit 1
}

$pngBytes = [System.IO.File]::ReadAllBytes($PngPath)
$pngSize = $pngBytes.Length

# Create ICO file header (6 bytes)
# 00 00 (Reserved)
# 01 00 (Type: 1 = Icon)
# 01 00 (Count: 1 image)
$header = [byte[]]@(0x00, 0x00, 0x01, 0x00, 0x01, 0x00)

# Create Directory Entry (16 bytes)
# 00 (Width: 0 means 256)
# 00 (Height: 0 means 256)
# 00 (Color count: 0)
# 00 (Reserved)
# 01 00 (Planes: 1)
# 20 00 (Bit count: 32)
# Size (4 bytes, little-endian)
# Offset (4 bytes, little-endian, 22)
$sizeBytes = [System.BitConverter]::GetBytes([uint32]$pngSize)
$offsetBytes = [System.BitConverter]::GetBytes([uint32]22)

$entry = [byte[]]@(
    0x00, 0x00, 0x00, 0x00,
    0x01, 0x00, 0x20, 0x00,
    $sizeBytes[0], $sizeBytes[1], $sizeBytes[2], $sizeBytes[3],
    $offsetBytes[0], $offsetBytes[1], $offsetBytes[2], $offsetBytes[3]
)

$icoBytes = New-Object byte[] ($header.Length + $entry.Length + $pngBytes.Length)
[System.Array]::Copy($header, 0, $icoBytes, 0, $header.Length)
[System.Array]::Copy($entry, 0, $icoBytes, $header.Length, $entry.Length)
[System.Array]::Copy($pngBytes, 0, $icoBytes, ($header.Length + $entry.Length), $pngBytes.Length)

[System.IO.File]::WriteAllBytes($IcoPath, $icoBytes)
Write-Host "Successfully converted $PngPath to $IcoPath"
