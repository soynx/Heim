package dev.soynx.heim.api.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/** One group in {@code PUT /api/tabs/{id}/groups}; {@code id} keeps an existing group of that tab. */
public record GroupRequest(String id, @Size(max = 100) String name, List<String> serviceIds) {
}
