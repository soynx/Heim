package dev.soynx.heim.config.model;

/** Login toggles and the single admin's credentials. {@code passwordHash} must never leave the server. */
public class AuthSettings extends Extensible {

    private boolean requireLoginToView;
    private boolean requireLoginToEdit;
    private String username;
    /** BCrypt hash. */
    private String passwordHash;

    public boolean isRequireLoginToView() {
        return requireLoginToView;
    }

    public void setRequireLoginToView(boolean requireLoginToView) {
        this.requireLoginToView = requireLoginToView;
    }

    public boolean isRequireLoginToEdit() {
        return requireLoginToEdit;
    }

    public void setRequireLoginToEdit(boolean requireLoginToEdit) {
        this.requireLoginToEdit = requireLoginToEdit;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
