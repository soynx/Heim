package dev.soynx.heim.config.model;

public class Settings extends Extensible {

    public static final String DEFAULT_TITLE = "Heim";
    public static final String DEFAULT_THEME = "auto";

    private String title = DEFAULT_TITLE;
    /** Reserved for the UI; the backend stores it verbatim. */
    private String theme = DEFAULT_THEME;
    private BackgroundRef background = new BackgroundRef();
    private String customCss = "";
    private ProbeDefaults probe = new ProbeDefaults();
    private AuthSettings auth = new AuthSettings();

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public BackgroundRef getBackground() {
        return background;
    }

    public void setBackground(BackgroundRef background) {
        this.background = background;
    }

    public String getCustomCss() {
        return customCss;
    }

    public void setCustomCss(String customCss) {
        this.customCss = customCss;
    }

    public ProbeDefaults getProbe() {
        return probe;
    }

    public void setProbe(ProbeDefaults probe) {
        this.probe = probe;
    }

    public AuthSettings getAuth() {
        return auth;
    }

    public void setAuth(AuthSettings auth) {
        this.auth = auth;
    }
}
