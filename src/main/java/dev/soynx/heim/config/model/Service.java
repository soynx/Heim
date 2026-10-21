package dev.soynx.heim.config.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A dashboard card. Tab membership is not stored here but in {@link Group#getServiceIds()}. */
public class Service extends Extensible {

    private String id;
    private String name;
    private String description;
    private String color;
    private IconRef icon = new IconRef();
    private String primaryLinkId;
    private List<Link> links = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public Optional<Link> findLink(String linkId) {
        return links.stream().filter(l -> linkId != null && linkId.equals(l.getId())).findFirst();
    }

    /** The link a favicon icon comes from: the icon's favicon link, else the primary link, else the first. */
    public Optional<Link> faviconLink() {
        return findLink(icon != null ? icon.getFaviconLinkId() : null)
                .or(() -> findLink(primaryLinkId))
                .or(() -> links.stream().findFirst());
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public IconRef getIcon() {
        return icon;
    }

    public void setIcon(IconRef icon) {
        this.icon = icon;
    }

    public String getPrimaryLinkId() {
        return primaryLinkId;
    }

    public void setPrimaryLinkId(String primaryLinkId) {
        this.primaryLinkId = primaryLinkId;
    }

    public List<Link> getLinks() {
        return links;
    }

    public void setLinks(List<Link> links) {
        this.links = links;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
