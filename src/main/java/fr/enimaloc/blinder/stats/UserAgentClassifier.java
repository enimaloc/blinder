package fr.enimaloc.blinder.stats;

/** Classe grossièrement un User-Agent en navigateur et système (heuristique, sans dépendance). */
public final class UserAgentClassifier {

    private static final String OTHER = "Autre";

    private UserAgentClassifier() {
    }

    public record Result(String browser, String os) {
    }

    public static Result classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new Result(OTHER, OTHER);
        }
        return new Result(browser(userAgent), os(userAgent));
    }

    private static String browser(String ua) {
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) {
            return "Edge";
        }
        if (ua.contains("OPR/") || ua.contains("Opera")) {
            return "Opera";
        }
        if (ua.contains("Firefox/") || ua.contains("FxiOS")) {
            return "Firefox";
        }
        if (ua.contains("Chrome/") || ua.contains("CriOS")) {
            return "Chrome";
        }
        if (ua.contains("Safari/") && ua.contains("Version/")) {
            return "Safari";
        }
        return OTHER;
    }

    private static String os(String ua) {
        if (ua.contains("Windows")) {
            return "Windows";
        }
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) {
            return "iOS";
        }
        if (ua.contains("Mac OS X")) {
            return "macOS";
        }
        if (ua.contains("Android")) {
            return "Android";
        }
        if (ua.contains("CrOS")) {
            return "ChromeOS";
        }
        if (ua.contains("Linux")) {
            return "Linux";
        }
        return OTHER;
    }
}
