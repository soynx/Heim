package dev.soynx.heim.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.soynx.heim.error.RevisionConflictException;

/**
 * Per-resource version tags for optimistic locking. A tag is a hash of the resource's canonical JSON
 * without server-managed fields, so background writes (e.g. a freshly cached icon) do not invalidate
 * what an editor has open, while any real edit of the resource does.
 */
public final class ResourceTags {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .build();

    private ResourceTags() {
    }

    /**
     * Tag of {@code resource}.
     *
     * @param excluded dotted paths of fields to ignore, e.g. {@code "icon.cachedFile"}
     */
    public static String of(Object resource, String... excluded) {
        JsonNode tree = MAPPER.valueToTree(resource);
        for (String path : excluded) {
            remove(tree, path.split("\\."));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(tree));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Throws unless {@code expected} is null (no check requested) or equals {@code current}. Call it
     * inside the {@link ConfigStore#update} mutator so check and write are atomic.
     */
    public static void check(String expected, String current, String resource) {
        if (expected != null && !expected.equals(current)) {
            throw new RevisionConflictException(resource + " was modified concurrently", current);
        }
    }

    private static void remove(JsonNode node, String[] path) {
        for (int i = 0; i < path.length - 1 && node != null; i++) {
            node = node.get(path[i]);
        }
        if (node instanceof ObjectNode object) {
            object.remove(path[path.length - 1]);
        }
    }
}
