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

import dev.soynx.heim.api.dto.ServiceDto;
import dev.soynx.heim.api.dto.ServiceRequest;
import dev.soynx.heim.config.Versioned;
import dev.soynx.heim.service.ServiceManager;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceManager services;

    public ServiceController(ServiceManager services) {
        this.services = services;
    }

    @GetMapping
    public ResponseEntity<List<ServiceDto>> list() {
        return ETags.ok(services.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServiceDto> get(@PathVariable String id) {
        return ETags.ok(services.get(id));
    }

    @PostMapping
    public ResponseEntity<ServiceDto> create(@Valid @RequestBody ServiceRequest request,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        Versioned<ServiceDto> created = services.create(request, ETags.expectedRevision(ifMatch));
        return ETags.created(URI.create("/api/services/" + created.value().id()), created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ServiceDto> update(@PathVariable String id, @Valid @RequestBody ServiceRequest request,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ETags.ok(services.update(id, request, ETags.expectedRevision(ifMatch)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
            @RequestHeader(name = ETags.IF_MATCH, required = false) String ifMatch) {
        return ETags.noContent(services.delete(id, ETags.expectedRevision(ifMatch)));
    }
}
