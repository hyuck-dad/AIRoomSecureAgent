package com.airoom.secureagent.capture;

import com.airoom.secureagent.anomaly.EventType;
import com.airoom.secureagent.anomaly.LogEmitter;
import com.airoom.secureagent.anomaly.LogEvent;
import com.airoom.secureagent.log.LogManager;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

/**
 * 프로세스 기반 캡처/녹화 도구 감지기
 *
 * - 기존 동작 유지: "tasklist" 출력 파싱으로 빠르게 1차 감지
 * - NVIDIA Share.exe 는 idle 시 오탐이 잦아, OSHI 로 CPU 사용률 필터링(간단 기준) 적용
 * - 중복 알림 방지: 동일 프로세스는 DETECTION_INTERVAL_MS 내 재발행 차단
 *
 * 변경점:
 * - 문자열 로그 직접 전송 → LogEmitter.emit(LogEvent, line) 로 통합
 * - 프로세스 정의에 EventType 부여 (CAPTURE / RECORDING)
 */
public class ProcessMonitor {

    /** 마지막 감지 시각 저장: 프로세스 파일명 기준 */
    private static final Map<String, Long> lastDetectedMap = new HashMap<>();

    /** 동일 프로세스 감지 중복 방지 간격 */
    private static final long DETECTION_INTERVAL_MS = 10_000; // 10초

    /** 감지 대상: 주요 캡처/녹화 도구 (앱 이름 + 이벤트 타입) */
    private static final Map<String, ProcDef> suspiciousProcesses = Map.ofEntries(
            entry("SnippingTool.exe",      new ProcDef("윈도우 캡처 도구", EventType.CAPTURE)),
            entry("ALCapture.exe",         new ProcDef("알캡처", EventType.CAPTURE)),
            entry("Snipaste.exe",          new ProcDef("스닙페이스트", EventType.CAPTURE)),
            entry("ShareX.exe",            new ProcDef("ShareX", EventType.CAPTURE)),

            entry("obs64.exe",             new ProcDef("OBS Studio", EventType.RECORDING)),
            entry("obs32.exe",             new ProcDef("OBS Studio (32bit)", EventType.RECORDING)),
            entry("bdcam.exe",             new ProcDef("반디캠", EventType.RECORDING)),
            entry("GomCam.exe",            new ProcDef("곰캠", EventType.RECORDING)),
            entry("CamtasiaStudio.exe",    new ProcDef("캠타시아", EventType.RECORDING)),
            entry("ScreenToGif.exe",       new ProcDef("스크린투GIF", EventType.RECORDING)),
            entry("GameBar.exe",           new ProcDef("Xbox Game Bar", EventType.RECORDING)),
            entry("BroadcastDVRServer.exe",new ProcDef("Windows 녹화 서버", EventType.RECORDING)),
            entry("GameBarFT.exe",         new ProcDef("Xbox GameBar FT", EventType.RECORDING)),
            entry("NVIDIA Share.exe",      new ProcDef("NVIDIA 녹화 툴", EventType.RECORDING))
    );

    /** (간단 레코드) 프로세스 정의 */
    private record ProcDef(String appName, EventType type) {}
    private static Map.Entry<String, ProcDef> entry(String k, ProcDef v) { return new AbstractMap.SimpleEntry<>(k, v); }

    /** OSHI 핸들 (NVIDIA Share 필터용) */
    private static final SystemInfo systemInfo = new SystemInfo();
    private static final OperatingSystem os = systemInfo.getOperatingSystem();

    /**
     * 프로세스 감지 호출: 짧은 주기로 반복 호출됨 (예: 200ms)
     * - tasklist 결과를 라인 단위로 읽으며 suspciousProcesses 키가 포함되면 감지
     * - NVIDIA Share.exe 는 추가로 CPU 사용률이 낮으면(Idle) 무시
     */
    public static void detect() {
        try {
            // 1) tasklist 기반 감지 (Windows 전용)
            Process process = Runtime.getRuntime().exec("tasklist");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 2) 대상 목록을 스캔
                    for (Map.Entry<String, ProcDef> e : suspiciousProcesses.entrySet()) {
                        String procName = e.getKey();
                        ProcDef def = e.getValue();

                        if (!line.contains(procName)) continue; // 이 라인에 해당 프로세스 없음

                        long now = System.currentTimeMillis();
                        Long lastDetected = lastDetectedMap.get(procName);

                        // 3) 특정 프로세스 예외 처리: NVIDIA Share.exe 는 idle 이면 스킵
                        if ("NVIDIA Share.exe".equals(procName)) {
                            // OSHI 로 현재 실행 중 목록 조회 → 이름 매칭 → CPU 점유 확인
                            // (NOTE) cumulative 은 초기 0일 수 있어 간단히 2% 기준으로 필터만
                            List<OSProcess> processes = os.getProcesses();
                            Optional<OSProcess> target = processes.stream()
                                    .filter(p -> procName.equalsIgnoreCase(p.getName()))
                                    .findFirst();

                            if (target.isPresent()) {
                                double cpu = target.get().getProcessCpuLoadCumulative() * 100.0;
                                if (cpu < 2.0) {
                                    // idle 상태로 판단: 알림 생략
                                    continue;
                                }
                            } else {
                                // OSHI 로 못 찾은 경우: 보수적으로 생략 (tasklist 라인 매칭만으로는 오탐 가능)
                                continue;
                            }
                        }

                        // 4) 중복 알림 방지
                        if (lastDetected == null || (now - lastDetected > DETECTION_INTERVAL_MS)) {
                            String lineLog = "[프로세스 감지] " + def.appName() + " 실행 중 (" + procName + ")";
                            System.out.println("[SecureAgent] " + lineLog);

                            // 5) 구조화 이벤트 + 라인 로그 동시에 발행
                            LogEvent ev = LogEvent.of(
                                    def.type(),                 // CAPTURE or RECORDING
                                    procName,                   // source: 프로세스 파일명
                                    "-",                        // pageOrPath: (미확정) 브라우저 타이틀/포어그라운드 윈도우 → TODO
                                    def.appName(),              // note: 보기 쉬운 앱 이름
                                    LogManager.getUserId()      // userId: 현재는 LogManager에서 하드코딩
                            );
                            LogEmitter.emit(ev, lineLog);

                            lastDetectedMap.put(procName, now);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // tasklist 실행 실패 등
            String err = "[ProcessMonitor] 감지 중 예외: " + e.getMessage();
            System.err.println(err);
            // 여기서 굳이 서버 전송까지는 하지 않음(노이즈). 필요 시 LogEmitter 사용 가능.
        }
    }
}
