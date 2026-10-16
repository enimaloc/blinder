package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.common.UrlPolicy;
import fr.enimaloc.blinder.user.User;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChannelService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NotificationChannelRepository channels;

    @Transactional(readOnly = true)
    public List<NotificationChannel> list(User owner) {
        return channels.findByOwnerOrderByNameAsc(owner);
    }

    @Transactional(readOnly = true)
    public NotificationChannel get(User owner, UUID id) {
        return channels.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public ChannelForm toForm(NotificationChannel channel) {
        ChannelForm form = new ChannelForm();
        form.setName(channel.getName());
        form.setType(channel.getType());
        form.setTarget(channel.getTarget());
        form.setAllowPrivateNetwork(channel.isAllowPrivateNetwork());
        form.setPreselected(channel.isPreselected());
        form.setEnabled(channel.isEnabled());
        return form;
    }

    @Transactional
    public NotificationChannel create(User owner, ChannelForm form) {
        NotificationChannel channel = new NotificationChannel(owner, form.getName().trim(), form.getType(),
                checkedTarget(form));
        apply(channel, form);
        return channels.save(channel);
    }

    @Transactional
    public NotificationChannel update(User owner, UUID id, ChannelForm form) {
        NotificationChannel channel = get(owner, id);
        channel.setName(form.getName().trim());
        channel.setType(form.getType());
        channel.setTarget(checkedTarget(form));
        apply(channel, form);
        return channels.save(channel);
    }

    @Transactional
    public void regenerateSecret(User owner, UUID id) {
        NotificationChannel channel = get(owner, id);
        if (channel.getType() == ChannelType.WEBHOOK) {
            channel.setSecret(newSecret());
        }
    }

    @Transactional
    public void delete(User owner, UUID id) {
        channels.delete(get(owner, id));
    }

    private void apply(NotificationChannel channel, ChannelForm form) {
        boolean webhook = form.getType() == ChannelType.WEBHOOK;
        channel.setAllowPrivateNetwork(webhook && form.isAllowPrivateNetwork());
        channel.setPreselected(form.isPreselected());
        channel.setEnabled(form.isEnabled());
        if (!webhook) {
            channel.setSecret(null);
        } else if (channel.getSecret() == null) {
            channel.setSecret(newSecret());
        }
    }

    private static String checkedTarget(ChannelForm form) {
        String target = form.getTarget().trim();
        if (form.getType() == ChannelType.WEBHOOK) {
            if (!UrlPolicy.isHttpUrl(target) || !hasHost(target)) {
                throw new FieldErrorException("target", "URL http(s) absolue requise");
            }
        } else {
            try {
                new InternetAddress(target, true).validate();
            } catch (AddressException e) {
                throw new FieldErrorException("target", "Adresse e-mail invalide");
            }
        }
        return target;
    }

    private static boolean hasHost(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null && !host.isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
