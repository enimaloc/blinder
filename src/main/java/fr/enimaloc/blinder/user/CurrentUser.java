package fr.enimaloc.blinder.user;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final UserRepository users;

    public User require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof BlinderUserDetails details)) {
            throw new AuthenticationCredentialsNotFoundException("No Blinder user in security context");
        }
        User user = users.findById(details.userId())
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("User no longer exists"));
        if (!user.getPasswordHash().equals(details.getPassword())) {
            throw new AuthenticationCredentialsNotFoundException("Credentials changed");
        }
        return user;
    }
}
