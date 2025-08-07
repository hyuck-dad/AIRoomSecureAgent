package com.airoom.secureagent.steganography;

import com.airoom.secureagent.util.CryptoUtil;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Base64;
import java.util.Iterator;

public class ImageStegoEncoder {

    public static void encode(String inputImagePath, String outputImagePath, String payload) {

        /*
        JPEG·PNG 구분 없이 픽셀 LSB 방식을 쓰면,
        Java PNG 라이터가 ICC 프로파일, 컬러 매니지먼트, 프리멀티플라이드 등으로 인해
        여전히 미세하게 픽셀이 변조되어 복호화 실패가 반복될 수밖에 없습니다.
        이제는 픽셀 기반이 아니라 PNG 메타데이터(tEXt 청크) 에 Base64 문자열을 직접 삽입하는 방법으로 전환합시다.
        이 방식은 픽셀 데이터를 전혀 건드리지 않기 때문에 100% 그대로 보존되고, 복호화 시에도 절대 깨지지 않습니다.
        ✔️ 장점
픽셀 미변경: 이미지 화질·비트가 완전 보존됩니다.
간단 안정: PNG 스펙상 tEXt 청크는 무손실이며, Java ImageIO가 자동 보정하지 않습니다.
포맷 호환: 원본이 JPEG, BMP여도 ImageIO.read → PNG 라이터만 거치면 됩니다.
         */

        try {
            // 1) AES로 암호화 → byte[] → Base64 문자열
            byte[] encrypted = CryptoUtil.encryptToBytes(payload);
            String base64 = Base64.getEncoder().encodeToString(encrypted);

            // 2) 원본 이미지 로드
            BufferedImage image = ImageIO.read(new File(inputImagePath));

            // 3) PNG Writer 준비
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();

            // 4) 메타데이터 트리 가져오기
            ImageTypeSpecifier spec = ImageTypeSpecifier.createFromRenderedImage(image);
            IIOMetadata metadata = writer.getDefaultImageMetadata(spec, param);
            String nativeFormat = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(nativeFormat);

            // 5) tEXt → tEXtEntry 추가
            IIOMetadataNode textNode = new IIOMetadataNode("tEXt");
            IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "StegoPayload");
            entry.setAttribute("value", base64);
            textNode.appendChild(entry);
            root.appendChild(textNode);

            // 6) 트리 저장
            metadata.setFromTree(nativeFormat, root);

            // 7) 이미지 + 메타데이터 쓰기
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(new File(outputImagePath))) {
                writer.setOutput(ios);
                writer.write(metadata, new IIOImage(image, null, metadata), param);
            }
            writer.dispose();
            System.out.println("[ImageStegoEncoder] 메타데이터에 Stego 삽입 완료");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
