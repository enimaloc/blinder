package fr.enimaloc.blinder.user;

import fr.enimaloc.blinder.common.FieldErrorException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    @Transactional
    public User changePassword(User user, PasswordChangeForm form) {
        if (!encoder.matches(form.getCurrentPassword(), user.getPasswordHash())) {
            throw new FieldErrorException("currentPassword", "Mot de passe actuel incorrect");
        }
        if (!Objects.equals(form.getNewPassword(), form.getConfirmPassword())) {
            throw new FieldErrorException("confirmPassword", "Les mots de passe ne correspondent pas");
        }
        user.setPasswordHash(encoder.encode(form.getNewPassword()));
        return users.save(user);
    }
}
