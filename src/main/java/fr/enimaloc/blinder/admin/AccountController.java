package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.user.AccountService;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.PasswordChangeForm;
import jakarta.validation.Valid;
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

    @GetMapping
    public String show(Model model) {
        model.addAttribute("form", new PasswordChangeForm());
        model.addAttribute("username", currentUser.require().getUsername());
        return "admin/account";
    }

    @PostMapping("/password")
    public String changePassword(@Valid @ModelAttribute("form") PasswordChangeForm form,
                                 BindingResult binding, Model model) {
        model.addAttribute("username", currentUser.require().getUsername());
        if (binding.hasErrors()) {
            return "admin/account";
        }
        try {
            accounts.changePassword(currentUser.require(), form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return "admin/account";
        }
        return "redirect:/_/account?changed";
    }
}
