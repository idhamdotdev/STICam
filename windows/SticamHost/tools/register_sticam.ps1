$obsPath = "Registry::HKEY_CLASSES_ROOT\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{A3FCE0F5-3493-419F-958A-ABA1250EC20B}"
$sticamPath = "Registry::HKEY_CURRENT_USER\Software\Classes\CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance\{D77F129F-53C4-4959-8984-BB2996200234}"

if (Test-Path $obsPath) {
    if (!(Test-Path $sticamPath)) {
        New-Item -Path $sticamPath -Force | Out-Null
    }
    
    $obsProps = Get-ItemProperty -Path $obsPath
    Set-ItemProperty -Path $sticamPath -Name "FriendlyName" -Value "Sticam Camera"
    Set-ItemProperty -Path $sticamPath -Name "CLSID" -Value $obsProps.CLSID
    
    if ($obsProps.FilterData) {
        Set-ItemProperty -Path $sticamPath -Name "FilterData" -Value $obsProps.FilterData -Type Binary
    }
    Write-Host "SUCCESS! Standalone Sticam Camera registered."
} else {
    Write-Host "OBS Virtual Camera was not found. Please install OBS or register it first."
}
