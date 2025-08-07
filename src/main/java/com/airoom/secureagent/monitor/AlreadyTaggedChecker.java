package com.airoom.secureagent.monitor;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Path;

public class AlreadyTaggedChecker {

    private static final String KEYWORD = "StegoPayload";

    public static boolean isTagged(Path p) {
        String lower = p.toString().toLowerCase();
        try {
            if (lower.endsWith(".png") || lower.matches(".*\\.(jpe?g)$")) {
                try (ImageInputStream iis = ImageIO.createImageInputStream(p.toFile())) {
                    ImageReader r = ImageIO.getImageReaders(iis).next();
                    r.setInput(iis, true);
                    IIOMetadata meta = r.getImageMetadata(0);
                    return meta.getAsTree(meta.getNativeMetadataFormatName())
                            .toString().contains(KEYWORD);
                }
            } else if (lower.endsWith(".pdf")) {
                try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(p.toFile())) {
                    return doc.getDocumentInformation()
                            .getCustomMetadataValue("X-Doc-Tracking-Key") != null;
                }
            }
        } catch (Exception ignore) { }
        return false;
    }
}
