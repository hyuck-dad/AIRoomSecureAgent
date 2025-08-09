package com.airoom.secureagent.steganography;

import com.airoom.secureagent.util.CryptoUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.io.File;
import java.nio.file.*;

import static java.nio.file.StandardCopyOption.*;

/**
 *  PDF : ① X-Doc-Tracking-Key 메타 + ② 대각선 워터마크
 *  tmp 에 먼저 저장한 뒤 ATOMIC_MOVE 로 원본을 한번에 교체
 *
 * [변경 요약]
 * - (버그 수정) 입력 로드 경로를 outputPdf가 아닌 inputPdf로 수정
 * - (신규) encPayloadB64(이미 암호화된 Base64 본문)를 직접 받는 오버로드 추가
 */
public class PdfStegoWithWatermarkEncoder {

    private static final String METADATA_KEY = "X-Doc-Tracking-Key";

    /** (기존) 평문 payload를 받아 내부에서 암호화 후 삽입 */
    public static boolean embed(String inputPdf,
                                String outputPdf,
                                String payload,
                                String watermarkText,
                                float opacity) {

        try {
            String encrypted = CryptoUtil.encrypt(payload); // Base64
            return embedEncrypted(inputPdf, outputPdf, encrypted, watermarkText, opacity);
        } catch (Exception e) {
            System.err.println("[PdfStegoWithWatermarkEncoder] 오류(embed-plain): " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /** (신규 권장) 이미 암호화된 Base64(encPayloadB64)를 메타데이터에 바로 삽입 */
    public static boolean embedEncrypted(String inputPdf,
                                         String outputPdf,
                                         String encPayloadB64,
                                         String watermarkText,
                                         float opacity) {

        /* 최종 파일 경로 (Dispatcher 에서 input == output 로 들어올 수도 있음) */
        Path outPath = Paths.get(outputPdf);
        Path inPath  = Paths.get(inputPdf);  // ★ [중요] 입력은 inputPdf에서 로드

        try (PDDocument doc = PDDocument.load(inPath.toFile())) {

            /* -------- 1) 메타데이터 -------- */
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setCustomMetadataValue(METADATA_KEY, encPayloadB64); // ★ 이미 암호화된 Base64를 그대로 저장
            doc.setDocumentInformation(info);

            /* -------- 2) 페이지 워터마크 -------- */
            for (PDPage page : doc.getPages()) {
                PDRectangle r = page.getMediaBox();
                float w = r.getWidth(), h = r.getHeight();

                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(Math.max(0f, Math.min(1f, opacity)));

                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setGraphicsStateParameters(gs);
                    // 화면 크기 기반 동적 폰트 & 간격 (가독/일관성 개선)
                    float base = Math.max(18, Math.min(w, h) / 10f);
                    cs.setFont(PDType1Font.HELVETICA_BOLD, base);
                    cs.setNonStrokingColor(250, 0, 0);

                    float stepX = Math.max(200, w / 4f);
                    float stepY = Math.max(150, h / 4f);

                    for (float y = 0; y < h; y += stepY) {
                        for (float x = 0; x < w; x += stepX) {
                            cs.beginText();
                            cs.setTextMatrix(Matrix.getRotateInstance(
                                    (float) Math.toRadians(-30), x, y));
                            cs.showText(watermarkText);
                            cs.endText();
                        }
                    }
                }
            }

            /* -------- 3) tmp 파일로 저장 후 atomic 교체 -------- */
            Path tmp = Files.createTempFile(outPath.getParent(), "aidt_", ".tmp");
            doc.save(tmp.toFile());      // 임시 파일에 먼저 저장
            doc.close();                 // 핸들 닫기

            Files.move(tmp, outPath, ATOMIC_MOVE, REPLACE_EXISTING);
            System.out.println("[PdfStegoWithWatermarkEncoder] 완료: " + outputPdf);
            return true;

        } catch (Exception e) {
            System.err.println("[PdfStegoWithWatermarkEncoder] 오류(embedEncrypted): " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
