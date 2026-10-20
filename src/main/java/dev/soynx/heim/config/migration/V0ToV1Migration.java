package dev.soynx.heim.config.migration;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Configs without {@code schemaVersion} are treated as version 0. Their shape already matches v1,
 * so nothing changes; this step mainly anchors the chain for future migrations.
 */
@Component
public class V0ToV1Migration implements Migration {

    @Override
    public int fromVersion() {
        return 0;
    }

    @Override
    public void migrate(ObjectNode root) {
        // v0 and v1 are structurally identical.
    }
}
