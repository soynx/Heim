package dev.soynx.heim.icon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SvgSanitizerTest {

    private static String sanitize(String svg) {
        byte[] result = SvgSanitizer.sanitize(svg.getBytes(StandardCharsets.UTF_8));
        return result == null ? null : new String(result, StandardCharsets.UTF_8);
    }

    @Test
    void keepsHarmlessContent() {
        String out = sanitize("""
                <?xml version="1.0"?>
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><defs><linearGradient id="g"/></defs>\
                <path fill="url(#g)" d="M0 0h24v24H0z"/><a href="https://example.com"><text>Hi</text></a></svg>""");
        assertThat(out).contains("viewBox=\"0 0 24 24\"", "<path fill=\"url(#g)\" d=\"M0 0h24v24H0z\"",
                "href=\"https://example.com\"", "<linearGradient");
    }

    @Test
    void removesScriptsHandlersAndScriptUrls() {
        String out = sanitize("""
                <!DOCTYPE svg [<!ENTITY x "boom">]>
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" onload="alert(1)">
                  <script>alert(2)</script>
                  <svg:script>alert(3)</svg:script>
                  <foreignObject><iframe src="https://evil"></iframe></foreignObject>
                  <a xlink:href="java&#x09;script:alert(4)"><circle r="4" onClick="alert(5)"/></a>
                  <a href=" JavaScript:alert(6)">x</a>
                  <set attributeName="href" to="javascript:alert(7)"/>
                  <rect width="1" height="1"/>
                </svg>""");
        assertThat(out).isNotNull().contains("<rect", "<circle r=\"4\"");
        assertThat(out.toLowerCase()).doesNotContain("script", "onload", "onclick", "foreignobject", "iframe",
                "alert", "entity");
    }

    @Test
    void rejectsNonSvgDocuments() {
        assertThat(sanitize("<html><body><svg></svg></body></html>")).isNull();
        assertThat(sanitize("plain text")).isNull();
        assertThat(sanitize("<svg></svg><svg></svg>")).isNull();
    }

    @Test
    void formatDetectionSniffsSvgAndIco() {
        assertThat(IconFormat.detect("﻿  <?xml version='1.0'?><svg/>".getBytes(StandardCharsets.UTF_8)))
                .contains(IconFormat.SVG);
        assertThat(IconFormat.detect("<!doctype html><html><svg/></html>".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(IconFormat.detect(new byte[] {0, 0, 1, 0, 1, 0, 16, 16})).contains(IconFormat.ICO);
        assertThat(IconFormat.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0}))
                .contains(IconFormat.PNG);
        assertThat(IconFormat.detect(new byte[0])).isEmpty();
    }
}
