package fr.enimaloc.blinder.notification;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class EmailSenderTest {

    final JavaMailSender mail = mock(JavaMailSender.class);
    final NotificationProperties properties = new NotificationProperties("blinder@example.org", null);

    @SuppressWarnings("unchecked")
    private EmailSender sender(JavaMailSender mailOrNull) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailOrNull);
        return new EmailSender(provider, properties, JsonMapper.builder().build());
    }

    private NotificationDelivery delivery(String target, String payload, TriggerType event, String label) {
        NotificationChannel channel = new NotificationChannel(null, "c", ChannelType.EMAIL, target);
        return new NotificationDelivery(null, channel, null, label, event, payload, Instant.now());
    }

    @Test
    void sendsToParsedAddressWithExpectedSubjectAndBody() {
        String payload = "{\"event\":\"VISIT\",\"text\":\"Visite de a.test/x depuis 1.2.3.4\","
                + "\"visit\":{\"ip\":\"1.2.3.4\",\"country\":\"FR\",\"city\":\"Lyon\",\"browser\":\"Firefox\","
                + "\"os\":\"Linux\",\"referer\":null,\"userAgent\":\"UA/1\",\"outcome\":\"DENIED\",\"reason\":\"EXPIRED\"}}";
        NotificationDelivery delivery = delivery("Moi <me@example.com>", payload, TriggerType.VISIT, "a.test/x");
        SendResult result = sender(mail).send(delivery.getChannel(), delivery);
        assertThat(result).isEqualTo(new SendResult(true, null));
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getTo()).containsExactly("me@example.com");
        assertThat(message.getFrom()).isEqualTo("blinder@example.org");
        assertThat(message.getSubject()).isEqualTo("[Blinder] Visite — a.test/x");
        assertThat(message.getText()).contains("Visite de a.test/x depuis 1.2.3.4").contains("1.2.3.4")
                .contains("Ville : Lyon").contains("Navigateur : Firefox").contains("User-Agent : UA/1")
                .contains("Issue : Refusée").contains("Raison : Expiré");
    }

    @Test
    void rejectsGroupAddress() {
        NotificationDelivery delivery = delivery("Équipe: a@example.com, b@example.com;", "{\"text\":\"t\"}", TriggerType.TEST, "test");
        assertThat(sender(mail).send(delivery.getChannel(), delivery)).isEqualTo(new SendResult(false, "Cible invalide"));
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void noMailSenderMeansSmtpNotConfigured() {
        NotificationDelivery delivery = delivery("me@example.com", "{\"text\":\"t\"}", TriggerType.TEST, "test");
        assertThat(sender(null).send(delivery.getChannel(), delivery)).isEqualTo(new SendResult(false, "SMTP non configuré"));
    }

    @Test
    void rejectsLineBreaksInTarget() {
        NotificationDelivery delivery = delivery("me@example.com\r\nBcc: x@evil.test", "{\"text\":\"t\"}", TriggerType.TEST, "test");
        assertThat(sender(mail).send(delivery.getChannel(), delivery)).isEqualTo(new SendResult(false, "Cible invalide"));
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void senderExceptionsNeverEscape() {
        doThrow(new MailSendException("x".repeat(900))).when(mail).send(any(SimpleMailMessage.class));
        NotificationDelivery delivery = delivery("me@example.com", "{\"text\":\"t\"}", TriggerType.TEST, "test");
        SendResult result = sender(mail).send(delivery.getChannel(), delivery);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).hasSizeLessThanOrEqualTo(500).startsWith("MailSendException");
    }
}
