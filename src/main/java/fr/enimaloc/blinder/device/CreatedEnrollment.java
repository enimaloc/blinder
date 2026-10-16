package fr.enimaloc.blinder.device;

/** Résultat d'une création d'enrôlement : le jeton brut n'est affiché qu'une fois. */
public record CreatedEnrollment(DeviceEnrollment enrollment, String host, String token) {
}
