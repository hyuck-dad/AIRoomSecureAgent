; installer/setup.iss
; ← 파일 인코딩 꼭 UTF-8 with BOM

; 중괄호 이스케이프: {{ ... }}
#define AppId        "{{c6e987ca-0b39-44a4-b742-7b7380b1ce56}}"
#define AppNameKor   "보안 지킴이"
#define AppPublisher "AIROOM"
#define AppVersion   GetEnv("APP_VERSION")
#define OutputDir    GetEnv("OUTPUT_DIR")
#define AppImageDir  GetEnv("APP_IMAGE_DIR")
#define IconIco      GetEnv("APP_ICON")

[Setup]
AppId={#AppId}
AppName={#AppNameKor}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppNameKor}
; 시작 메뉴 완전 비활성화(마법사 페이지도 숨김)
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename={#AppNameKor}-{#AppVersion} 설치
SetupIconFile={#IconIco}
UninstallDisplayIcon={app}\SecureAgent\SecureAgent.exe
ArchitecturesInstallIn64BitMode=x64
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}\SecureAgent"; Flags: recursesubdirs ignoreversion

[Icons]
; 바탕화면 바로가기만 생성 (시작 메뉴 없음)
Name: "{autodesktop}\보안 지킴이"; Filename: "{app}\SecureAgent\SecureAgent.exe"; WorkingDir: "{app}\SecureAgent"

[Run]
Filename: "{app}\SecureAgent\SecureAgent.exe"; Description: "보안 지킴이 실행"; Flags: nowait postinstall skipifsilent
