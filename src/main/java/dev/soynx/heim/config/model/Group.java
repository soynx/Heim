package dev.soynx.heim.config.model;

import java.util.ArrayList;
import java.util.List;

/** Ordered section of a tab. v1 uses a single unnamed group per tab. */
public class Group extends Extensible {

    private String id;
    private String name;
    private List<String> serviceIds = new ArrayList<>();

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

    public List<String> getServiceIds() {
        return serviceIds;
    }

    public void setServiceIds(List<String> serviceIds) {
        this.serviceIds = serviceIds;
    }
}
