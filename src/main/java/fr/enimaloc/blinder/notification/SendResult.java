package fr.enimaloc.blinder.notification;

/** Résultat d'un envoi : jamais d'exception sortante, l'erreur est un texte court. */
public record SendResult(boolean success, String error) {

    static final int MAX_ERROR = 500;

    public static SendResult ok() {
        return new SendResult(true, null);
    }

    public static SendResult failure(String error) {
        return new SendResult(false, truncate(error));
    }

    public static SendResult failure(Exception e) {
        String message = e.getMessage();
        return failure(e.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }

    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR ? text : text.substring(0, MAX_ERROR);
    }
}
