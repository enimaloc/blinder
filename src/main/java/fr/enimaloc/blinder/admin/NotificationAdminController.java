package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.notification.ChannelForm;
import fr.enimaloc.blinder.notification.ChannelService;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
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
@RequestMapping("/_/notifications")
@RequiredArgsConstructor
public class NotificationAdminController {

    private static final String FORM = "admin/notifications/form";

    private final CurrentUser currentUser;
    private final ChannelService channels;
    private final ObjectProvider<JavaMailSender> mailSender;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("channels", channels.list(currentUser.require()));
        return "admin/notifications/list";
    }

    @GetMapping("/channels/new")
    public String newForm(Model model) {
        model.addAttribute("form", new ChannelForm());
        model.addAttribute("smtpConfigured", smtpConfigured());
        return FORM;
    }

    @PostMapping("/channels")
    public String create(@Valid @ModelAttribute("form") ChannelForm form, BindingResult binding, Model model) {
        model.addAttribute("smtpConfigured", smtpConfigured());
        if (binding.hasErrors()) {
            return FORM;
        }
        try {
            channels.create(currentUser.require(), form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return FORM;
        }
        return "redirect:/_/notifications";
    }

    @GetMapping("/channels/{id}")
    public String edit(@PathVariable UUID id, Model model) {
        NotificationChannel channel = channels.get(currentUser.require(), id);
        model.addAttribute("form", channels.toForm(channel));
        populateEdit(model, channel);
        return FORM;
    }

    @PostMapping("/channels/{id}")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("form") ChannelForm form,
                         BindingResult binding, Model model) {
        User owner = currentUser.require();
        populateEdit(model, channels.get(owner, id));
        if (binding.hasErrors()) {
            return FORM;
        }
        try {
            channels.update(owner, id, form);
        } catch (FieldErrorException e) {
            binding.rejectValue(e.field(), "invalid", e.getMessage());
            return FORM;
        }
        return "redirect:/_/notifications";
    }

    @PostMapping("/channels/{id}/secret")
    public String regenerateSecret(@PathVariable UUID id) {
        channels.regenerateSecret(currentUser.require(), id);
        return "redirect:/_/notifications/channels/" + id;
    }

    @PostMapping("/channels/{id}/delete")
    public String delete(@PathVariable UUID id) {
        channels.delete(currentUser.require(), id);
        return "redirect:/_/notifications";
    }

    private void populateEdit(Model model, NotificationChannel channel) {
        model.addAttribute("channelId", channel.getId());
        model.addAttribute("secret", channel.getSecret());
        model.addAttribute("smtpConfigured", smtpConfigured());
    }

    private boolean smtpConfigured() {
        return mailSender.getIfAvailable() != null;
    }
}
