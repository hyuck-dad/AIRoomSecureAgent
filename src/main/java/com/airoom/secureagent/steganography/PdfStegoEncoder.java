package com.airoom.secureagent.steganography;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.io.File;
import java.io.IOException;

public class PdfStegoEncoder {

    // PDF용 삽입/검출

    /*
    * PDF는 반면에 구조적으로는 내부에 텍스트 또는 이미지 객체가 포함된 정형 문서 포맷이라
삽입과 검출이 대부분 쌍으로 동작하는 고정된 방식이 많아.
→ 예: 특정 XObject나 ContentStream에 워터마크 삽입 → 나중에 다시 해당 객체 분석
→ 따라서 하나의 클래스에서 삽입/검출 로직을 모두 포함해도 되는 경우가 많아.
* 즉, 이미지의 경우 기능적 분리가 의미 있고, PDF는 통합된 하나의 유틸 클래스로도 충분하다는 판단이야.
    * */

    // PDF에 추적 정보 삽입 (Metadata + 암호화)
    // 삽입 위치 : PDF 메타데이터 (CustomMetadata)에 암호화된 문자열 저장
    // 보안성 : 메타키 난독화 + 암호화된 값 → 쉽게 읽기/삭제 불가


    private static final String METADATA_KEY = "X-Doc-Tracking-Key"; // 난독화 가능

    public static void embed(String inputPdfPath, String outputPdfPath, String payload) {
        try (PDDocument document = PDDocument.load(new File(inputPdfPath))) {

            // 암호화된 PDF는 거부
            if (document.isEncrypted()) {
                System.err.println("[PdfStegoEncoder] 이 PDF는 암호화되어 있어 Stego 삽입이 불가능합니다.");
                return;
            }

            // AES 암호화된 삽입 정보
            String encrypted = StegoCryptoUtil.encrypt(payload);

            // PDF 메타데이터 객체 가져오기
            PDDocumentInformation info = document.getDocumentInformation();

            // 기존 정보 유지 + 커스텀 정보 삽입
            PDDocumentInformation newInfo = new PDDocumentInformation();
            newInfo.setTitle(info.getTitle());
            newInfo.setAuthor(info.getAuthor());
            newInfo.setSubject(info.getSubject());
            newInfo.setKeywords(info.getKeywords());

            // 삽입
            newInfo.setCustomMetadataValue(METADATA_KEY, encrypted);
            document.setDocumentInformation(newInfo);

            // 저장
            document.save(outputPdfPath);
            System.out.println("[PdfStegoEncoder] PDF에 Stego 정보 삽입 완료");

        } catch (Exception e) {
            System.err.println("[PdfStegoEncoder] 삽입 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }
}


