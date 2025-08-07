package com.airoom.secureagent.steganography;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.nio.file.*;

import static java.nio.file.StandardCopyOption.*;

/**
 *  PDF : ① X-Doc-Tracking-Key 메타 + ② 대각선 워터마크
 *  tmp 에 먼저 저장한 뒤 ATOMIC_MOVE 로 원본을 한번에 교체
 */
public class PdfStegoWithWatermarkEncoder {

    private static final String METADATA_KEY = "X-Doc-Tracking-Key";

    /** true = 성공, false = 실패 */
    public static boolean embed(String inputPdf,
                                String outputPdf,
                                String payload,
                                String watermarkText,
                                float opacity) {

        /* 최종 파일 경로 (Dispatcher 에서 input == output 로 들어옴) */
        Path orig = Paths.get(outputPdf);

        try (PDDocument doc = PDDocument.load(orig.toFile())) {

            /* -------- 1) 메타데이터 -------- */
            String encrypted = StegoCryptoUtil.encrypt(payload);
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setCustomMetadataValue(METADATA_KEY, encrypted);
            doc.setDocumentInformation(info);

            /* -------- 2) 페이지 워터마크 -------- */
            for (PDPage page : doc.getPages()) {
                PDRectangle r = page.getMediaBox();
                float w = r.getWidth(), h = r.getHeight();

                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(opacity);

                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setGraphicsStateParameters(gs);
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 36);
                    cs.setNonStrokingColor(250, 0, 0);

                    for (float y = 0; y < h; y += 150) {
                        for (float x = 0; x < w; x += 250) {
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
            Path tmp = Files.createTempFile(orig.getParent(), "aidt_", ".tmp");
            doc.save(tmp.toFile());      // 임시 파일에 먼저 저장
            doc.close();                 // 핸들 닫기

            Files.move(tmp, orig, ATOMIC_MOVE, REPLACE_EXISTING);
            System.out.println("[PdfStegoWithWatermarkEncoder] 완료: " + outputPdf);
            return true;

        } catch (Exception e) {
            System.err.println("[PdfStegoWithWatermarkEncoder] 오류: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
