; Inno Setup script for Mirto-Launcher (Windows)
; Builds a single-file installer that embeds the application AND a private
; JavaFX 17 runtime (jre\), so end users do not need Java installed.
;
; Compile with:
;   ISCC.exe /DAppVersion=<version> installer.iss
; (build-installer.ps1 does this automatically)

#define AppName "Mirto-Launcher"
#define AppExe "MirtoLauncher.exe"
#define AppPublisher "Paolo Marcheschi"
#define AppUrl "https://github.com/marcheschi/Mirto-launcher"
#ifndef AppVersion
#define AppVersion "0.0.0"
#endif

[Setup]
AppId={{7E1B2F6A-52C4-4B7D-9A21-3D6C0A55B10F}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppUrl}
AppSupportURL={#AppUrl}
DefaultGroupName=Mirto-Launcher
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppExe}
; 64-bit only install (the embedded Zulu FX runtime is win_x64)
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible
; No admin rights required: per-user install under %LocalAppData%. This also
; means the app dir stays writable, so data\ and cache\ always work.
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\MirtoLauncher
; Single big file (JRE inside): use lzma2 for best compression
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
OutputDir=output
OutputBaseFilename=MirtoLauncher-{#AppVersion}-windows-x64-setup
SetupIconFile=BridgeLinkLauncher.ico
Uninstallable=yes
CloseApplications=yes
RestartApplications=no

[Messages]
; Friendlier wording: this installer ships its own Java runtime
WelcomeLabel2=This will install [name/ver] on your computer.%n%nA private Java 17 runtime with JavaFX is embedded: no Java installation is required.

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Application files
Source: "app\MirtoLauncher.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "app\mirto-launcher-*.jar"; DestDir: "{app}"; Flags: ignoreversion
Source: "app\lib\java-console.jar"; DestDir: "{app}\lib"; Flags: ignoreversion recursesubdirs createallsubdirs
; Embedded JavaFX runtime (java embedded)
Source: "app\jre\*"; DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Remove the cache on uninstall; keep data\ (saved connections) on purpose.
Type: filesandordirs; Name: "{app}\cache"
