package com.yumpoo.platform.catalog.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class WorkspaceUpdateRequest {

    @NotBlank
    @Size(max = 80)
    private String name;

    @Size(max = 500)
    private String description;

    @NotNull
    @PositiveOrZero
    private Integer sortOrder;

    private boolean descriptionPresent;

    public WorkspaceUpdateRequest() {
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public int sortOrder() {
        return sortOrder == null ? 0 : sortOrder;
    }

    @JsonSetter("name")
    public void setName(String name) {
        this.name = name;
    }

    @JsonSetter("description")
    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }

    @JsonSetter("sortOrder")
    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    @AssertTrue(message = "description must be present, and may be null")
    public boolean isDescriptionPresent() {
        return descriptionPresent;
    }
}
