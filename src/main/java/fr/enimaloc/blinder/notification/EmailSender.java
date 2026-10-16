package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.stats.VisitOutcome;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class EmailSender implements ChannelSender {

    private static final Map<String, String> VISIT_FIELDS = new LinkedHashMap<>();

    static {
        VISIT_FIELDS.put("ip", "IP");
        VISIT_FIELDS.put("country", "Pays");
        VISIT_FIELDS.put("city", "Ville");
        VISIT_FIELDS.put("browser", "Navigateur");
        VISIT_FIELDS.put("os", "Système");
        VISIT_FIELDS.put("referer", "Referer");
        VISIT_FIELDS.put("userAgent", "User-Agent");
        VISIT_FIELDS.put("outcome", "Issue");
        VISIT_FIELDS.put("reason", "Raison");
    }

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
            message.setSubject(oneLine("[Blinder] " + delivery.getEvent().label() + " — " + delivery.getLinkLabel()));
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
            if (address.isGroup()) {
                return null;
            }
            String parsed = address.getAddress();
            return parsed == null || parsed.isBlank() ? null : parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private static String oneLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private String body(String payload) {
        JsonNode root = mapper.readTree(payload);
        StringBuilder text = new StringBuilder(root.path("text").asString(""));
        JsonNode visit = root.path("visit");
        if (visit.isObject()) {
            text.append("\n");
            VISIT_FIELDS.forEach((field, name) -> {
                JsonNode value = visit.path(field);
                if (!value.isNull() && !value.isMissingNode()) {
                    text.append("\n").append(name).append(" : ").append(display(field, value.asString("")));
                }
            });
        }
        return text.toString();
    }

    /** Libellés français des énumérations ; valeur brute si inconnue. */
    private static String display(String field, String value) {
        try {
            return switch (field) {
                case "outcome" -> VisitOutcome.valueOf(value).label();
                case "reason" -> DenyReason.valueOf(value).label();
                default -> value;
            };
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
