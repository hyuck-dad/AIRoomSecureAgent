package com.airoom.secureagent;
// 상단 import에 추가
import com.airoom.secureagent.browser.BrowserContextVerifier;
import com.airoom.secureagent.payload.PayloadFactory;
import com.airoom.secureagent.payload.PayloadManager;
import com.airoom.secureagent.payload.ForensicPayload;
import com.airoom.secureagent.anomaly.AlertSender;
import com.airoom.secureagent.steganography.ImageStegoWithWatermarkEncoder;
import com.airoom.secureagent.steganography.PdfStegoWithWatermarkEncoder;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;

import com.airoom.secureagent.ui.TrayBootstrap;
import com.airoom.secureagent.util.SelfIntegrity;
import com.airoom.secureagent.util.SingleInstance;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import com.airoom.secureagent.anomaly.EventType;
import com.airoom.secureagent.anomaly.LogEmitter;
import com.airoom.secureagent.anomaly.LogEvent;
import com.airoom.secureagent.capture.CaptureDetector;
import com.airoom.secureagent.capture.ProcessMonitor;
import com.airoom.secureagent.device.DeviceFingerprint;
import com.airoom.secureagent.device.DeviceFingerprintCollector;
import com.airoom.secureagent.monitor.GlobalWatcher;
import com.airoom.secureagent.server.StatusServer;
import com.airoom.secureagent.steganography.ImageStegoDecoder;
import com.airoom.secureagent.steganography.PdfStegoDecoder;
import com.airoom.secureagent.log.LogManager;
import com.airoom.secureagent.log.FileSpoolStore;
import com.airoom.secureagent.log.HttpLogger;
import com.airoom.secureagent.network.RetryWorker;

import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

public class SecureAgentMain {

    /** 테스트/데모 플래그 */
    public static final boolean TEST_MODE = Boolean.getBoolean("secureagent.test");   // true=테스트용 좁은 경로 감시
    /** 선택적 스모크 테스트. 실행 시 -Daidt.smoke=true 로 주면 자동 가짜 이벤트 주입 */
    private static final boolean SMOKE_TEST = Boolean.getBoolean("secureagent.smoke");
    // 클래스 상단 플래그 근처에 추가
    private static final boolean FORENSIC_SMOKE = Boolean.getBoolean("secureagent.forensic");  // 포렌식 체인(토큰/HMAC, /event 검증) 빠른 점검

    // 0.5초 주기로 돌리되, 디바운스 1.2초
    private static volatile boolean lastOpen=false;
    private static volatile long lastFlipMs=0;
    private static final int DEBOUNCE_MS=1200;

    private static ScheduledExecutorService procMonES;     // 프로세스 모니터
    private static ScheduledExecutorService browserES;     // 브라우저 컨텍스트 감시
    private static ExecutorService fsWatcherES;            // 파일시스템 워처
    private static RetryWorker retryWorkerRef;             // 재전송 워커 참조

    public static void main(String[] args) {
//        String imgDecode1 = ImageStegoDecoder.decode("C:\\SpringBootDev\\workspaceintellij\\AIRoomSecureAgent\\test-files\\test001.png");
//        System.out.println(imgDecode1);
//        String imgDecode2 = ImageStegoDecoder.decode("C:\\SpringBootDev\\workspaceintellij\\AIRoomSecureAgent\\test-files\\test002.png");
//        System.out.println(imgDecode2);
//        String imgDecode3 = ImageStegoDecoder.decode("C:\\SpringBootDev\\workspaceintellij\\AIRoomSecureAgent\\test-files\\test003.png");
//        System.out.println(imgDecode3);
//        String pdfDecode = PdfStegoDecoder.extract("C:\\SpringBootDev\\workspaceintellij\\AIRoomSecureAgent\\test-files\\111.pdf");
//        System.out.println(pdfDecode);

        // 0-a) 단일 실행 보장 (per-user 설치 구조와 맞춰 LOCALAPPDATA 쪽에 락 파일)
        Path lockFile;
        try {
            String la = System.getenv("LOCALAPPDATA");
            if (la == null || la.isBlank()) la = Paths.get(System.getProperty("user.home"), "AppData","Local").toString();
            lockFile = Paths.get(la, "SecureAgent", "run.lock");
        } catch (Throwable t) {
            lockFile = Paths.get(System.getProperty("user.home"), "AppData","Local","SecureAgent","run.lock");
        }
        SingleInstance.acquireOrExit(lockFile);

        try {
            System.out.println("[SecureAgent] 보안 에이전트가 시작되었습니다. TEST_MODE=" + TEST_MODE);

            /* 0) 단말 식별자 수집 & 콘솔 출력 (단말 식별자 검증) */
            DeviceFingerprint fp = DeviceFingerprintCollector.collect();
            DeviceFingerprintCollector.print(fp);


            /* 1) 로컬 상태 서버 시작 (로그 수신 엔드포인트 포함) */
            // agent.properties 읽어와 StatusServer에 전달
            Properties p = new Properties();
            try (InputStream is = SecureAgentMain.class.getClassLoader().getResourceAsStream("agent.properties")) { if (is != null) p.load(is); }
            SelfIntegrity.initOnce(SecureAgentMain.class);
            String ver = p.getProperty("agent.version", "1.0.0");
            String sha = p.getProperty("agent.sha256Override"); // 있으면 우선
            if (sha == null || sha.isBlank() || "DEV-SHA256-PLACEHOLDER".equalsIgnoreCase(sha)) {
                sha = SelfIntegrity.sha256();                   // 없으면 실시간 계산
            }

            // ---- 트레이 아이콘 초기화 ----
            String shaShort = (sha.length() >= 8 ? sha.substring(0, 8) : sha);
            TrayBootstrap.init(ver, shaShort, sha);

            // ---- 상태 서버 시작 & 툴팁 반영 ----
            StatusServer.configure(ver, sha);
            StatusServer.startServer();
            TrayBootstrap.updateTooltip("SecureAgent " + ver + " (" + shaShort + ")\n동작 중");

            // 상태 변화 시 트레이 알림/툴팁 업데이트를 받도록 리스너 연결
            StatusServer.onActiveChange(active -> {
                if (active) {
                    TrayBootstrap.notifyInfo("브라우저 보호 활성");
                    TrayBootstrap.updateTooltip("보호: 활성");
                } else {
//                    TrayBootstrap.notifyWarn("브라우저 보호 비활성");
                    TrayBootstrap.updateTooltip("보호: 비활성");
                }
            });

            /* 1.5) 오프라인 스풀 + 재전송 워커 초기화 */
            FileSpoolStore spool = new FileSpoolStore();
            spool.recoverOrphanedSending();           // 이전 크래시 복구, 이전 실행 중단 복구 -  비정상 종료로 남아있을 수 있는 sending 파일 복구
            HttpLogger.setOfflineStore(spool);        // 실패 시 자동 스풀
            RetryWorker retryWorker = new RetryWorker(spool);
            retryWorker.start();                      // 주기 재전송 시작
            StatusServer.registerFlushCallback(retryWorker::flushNow); // /flush 트리거
            retryWorker.flushNow();                   // 시작 직후 1회 비우기 시도
            retryWorkerRef = retryWorker;
            /* 2) 캡처 감지(키보드 + 프로세스) */
            // - PrintScreen 감시 스레드
            CaptureDetector.startCaptureWatch();
            // - 프로세스(OBS/GameBar 등) 5초 주기 감시
            procMonES = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "proc-monitor");
                t.setDaemon(true);
                return t;
            });
            procMonES.scheduleAtFixedRate(ProcessMonitor::detect, 0, 5, TimeUnit.SECONDS);

            // === 브라우저 활성탭 감시 → Agent 신호 세팅 ===
            browserES = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "browser-context");
                t.setDaemon(true);
                return t;
            });
            browserES.scheduleAtFixedRate(() -> {
                try {
                    boolean open = BrowserContextVerifier.isTargetBrowserOpenAnywhere();
                    long now = System.currentTimeMillis();
                    if (open != lastOpen && (now - lastFlipMs) >= DEBOUNCE_MS) {
                        lastOpen = open; lastFlipMs = now;
                        StatusServer.setAgentActive(open);
                    }
                } catch (Throwable ignore) {}
            }, 0, 500, java.util.concurrent.TimeUnit.MILLISECONDS);


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

            fsWatcherES = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fs-watcher");
                t.setDaemon(true);
                return t;
            });
            fsWatcherES.submit(() -> {
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

            if (FORENSIC_SMOKE) {
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "forensic-smoke");
                    t.setDaemon(true);
                    return t;
                }).schedule(SecureAgentMain::runForensicSmokeTest, 2, TimeUnit.SECONDS);
            }

            // ---- 종료 훅: 트레이/워커 정리 ----
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    TrayBootstrap.notifyInfo("SecureAgent 종료 중…");
                    TrayBootstrap.remove();
                } catch (Throwable ignored) {}
            }));

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

    /* =========================
     * 포렌식 스모크: token/HMAC ↔ /event 검증 + 스테가 삽입/복호화
     * ========================= */
    private static void runForensicSmokeTest() {
        try {
            Path base = Paths.get(System.getProperty("user.home"), "SecureAgent", "test-files");
            Files.createDirectories(base);

            // --- (A) CAPTURE 이벤트 1건: 서버 /event 실시간 검증만 ---
            ForensicPayload pCap = PayloadFactory.forEvent(EventType.CAPTURE, "-");
            AlertSender.sendForensicEvent(pCap);
            System.out.println("[FORENSIC] CAPTURE sent. token=" +
                    PayloadManager.makeVisibleToken(pCap, 12));

            // --- (B) 이미지: 샘플 생성 → 스테가 삽입 → /event → 디코딩 확인 ---
            Path imgIn  = base.resolve("in.png");
            Path imgOut = base.resolve("out.png");
            makeSamplePng(imgIn);

            ForensicPayload pImg = PayloadFactory.forEvent(EventType.STEGO_IMAGE, imgOut.toString());
            String encB64Img = PayloadManager.encryptPayload(pImg);
            String tokenImg  = PayloadManager.makeVisibleToken(pImg, 12);
            String wmImg     = "AIROOM" + tokenImg + " " + PayloadManager.boundUserId() + " " + pImg.ts();

            ImageStegoWithWatermarkEncoder.encodeEncrypted(
                    imgIn.toString(), imgOut.toString(), encB64Img, wmImg, 0.12f);
            AlertSender.sendForensicEvent(pImg);

            String decodedImg = ImageStegoDecoder.decode(imgOut.toString());
            System.out.println("[FORENSIC] Image decoded payload: " + decodedImg);

            // --- (C) PDF: 샘플 생성 → 스테가 삽입 → /event → 디코딩 확인 ---
            Path pdfIn  = base.resolve("in.pdf");
            Path pdfOut = base.resolve("out.pdf");
            makeSamplePdf(pdfIn);

            ForensicPayload pPdf = PayloadFactory.forEvent(EventType.STEGO_PDF, pdfOut.toString());
            String encB64Pdf = PayloadManager.encryptPayload(pPdf);
            String tokenPdf  = PayloadManager.makeVisibleToken(pPdf, 12);
            String wmPdf     = "AIROOM" + tokenPdf + " " + PayloadManager.boundUserId() + " " + pPdf.ts();

            PdfStegoWithWatermarkEncoder.embedEncrypted(
                    pdfIn.toString(), pdfOut.toString(), encB64Pdf, wmPdf, 0.12f);
            AlertSender.sendForensicEvent(pPdf);

            String decodedPdf = PdfStegoDecoder.extract(pdfOut.toString());
            System.out.println("[FORENSIC] PDF decoded payload: " + decodedPdf);

            System.out.println("[FORENSIC] 완료. 상태 서버 콘솔에 [/event] verify=true 라인이 찍혀야 정상입니다.");

        } catch (Exception e) {
            System.err.println("[FORENSIC] 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void makeSamplePng(Path file) throws Exception {
        BufferedImage bi = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0,0,640,360);
        g.setColor(Color.BLACK); g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g.drawString("Sample PNG " + System.currentTimeMillis(), 20, 180);
        g.dispose();
        ImageIO.write(bi, "png", file.toFile());
    }

    private static void makeSamplePdf(Path file) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Sample PDF " + System.currentTimeMillis());
                cs.endText();
            }
            doc.save(file.toFile());
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

    public static void shutdownAndExit() {
        try {
            // 트레이 먼저 제거(EDT 안전)
            TrayBootstrap.remove();
        } catch (Throwable ignore) {}

        // 워커/스케줄러 종료
        try { if (retryWorkerRef != null) retryWorkerRef.shutdown(); } catch (Throwable ignore) {}
        try { if (procMonES != null) procMonES.shutdownNow(); } catch (Throwable ignore) {}
        try { if (browserES  != null) browserES.shutdownNow(); } catch (Throwable ignore) {}
        try { if (fsWatcherES!= null) fsWatcherES.shutdownNow(); } catch (Throwable ignore) {}

        // 1초 정도 정리 대기
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        System.exit(0);
    }


}
