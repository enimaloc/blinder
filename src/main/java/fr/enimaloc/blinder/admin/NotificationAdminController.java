package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.notification.ChannelForm;
import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.notification.ChannelService;
import fr.enimaloc.blinder.notification.DeliveryService;
import fr.enimaloc.blinder.notification.NotificationDelivery;
import fr.enimaloc.blinder.notification.TriggerType;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/_/notifications")
@RequiredArgsConstructor
public class NotificationAdminController {

    private static final String FORM = "admin/notifications/form";

    private final CurrentUser currentUser;
    private final ChannelService channels;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final DeliveryService deliveries;
    private final BlinderProperties blinder;

    /** Ligne de la liste des envois, déjà mise en forme (fuseau admin, libellés français). */
    public record DeliveryRow(UUID id, String date, String channel, String event, String link, String status,
                              int attempts, String error, boolean retryable) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @GetMapping
    public String list(Model model) {
        User owner = currentUser.require();
        model.addAttribute("channels", channels.list(owner));
        model.addAttribute("deliveries", rows(deliveries.recent(owner)));
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

    @PostMapping("/deliveries/{id}/retry")
    public String retry(@PathVariable UUID id, RedirectAttributes redirect) {
        deliveries.retry(currentUser.require(), id);
        redirect.addFlashAttribute("info", "Envoi relancé");
        return "redirect:/_/notifications";
    }

    @PostMapping("/channels/{id}/test")
    public String test(@PathVariable UUID id, RedirectAttributes redirect) {
        deliveries.enqueueTest(currentUser.require(), id);
        redirect.addFlashAttribute("info", "Test programmé");
        return "redirect:/_/notifications";
    }

    private List<DeliveryRow> rows(List<NotificationDelivery> found) {
        return found.stream().map(d -> new DeliveryRow(d.getId(),
                DATE.format(d.getCreatedAt().atZone(blinder.admin().timeZone())),
                d.getChannel().getName(), eventLabel(d.getEvent()),
                d.getLinkLabel() == null ? "" : d.getLinkLabel(),
                switch (d.getStatus()) {
                    case PENDING -> "En attente";
                    case SENT -> "Envoyé";
                    case FAILED -> "Échec";
                },
                d.getAttempts(), d.getLastError(),
                d.getStatus() == fr.enimaloc.blinder.notification.DeliveryStatus.FAILED)).toList();
    }

    private static String eventLabel(TriggerType event) {
        return switch (event) {
            case VISIT -> "Visite";
            case THRESHOLD -> "Seuil de visites";
            case EXHAUSTED -> "Lien épuisé";
            case EXPIRED -> "Lien expiré";
            case DENIALS -> "Refus répétés";
            case TEST -> "Test";
        };
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
