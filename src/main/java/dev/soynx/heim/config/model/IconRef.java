package dev.soynx.heim.config.model;

/** Icon of a service or tab. {@code cachedFile} names a file in the icons directory, or is null. */
public class IconRef extends Extensible {

    private IconSource source = IconSource.FAVICON;
    /** Library slug, remote URL or original upload file name, depending on {@link #source}. */
    private String value;
    /** Link to fetch the favicon from; null means the primary link. */
    private String faviconLinkId;
    private String cachedFile;

    public IconSource getSource() {
        return source;
    }

    public void setSource(IconSource source) {
        this.source = source;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getFaviconLinkId() {
        return faviconLinkId;
    }

    public void setFaviconLinkId(String faviconLinkId) {
        this.faviconLinkId = faviconLinkId;
    }

    public String getCachedFile() {
        return cachedFile;
    }

    public void setCachedFile(String cachedFile) {
        this.cachedFile = cachedFile;
    }
}
