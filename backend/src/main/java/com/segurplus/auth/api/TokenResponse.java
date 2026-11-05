package com.segurplus.auth.api;

/** El access token va en el cuerpo (el frontend lo guarda solo en memoria); el refresh solo en cookie HttpOnly. */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
