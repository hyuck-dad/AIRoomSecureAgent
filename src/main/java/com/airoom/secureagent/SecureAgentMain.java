package com.airoom.secureagent;

import com.airoom.secureagent.anomaly.EventType;
import com.airoom.secureagent.anomaly.LogEmitter;
import com.airoom.secureagent.anomaly.LogEvent;
import com.airoom.secureagent.capture.CaptureDetector;
import com.airoom.secureagent.capture.ProcessMonitor;
import com.airoom.secureagent.monitor.GlobalWatcher;
import com.airoom.secureagent.server.StatusServer;
import com.airoom.secureagent.steganography.ImageStegoDecoder;
import com.airoom.secureagent.steganography.PdfStegoDecoder;
import com.airoom.secureagent.log.LogManager;
import com.airoom.secureagent.log.FileSpoolStore;
import com.airoom.secureagent.log.HttpLogger;
import com.airoom.secureagent.network.RetryWorker;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class SecureAgentMain {

    /** 테스트/데모 플래그 */
    public static final boolean TEST_MODE = true;   // true=테스트용 좁은 경로 감시
    /** 선택적 스모크 테스트. 실행 시 -Daidt.smoke=true 로 주면 자동 가짜 이벤트 주입 */
    private static final boolean SMOKE_TEST = true;

    public static void main(String[] args) {
        try {
            System.out.println("[SecureAgent] 보안 에이전트가 시작되었습니다. TEST_MODE=" + TEST_MODE);

            /* 1) 로컬 상태 서버 시작 (로그 수신 엔드포인트 포함) */
            StatusServer.startServer();

            /* 1.5) 오프라인 스풀 + 재전송 워커 초기화 */
            FileSpoolStore spool = new FileSpoolStore();
            spool.recoverOrphanedSending();           // 이전 크래시 복구, 이전 실행 중단 복구 -  비정상 종료로 남아있을 수 있는 sending 파일 복구
            HttpLogger.setOfflineStore(spool);        // 실패 시 자동 스풀
            RetryWorker retryWorker = new RetryWorker(spool);
            retryWorker.start();                      // 주기 재전송 시작
            StatusServer.registerFlushCallback(retryWorker::flushNow); // /flush 트리거
            retryWorker.flushNow();                   // 시작 직후 1회 비우기 시도

            /* 2) 캡처 감지(키보드 + 프로세스) */
            // - PrintScreen 감시 스레드
            CaptureDetector.startCaptureWatch();
            // - 프로세스(OBS/GameBar 등) 5초 주기 감시
            ScheduledExecutorService es = Executors.newScheduledThreadPool(2);
            es.scheduleAtFixedRate(ProcessMonitor::detect, 0, 5, TimeUnit.SECONDS);

            /* 3) 파일 시스템 감시(GlobalWatcher) */
            Path home = Paths.get(System.getProperty("user.home"));
            List<Path> watchRoots = new ArrayList<>(
                    TEST_MODE
                            ? List.of(
                            home.resolve("Downloads"),
                            home.resolve("Documents"),
                            home.resolve("Desktop")
                    )
                            : List.of(home) // 운영: 홈 전체
            );
            if (!TEST_MODE) {
                // 운영일 때 추가 드라이브 포함(D:, E: ...)
                for (Path root : FileSystems.getDefault().getRootDirectories()) {
                    if (!root.toString().equalsIgnoreCase("C:\\")) {
                        watchRoots.add(root);
                    }
                }
            }

            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fs-watcher");
                t.setDaemon(true);
                return t;
            }).submit(() -> {
                try { new GlobalWatcher(watchRoots).run(); }
                catch (Exception e) { e.printStackTrace(); }
            });

            /* 4) 선택: 스모크 테스트 (가짜 이벤트로 탐지 파이프라인 빠르게 검증)
                   실행 옵션 예)  java -Daidt.smoke=true -jar app.jar
             */
            if (SMOKE_TEST) {
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "smoke");
                    t.setDaemon(true);
                    return t;
                }).submit(SecureAgentMain::runSmokeTest);
            }

            /* 메인 스레드는 대기만 */
            System.out.println("[SecureAgent] 초기화 완료. 감시 중… (SMOKE=" + SMOKE_TEST + ")");
        } catch (Exception e) {
            System.err.println("초기화 중 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /* === 파일 하나 즉시 복호화(기존 유지) === */
    public static String decodeOnce(Path p) {
        String n = p.toString().toLowerCase();
        try {
            if (n.endsWith(".pdf"))
                return PdfStegoDecoder.extract(p.toString());
            else if (n.endsWith(".png") || n.matches(".*\\.(jpe?g)$"))
                return ImageStegoDecoder.decode(p.toString());
            else
                return "지원되지 않는 형식";
        } catch (Exception e) {
            return "복호화 오류: " + e.getMessage();
        }
    }

    /* =========================
     * 스모크 테스트 (한 번에 ALERT/SUPPRESS/ALERT 검증)
     * ========================= */
    private static void runSmokeTest() {
        try {
            final String user = safeUserId();

            // AnomalyDetector 설정과 맞추기
            final int COOLDOWN_SEC        = 20;   // ALERT_COOLDOWN_MS/1000
            final int CAPTURE_THRESHOLD   = 5;    // 30s 윈도 내 5회
            final int STEGO_THRESHOLD     = 10;   // 60s 윈도 내 10회
            final int RECORDING_SECONDS   = 31;   // 30s 임계 + 1s
            final int POST_ALERT_PINGS    = 5;    // ALERT 직후 쿨다운 내 추가 핑
            final int INACTIVE_GAP_WAIT_S = 16;   // RECORDING_INACTIVE_GAP(15s) 초과 대기

            System.out.println("[SecureAgent][SMOKE] === 시작 ===");
            LogManager.writeLog("[SMOKE] 시작: ALERT→SUPPRESS→ALERT 흐름 점검 (쿨다운=" + COOLDOWN_SEC + "s)");

            /* ========= CAPTURE ========= */
            System.out.println("[SMOKE] CAPTURE 라운드#1 → 예상: ALERT 1회");
            for (int i = 1; i <= CAPTURE_THRESHOLD; i++) {
                emit(EventType.CAPTURE, "keyboard", "-", "PrintScreen#R1-" + i, user);
                TimeUnit.MILLISECONDS.sleep(800);
            }

            System.out.println("[SMOKE] CAPTURE 라운드#2(쿨다운 내 재임계) → 예상: SUPPRESS");
            for (int i = 1; i <= CAPTURE_THRESHOLD; i++) {
                emit(EventType.CAPTURE, "keyboard", "-", "PrintScreen#R2-" + i, user);
                TimeUnit.MILLISECONDS.sleep(800);
            }

            System.out.println("[SMOKE] CAPTURE 쿨다운 대기 " + (COOLDOWN_SEC + 2) + "s");
            TimeUnit.SECONDS.sleep(COOLDOWN_SEC + 2);

            System.out.println("[SMOKE] CAPTURE 라운드#3(쿨다운 종료 후) → 예상: ALERT 1회");
            for (int i = 1; i <= CAPTURE_THRESHOLD; i++) {
                emit(EventType.CAPTURE, "keyboard", "-", "PrintScreen#R3-" + i, user);
                TimeUnit.MILLISECONDS.sleep(800);
            }

            /* ========= STEGO_IMAGE ========= */
            System.out.println("[SMOKE] STEGO_IMAGE 라운드#1 → 예상: ALERT 1회");
            for (int i = 1; i <= STEGO_THRESHOLD; i++) {
                emit(EventType.STEGO_IMAGE, "image", "C:/Downloads/test(" + i + ").png", null, user);
                TimeUnit.MILLISECONDS.sleep(300);
            }

            System.out.println("[SMOKE] STEGO_IMAGE 라운드#2(쿨다운 내 재임계) → 예상: SUPPRESS");
            for (int i = 1; i <= STEGO_THRESHOLD; i++) {
                emit(EventType.STEGO_IMAGE, "image", "C:/Downloads/test(" + (i + 10) + ").png", null, user);
                TimeUnit.MILLISECONDS.sleep(300);
            }

            System.out.println("[SMOKE] STEGO_IMAGE 쿨다운 대기 " + (COOLDOWN_SEC + 2) + "s");
            TimeUnit.SECONDS.sleep(COOLDOWN_SEC + 2);

            System.out.println("[SMOKE] STEGO_IMAGE 라운드#3(쿨다운 종료 후) → 예상: ALERT 1회");
            for (int i = 1; i <= STEGO_THRESHOLD; i++) {
                emit(EventType.STEGO_IMAGE, "image", "C:/Downloads/test(" + (i + 20) + ").png", null, user);
                TimeUnit.MILLISECONDS.sleep(300);
            }

            /* ========= STEGO_PDF ========= */
            System.out.println("[SMOKE] STEGO_PDF 라운드#1 → 예상: ALERT 1회");
            for (int i = 1; i <= STEGO_THRESHOLD; i++) {
                emit(EventType.STEGO_PDF, "pdf", "C:/Downloads/test(" + i + ").pdf", null, user);
                TimeUnit.MILLISECONDS.sleep(300);
            }

            System.out.println("[SMOKE] STEGO_PDF 라운드#2(쿨다운 내 재임계) → 예상: SUPPRESS");
            for (int i = 1; i <= STEGO_THRESHOLD; i++) {
                emit(EventType.STEGO_PDF, "pdf", "C:/Downloads/test(" + (i + 10) + ").pdf", null, user);
                TimeUnit.MILLISECONDS.sleep(300);
            }

            System.out.println("[SMOKE] STEGO_PDF 쿨다운 대기 " + (COOLDOWN_SEC + 2) + "s");
            TimeUnit.SECONDS.sleep(COOLDOWN_SEC + 2);

            System.out.println("[SMOKE] STEGO_PDF 라운드#3(쿨다운 종료 후) → 예상: ALERT 1회");
            for (int i = 1; i <= STEGO_THRESHOLD; i++) {
                emit(EventType.STEGO_PDF, "pdf", "C:/Downloads/test(" + (i + 20) + ").pdf", null, user);
                TimeUnit.MILLISECONDS.sleep(300);
            }

            /* ========= RECORDING ========= */
            System.out.println("[SMOKE] RECORDING 세션#1 시작(≈" + RECORDING_SECONDS + "s) → 예상: ALERT 1회");
            for (int i = 0; i < RECORDING_SECONDS; i++) {
                emit(EventType.RECORDING, "obs64.exe", "-", "OBS_ACTIVE", user);
                TimeUnit.SECONDS.sleep(1);
            }

            // ★ ALERT 직후에도 쿨다운 창(20s) 내 추가 핑 → AnomalyDetector가 SUPPRESS 1회 기록
            System.out.println("[SMOKE] RECORDING 세션#1: ALERT 이후 쿨다운 내 추가 핑(" + POST_ALERT_PINGS + "s) → 예상: SUPPRESS 1회");
            for (int i = 0; i < POST_ALERT_PINGS; i++) {
                emit(EventType.RECORDING, "obs64.exe", "-", "OBS_ACTIVE", user);
                TimeUnit.SECONDS.sleep(1);
            }

            // 세션 경계 넘기기(현재 INACTIVE_GAP=15s 가정)
            System.out.println("[SMOKE] 세션 종료 유도 대기 (INACTIVE_GAP 초과: " + INACTIVE_GAP_WAIT_S + "s)");
            TimeUnit.SECONDS.sleep(INACTIVE_GAP_WAIT_S);

            System.out.println("[SMOKE] RECORDING 세션#2 시작(≈" + RECORDING_SECONDS + "s) → 예상: ALERT 1회");
            for (int i = 0; i < RECORDING_SECONDS; i++) {
                emit(EventType.RECORDING, "obs64.exe", "-", "OBS_ACTIVE", user);
                TimeUnit.SECONDS.sleep(1);
            }

            System.out.println("[SMOKE] === 완료 ===  capture-log.txt 및 상태 서버 콘솔에서");
            System.out.println("        - CAPTURE/STEGO: ALERT → SUPPRESS → ALERT");
            System.out.println("        - RECORDING: ALERT 1회 + SUPPRESS 1회(세션#1), 그 후 세션#2 ALERT 1회");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[SMOKE] 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }




    /** 공용 이벤트 발행 헬퍼 */
    private static void emit(EventType type, String source, String pageOrPath, String windowTitle, String user) {
        LogEvent ev = LogEvent.of(type, source, pageOrPath, windowTitle, user);
        String line = "[TEST] synthetic " + type + " → " + source + " | " + pageOrPath;
        LogEmitter.emit(ev, line); // LogManager/HttpLogger/AnomalyDetector 흐름을 모두 태움
    }

    private static String safeUserId() {
        try { return LogManager.getUserId(); }
        catch (Throwable t) { return "user-unknown"; }
    }
}
