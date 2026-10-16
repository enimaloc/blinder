package fr.enimaloc.blinder.security;

/** Limiteurs de l'application : mot de passe de lien (per-client et agrégé), login admin et jetons d'API (per-client). */
public record RateLimiters(RateLimiter linkPassword, RateLimiter linkPasswordAggregate,
                           RateLimiter adminLogin, RateLimiter apiAuth) {
}
