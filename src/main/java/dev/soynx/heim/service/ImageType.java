package dev.soynx.heim.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Raster image formats accepted for uploads, detected from magic bytes rather than client headers. */
public enum ImageType {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp");

    /** Enough leading bytes for {@link #detect}. */
    public static final int SNIFF_LENGTH = 16;

    private final String extension;
    private final String mediaType;

    ImageType(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() {
        return extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public static Optional<ImageType> detect(byte[] data) {
        if (startsWith(data, 0, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})) {
            return Optional.of(PNG);
        }
        if (startsWith(data, 0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return Optional.of(JPEG);
        }
        if (startsWith(data, 0, ascii("GIF87a")) || startsWith(data, 0, ascii("GIF89a"))) {
            return Optional.of(GIF);
        }
        if (startsWith(data, 0, ascii("RIFF")) && startsWith(data, 8, ascii("WEBP"))) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        return data.length >= offset + prefix.length
                && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
