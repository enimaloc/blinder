package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.access.LinkStatusResolver;
import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainService;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.link.LinkService;
import fr.enimaloc.blinder.common.StaleEditException;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/_")
@RequiredArgsConstructor
public class LinkAdminController {

    private final CurrentUser currentUser;
    private final LinkService links;
    private final DomainService domains;
    private final LinkStatusResolver statuses;

    @GetMapping("/")
    public String list(@RequestParam(name = "domain", required = false) UUID domainId, Model model) {
        User owner = currentUser.require();
        List<LinkRow> rows = links.list(owner, domainId).stream()
                .map(link -> new LinkRow(link, statuses.statusOf(link)))
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("domains", domains.list(owner));
        model.addAttribute("selectedDomain", domainId);
        return "admin/links/list";
    }

    @GetMapping("/links/new")
    public String newForm(Model model) {
        User owner = currentUser.require();
        List<Domain> owned = domains.list(owner);
        LinkForm form = new LinkForm();
        if (!owned.isEmpty()) {
            form.setDomainId(owned.getFirst().getId());
        }
        model.addAttribute("form", form);
        model.addAttribute("domains", owned);
        return "admin/links/form";
    }

    @PostMapping("/links")
    public String create(@Valid @ModelAttribute("form") LinkForm form, BindingResult binding, Model model) {
        User owner = currentUser.require();
        model.addAttribute("domains", domains.list(owner));
        if (binding.hasErrors()) {
            return "admin/links/form";
        }
        try {
            links.create(owner, form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return "admin/links/form";
        } catch (DataIntegrityViolationException e) {
            binding.rejectValue("slug", "duplicate", "Ce slug existe déjà sur ce domaine");
            return "admin/links/form";
        }
        return "redirect:/_/";
    }

    @GetMapping("/links/{id}")
    public String edit(@PathVariable UUID id, Model model) {
        User owner = currentUser.require();
        Link link = links.get(owner, id);
        model.addAttribute("form", links.toForm(link));
        populateEdit(model, owner, link);
        return "admin/links/form";
    }

    @PostMapping("/links/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") LinkForm form,
                         BindingResult binding, Model model) {
        User owner = currentUser.require();
        populateEdit(model, owner, links.get(owner, id));
        if (binding.hasErrors()) {
            return "admin/links/form";
        }
        try {
            links.update(owner, id, form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return "admin/links/form";
        } catch (StaleEditException | ObjectOptimisticLockingFailureException e) {
            model.addAttribute("staleError", true);
            return "admin/links/form";
        } catch (DataIntegrityViolationException e) {
            binding.rejectValue("slug", "duplicate", "Ce slug existe déjà sur ce domaine");
            return "admin/links/form";
        }
        return "redirect:/_/";
    }

    @PostMapping("/links/{id}/reset")
    public String reset(@PathVariable UUID id) {
        links.resetUsage(currentUser.require(), id);
        return "redirect:/_/links/" + id;
    }

    @PostMapping("/links/{id}/delete")
    public String delete(@PathVariable UUID id) {
        links.delete(currentUser.require(), id);
        return "redirect:/_/";
    }

    private void populateEdit(Model model, User owner, Link link) {
        model.addAttribute("linkId", link.getId());
        model.addAttribute("useCount", link.getUseCount());
        model.addAttribute("status", statuses.statusOf(link));
        model.addAttribute("publicUrl", link.getDomain().getHost() + "/" + link.getSlug());
        model.addAttribute("domains", domains.list(owner));
    }
}
