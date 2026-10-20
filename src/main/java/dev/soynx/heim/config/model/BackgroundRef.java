package dev.soynx.heim.config.model;

/** Dashboard background. {@code cachedFile} names a file in the uploads directory, or is null. */
public class BackgroundRef extends Extensible {

    private BackgroundSource source = BackgroundSource.NONE;
    /** Remote URL for {@link BackgroundSource#URL}, original file name for uploads. */
    private String value;
    private String cachedFile;

    public BackgroundSource getSource() {
        return source;
    }

    public void setSource(BackgroundSource source) {
        this.source = source;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getCachedFile() {
        return cachedFile;
    }

    public void setCachedFile(String cachedFile) {
        this.cachedFile = cachedFile;
    }
}
