package com.airoom.secureagent.steganography;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.io.File;

public class PdfStegoDecoder {
    // PDF에서 Stego 정보 추출 및 복호화
    // 삽입 위치 : PDF의 CustomMetadata → key 값으로 삽입된 암호화 문자열 추출
    // 복호화 방식 : AES로 복호화 (앞서 삽입된 암호화된 문자열 대상)

    private static final String METADATA_KEY = "X-Doc-Tracking-Key"; // Encoder와 동일하게 맞춰야 함

    public static String extract(String inputPdfPath) {
        try (PDDocument document = PDDocument.load(new File(inputPdfPath))) {

            PDDocumentInformation info = document.getDocumentInformation();
            String encrypted = info.getCustomMetadataValue(METADATA_KEY);

            if (encrypted == null) {
                throw new IllegalStateException("삽입된 Stego 정보가 존재하지 않습니다.");
            }

            String decrypted = StegoCryptoUtil.decrypt(encrypted);
            System.out.println("[PdfStegoDecoder] 복호화 성공: " + decrypted);
            return decrypted;

        } catch (Exception e) {
            System.err.println("[PdfStegoDecoder] 추출 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

}
