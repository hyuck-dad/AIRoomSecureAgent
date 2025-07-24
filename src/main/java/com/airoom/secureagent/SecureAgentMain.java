package com.airoom.secureagent;

import com.airoom.secureagent.capture.CaptureDetector;
import com.airoom.secureagent.server.StatusServer;
import com.airoom.secureagent.steganography.*;
import com.airoom.secureagent.watermark.WatermarkOverlay;

import java.io.File;

public class SecureAgentMain {
    public static void main(String[] args) {
        System.out.println("[SecureAgent] 보안 에이전트가 시작되었습니다.");

        // 워터마크 테스트 (발표 시 opacity = 0.3f -> 0.01f 로 시연)
//        WatermarkOverlay.showOverlay("AIDT-2025-07-18-userId123", 0.3f);
        // 실제 서비스는 0.005f 로 할 것 - 초저알파
//        WatermarkOverlay.showOverlay("AIDT-2025-07-18-userId123", 0.005f);
        // 개발 과정에서는 그냥 0.0f
//        WatermarkOverlay.showOverlay("AIDT-2025-07-18-userId123", 0.0f);

//        WatermarkOverlay.showOverlay("gotcha~! AIDT-2025-07-18-userId123", 0.8f); // 30% 투명도

        try {
//            String data = "userId123|2025-07-22|hash=ABCD1234";
//            String encrypted = StegoCryptoUtil.encrypt(data);
//            String decrypted = StegoCryptoUtil.decrypt(encrypted);
//
//            System.out.println("암호화 결과: " + encrypted);
//            System.out.println("복호화 결과: " + decrypted);

            // --- Stego 테스트 시작 ---
            String payload = "Stego삽입 테스트 중|2025-07-22|hash=A1B2C3";

            // 이미지 경로
            String imageInput = "test-files/testImageJpg.jpg";
            String imageOutput = "test-files/output2.png";

            // PDF 경로
            String pdfInput = "test-files/testPDF.pdf";
            String pdfOutput = "test-files/secured1.pdf";

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

            ImageStegoWithWatermarkEncoder.encode(
                    "test-files/testImage.png",
                    "test-files/output-watermarked.png",
                    "userA|2025-07-22|hash=XYZ",
                    "test success~",
                    0.15f  // 50% 투명도
            );
            String imageDecoded = ImageStegoDecoder.decode("test-files/output-watermarked.png");
            System.out.println("[TEST] 이미지 복원 결과: " + imageDecoded);

            PdfStegoWithWatermarkEncoder.embed(
                    "test-files/testPDF.pdf",
                    "test-files/secured-watermarked.pdf",
                    "userA|2025-07-22|hash=XYZ",
                    "test success~",
                    0.015f  // 50% 투명도
            );
            String pdfDecoded = PdfStegoDecoder.extract("test-files/secured-watermarked.pdf");
            System.out.println("[TEST] PDF 복원 결과: " + pdfDecoded);



            StatusServer.startServer();
            CaptureDetector.startCaptureWatch();

        } catch (Exception e) {
            System.err.println("서버 시작 중 오류: " + e.getMessage());
        }
    }
}
