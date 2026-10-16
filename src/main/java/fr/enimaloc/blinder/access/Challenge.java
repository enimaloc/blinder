package fr.enimaloc.blinder.access;

/**
 * Demande une interaction avant d'autoriser.
 *
 * @param rejected {@code true} si une réponse a été fournie et refusée (mauvais mot de passe)
 */
public record Challenge(ChallengeType type, boolean rejected) implements AccessDecision {
}
