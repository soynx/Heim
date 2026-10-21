package dev.soynx.heim.security;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.ConfigChangedEvent;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.probe.StatusBroadcaster;

/**
 * Status streams are authorized once, when they are opened. When the login toggles or the credentials
 * change, every open stream is ended so that clients have to reconnect under the new rules.
 */
@Component
public class StatusStreamGuard {

    private final StatusBroadcaster broadcaster;
    private Rules rules;
    private long revision;

    public StatusStreamGuard(StatusBroadcaster broadcaster, ConfigStore store) {
        this.broadcaster = broadcaster;
        this.rules = Rules.of(store.auth());
        this.revision = store.revision();
    }

    /** Events are published after the store lock is released, so they can arrive out of order. */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        Rules next = Rules.of(event.config().getSettings().getAuth());
        synchronized (this) {
            if (event.revision() <= revision) {
                return;
            }
            revision = event.revision();
            if (next.equals(rules)) {
                return;
            }
            rules = next;
        }
        broadcaster.closeAll();
    }

    /** What decides access; the credentials only as a stamp, so the hash is never kept or logged here. */
    private record Rules(boolean requireLoginToView, boolean requireLoginToEdit, AdminSession.CredentialStamp stamp) {

        static Rules of(AuthSettings auth) {
            return new Rules(auth.isRequireLoginToView(), auth.isRequireLoginToEdit(),
                    AdminSession.hasCredentials(auth) ? AdminSession.CredentialStamp.of(auth) : null);
        }
    }
}
