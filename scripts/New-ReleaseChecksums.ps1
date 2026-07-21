[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Container })]
    [string] $InputDirectory,

    [string] $OutputFile = 'SHA256SUMS.txt'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath $InputDirectory).Path
$outputPath = if ([System.IO.Path]::IsPathRooted($OutputFile)) {
    [System.IO.Path]::GetFullPath($OutputFile)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $root $OutputFile))
}

$files = @(
    Get-ChildItem -LiteralPath $root -File -Recurse |
        Where-Object { $_.FullName -ne $outputPath } |
        Sort-Object FullName
)

if ($files.Count -eq 0) {
    throw "No release files were found under '$root'."
}

$lines = foreach ($file in $files) {
    $relativePath = $file.FullName.Substring($root.Length)
    $relativePath = $relativePath.TrimStart([char[]] @('\', '/')).Replace('\', '/')
    $digest = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$digest  $relativePath"
}

$encoding = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllLines($outputPath, $lines, $encoding)

Write-Host "Wrote $($files.Count) SHA-256 entries to '$outputPath'."
