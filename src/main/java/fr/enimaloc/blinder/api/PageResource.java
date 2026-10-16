package fr.enimaloc.blinder.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Page de résultats : {@code {items, page, size, total}}. */
public record PageResource<T>(List<T> items, int page, int size, long total) {

    /** @param mapper convertit tout le contenu de la page d'un coup (préchargements groupés possibles) */
    static <E, T> PageResource<T> of(Page<E> page, Function<List<E>, List<T>> mapper) {
        return new PageResource<>(mapper.apply(page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements());
    }
}
