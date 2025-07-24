package com.airoom.secureagent.steganography;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.util.Base64;
import java.util.Iterator;
import org.w3c.dom.NodeList;

public class ImageStegoDecoder {

    public static String decode(String inputImagePath) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new File(inputImagePath))) {
            // 1) PNG Reader 준비
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
            ImageReader reader = readers.next();
            reader.setInput(iis, true);

            // 2) 메타데이터 가져오기
            IIOMetadata metadata;
            try {
                metadata = reader.getImageMetadata(0);
            } catch (Exception e) {
                // 메타데이터 자체를 못 읽어오면 곧바로 실패 처리
                System.err.println("[ImageStegoDecoder] 추출 중 오류 발생: 삽입된 Stego 정보가 존재하지 않습니다.");
                e.printStackTrace();
                return null;
            }
            String nativeFormat = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(nativeFormat);

            // 3) tEXtEntry 찾기
            NodeList list = root.getElementsByTagName("tEXtEntry");
            for (int i = 0; i < list.getLength(); i++) {
                IIOMetadataNode entry = (IIOMetadataNode) list.item(i);
                if ("StegoPayload".equals(entry.getAttribute("keyword"))) {
                    // 4) Base64 → byte[] → AES 복호화
                    String base64 = entry.getAttribute("value");
                    byte[] encrypted = Base64.getDecoder().decode(base64);
                    return StegoCryptoUtil.decryptFromBytes(encrypted);
                }
            }

            throw new IllegalStateException("삽입된 Stego 정보를 찾지 못했습니다.");
        } catch (Exception e) {
            System.err.println("[ImageStegoDecoder] 추출 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
