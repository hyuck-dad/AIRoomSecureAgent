package com.airoom.secureagent.steganography;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Base64;
import java.util.Iterator;

public class ImageStegoWithWatermarkEncoder {

    /**
     * 1) PNG tEXt 기반 Stego 삽입
     * 2) Graphics2D 로 투명 워터마크 오버레이
     */
    public static void encode(String inputImagePath,
                              String outputImagePath,
                              String payload,
                              String watermarkText,
                              float watermarkOpacity) {
        try {
            // — Stego: AES 암호화 → Base64
            byte[] encrypted = StegoCryptoUtil.encryptToBytes(payload);
            String base64 = Base64.getEncoder().encodeToString(encrypted);

            // — 원본 이미지 로드
            BufferedImage src = ImageIO.read(new File(inputImagePath));

            // — 1) 워터마크 오버레이 그리기
            BufferedImage watermarked = new BufferedImage(
                    src.getWidth(), src.getHeight(),
                    BufferedImage.TYPE_INT_ARGB
            );
            Graphics2D g = watermarked.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.setFont(new Font("Arial", Font.BOLD, 40));

            // opacity 적용 (0.0 ~ 1.0)
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, watermarkOpacity
            ));
            g.setColor(new Color(255, 0, 0, (int)(watermarkOpacity*255)));

            // 반복 대각선 출력 (WatermarkOverlay 로직 차용)
            for (int x = 0; x < watermarked.getWidth(); x += 300) {
                for (int y = 0; y < watermarked.getHeight(); y += 200) {
                    g.rotate(Math.toRadians(-30), x, y);
                    g.drawString(watermarkText, x, y);
                    g.rotate(Math.toRadians(30), x, y);
                }
            }
            g.dispose();

            // — 2) Stego 메타데이터 삽입 (tEXt chunk)
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();

            ImageTypeSpecifier spec = ImageTypeSpecifier.createFromRenderedImage(watermarked);
            IIOMetadata metadata = writer.getDefaultImageMetadata(spec, param);
            String nativeFormat = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode)metadata.getAsTree(nativeFormat);

            IIOMetadataNode textNode = new IIOMetadataNode("tEXt");
            IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "StegoPayload");
            entry.setAttribute("value", base64);
            textNode.appendChild(entry);
            root.appendChild(textNode);
            metadata.setFromTree(nativeFormat, root);

            // — 3) 파일 쓰기
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(
                    new File(outputImagePath))) {
                writer.setOutput(ios);
                writer.write(metadata, new IIOImage(watermarked, null, metadata), param);
            }
            writer.dispose();
            System.out.println("[ImageStegoWithWatermarkEncoder] 완료: " + outputImagePath);

        } catch (Exception e) {
            System.err.println("[ImageStegoWithWatermarkEncoder] 오류");
            e.printStackTrace();
        }
    }
}
