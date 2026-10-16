package fr.enimaloc.blinder.access;

import java.util.UUID;

/** Appareils autorisés d'un lien (lus en base à chaque visite : la relation est paresseuse). */
public interface LinkDeviceLookup {

    boolean hasDevices(UUID linkId);

    boolean isAllowed(UUID linkId, UUID deviceId);
}
