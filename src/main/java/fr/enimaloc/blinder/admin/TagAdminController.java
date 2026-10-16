package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.tag.TagForm;
import fr.enimaloc.blinder.tag.TagService;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
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

import java.util.UUID;

@Controller
@RequestMapping("/_/tags")
@RequiredArgsConstructor
public class TagAdminController {

    private static final String VIEW = "admin/tags/list";

    private final CurrentUser currentUser;
    private final TagService tags;

    @GetMapping
    public String list(Model model) {
        populate(model, currentUser.require());
        return VIEW;
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("createForm") TagForm form, BindingResult binding, Model model) {
        User owner = currentUser.require();
        if (!binding.hasErrors()) {
            try {
                tags.create(owner, form.getName(), form.effectiveColor());
                return "redirect:/_/tags";
            } catch (FieldErrorException e) {
                model.addAttribute("createError", e.getMessage());
            }
        } else {
            model.addAttribute("createError", "Valeur invalide");
        }
        populate(model, owner);
        return VIEW;
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("editForm") TagForm form, BindingResult binding,
                         Model model) {
        User owner = currentUser.require();
        tags.get(owner, id);
        if (!binding.hasErrors()) {
            try {
                tags.update(owner, id, form.getName(), form.effectiveColor());
                return "redirect:/_/tags";
            } catch (FieldErrorException e) {
                model.addAttribute("rowError", e.getMessage());
            }
        } else {
            model.addAttribute("rowError", "Valeur invalide");
        }
        model.addAttribute("rowErrorId", id);
        populate(model, owner);
        return VIEW;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id) {
        tags.delete(currentUser.require(), id);
        return "redirect:/_/tags";
    }

    private void populate(Model model, User owner) {
        model.addAttribute("tags", tags.list(owner));
        model.addAttribute("counts", tags.linkCounts(owner));
    }
}
