#define AppName "HyperAudio Bridge"
#define AppVersion "0.6.1"
#define RootDir SourcePath + "..\"

[Setup]
AppId={{44F43465-25C5-4EBB-B187-00B5A3A6A62B}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=HyperAudio Bridge
DefaultDirName={localappdata}\Programs\HyperAudio Bridge
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.19041
OutputDir={#RootDir}dist\0.6.1
OutputBaseFilename=HyperAudioBridge-0.6.1-windows-x64-setup
SetupIconFile={#RootDir}windows\HyperAudio.Rust\assets\app.ico
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\hyperaudio.exe
AppMutex=Local\HyperAudioBridge
CloseApplications=yes
RestartApplications=no
SetupLogging=yes

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
chinesesimp.DesktopShortcut=创建桌面快捷方式
chinesesimp.ShortcutOptions=快捷方式：
chinesesimp.LaunchProgram=启动 HyperAudio Bridge
english.DesktopShortcut=Create a desktop shortcut
english.ShortcutOptions=Shortcuts:
english.LaunchProgram=Launch HyperAudio Bridge

[Tasks]
Name: "desktopicon"; Description: "{cm:DesktopShortcut}"; GroupDescription: "{cm:ShortcutOptions}"; Flags: unchecked

[Files]
Source: "{#RootDir}dist\0.6.1\windows\hyperaudio.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RootDir}LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RootDir}NOTICE"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RootDir}README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RootDir}THIRD_PARTY_NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RootDir}dist\0.6.1\licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#RootDir}dist\0.6.1\RUST_DEPENDENCIES.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\hyperaudio.exe"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\hyperaudio.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\hyperaudio.exe"; Description: "{cm:LaunchProgram}"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var Command: String;
begin
  if CurUninstallStep = usUninstall then
    if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'HyperAudioBridge', Command) then
      if Pos(Lowercase('"' + ExpandConstant('{app}\hyperaudio.exe') + '"'), Lowercase(Command)) = 1 then
        RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'HyperAudioBridge');
end;
