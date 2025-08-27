#define AppNameKor     "보안지킴이"
#define AppVersion     GetEnv("APP_VERSION")
#define AppImageDir    GetEnv("APP_IMAGE_DIR")     ; ...\app-image\보안지킴이
#define OutputDir      GetEnv("OUTPUT_DIR")
#define AppIcon        GetEnv("APP_ICON")

[Setup]
; 실행 중인 앱 닫기 / 닫은 앱 재시작 / Restart Manager 사용
CloseApplications=yes
RestartApplications=yes
RestartManagerSupport=all
; UAC 없는 경로에 설치하므로 관리자 권한 불필요
PrivilegesRequired=lowest
AppId={{c6e987ca-0b39-44a4-b742-7b7380b1ce56}}
AppName={#AppNameKor}
AppVersion={#AppVersion}
AppPublisher=AIROOM
DefaultDirName={localappdata}\Programs\{#AppNameKor}
OutputDir={#OutputDir}
OutputBaseFilename={#AppNameKor}-{#AppVersion}
ArchitecturesInstallIn64BitMode=x64
DisableDirPage=yes
DisableProgramGroupPage=yes
UsePreviousLanguage=no
; 시작 메뉴는 생성하지 않음
; 추가: 인스톨러(EXE) 자체 아이콘
SetupIconFile={#AppIcon}
; 추가: 제어판(프로그램 추가/제거) 아이콘
UninstallDisplayIcon={app}\{#AppNameKor}.exe,0
UsePreviousAppDir=no
CloseApplicationsFilter=보안지킴이.exe;보안 지킴이.exe;SecureAgent.exe
VersionInfoVersion={#AppVersion}.0

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Files]
; APP_IMAGE 전체를 설치 폴더로 복사
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion restartreplace

[Icons]
; 바탕화면 아이콘만 (시작 메뉴 X)
Name: "{commondesktop}\{#AppNameKor}"; Filename: "{app}\{#AppNameKor}.exe"; IconFilename: "{#AppIcon}"

[Run]
; 설치 완료 후 자동 실행(선택)
Filename: "{app}\{#AppNameKor}.exe"; Description: "{cm:LaunchProgram, {#AppNameKor}}"; Flags: nowait postinstall skipifsilent

[InstallDelete]
Type: files; Name: "{commondesktop}\보안 지킴이.lnk"