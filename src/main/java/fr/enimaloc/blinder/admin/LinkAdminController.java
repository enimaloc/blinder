package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.access.LinkStatusResolver;
import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.device.DeviceService;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainService;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkEditService;
import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.link.LinkService;
import fr.enimaloc.blinder.link.preview.LinkPreviewService;
import fr.enimaloc.blinder.notification.ChannelService;
import fr.enimaloc.blinder.notification.LinkNotificationService;
import fr.enimaloc.blinder.common.StaleEditException;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.tag.TagService;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import fr.enimaloc.blinder.link.MetaMode;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/_")
@RequiredArgsConstructor
public class LinkAdminController {

    private static final DateTimeFormatter PREVIEW_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final CurrentUser currentUser;
    private final LinkService links;
    private final LinkEditService edits;
    private final DomainService domains;
    private final LinkStatusResolver statuses;
    private final DeviceService deviceService;
    private final LinkPreviewService previews;
    private final BlinderProperties properties;
    private final LinkNotificationService linkNotifications;
    private final ChannelService channels;
    private final TagService tags;

    @GetMapping("/")
    public String list(@RequestParam(name = "domain", required = false) UUID domainId,
                       @RequestParam(name = "tag", required = false) UUID tagId, Model model) {
        User owner = currentUser.require();
        List<LinkRow> rows = links.list(owner, domainId, tagId).stream()
                .map(link -> new LinkRow(link, statuses.statusOf(link), sortedTags(link)))
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("domains", domains.list(owner));
        model.addAttribute("allTags", tags.list(owner));
        model.addAttribute("selectedDomain", domainId);
        model.addAttribute("selectedTag", tagId);
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
        linkNotifications.preselect(owner, form);
        model.addAttribute("form", form);
        model.addAttribute("allTags", tags.list(owner));
        model.addAttribute("channels", channels.list(owner));
        model.addAttribute("domains", owned);
        model.addAttribute("devices", deviceService.all(owner));
        model.addAttribute("hasPassword", false);
        model.addAttribute("limitedOrProtected", false);
        return "admin/links/form";
    }

    @PostMapping("/links")
    public String create(@Valid @ModelAttribute("form") LinkForm form, BindingResult binding, Model model,
                         RedirectAttributes flash) {
        User owner = currentUser.require();
        model.addAttribute("domains", domains.list(owner));
        model.addAttribute("channels", channels.list(owner));
        model.addAttribute("allTags", tags.list(owner));
        model.addAttribute("devices", deviceService.all(owner));
        model.addAttribute("hasPassword", false);
        model.addAttribute("limitedOrProtected", form.declaresLimitOrProtection(false));
        if (binding.hasErrors()) {
            return "admin/links/form";
        }
        try {
            Link created = edits.create(owner, form);
            warnIfExposed(flash, created);
            edits.refreshPreviewAfterSave(owner, created, null);
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
        LinkForm form = links.toForm(link);
        linkNotifications.fillForm(form, id);
        form.setTags(tags.format(link));
        model.addAttribute("form", form);
        populateEdit(model, owner, link);
        return "admin/links/form";
    }

    @PostMapping("/links/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") LinkForm form,
                         BindingResult binding, Model model,
                         RedirectAttributes flash) {
        User owner = currentUser.require();
        Link current = links.get(owner, id);
        populateEdit(model, owner, current);
        model.addAttribute("limitedOrProtected", form.declaresLimitOrProtection(current.getPasswordHash() != null));
        if (binding.hasErrors()) {
            return "admin/links/form";
        }
        try {
            // `current` vient d'un chargement distinct de celui de update() (open-in-view désactivé)
            String previousTarget = current.getTargetUrl();
            Link updated = edits.update(owner, id, form);
            warnIfExposed(flash, updated);
            edits.refreshPreviewAfterSave(owner, updated, previousTarget);
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
        edits.resetUsage(currentUser.require(), id);
        return "redirect:/_/links/" + id;
    }

    @PostMapping("/links/{id}/preview/refresh")
    public String refreshPreview(@PathVariable UUID id) {
        previews.refresh(currentUser.require(), id);
        return "redirect:/_/links/" + id;
    }

    @PostMapping("/links/{id}/delete")
    public String delete(@PathVariable UUID id) {
        links.delete(currentUser.require(), id);
        return "redirect:/_/";
    }

    private void populateEdit(Model model, User owner, Link link) {
        model.addAttribute("linkId", link.getId());
        model.addAttribute("channels", channels.list(owner));
        model.addAttribute("allTags", tags.list(owner));
        model.addAttribute("useCount", link.getUseCount());
        model.addAttribute("status", statuses.statusOf(link));
        model.addAttribute("publicUrl", link.getDomain().getHost() + "/" + link.getSlug());
        model.addAttribute("domains", domains.list(owner));
        model.addAttribute("devices", deviceService.all(owner));
        model.addAttribute("hasPassword", link.getPasswordHash() != null);
        model.addAttribute("noDeviceLeft", links.hasNoDeviceLeft(link));
        model.addAttribute("limitedOrProtected", LinkService.isLimitedOrProtected(link));
        var preview = previews.current(link.getId()).orElse(null);
        model.addAttribute("preview", preview);
        model.addAttribute("previewFetchedAt", preview == null || preview.getFetchedAt() == null ? null
                : PREVIEW_DATE.format(preview.getFetchedAt().atZone(properties.admin().timeZone())));
    }

    private static List<Tag> sortedTags(Link link) {
        return link.getTags().stream().sorted(java.util.Comparator.comparing(Tag::getNameKey)).toList();
    }

    private static void warnIfExposed(RedirectAttributes flash, Link saved) {
        if (saved.getMetaMode() == MetaMode.PASSTHROUGH && LinkService.isLimitedOrProtected(saved)) {
            flash.addFlashAttribute("warning", "Aperçu repris de la cible sur un lien limité ou protégé : "
                    + "son titre, sa description et son image sont visibles publiquement.");
        }
    }
}
