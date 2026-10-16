package fr.enimaloc.blinder.security;

/** Limiteurs de l'application : mot de passe de lien (per-client et agrégé) et login admin (per-client). */
public record RateLimiters(RateLimiter linkPassword, RateLimiter linkPasswordAggregate,
                           RateLimiter adminLogin) {
}
