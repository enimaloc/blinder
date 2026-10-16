package fr.enimaloc.blinder.access;

public sealed interface AccessDecision permits Allow, Deny, Challenge {
}
