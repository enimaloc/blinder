package fr.enimaloc.blinder.access;

/** Demande une interaction (mot de passe…) avant d'autoriser. Utilisé à partir du lot 2. */
public record Challenge(String view) implements AccessDecision {
}
