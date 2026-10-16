package fr.enimaloc.blinder.stats;

import java.util.UUID;

/** Portée d'une lecture de statistiques : tous les liens, un lien, ou les liens portant un tag. */
public record StatsScope(UUID linkId, UUID tagId) {

    public static StatsScope all() {
        return new StatsScope(null, null);
    }

    public static StatsScope link(UUID linkId) {
        return new StatsScope(linkId, null);
    }

    public static StatsScope tag(UUID tagId) {
        return new StatsScope(null, tagId);
    }

    /** Fragment JPQL constant (préfixé par {@code and}) sur l'alias de visite ou de cumul donné ; le paramètre {@code :owner} est lié par l'appelant. */
    String clause(String alias) {
        String clause = "";
        if (linkId != null) {
            clause += " and " + alias + ".link.id = :linkId";
        }
        if (tagId != null) {
            clause += " and " + alias + ".link.id in (select sl.id from Link sl join sl.tags st"
                    + " where st.id = :tagId and sl.owner = :owner and st.owner = :owner)";
        }
        return clause;
    }

    void bind(jakarta.persistence.Query query) {
        if (linkId != null) {
            query.setParameter("linkId", linkId);
        }
        if (tagId != null) {
            query.setParameter("tagId", tagId);
        }
    }
}
