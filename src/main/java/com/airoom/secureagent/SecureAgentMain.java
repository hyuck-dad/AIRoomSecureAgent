package com.airoom.secureagent;

import com.airoom.secureagent.capture.CaptureDetector;
import com.airoom.secureagent.capture.ProcessMonitor;
import com.airoom.secureagent.monitor.GlobalWatcher;
import com.airoom.secureagent.monitor.PrintHookBridge;
import com.airoom.secureagent.server.StatusServer;
import com.airoom.secureagent.steganography.*;
import com.airoom.secureagent.watermark.WatermarkOverlay;
import com.airoom.secureagent.browser.BrowserContextVerifier;


import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SecureAgentMain {

    public static final boolean TEST_MODE = true;
    // true → 테스트 / false → 운영

    public static void main(String[] args) {
        try {
            System.out.println("[SecureAgent] 보안 에이전트가 시작되었습니다.");

            /* 1) 프로세스·CPU 감시는 기존 그대로 */
            ScheduledExecutorService es = Executors.newScheduledThreadPool(2);
            es.scheduleAtFixedRate(ProcessMonitor::detect, 0, 5, TimeUnit.SECONDS);

            /* 2) 감시 루트 정의 */
            Path home = Paths.get(System.getProperty("user.home"));
            List<Path> watchRoots = new ArrayList<>(       // ArrayList 로 만들어야 add() 가 가능
                    TEST_MODE
                            ? List.of(                               // ── 테스트 전용 최소 범위
                            home.resolve("Downloads"),
                            home.resolve("Documents"),
                            home.resolve("Desktop")
                    )
                            : List.of(                            // ── 운영 모드 : 우선 홈 전체
                            home
                    )
            );
            // TEST_MODE=true → 3개 폴더만 감시
            // TEST_MODE=false → 홈 + D:, E:\ …까지 자동 포함

            /* 1-1) 운영 모드일 때만 추가 드라이브(D:, E: …) 포함 */
            if (!TEST_MODE) {
                for (Path root : FileSystems.getDefault().getRootDirectories()) {
                    if (!root.toString().equalsIgnoreCase("C:\\")) {
                        watchRoots.add(root);               // ★ 여기서 추가
                    }
                }
            }

            /* 3) 전역 Watcher를 **백그라운드 스레드**로 구동 */
            Executors.newSingleThreadExecutor().submit(() -> {
                try { new GlobalWatcher(watchRoots).run(); }
                catch (Exception e) { e.printStackTrace(); }
            });

            /* Print → PDF 후킹, DLL 준비되면 주석 해제 */
//        PrintHookBridge.init();

            /* 4) (선택) 로컬 상태 서버 – 이미 구현돼 있으면 유지 */
            StatusServer.startServer();


            // 워터마크 테스트 (발표 시 opacity = 0.3f -> 0.01f 로 시연)
//        WatermarkOverlay.showOverlay("AIDT-2025-07-18-userId123", 0.3f);
            // 실제 서비스는 0.005f 로 할 것 - 초저알파
//        WatermarkOverlay.showOverlay("AIDT-2025-07-18-userId123", 0.005f);
            // 개발 과정에서는 그냥 0.0f
//        WatermarkOverlay.showOverlay("AIDT-2025-07-18-userId123", 0.0f);

//        WatermarkOverlay.showOverlay("gotcha~! AIDT-2025-07-18-userId123", 0.8f); // 30% 투명도


//            String data = "userId123|2025-07-22|hash=ABCD1234";
//            String encrypted = StegoCryptoUtil.encrypt(data);
//            String decrypted = StegoCryptoUtil.decrypt(encrypted);
//
//            System.out.println("암호화 결과: " + encrypted);
//            System.out.println("복호화 결과: " + decrypted);

//            // --- Stego 테스트 시작 ---
//            String payload = "Stego삽입 테스트 중|2025-07-22|hash=A1B2C3";
//
//            // 이미지 경로
//            String imageInput = "test-files/testImageJpg.jpg";
//            String imageOutput = "test-files/output2.png";
//
//            // PDF 경로
//            String pdfInput = "test-files/testPDF.pdf";
//            String pdfOutput = "test-files/secured1.pdf";

//            // 이미지 Stego 삽입 + 추출
//            if (new File(imageInput).exists()) {
//                ImageStegoEncoder.encode(imageInput, imageOutput, payload);
//                String imageDecoded = ImageStegoDecoder.decode(imageOutput);
//                System.out.println("[TEST] 이미지 복원 결과: " + imageDecoded);
//            } else {
//                System.out.println("[TEST] 이미지 파일이 존재하지 않습니다: " + imageInput);
//            }
//
//            // PDF Stego 삽입 + 추출
//            if (new File(pdfInput).exists()) {
//                PdfStegoEncoder.embed(pdfInput, pdfOutput, payload);
//                String pdfDecoded = PdfStegoDecoder.extract(pdfOutput);
//                System.out.println("[TEST] PDF 복원 결과: " + pdfDecoded);
//            } else {
//                System.out.println("[TEST] PDF 파일이 존재하지 않습니다: " + pdfInput);
//            }
//
//            // --- Stego 테스트 끝 ---

//            ImageStegoWithWatermarkEncoder.encode(
//                    "test-files/1.jpg",
//                    "test-files/output1.png",
//                    "userA|2025-07-22|hash=XYZ",
//                    "YangJH Security",
//                    0.9f  // 50% 0.5f 투명도  -> 15% 0.15f
//            );
//            String imageDecoded = ImageStegoDecoder.decode("test-files/output-watermarked.png");
//            System.out.println("[TEST] 이미지 복원 결과: " + imageDecoded);
//
//            PdfStegoWithWatermarkEncoder.embed(
//                    "test-files/testPDF.pdf",
//                    "test-files/secured-watermarked.pdf",
//                    "userA|2025-07-22|hash=XYZ",
//                    "YangJH Security",
//                    0.015f  // 50% 투명도
//            );
//            String pdfDecoded = PdfStegoDecoder.extract("test-files/secured-watermarked.pdf");
//            System.out.println("[TEST] PDF 복원 결과: " + pdfDecoded);



//            StatusServer.startServer();
//            // 감시 스레드 시작
//            new Thread(() -> {
//                boolean isMonitoring = false;
//
//                while (true) {
//                    boolean targetFound = BrowserContextVerifier.isTargetBrowserOpenAnywhere();
//
//                    if (targetFound && !isMonitoring) {
//                        System.out.println("[SecureAgent] 타겟 브라우저 감지됨 → 감지 기능 시작");
//                        isMonitoring = true;
//                        // 감지 시작 로직 삽입
//                    } else if (!targetFound && isMonitoring) {
//                        System.out.println("[SecureAgent] 타겟 브라우저 종료됨 → 감지 기능 중단");
//                        isMonitoring = false;
//                        // 감지 중단 로직 삽입
//                    }
//
//                    try {
//                        Thread.sleep(3000); // 3초마다 감시
//                    } catch (InterruptedException e) {
//                        break;
//                    }
//                }
//            }).start();
//
        } catch (Exception e) {
            System.err.println("서버 시작 중 오류: " + e.getMessage());
        }
    }

    /* === 파일 하나 즉시 복호화 === */
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
}
