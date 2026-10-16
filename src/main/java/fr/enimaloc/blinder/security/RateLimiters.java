package fr.enimaloc.blinder.security;

/** Limiteurs de l'application : mot de passe de lien et login admin. */
public record RateLimiters(RateLimiter linkPassword, RateLimiter adminLogin) {
}
