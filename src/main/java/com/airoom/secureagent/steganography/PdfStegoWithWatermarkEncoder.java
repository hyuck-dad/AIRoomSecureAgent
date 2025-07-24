package com.airoom.secureagent.steganography;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;  // ← 반드시 추가
import java.io.File;

public class PdfStegoWithWatermarkEncoder {

    private static final String METADATA_KEY = "X-Doc-Tracking-Key";

    public static void embed(String inputPdf,
                             String outputPdf,
                             String payload,
                             String watermarkText,
                             float watermarkOpacity) {
        try (PDDocument doc = PDDocument.load(new File(inputPdf))) {

            // 1) 메타데이터 삽입
            String encrypted = StegoCryptoUtil.encrypt(payload);
            var info = doc.getDocumentInformation();
            var newInfo = new org.apache.pdfbox.pdmodel.PDDocumentInformation();
            newInfo.setTitle(info.getTitle());
            newInfo.setAuthor(info.getAuthor());
            newInfo.setSubject(info.getSubject());
            newInfo.setKeywords(info.getKeywords());
            newInfo.setCustomMetadataValue(METADATA_KEY, encrypted);
            doc.setDocumentInformation(newInfo);

            // 2) 페이지별 워터마크
            for (PDPage page : doc.getPages()) {
                PDRectangle rect = page.getMediaBox();
                float width = rect.getWidth();
                float height = rect.getHeight();
                float fontSize = 36;

                // 투명도 상태 객체
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(watermarkOpacity);

                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, AppendMode.APPEND, true, true)) {
                    cs.setGraphicsStateParameters(gs);
                    cs.setFont(PDType1Font.HELVETICA_BOLD, fontSize);
                    cs.setNonStrokingColor(250, 0, 0);

                    // 대각선으로 반복
                    for (float y = 0; y < height; y += 150) {
                        for (float x = 0; x < width; x += 250) {
                            cs.beginText();
                            // Matrix.getRotateInstance 로 회전 및 위치 지정
                            cs.setTextMatrix(
                                    Matrix.getRotateInstance(
                                            (float)Math.toRadians(-30),
                                            x, y
                                    )
                            );
                            cs.showText(watermarkText);
                            cs.endText();
                        }
                    }
                }
            }

            doc.save(outputPdf);
            System.out.println("[PdfStegoWithWatermarkEncoder] 완료: " + outputPdf);

        } catch (Exception e) {
            System.err.println("[PdfStegoWithWatermarkEncoder] 오류");
            e.printStackTrace();
        }
    }
}
