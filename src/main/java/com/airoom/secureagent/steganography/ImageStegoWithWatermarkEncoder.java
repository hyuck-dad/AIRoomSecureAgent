package com.airoom.secureagent.steganography;

import com.airoom.secureagent.util.CryptoUtil;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/**
 * PNG  : tEXt  "StegoPayload"
 * JPEG : COM marker "StegoPayload:<base64>"
 * + 대각선 반투명 워터마크
 */
public class ImageStegoWithWatermarkEncoder {

    private static final String KEYWORD = "StegoPayload";

    public static boolean encode(String input, String output,
                                 String payload, String wmText, float opacity) {

        try {
            /* 포맷 판별 */
            boolean isPng  = input.toLowerCase().endsWith(".png");
            boolean isJpeg = input.toLowerCase().matches(".*\\.(jpe?g)$");
            if (!isPng && !isJpeg) return false;

            /* 0) 암호화 → Base64 */
            String base64 = Base64.getEncoder()
                    .encodeToString(CryptoUtil.encryptToBytes(payload));

            /* 1) 원본 이미지 */
            BufferedImage src = ImageIO.read(new File(input));
            if (src == null) throw new IllegalStateException("이미지 로드 실패: " + input);

            /* 2) 워터마크 오버레이 */
            int targetType = (isJpeg ? BufferedImage.TYPE_INT_RGB   // ★ JPEG: 알파 없는 RGB
                    : BufferedImage.TYPE_INT_ARGB); // PNG : 알파 유지
            BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), targetType);
            Graphics2D g = dst.createGraphics();
            g.drawImage(src, 0, 0, null);
            /* 투명도 적용 — JPEG 의 경우 알파가 flatten 되지만, 효과는 남음 */
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
//            g.setFont(new Font("Arial", Font.BOLD, 40));
            // 기존: 고정 40px
            float scale = Math.max(src.getWidth(), src.getHeight()) / 1000f; // 1000px 기준
            int fontPx = Math.max(14, Math.round(40 * scale)); // 최소 14 px
            int stepX  = Math.max(120, Math.round(300 * scale)); // 최소 간격
            int stepY  = Math.max( 80, Math.round(200 * scale));
            g.setFont(new Font("Arial", Font.BOLD, fontPx));
            g.setColor(new Color(255, 0, 0, Math.round(opacity * 255)));
//            for (int x = 0; x < dst.getWidth(); x += 300) {
//                for (int y = 0; y < dst.getHeight(); y += 200) {
            for (int x = 0; x < dst.getWidth(); x += stepX) {
                for (int y = 0; y < dst.getHeight(); y += stepY) {
                    g.rotate(Math.toRadians(-30), x, y);
                    g.drawString(wmText, x, y);
                    g.rotate(Math.toRadians(30), x, y);
                }
            }
            g.dispose();



            String fmt = isPng ? "png" : "jpeg";
            ImageWriter writer = ImageIO.getImageWritersByFormatName(fmt).next();
            ImageWriteParam param = writer.getDefaultWriteParam();

            /* 3) 메타데이터 */
            ImageTypeSpecifier spec = ImageTypeSpecifier.createFromRenderedImage(dst);
            IIOMetadata meta = writer.getDefaultImageMetadata(spec, param);
            String nativeName = meta.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(nativeName);

            if (isPng) {
                IIOMetadataNode text = new IIOMetadataNode("tEXt");
                IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
                entry.setAttribute("keyword", KEYWORD);
                entry.setAttribute("value", base64);
                text.appendChild(entry);
                root.appendChild(text);
                meta.setFromTree(nativeName, root);
            } else {                          // JPEG
                IIOMetadataNode seq = getOrCreate(root, "markerSequence");
                IIOMetadataNode com = new IIOMetadataNode("com");
                com.setAttribute("comment", KEYWORD + ":" + base64);
                seq.appendChild(com);
                meta.setFromTree(nativeName, root);
            }

            /* 4) tmp 파일에 먼저 기록 */
            Path orig = Paths.get(output);                              // 최종 위치
            Path tmp  = Files.createTempFile(orig.getParent(), "aidt_", ".tmp");

            try (ImageOutputStream ios = ImageIO.createImageOutputStream(tmp.toFile())) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(dst, null, meta), param);
                ios.flush();                       // 버퍼 플러시
                writer.dispose();
            }

            /* 5) 한 번의 시스템 호출로 교체 */
            Files.move(tmp, orig,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[ImageStegoWithWatermarkEncoder] 완료: " + output);
            return true;   // 성공


        } catch (Exception e) {
            System.err.println("[ImageStegoWithWatermarkEncoder] 오류: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /* util */
    private static IIOMetadataNode getOrCreate(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++)
            if (parent.item(i).getNodeName().equals(name))
                return (IIOMetadataNode) parent.item(i);
        IIOMetadataNode n = new IIOMetadataNode(name);
        parent.appendChild(n);
        return n;
    }
}
