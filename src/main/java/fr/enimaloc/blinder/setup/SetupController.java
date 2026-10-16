package fr.enimaloc.blinder.setup;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.common.HostNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ui.Model;

@Controller
@RequestMapping("/_/setup")
@RequiredArgsConstructor
public class SetupController {

    private final SetupGuard guard;
    private final SetupService setup;

    @GetMapping
    public String form(HttpServletRequest request, Model model) {
        requirePending();
        SetupForm form = new SetupForm();
        form.setHost(HostNormalizer.normalize(request.getServerName()));
        model.addAttribute("form", form);
        return "admin/setup";
    }

    @PostMapping
    public String submit(@Valid @ModelAttribute("form") SetupForm form, BindingResult binding) {
        requirePending();
        if (binding.hasErrors()) {
            return "admin/setup";
        }
        try {
            setup.complete(form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return "admin/setup";
        }
        return "redirect:/_/login?setup";
    }

    private void requirePending() {
        if (!guard.isSetupPending()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
