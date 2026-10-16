; AirMirror for Windows - Inno Setup script
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{DAC5E3FA-4C4E-4A81-BCCB-E0A8468FA698}
AppName=AirMirror
AppVersion={#AppVersion}
AppPublisher=Ahusan
DefaultDirName={autopf}\AirMirror
DefaultGroupName=AirMirror
DisableProgramGroupPage=yes
OutputDir=..\dist
OutputBaseFilename=AirMirror-PC-Setup
SetupIconFile=launcher\airmirror.ico
UninstallDisplayIcon={app}\AirMirror.exe
Compression=lzma2/ultra64
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
WizardStyle=modern
CloseApplications=force

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "autostart"; Description: "Start AirMirror when Windows starts"; GroupDescription: "Startup:"

[Files]
Source: "..\dist\AirMirror\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\AirMirror"; Filename: "{app}\AirMirror.exe"
Name: "{group}\Uninstall AirMirror"; Filename: "{uninstallexe}"
Name: "{autodesktop}\AirMirror"; Filename: "{app}\AirMirror.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "AirMirror"; \
  ValueData: """{app}\AirMirror.exe"" --tray"; Flags: uninsdeletevalue; Tasks: autostart

[Run]
; let the iPhone reach the receiver through Windows Firewall
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""AirMirror"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""AirMirror"" dir=in action=allow program=""{app}\bin\uxplay.exe"" enable=yes profile=any"; Flags: runhidden
Filename: "{app}\AirMirror.exe"; Description: "Start AirMirror now"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/f /im AirMirror.exe"; Flags: runhidden; RunOnceId: "KillTray"
Filename: "{sys}\taskkill.exe"; Parameters: "/f /im uxplay.exe"; Flags: runhidden; RunOnceId: "KillReceiver"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""AirMirror"""; Flags: runhidden; RunOnceId: "DelFirewall"
