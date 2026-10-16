package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.domain.DomainForm;
import fr.enimaloc.blinder.common.StaleEditException;
import fr.enimaloc.blinder.domain.DomainService;
import fr.enimaloc.blinder.user.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/_/domains")
@RequiredArgsConstructor
public class DomainAdminController {

    private final CurrentUser currentUser;
    private final DomainService domains;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("domains", domains.list(currentUser.require()));
        return "admin/domains/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new DomainForm());
        return "admin/domains/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") DomainForm form, BindingResult binding) {
        if (binding.hasErrors()) {
            return "admin/domains/form";
        }
        try {
            domains.create(currentUser.require(), form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return "admin/domains/form";
        }
        return "redirect:/_/domains";
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable UUID id, Model model) {
        model.addAttribute("form", domains.toForm(domains.get(currentUser.require(), id)));
        model.addAttribute("domainId", id);
        return "admin/domains/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") DomainForm form,
                         BindingResult binding, Model model) {
        model.addAttribute("domainId", id);
        if (binding.hasErrors()) {
            return "admin/domains/form";
        }
        try {
            domains.update(currentUser.require(), id, form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return "admin/domains/form";
        } catch (StaleEditException e) {
            model.addAttribute("staleError", true);
            return "admin/domains/form";
        }
        return "redirect:/_/domains";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirect) {
        if (!domains.delete(currentUser.require(), id)) {
            redirect.addFlashAttribute("error", "Ce domaine contient encore des liens.");
        }
        return "redirect:/_/domains";
    }
}
