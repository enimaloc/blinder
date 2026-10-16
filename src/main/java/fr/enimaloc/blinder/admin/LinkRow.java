package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.access.LinkStatus;
import fr.enimaloc.blinder.link.Link;

public record LinkRow(Link link, LinkStatus status) {
}
