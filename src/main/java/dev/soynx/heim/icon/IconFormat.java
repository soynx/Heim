package dev.soynx.heim.icon;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import dev.soynx.heim.service.ImageType;

/** Icon file formats, detected from content rather than headers or file names. */
public enum IconFormat {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    GIF("gif", "image/gif"),
    WEBP("webp", "image/webp"),
    SVG("svg", "image/svg+xml"),
    ICO("ico", "image/x-icon");

    private static final byte[] ICO_MAGIC = {0, 0, 1, 0};
    private static final int SNIFF_LENGTH = 4096;

    private final String extension;
    private final String mediaType;

    IconFormat(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    public String extension() {
        return extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public static Optional<IconFormat> detect(byte[] data) {
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        Optional<ImageType> raster = ImageType.detect(data);
        if (raster.isPresent()) {
            return Optional.of(switch (raster.get()) {
                case PNG -> PNG;
                case JPEG -> JPEG;
                case GIF -> GIF;
                case WEBP -> WEBP;
            });
        }
        if (data.length >= 6 && Arrays.equals(data, 0, 4, ICO_MAGIC, 0, 4)) {
            return Optional.of(ICO);
        }
        return looksLikeSvg(data) ? Optional.of(SVG) : Optional.empty();
    }

    /** Format of a stored icon, judged by the extension Heim gave it. */
    public static Optional<IconFormat> fromFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.extension.equals(ext)).findFirst();
    }

    /** Text sniff: markup that opens an {@code <svg>} element and is not an HTML page. */
    private static boolean looksLikeSvg(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, SNIFF_LENGTH), StandardCharsets.UTF_8)
                .replace("﻿", "")
                .strip()
                .toLowerCase(Locale.ROOT);
        return head.startsWith("<") && head.contains("<svg") && !head.contains("<html");
    }
}
