#define AppNameKor   "보안지킴이"
#define AppNameAscii "SecureAgent"        ; 앱 내부 실행파일/표시용(영문)
#define AppNameSlug  "secureagent"        ; 출력 파일명용(전부 소문자)
#define AppVersion   GetEnv("APP_VERSION")
#define AppImageDir  GetEnv("APP_IMAGE_DIR")   ; ...\app-image\SecureAgent
#define OutputDir    GetEnv("OUTPUT_DIR")
#define AppIcon      GetEnv("APP_ICON")

[Setup]
; 실행 중인 앱 닫기 / 닫은 앱 재시작 / Restart Manager 사용
CloseApplications=yes
RestartApplications=yes
; UAC 없는 경로에 설치하므로 관리자 권한 불필요
PrivilegesRequired=lowest
AppId={{c6e987ca-0b39-44a4-b742-7b7380b1ce56}}
AppName={#AppNameKor}
AppVersion={#AppVersion}
AppPublisher=AIROOM
DefaultDirName={localappdata}\Programs\{#AppNameKor}
OutputDir={#OutputDir}
OutputBaseFilename={#AppNameAscii}-{#AppVersion}
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
CloseApplicationsFilter=보안지킴이.exe,보안 지킴이.exe,SecureAgent.exe
VersionInfoVersion={#AppVersion}.0

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Files]
; APP_IMAGE 전체를 설치 폴더로 복사
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion restartreplace

[Icons]
; 바탕화면 아이콘만 (시작 메뉴 X)
Name: "{userdesktop}\{#AppNameKor}"; Filename: "{app}\{#AppNameKor}.exe"; IconFilename: "{#AppIcon}"

[Run]
; 설치 완료 후 자동 실행(선택)
Filename: "{app}\{#AppNameKor}.exe"; Description: "{cm:LaunchProgram, {#AppNameKor}}"; Flags: nowait postinstall skipifsilent

[InstallDelete]
Type: files; Name: "{commondesktop}\보안 지킴이.lnk"; Check: IsAdminLoggedOn
Type: files; Name: "{userdesktop}\보안 지킴이.lnk"

[Code]
const
  AppKeyFmt = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\%s_is1';
  AppIdRaw  = '{c6e987ca-0b39-44a4-b742-7b7380b1ce56}';

function HasMachineInstall: Boolean;
var s: string;
begin
  Result :=
    RegQueryStringValue(HKLM, Format(AppKeyFmt, [AppIdRaw]), 'UninstallString', s) or
    RegQueryStringValue(HKLM, Format(AppKeyFmt, [AppIdRaw]), 'QuietUninstallString', s);
end;

{ ←↓↓ 이걸 위로 올립니다 }
procedure KillIfRunning(const Image: string);
var rc: Integer;
begin
  if Image <> '' then
    Exec(ExpandConstant('{sys}\taskkill.exe'),
         '/F /IM "' + Image + '"', '', SW_HIDE, ewWaitUntilTerminated, rc);
end;

function InitializeSetup(): Boolean;
begin
  if HasMachineInstall and (not IsAdminLoggedOn) then
  begin
    MsgBox(
      '이 PC에는 과거 "모든 사용자용" 설치가 남아 있습니다.'#13#10+
      '제어판(앱 및 기능)에서 "보안지킴이"를 제거한 뒤 다시 실행해 주세요.'#13#10+
      '또는 설치 파일을 관리자 권한으로 실행해 한 번만 정리할 수 있습니다.',
      mbInformation, MB_OK);
    Result := False;  // 설치 중단 (중복 방지)
    exit;
  end;
  Result := True;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  // 실행 중인 과거 프로세스 종료
  KillIfRunning('보안지킴이.exe');
  KillIfRunning('보안 지킴이.exe');
  KillIfRunning('SecureAgent.exe');
  Result := '';
end;
