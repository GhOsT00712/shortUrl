package com.ghost.urlshortner.models;

import java.util.Date;

import org.hibernate.validator.constraints.URL;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** HTTP creation input; expiryAt is an absolute instant, not a duration from creation. */
public record UrlshortnerCreateRequest(
        @NotBlank @Size(max = 8192) @Pattern(regexp = "\\A[^\\p{Cntrl}]*\\z")
        @URL(regexp = "(?i)https?://.*") String url,
        @NotNull @Future Date expiryAt) {
}
