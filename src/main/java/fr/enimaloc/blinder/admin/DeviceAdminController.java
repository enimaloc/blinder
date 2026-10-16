package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.common.PublicUrls;
import fr.enimaloc.blinder.device.CreatedEnrollment;
import fr.enimaloc.blinder.device.DeviceService;
import fr.enimaloc.blinder.device.EnrollmentForm;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainService;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/_/devices")
@RequiredArgsConstructor
public class DeviceAdminController {

    private final CurrentUser currentUser;
    private final DeviceService devices;
    private final DomainService domains;

    @GetMapping
    public String list(Model model) {
        User owner = currentUser.require();
        model.addAttribute("devices", devices.list(owner));
        model.addAttribute("pending", devices.pendingEnrollments(owner));
        return "admin/devices/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        User owner = currentUser.require();
        List<Domain> enabled = enabledDomains(owner);
        EnrollmentForm form = new EnrollmentForm();
        if (!enabled.isEmpty()) {
            form.setStartDomainId(enabled.getFirst().getId());
        }
        model.addAttribute("form", form);
        model.addAttribute("domains", enabled);
        return "admin/devices/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") EnrollmentForm form, BindingResult binding,
                         HttpServletRequest request, Model model) {
        User owner = currentUser.require();
        model.addAttribute("domains", enabledDomains(owner));
        if (binding.hasErrors()) {
            return "admin/devices/form";
        }
        CreatedEnrollment created;
        try {
            created = devices.createEnrollment(owner, form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return "admin/devices/form";
        }
        model.addAttribute("name", created.enrollment().getName());
        model.addAttribute("enrollmentUrl", PublicUrls.base(request.getScheme(), created.host(),
                request.getServerPort()) + "/_/enroll/" + created.token());
        return "admin/devices/created";
    }

    @PostMapping("/{id}/rename")
    public String rename(@PathVariable UUID id, @RequestParam String name, RedirectAttributes redirect) {
        try {
            devices.rename(currentUser.require(), id, name);
        } catch (FieldErrorException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/_/devices";
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable UUID id) {
        devices.revoke(currentUser.require(), id);
        return "redirect:/_/devices";
    }

    @PostMapping("/enrollments/{id}/cancel")
    public String cancel(@PathVariable UUID id) {
        devices.cancelEnrollment(currentUser.require(), id);
        return "redirect:/_/devices";
    }

    private List<Domain> enabledDomains(User owner) {
        return domains.list(owner).stream().filter(Domain::isEnabled).toList();
    }
}
