# AIRoom SecureAgent (보안지킴이)

Windows 환경에서 **교육 콘텐츠 유출 방지**와 **서비스 접근 게이팅**을 동시에 수행하는 로컬 보안 에이전트입니다. 프론트엔드·백엔드와 연동해 에이전트 실행/무결성/활성 상태를 검증하고, 다중 디스플레이 워터마크, 다운로드 시점 스테가노그래피, 이상행위 탐지, 오프라인 스풀러(재전송)까지 제공합니다.

---

## ✨ 핵심 기능 (요약)

1. **최초 진입 게이팅**: 최신 버전 실행 여부 자동 검증 → 미설치/미실행 시 설치 유도, 설치 완료 즉시 자동 실행.
2. **무결성 검증**: 실행 시 **버전/바이너리 해시** 확인, 설치 과정의 GUID로 기존 인스턴스 식별·교체 후 자동 실행.
3. **Heartbeat 모니터링**: 비정상 종료 감지 시 웹서비스 차단, 실행 중에만 이용 허용.
4. **프로세스 레벨 탐지(JNA)**: 서비스 이용 시에만 ‘보호: 활성’ 유지.
5. **다중 디스플레이 워터마크**: 서브 모니터 포함 전체 화면에 오버레이(활/비활성 창 무관).
6. **다운로드 보호 파이프라인**: 이미지·PDF에 **워터마크 + 스테가** 자동 삽입, `ATOMIC_MOVE`로 누락/손상 방지.
7. **정확한 포렌식 복원**: PNG/JPG/PDF 메타데이터 기반으로 포맷별 스테가 복원.
8. **이상행위 실시간 탐지**: JNA·OSHI 기반 캡처/녹화 도구·PrintScreen 탐지, **암호화 로그** 전송.
9. **오프라인 보장(At-least-once)**: AES-256 로컬 큐(FileSpoolStore), 네트워크 복구 후 자동 재전송·롤백.

---

## 🧭 동작 개요

### 1) 로컬 상태 서버(에이전트)

* 기동 시 127.0.0.1의 **4455–4460 포트** 중 가용 포트에 바인딩하고, 여러 엔드포인트를 제공합니다(`GET /status`, `POST /activate-watermark`, `POST /bind-session`, `POST /log`, `POST /flush`, `POST /event`, `POST /download-tag`, `GET /metrics`). &#x20;
* `/status`는 `version`, `sha256`, `startedAt`, `port`를 반환합니다.&#x20;
* `/bind-session` 수신 시 바인딩된 사용자 정보를 즉시 워터마크 텍스트에 반영(새로고침)합니다.&#x20;
* 워터마크 on/off는 FE 생존 신호와 에이전트 활성 신호를 종합하여 평가하며, 상태 변화 시 오버레이를 표시/해제합니다.  &#x20;

### 2) 프론트엔드 연동

* 활성 탭 여부를 기준으로 **15초 주기** 핑 및 포커스/가시성 이벤트로 워터마크 활성 상태를 동기화합니다(`POST /activate-watermark`).  &#x20;
* 에이전트의 **버전·SHA-256**을 서버에 제출해 검증합니다(`POST /api/agent/verify`).&#x20;
* 로그인 후 세션은 로컬 에이전트에 바인딩합니다(`POST /bind-session`).&#x20;

### 3) 캡처/녹화 및 키 입력 탐지

* Windows `tasklist` 출력 파싱 + **OSHI**로 CPU 사용률을 확인하여 오탐을 줄이고, 주요 캡처/녹화 도구(예: SnippingTool, OBS, Bandicam 등)를 탐지합니다. &#x20;
* **PrintScreen 키**도 감지하여 구조화 이벤트 전송을 수행합니다.&#x20;

### 4) 워터마크(다중 디스플레이)

* 모든 디스플레이에 **투명 JWindow 오버레이**를 띄워 텍스트 워터마크를 표시합니다.&#x20;
* 텍스트는 바운드된 사용자 ID 등으로 동적 구성됩니다(`"AIRoom " + boundUserId()`).&#x20;

### 5) 다운로드 보호 파이프라인

* 파일시스템 Watcher가 다운로드/이동을 감지하여, **이미 태깅된 파일**은 건너뛰고(탐지 키: `StegoPayload`, PDF: `X-Doc-Tracking-Key`), 신규 파일에 워터마크/스테가를 삽입합니다. &#x20;
* 운영 모드에서는 \*\*사용자 홈 전체 및 추가 드라이브(D:, E: …)\*\*까지 감시합니다.&#x20;

### 6) 스테가 복원(포렌식)

* PNG는 `tEXtEntry(keyword="StegoPayload")`에서 Base64로 암호화된 페이로드를 추출·복호화하고, JPG는 EXIF/COM을, PDF는 커스텀 메타데이터를 활용합니다. &#x20;

### 7) 오프라인 스풀러 & 재전송

* 로컬 큐는 `APPDATA/SecureAgent/spool/{ready,sending}`에 저장되며, 저장 시점 암호화(AES)와 \*\*원자적 쓰기/이동(`ATOMIC_MOVE`)\*\*를 사용합니다.  &#x20;
* **지수 백오프** 기반 재시도, 성공 시 삭제/실패 시 롤백, `/flush`로 즉시 비우기 트리거를 제공합니다.  &#x20;

---

## 🔌 로컬/백엔드/프론트 API 요약

**Agent (127.0.0.1:4455–4460)**

* `GET /status` : `{version, sha256, startedAt, heartbeatId, port}` 반환.&#x20;
* `POST /bind-session` : `{memberId, jwt}` 바인딩 → 워터마크 텍스트 즉시 갱신.&#x20;
* `POST /activate-watermark` : `{active}` 로 FE 활성 신호 전달.&#x20;
* `POST /log`, `POST /event`, `POST /download-tag`, `POST /flush`, `GET /metrics` 제공.&#x20;

**Frontend**

* 활성 탭 동기화(`bindActiveTabWatermark`) 및 15초 핑.&#x20;
* 에이전트 검증: `POST /api/agent/verify {sha256, version}`.&#x20;
* 세션 바인딩: `POST http://127.0.0.1:{port}/bind-session`.&#x20;

---

## ⚙️ 구성/운영 옵션

* 로컬 파일 로그(개발용): `-Dsecureagent.devlog=true`&#x20;
* 스풀 저장 암호화 on/off: `-Daidt.spool.encrypt=true` 또는 `AIDT_SPOOL_ENCRYPT=true/1`&#x20;
* 스모크 테스트: `-Daidt.smoke=true` (옵션), 포렌식 스모크도 제공. &#x20;

---

## 🧩 기술 포인트 (슬라이드 요약을 README에 맞춰 정리)

* **게이트 & 무결성**: 최초 진입에서 에이전트 실행·버전·해시를 검증하고, 실행 중에만 이용 허용. (FE `verify`, Agent `/status`) &#x20;
* **다중 디스플레이 워터마크**: 모든 화면에 투명 오버레이, 사용자/디바이스·시간 기반 동적 텍스트. &#x20;
* **다운로드 시점 보호**: 스테가 + 워터마크 동시 삽입 후 원자 교체로 부분쓰기/경합 방지. (설계 요지)
* **스테가 복원**: PNG `tEXtEntry:StegoPayload`, JPG EXIF/COM, PDF `X-Doc-Tracking-Key` 복원 경로 제공. &#x20;
* **이상행위 탐지**: `tasklist` + OSHI CPU 필터로 캡처/녹화 도구 및 PrintScreen 탐지.  &#x20;
* **오프라인 보장**: AES 스풀, `ready→sending` 원자 이동, 실패 시 롤백, 백오프 재전송, `/flush`.  &#x20;

---

## 🛠️ “문제–해결–배운 점” (개발 회고 요약)

1. **설치 UX & 브랜딩**

* 문제: 58MB 설치파일, 설치 후 수동 실행, 한글 브랜딩 미흡.
* 해결: jpackage App Image + Inno Setup 2단 빌드, LZMA2 압축, 설치 완료 **자동 실행**, 한글 명칭 통일.
* 배움: 패키징 전략만으로도 용량·경험 크게 개선.

2. **로그 유실 0 스풀러**

* 문제: 네트워크 오류 시 유실·고아(sending) 파일.
* 해결: `ready↔sending` 상태머신, 재기동 orphan 복구, 지수 백오프 배치 재전송, `/flush` 연동.  &#x20;
* 배움: **회복탄력성 우선 설계**와 표준 플로우 편입.

3. **다운로드 시점 보호**

* 문제: 부분쓰기·즉시 열람으로 삽입 누락/손상 위험, 훅킹 후처리 한계.
* 해결: 감지→삽입→\*\*원자 교체(ATOMIC\_MOVE)\*\*로 최종 파일만 노출, 가시+비가시 이중화.

4. **Composite Device ID**

* 문제: 단일 식별자 오탐/중복, 원문 전송의 프라이버시 리스크.
* 해결: 다중 하드웨어 신호 결합·해시 토큰, 서버 검증·상관분석 강화.

5. **스테가 안정화 (LSB→메타 기반)**

* 문제: LSB는 변환/재저장에 취약.
* 해결: PNG tEXt/JPEG EXIF 등 **형식 인지형 메타 스테가** + 워터마크 이중화.&#x20;

6. **멀티모니터 & 상태 가시화**

* 문제: 보조 모니터 유출 창구, 보호 상태 인지 부족.
* 해결: 모든 디스플레이 오버레이 + FE/Agent 신호 기반 on/off, 바운드 사용자 텍스트 갱신. &#x20;

7. **Heartbeat 게이팅**

* 문제: 미실행/강제종료 시 무방비 노출.
* 해결: FE 가드 + 로컬 상태 서버 신호로 상시 검증, 미실행 시 차단·재실행 유도. (FE verify & ping) &#x20;

---

## 📂 디렉터리/구성(요지)

* `StatusServer` : 127.0.0.1 상태 서버(엔드포인트, 워터마크 토글/텍스트 갱신). &#x20;
* `WatermarkOverlay` : 다중 디스플레이 투명 오버레이 렌더링.&#x20;
* `GlobalWatcher` : 파일시스템 감시(운영: 홈 전체 + 추가 드라이브).&#x20;
* `AlreadyTaggedChecker` : `StegoPayload`/`X-Doc-Tracking-Key` 존재 검사.&#x20;
* `ImageStegoDecoder` : PNG/JPG 스테가 복원.&#x20;
* `ProcessMonitor` : tasklist + OSHI 기반 캡처/녹화 도구 탐지.&#x20;
* `FileSpoolStore` / `RetryWorker` : AES 스풀·원자 이동·백오프 재전송·롤백. &#x20;

---

## 🚀 설치 & 실행 (요지)

* **설치 파일**: Inno Setup 기반 설치 후 **자동 실행**(한글 브랜딩/아이콘).
* **최초 접속**: 프론트가 에이전트 실행/버전/해시를 검증, 미설치·미실행이면 설치/실행 유도.
* **실행 중 표시**: 시스템 트레이/오버레이로 ‘보호: 활성/비활성’ 상태를 가시화.

---

## 📌 주의/한계

* Windows 전용 구현(예: `tasklist`, APPDATA 경로, JWindow 오버레이). &#x20;
* 운영 환경에서는 파일 로그가 비활성화되어 있으며(옵션), 네트워크 장애 주입/스모크는 개발 시에만 사용하세요.  &#x20;

---
