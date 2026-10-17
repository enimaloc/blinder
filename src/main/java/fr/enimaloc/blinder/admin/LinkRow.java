package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.access.LinkStatus;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.tag.Tag;

import java.util.List;

/** @param headerName nom de l'en-tête contrôlé à la destination ({@code null} : pas de contrôle) */
public record LinkRow(Link link, LinkStatus status, List<Tag> tags, String headerName) {
}
