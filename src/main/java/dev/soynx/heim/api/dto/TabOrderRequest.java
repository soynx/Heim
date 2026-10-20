package dev.soynx.heim.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/** All tab ids in the desired order. */
public record TabOrderRequest(@NotNull List<String> tabIds) {
}
