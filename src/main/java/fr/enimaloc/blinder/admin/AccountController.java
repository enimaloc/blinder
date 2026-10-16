package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.user.AccountService;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.PasswordChangeForm;
import fr.enimaloc.blinder.user.BlinderUserDetails;
import fr.enimaloc.blinder.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/_/account")
@RequiredArgsConstructor
public class AccountController {

    private final CurrentUser currentUser;
    private final AccountService accounts;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    @GetMapping
    public String show(Model model) {
        model.addAttribute("form", new PasswordChangeForm());
        model.addAttribute("username", currentUser.require().getUsername());
        return "admin/account";
    }

    @PostMapping("/password")
    public String changePassword(@Valid @ModelAttribute("form") PasswordChangeForm form,
                                 BindingResult binding, Model model,
                                 HttpServletRequest request, HttpServletResponse response) {
        User user = currentUser.require();
        model.addAttribute("username", user.getUsername());
        if (binding.hasErrors()) {
            return "admin/account";
        }
        try {
            user = accounts.changePassword(user, form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return "admin/account";
        }
        BlinderUserDetails details = new BlinderUserDetails(user);
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        request.changeSessionId();
        securityContextRepository.saveContext(context, request, response);
        return "redirect:/_/account?changed";
    }
}
