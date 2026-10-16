package fr.enimaloc.blinder.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Page de résultats : {@code {items, page, size, total}}. */
public record PageResource<T>(List<T> items, int page, int size, long total) {

    static <E, T> PageResource<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResource<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements());
    }
}
