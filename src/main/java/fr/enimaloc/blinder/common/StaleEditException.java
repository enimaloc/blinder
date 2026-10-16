package fr.enimaloc.blinder.common;

/** L'entité a été modifiée depuis l'affichage du formulaire. */
public class StaleEditException extends RuntimeException {

    public StaleEditException() {
        super("Modifié entre-temps");
    }
}
