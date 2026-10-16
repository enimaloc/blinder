package fr.enimaloc.blinder.setup;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.common.HostNormalizer;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.security.AdminRequestMatcher;
import fr.enimaloc.blinder.user.Role;
import fr.enimaloc.blinder.user.User;
import fr.enimaloc.blinder.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SetupService {

    private final SetupGuard guard;
    private final UserRepository users;
    private final DomainRepository domains;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transactions;
    private final AdminRequestMatcher adminMatcher;

    /** Sérialisé : deux soumissions simultanées ne peuvent pas créer deux admins. */
    public synchronized void complete(SetupForm form) {
        if (!guard.isSetupPending()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!guard.tokenMatches(form.getToken())) {
            throw new FieldErrorException("token", "Jeton invalide");
        }
        if (form.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new FieldErrorException("password", "72 octets maximum");
        }
        if (!Objects.equals(form.getPassword(), form.getPasswordConfirm())) {
            throw new FieldErrorException("passwordConfirm", "Les mots de passe ne correspondent pas");
        }
        String host = HostNormalizer.normalize(form.getHost());
        if (!HostNormalizer.isValid(host)) {
            throw new FieldErrorException("host", "Nom de domaine invalide");
        }
        if (adminMatcher.isAdminHost(host)) {
            throw new FieldErrorException("host", "Ce domaine est réservé au panel d'administration");
        }
        transactions.executeWithoutResult(status -> {
            if (users.count() > 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            User admin = users.save(new User(form.getUsername().trim(), encoder.encode(form.getPassword()), Role.ADMIN));
            domains.save(new Domain(host, admin));
        });
        guard.invalidate();
    }
}
