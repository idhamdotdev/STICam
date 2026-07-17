[Setup]
; App Information
AppId={{C6B5F4A0-C14B-4E90-B8E5-3A4E839B7A33}
AppName=STICam Host
AppVersion=1.1.0
AppPublisher=idham.dev
AppPublisherURL=https://idham.dev
AppSupportURL=https://idham.dev
AppUpdatesURL=https://idham.dev

; Default installation folder
DefaultDirName={autopf}\STICam Host
DefaultGroupName=STICam Host
AllowNoIcons=yes

; Output settings
OutputDir=..\output_newest
OutputBaseFilename=STICamHost_Installer
Compression=lzma2/ultra64
SolidCompression=yes

; --- Custom UI & Design ---
WizardStyle=modern
; Force the Welcome page to show so the sidebar image is visible (hidden by default in IS 6)
DisableWelcomePage=no
; Uncomment and point to a 164x314 .bmp image for the left sidebar (first/last pages)
WizardImageFile=sidebar_image.bmp
; Uncomment and point to a 55x55 .bmp image for the top-right corner of inner pages
WizardSmallImageFile=small_image.bmp
; Uncomment and point to an .ico file to change the installer's taskbar/exe icon
SetupIconFile=installer_icon.ico

; Ask for admin rights for Program Files installation
PrivilegesRequired=admin

; Specify x64 architecture as we built for win-x64
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; Bundle all files from the publish directory (including the self-contained exe, ffmpeg dlls, adb.exe, etc)
Source: "SticamHost\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; NOTE: Don't use "Flags: ignoreversion" on any shared system files

[Icons]
; Start Menu shortcut
Name: "{group}\STICam Host"; Filename: "{app}\STICamHost.exe"
Name: "{group}\{cm:UninstallProgram,STICam Host}"; Filename: "{uninstallexe}"
; Desktop shortcut
Name: "{autodesktop}\STICam Host"; Filename: "{app}\STICamHost.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\STICamHost.exe"; Description: "{cm:LaunchProgram,STICam Host}"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  ErrorCode: Integer;
begin
  // Trigger after the uninstallation completes
  if CurUninstallStep = usPostUninstall then
  begin
    // Ask the user to take a survey to explain why they uninstalled
    if MsgBox('We''re sorry to see you go! Would you mind taking a quick 1-minute survey to help us improve STICam?', mbConfirmation, MB_YESNO) = idYes then
    begin
      // Replace this URL with your actual survey form link (e.g. Google Forms / Typeform)
      ShellExec('open', 'https://forms.gle/Aie9vuRV15PCXUSw9', '', '', SW_SHOWNORMAL, ewNoWait, ErrorCode);
    end;
  end;
end;
