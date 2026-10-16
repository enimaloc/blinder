package fr.enimaloc.blinder.notification;

import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Component
public class EmailSender implements ChannelSender {

    private static final List<String> VISIT_FIELDS = List.of("ip", "country", "city", "browser", "os", "referer",
            "outcome", "reason");

    private final ObjectProvider<JavaMailSender> mailSender;
    private final NotificationProperties properties;
    private final JsonMapper mapper;

    public EmailSender(ObjectProvider<JavaMailSender> mailSender, NotificationProperties properties,
                       JsonMapper mapper) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public ChannelType type() {
        return ChannelType.EMAIL;
    }

    @Override
    public SendResult send(NotificationChannel channel, NotificationDelivery delivery) {
        try {
            JavaMailSender sender = mailSender.getIfAvailable();
            if (sender == null) {
                return SendResult.failure("SMTP non configuré");
            }
            String recipient = recipient(channel.getTarget());
            if (recipient == null) {
                return SendResult.failure("Cible invalide");
            }
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.mailFrom());
            message.setTo(recipient);
            message.setSubject(oneLine("[Blinder] " + label(delivery.getEvent()) + " — " + delivery.getLinkLabel()));
            message.setText(body(delivery.getPayload()));
            sender.send(message);
            return SendResult.ok();
        } catch (Exception e) {
            return SendResult.failure(e);
        }
    }

    /** Adresse analysée (jamais la chaîne brute) ; {@code null} si invalide ou contenant un saut de ligne. */
    private static String recipient(String target) {
        if (target == null || target.indexOf('\r') >= 0 || target.indexOf('\n') >= 0) {
            return null;
        }
        try {
            InternetAddress address = new InternetAddress(target, true);
            address.validate();
            String parsed = address.getAddress();
            return parsed == null || parsed.isBlank() ? null : parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private static String oneLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String label(TriggerType event) {
        return switch (event) {
            case VISIT -> "Visite";
            case THRESHOLD -> "Seuil de visites atteint";
            case EXHAUSTED -> "Lien épuisé";
            case EXPIRED -> "Lien expiré";
            case DENIALS -> "Refus répétés";
            case TEST -> "Test";
        };
    }

    private String body(String payload) {
        JsonNode root = mapper.readTree(payload);
        StringBuilder text = new StringBuilder(root.path("text").asString(""));
        JsonNode visit = root.path("visit");
        if (visit.isObject()) {
            text.append("\n");
            for (String field : VISIT_FIELDS) {
                JsonNode value = visit.path(field);
                if (!value.isNull() && !value.isMissingNode()) {
                    text.append("\n").append(field).append(" : ").append(value.asString(""));
                }
            }
        }
        return text.toString();
    }
}
