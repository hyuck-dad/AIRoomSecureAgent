package com.airoom.secureagent.steganography;

import com.airoom.secureagent.anomaly.EventType;
import com.airoom.secureagent.anomaly.LogEmitter;
import com.airoom.secureagent.anomaly.LogEvent;
import com.airoom.secureagent.log.LogManager;
import com.airoom.secureagent.util.CryptoUtil;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import java.io.File;

/**
 * PDF에서 Stego 정보 추출 및 복호화
 * - 삽입 위치 : PDF의 CustomMetadata → 특정 key 값
 * - 복호화 방식 : AES (CryptoUtil)
 *
 * 변경점:
 * - 단순 stdout/stderr 로깅 → LogEmitter.emit(LogEvent, line) 사용
 * - 이벤트 타입: 성공(DECODE_SUCCESS) / 실패(DECODE_FAIL)
 */
public class PdfStegoDecoder {

    /** Encoder와 동일해야 하는 메타데이터 키 */
    private static final String METADATA_KEY = "X-Doc-Tracking-Key";

    /**
     * PDF에서 Stego 메타데이터를 읽어 복호화한 페이로드를 반환
     * @param inputPdfPath PDF 경로
     * @return 복호화된 문자열, 없거나 실패 시 null
     */
    public static String extract(String inputPdfPath) {
        try (PDDocument document = PDDocument.load(new File(inputPdfPath))) {
            PDDocumentInformation info = document.getDocumentInformation();
            String encrypted = info.getCustomMetadataValue(METADATA_KEY);

            if (encrypted == null) {
                String line = "[PdfStegoDecoder] 삽입된 Stego 정보 없음 → " + inputPdfPath;
                // 실패 이벤트 발행
                LogEvent ev = LogEvent.of(
                        EventType.DECODE_FAIL,
                        "pdf",
                        inputPdfPath,
                        "no-stego-metadata",
                        LogManager.getUserId()
                );
                LogEmitter.emit(ev, line);
                System.err.println(line);
                return null;
            }

            String decrypted = CryptoUtil.decrypt(encrypted);
            String ok = "[PdfStegoDecoder] 복호화 성공: " + decrypted + " → " + inputPdfPath;

            // 성공 이벤트 발행
            LogEvent ev = LogEvent.of(
                    EventType.DECODE_SUCCESS,
                    "pdf",
                    inputPdfPath,
                    decrypted,
                    LogManager.getUserId()
            );
            LogEmitter.emit(ev, ok);
            System.out.println(ok);

            return decrypted;

        } catch (Exception e) {
            String err = "[PdfStegoDecoder] 추출/복호화 오류: " + e.getMessage() + " → " + inputPdfPath;

            // 실패 이벤트 발행
            LogEvent ev = LogEvent.of(
                    EventType.DECODE_FAIL,
                    "pdf",
                    inputPdfPath,
                    e.getClass().getSimpleName(),
                    LogManager.getUserId()
            );
            LogEmitter.emit(ev, err);
            System.err.println(err);

            return null;
        }
    }
}
