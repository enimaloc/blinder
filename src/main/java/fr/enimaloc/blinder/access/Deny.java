package fr.enimaloc.blinder.access;

public record Deny(DenyReason reason) implements AccessDecision {
}
