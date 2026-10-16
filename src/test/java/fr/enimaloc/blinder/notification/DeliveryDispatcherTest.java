package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestData.class)
class DeliveryDispatcherTest {

    @Autowired
    TestData data;
    @Autowired
    NotificationDeliveryRepository deliveries;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    DeliveryService deliveryService;

    final AtomicReference<SendResult> next = new AtomicReference<>(new SendResult(true, null));
    final List<String> sent = new ArrayList<>();
    final Instant t0 = Instant.parse("2026-10-16T10:00:00Z");
    final AtomicReference<Instant> clockNow = new AtomicReference<>(t0);
    final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return clockNow.get();
        }
    };

    User owner;
    NotificationChannel channel;
    DeliveryDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        data.reset();
        owner = data.admin("admin");
        channel = data.channel(owner, "Mail", ChannelType.EMAIL, "a@example.com");
        ChannelSender sender = new ChannelSender() {
            @Override
            public ChannelType type() {
                return ChannelType.EMAIL;
            }

            @Override
            public SendResult send(NotificationChannel c, NotificationDelivery d) {
                sent.add(d.getId().toString());
                return next.get();
            }
        };
        dispatcher = new DeliveryDispatcher(deliveries, List.of(sender), transactions, clock);
    }

    /** Horloge et instant de répartition alignés, sauf quand un test les écarte volontairement. */
    private void dispatchAt(Instant now) {
        clockNow.set(now);
        dispatcher.dispatch(now);
    }

    private NotificationDelivery pending(Instant due) {
        return deliveries.saveAndFlush(new NotificationDelivery(owner, channel, null, "test", TriggerType.TEST,
                "{\"text\":\"t\"}", due));
    }

    private NotificationDelivery reload(NotificationDelivery d) {
        return deliveries.findById(d.getId()).orElseThrow();
    }

    @Test
    void successMarksSent() {
        NotificationDelivery d = pending(t0);
        next.set(new SendResult(true, null));
        dispatchAt(t0);
        NotificationDelivery after = reload(d);
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(after.getSentAt()).isEqualTo(t0);
        assertThat(after.getLastError()).isNull();
        assertThat(after.getAttempts()).isZero();
    }

    @Test
    void failuresBackOffThenGiveUpOnFifth() {
        NotificationDelivery d = pending(t0);
        next.set(new SendResult(false, "HTTP 500"));
        Instant now = t0;
        List<Duration> expected = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));
        for (int i = 0; i < 4; i++) {
            dispatchAt(now);
            NotificationDelivery after = reload(d);
            assertThat(after.getStatus()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(after.getAttempts()).isEqualTo(i + 1);
            assertThat(after.getLastError()).isEqualTo("HTTP 500");
            assertThat(after.getNextAttemptAt()).isEqualTo(now.plus(expected.get(i)));
            // avant l'échéance : ignoré
            int before = sent.size();
            dispatchAt(now.plus(expected.get(i)).minusSeconds(1));
            assertThat(sent).hasSize(before);
            now = now.plus(expected.get(i));
        }
        dispatchAt(now);
        NotificationDelivery last = reload(d);
        assertThat(last.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(last.getAttempts()).isEqualTo(5);
        int count = sent.size();
        dispatchAt(now.plus(Duration.ofDays(1)));
        assertThat(sent).hasSize(count);
    }

    @Test
    void notYetDueIsSkipped() {
        pending(t0.plusSeconds(60));
        dispatchAt(t0);
        assertThat(sent).isEmpty();
    }

    @Test
    void disabledChannelFailsWithoutSending() {
        channel.setEnabled(false);
        channels.saveAndFlush(channel);
        NotificationDelivery d = pending(t0);
        dispatchAt(t0);
        assertThat(sent).isEmpty();
        NotificationDelivery after = reload(d);
        assertThat(after.getLastError()).isEqualTo("Canal désactivé");
        assertThat(after.getAttempts()).isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void longErrorIsTruncatedTo500() {
        NotificationDelivery d = pending(t0);
        next.set(new SendResult(false, "e".repeat(900)));
        dispatchAt(t0);
        assertThat(reload(d).getLastError()).hasSize(500);
    }

    @Test
    void oneFailingDeliveryDoesNotStopOthers() {
        NotificationDelivery a = pending(t0);
        NotificationDelivery b = pending(t0.plusSeconds(1));
        ChannelSender exploding = new ChannelSender() {
            @Override
            public ChannelType type() {
                return ChannelType.EMAIL;
            }

            @Override
            public SendResult send(NotificationChannel c, NotificationDelivery d) {
                sent.add(d.getId().toString());
                if (d.getId().equals(a.getId())) {
                    throw new IllegalStateException("boom");
                }
                return new SendResult(true, null);
            }
        };
        new DeliveryDispatcher(deliveries, List.of(exploding), transactions, Clock.systemUTC()).dispatch(t0.plusSeconds(5));
        assertThat(sent).hasSize(2);
        assertThat(reload(b).getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(reload(a).getAttempts()).isEqualTo(1);
    }

    @Test
    void retryResetsFailedDelivery() {
        NotificationDelivery d = pending(t0);
        d.setStatus(DeliveryStatus.FAILED);
        d.setAttempts(5);
        d.setNextAttemptAt(null);
        deliveries.saveAndFlush(d);
        deliveryService.retry(owner, d.getId());
        NotificationDelivery after = reload(d);
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getNextAttemptAt()).isNotNull().isBeforeOrEqualTo(Instant.now().plusSeconds(1));
    }

    @Test
    void testOnDisabledChannelIsRefused() {
        channel.setEnabled(false);
        channels.saveAndFlush(channel);
        assertThatThrownBy(() -> deliveryService.enqueueTest(owner, channel.getId()))
                .isInstanceOf(fr.enimaloc.blinder.common.FieldErrorException.class).hasMessage("Canal désactivé");
        assertThat(deliveries.findAll()).isEmpty();
    }

    @Test
    void retryOnlyFailedAndOnlyOwn() {
        NotificationDelivery pending = pending(t0);
        assertThat(deliveryService.retry(owner, pending.getId())).isFalse();
        User other = data.admin("other");
        pending.setStatus(DeliveryStatus.FAILED);
        deliveries.saveAndFlush(pending);
        assertThatThrownBy(() -> deliveryService.retry(other, pending.getId())).isInstanceOf(ResponseStatusException.class);
        assertThat(reload(pending).getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void sentAtAndBackoffUseTheTimeOfSending() {
        NotificationDelivery ok = pending(t0);
        clockNow.set(t0.plusSeconds(20));
        dispatcher.dispatch(t0);
        assertThat(reload(ok).getSentAt()).isEqualTo(t0.plusSeconds(20));

        NotificationDelivery ko = pending(t0);
        next.set(new SendResult(false, "HTTP 500"));
        clockNow.set(t0.plusSeconds(40));
        dispatcher.dispatch(t0);
        assertThat(reload(ko).getNextAttemptAt()).isEqualTo(t0.plusSeconds(40).plus(Duration.ofMinutes(1)));
    }
}
