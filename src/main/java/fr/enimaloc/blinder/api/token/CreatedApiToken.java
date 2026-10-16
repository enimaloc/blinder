package fr.enimaloc.blinder.api.token;

/** Jeton créé : {@code rawToken} n'existe qu'en mémoire, pour être affiché une seule fois. */
public record CreatedApiToken(ApiToken token, String rawToken) {
}
