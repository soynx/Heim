package dev.soynx.heim.config.model;

/** One way of reaching a service. */
public class Link extends Extensible {

    private String id;
    private String label;
    private LinkKind kind;
    private String url;
    private boolean openInNewTab = true;
    private ProbeConfig probe = new ProbeConfig();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public LinkKind getKind() {
        return kind;
    }

    public void setKind(LinkKind kind) {
        this.kind = kind;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isOpenInNewTab() {
        return openInNewTab;
    }

    public void setOpenInNewTab(boolean openInNewTab) {
        this.openInNewTab = openInNewTab;
    }

    public ProbeConfig getProbe() {
        return probe;
    }

    public void setProbe(ProbeConfig probe) {
        this.probe = probe;
    }
}
