package com.airoom.secureagent.steganography;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.util.Base64;

import com.airoom.secureagent.util.CryptoUtil;
import org.w3c.dom.NodeList;
import com.airoom.secureagent.log.LogManager;
import com.airoom.secureagent.log.HttpLogger;

public class ImageStegoDecoder {

    private static final String KEYWORD = "StegoPayload";

    public static String decode(String path) {
        String lower = path.toLowerCase();

        try {
            if (lower.endsWith(".png"))
                return decodePng(path);
            else if (lower.matches(".*\\.(jpe?g)$"))
                return decodeJpeg(path);
            else {
                log("[Decode] 형식 미지원", path);
                return null;
            }
        } catch (Exception e) {
            log("[Decode] 복호화 오류: " + e.getMessage(), path);
            return null;
        }
    }

    /* ---------- PNG ---------- */
    private static String decodePng(String path) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new File(path))) {
            ImageReader r = ImageIO.getImageReadersByFormatName("png").next();
            r.setInput(iis, true);

            IIOMetadata meta = r.getImageMetadata(0);            // (1) 메타 읽기
            String fmt = meta.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);

            NodeList list = root.getElementsByTagName("tEXtEntry");
            for (int i = 0; i < list.getLength(); i++) {         // (2) 키 찾기
                IIOMetadataNode n = (IIOMetadataNode) list.item(i);
                if (KEYWORD.equals(n.getAttribute("keyword"))) {
                    String b64 = n.getAttribute("value");
                    byte[] enc = Base64.getDecoder().decode(b64);
                    return CryptoUtil.decryptFromBytes(enc);
                }
            }
            log("[Decode] Stego 키 없음", path);
            return null;

        } catch (Exception e) {
            log("[Decode] 메타데이터 읽기 실패: " + e.getMessage(), path);
            return null;
        }
    }

    /* ---------- JPEG ---------- */
    private static String decodeJpeg(String path) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new File(path))) {
            ImageReader r = ImageIO.getImageReadersByFormatName("jpeg").next();
            r.setInput(iis, true);

            IIOMetadata meta = r.getImageMetadata(0);
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(
                    meta.getNativeMetadataFormatName());

            NodeList coms = root.getElementsByTagName("com");
            for (int i = 0; i < coms.getLength(); i++) {
                String comment = ((IIOMetadataNode) coms.item(i)).getAttribute("comment");
                if (comment.startsWith(KEYWORD + ":")) {
                    String b64 = comment.substring(KEYWORD.length() + 1);
                    byte[] enc = Base64.getDecoder().decode(b64);
                    return CryptoUtil.decryptFromBytes(enc);
                }
            }
            log("[Decode] Stego 키 없음", path);
            return null;

        } catch (Exception e) {
            log("[Decode] 메타데이터 읽기 실패: " + e.getMessage(), path);
            return null;
        }
    }

    /* ---------- 공통 로그 ---------- */
    private static void log(String msg, String file) {
        String line = msg + " → " + file;
        LogManager.writeLog(line);
        HttpLogger.sendLog(line);
        System.err.println(line);
    }
}
