package com.yumpoo.platform.catalog.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ProductUpdateRequest {
    @NotBlank
    @Size(max = 80)
    private String name;

    @Size(max = 500)
    private String description;

    private boolean descriptionPresent;

    public ProductUpdateRequest() {
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
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

    @AssertTrue(message = "description must be present, and may be null")
    public boolean isDescriptionPresent() {
        return descriptionPresent;
    }
}
