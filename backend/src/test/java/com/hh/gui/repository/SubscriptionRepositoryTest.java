package com.hh.gui.repository;

import com.hh.gui.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class SubscriptionRepositoryTest {

    @Autowired
    private SubscriptionRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM subscriptions");
    }

    private static String inDays(int days) {
        return Instant.now().plusSeconds(days * 86400L).toString();
    }

    private Subscription save(long userId, String status, String expiresAt) {
        return save(userId, status, expiresAt, false);
    }

    private Subscription save(long userId, String status, String expiresAt, boolean cancelRequested) {
        Subscription s = new Subscription();
        s.setTelegramUserId(userId);
        s.setTelegramChatId(userId);
        s.setStatus(status);
        s.setExpiresAt(expiresAt);
        s.setCancelRequested(cancelRequested);
        return repo.save(s);
    }

    @Test
    void findActiveChatIds_excludesExpired() {
        // Регрессия: фильтр шёл только по status='active', а статус никто никогда не
        // снимал — оплатив месяц, подписчик получал рассылку бессрочно.
        save(100L, Subscription.STATUS_ACTIVE, inDays(10));
        save(200L, Subscription.STATUS_ACTIVE, inDays(-1));

        List<Long> chatIds = repo.findActiveChatIds();

        assertEquals(List.of(100L), chatIds, "просроченная подписка не должна попадать в рассылку");
    }

    @Test
    void findActiveChatIds_excludesActiveWithoutExpiry() {
        save(300L, Subscription.STATUS_ACTIVE, null);
        assertTrue(repo.findActiveChatIds().isEmpty(),
            "без даты окончания оплату подтвердить нельзя — доступ не выдаём");
    }

    @Test
    void findActiveChatIds_excludesPendingAndCancelled() {
        save(400L, Subscription.STATUS_PENDING, inDays(10));
        save(500L, Subscription.STATUS_CANCELLED, inDays(10));
        assertTrue(repo.findActiveChatIds().isEmpty());
    }

    @Test
    void expireDue_movesOnlyElapsedActiveRows() {
        save(100L, Subscription.STATUS_ACTIVE, inDays(10));
        save(200L, Subscription.STATUS_ACTIVE, inDays(-1));
        save(300L, Subscription.STATUS_PENDING, inDays(-5));

        int moved = repo.expireDue();

        assertEquals(1, moved);
        assertEquals(Subscription.STATUS_ACTIVE, repo.findByTelegramUserId(100L).orElseThrow().getStatus());
        assertEquals(Subscription.STATUS_EXPIRED, repo.findByTelegramUserId(200L).orElseThrow().getStatus());
        assertEquals(Subscription.STATUS_PENDING, repo.findByTelegramUserId(300L).orElseThrow().getStatus(),
            "истечение не должно трогать неоплаченные заявки");
    }

    @Test
    void expireDue_isIdempotent() {
        save(200L, Subscription.STATUS_ACTIVE, inDays(-1));
        assertEquals(1, repo.expireDue());
        assertEquals(0, repo.expireDue(), "повторный прогон не должен ничего находить");
    }

    @Test
    void expireDue_splitsCancelledFromExpiredByFlag() {
        // Доступ в обоих случаях уже закончился в один момент — expireDue не меняет
        // КОГДА строка перестаёт быть активной, только то, как она называется после:
        // 'expired' — лапс без предупреждения, 'cancelled' — осознанный отказ.
        save(100L, Subscription.STATUS_ACTIVE, inDays(-1), false);
        save(200L, Subscription.STATUS_ACTIVE, inDays(-1), true);

        int moved = repo.expireDue();

        assertEquals(2, moved);
        assertEquals(Subscription.STATUS_EXPIRED, repo.findByTelegramUserId(100L).orElseThrow().getStatus());
        assertEquals(Subscription.STATUS_CANCELLED, repo.findByTelegramUserId(200L).orElseThrow().getStatus());
    }

    // ── findDueRenewalReminders / markRenewalReminderSent ──

    @Test
    void findDueRenewalReminders_withinWindow() {
        Subscription due = save(100L, Subscription.STATUS_ACTIVE, inDays(2));       // внутри окна 3 дней
        save(200L, Subscription.STATUS_ACTIVE, inDays(10));                          // ещё далеко
        save(300L, Subscription.STATUS_ACTIVE, inDays(-1));                          // уже истекла

        List<Subscription> result = repo.findDueRenewalReminders(3, 50);

        assertEquals(1, result.size());
        assertEquals(due.getId(), result.get(0).getId());
    }

    @Test
    void findDueRenewalReminders_excludesCancelRequested() {
        save(100L, Subscription.STATUS_ACTIVE, inDays(2), true);
        assertTrue(repo.findDueRenewalReminders(3, 50).isEmpty(),
            "не стоит звать продлить того, кто уже попросил остановить");
    }

    @Test
    void findDueRenewalReminders_excludesAlreadyReminded() {
        Subscription s = save(100L, Subscription.STATUS_ACTIVE, inDays(2));
        repo.markRenewalReminderSent(s.getId());
        assertTrue(repo.findDueRenewalReminders(3, 50).isEmpty(),
            "повторный прогон не должен снова находить уже уведомлённого");
    }

    @Test
    void markRenewalReminderSent_setsTimestamp() {
        Subscription s = save(100L, Subscription.STATUS_ACTIVE, inDays(2));
        assertNull(s.getRenewalReminderSentAt());

        repo.markRenewalReminderSent(s.getId());

        assertNotNull(repo.findByTelegramUserId(100L).orElseThrow().getRenewalReminderSentAt());
    }
}
