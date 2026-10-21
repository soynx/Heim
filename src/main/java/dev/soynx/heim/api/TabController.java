package dev.soynx.heim.api;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.soynx.heim.api.dto.GroupRequest;
import dev.soynx.heim.api.dto.TabOrderRequest;
import dev.soynx.heim.api.dto.TabRequest;
import dev.soynx.heim.config.Tagged;
import dev.soynx.heim.config.model.Tab;
import dev.soynx.heim.service.TabManager;
import jakarta.validation.Valid;

/**
 * Tabs. A single tab's endpoints use that tab's ETag; {@code GET /api/tabs}, {@code POST} and
 * {@code PUT /order} use the tab order tag, which only changes when tabs are added, removed or
 * reordered.
 */
@RestController
@RequestMapping("/api/tabs")
public class TabController {

    private final TabManager tabs;

    public TabController(TabManager tabs) {
        this.tabs = tabs;
    }

    @GetMapping
    public ResponseEntity<List<Tab>> list() {
        return ETags.ok(tabs.list());
    }

    @PostMapping
    public ResponseEntity<Tab> create(@Valid @RequestBody TabRequest request,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        Tagged<Tab> created = tabs.create(request, ETags.expected(ifMatch));
        return ETags.created(URI.create("/api/tabs/" + created.value().getId()), created);
    }

    @PutMapping("/order")
    public ResponseEntity<List<Tab>> reorder(@Valid @RequestBody TabOrderRequest request,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ETags.ok(tabs.reorder(request.tabIds(), ETags.expected(ifMatch)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tab> get(@PathVariable String id) {
        return ETags.ok(tabs.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Tab> update(@PathVariable String id, @Valid @RequestBody TabRequest request,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ETags.ok(tabs.update(id, request, ETags.expected(ifMatch)));
    }

    /** Body: the tab's groups in order, each with its ordered service ids. */
    @PutMapping("/{id}/groups")
    public ResponseEntity<Tab> setGroups(@PathVariable String id, @RequestBody List<@Valid GroupRequest> groups,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ETags.ok(tabs.setGroups(id, groups, ETags.expected(ifMatch)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        tabs.delete(id, ETags.expected(ifMatch));
        return ResponseEntity.noContent().build();
    }
}
