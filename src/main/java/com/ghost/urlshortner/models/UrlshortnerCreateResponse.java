package com.ghost.urlshortner.models;

import java.util.Date;

/** Creation result; the short link is the service's public origin followed by /api/v1/shortCode. */
public record UrlshortnerCreateResponse(String shortCode, Date createdAt, Date expiryAt) {
}
