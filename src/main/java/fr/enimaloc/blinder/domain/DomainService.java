package fr.enimaloc.blinder.domain;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.common.HostNormalizer;
import fr.enimaloc.blinder.common.UrlPolicy;
import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.common.StaleEditException;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class DomainService {

    private final DomainRepository domains;
    private final LinkRepository links;
    private final String adminDomain;

    public DomainService(DomainRepository domains, LinkRepository links, BlinderProperties properties) {
        this.domains = domains;
        this.links = links;
        String configured = properties.admin().domain();
        this.adminDomain = configured == null || configured.isBlank() ? null : HostNormalizer.normalize(configured);
    }

    @Transactional(readOnly = true)
    public List<Domain> list(User owner) {
        return domains.findAllByOwnerOrderByHost(owner);
    }

    @Transactional(readOnly = true)
    public Domain get(User owner, UUID id) {
        return domains.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public DomainForm toForm(Domain domain) {
        DomainForm form = new DomainForm();
        form.setHost(domain.getHost());
        form.setEnabled(domain.isEnabled());
        form.setNotFoundUrl(domain.getNotFoundUrl());
        form.setVersion(domain.getVersion());
        return form;
    }

    @Transactional
    public Domain create(User owner, DomainForm form) {
        Domain domain = new Domain(checkedHost(form.getHost(), null), owner);
        apply(domain, form);
        return domains.save(domain);
    }

    @Transactional
    public Domain update(User owner, UUID id, DomainForm form) {
        Domain domain = get(owner, id);
        if (form.getVersion() != null && !Objects.equals(form.getVersion(), domain.getVersion())) {
            throw new StaleEditException();
        }
        domain.setHost(checkedHost(form.getHost(), id));
        apply(domain, form);
        return domains.save(domain);
    }

    /** @return {@code false} si le domaine contient encore des liens. */
    @Transactional
    public boolean delete(User owner, UUID id) {
        Domain domain = get(owner, id);
        if (links.existsByDomain(domain)) {
            return false;
        }
        domains.delete(domain);
        return true;
    }

    private String checkedHost(String raw, UUID currentId) {
        String host = HostNormalizer.normalize(raw);
        if (!HostNormalizer.isValid(host)) {
            throw new FieldErrorException("host", "Nom de domaine invalide");
        }
        if (host.equals(adminDomain)) {
            throw new FieldErrorException("host", "Ce domaine est réservé au panel d'administration");
        }
        domains.findByHost(host)
                .filter(existing -> !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new FieldErrorException("host", "Ce domaine est déjà déclaré");
                });
        return host;
    }

    private void apply(Domain domain, DomainForm form) {
        String notFoundUrl = blankToNull(form.getNotFoundUrl());
        if (notFoundUrl != null && !UrlPolicy.isHttpUrl(notFoundUrl)) {
            throw new FieldErrorException("notFoundUrl", "URL http(s) absolue requise");
        }
        domain.setEnabled(form.isEnabled());
        domain.setNotFoundUrl(notFoundUrl);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
