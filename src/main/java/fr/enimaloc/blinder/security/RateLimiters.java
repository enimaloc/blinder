package fr.enimaloc.blinder.security;

/** Limiteurs de l'application : mot de passe de lien et login admin, avec limites agrégées. */
public record RateLimiters(RateLimiter linkPassword, RateLimiter linkPasswordAggregate,
                           RateLimiter adminLogin, RateLimiter adminLoginAggregate) {
}
