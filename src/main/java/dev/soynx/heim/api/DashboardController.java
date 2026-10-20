package dev.soynx.heim.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.soynx.heim.api.dto.DashboardView;
import dev.soynx.heim.service.TabManager;

@RestController
public class DashboardController {

    private final TabManager tabs;

    public DashboardController(TabManager tabs) {
        this.tabs = tabs;
    }

    @GetMapping("/api/dashboard")
    public ResponseEntity<DashboardView> dashboard() {
        return ETags.ok(tabs.dashboard());
    }
}
