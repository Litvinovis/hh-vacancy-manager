package com.hh.gui.repository;

import com.hh.gui.model.Subscription;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SubscriptionRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<Subscription> rowMapper = (rs, rowNum) -> {
        Subscription s = new Subscription();
        s.setId(rs.getLong("id"));
        s.setTelegramUserId(rs.getLong("telegram_user_id"));
        s.setTelegramChatId(rs.getLong("telegram_chat_id"));
        s.setStatus(rs.getString("status"));
        s.setPlanPriceRub(rs.getInt("plan_price_rub"));
        s.setStartedAt(rs.getString("started_at"));
        s.setExpiresAt(rs.getString("expires_at"));
        s.setPaymentProvider(rs.getString("payment_provider"));
        s.setExternalPaymentId(rs.getString("external_payment_id"));
        s.setCreatedAt(rs.getString("created_at"));
        s.setUpdatedAt(rs.getString("updated_at"));
        return s;
    };

    public SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Subscription> findByTelegramUserId(long telegramUserId) {
        List<Subscription> results = jdbc.query(
            "SELECT * FROM subscriptions WHERE telegram_user_id = ?", rowMapper, telegramUserId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** Chat ids of every currently active subscriber — the paid instant-broadcast list. */
    public List<Long> findActiveChatIds() {
        return jdbc.queryForList(
            "SELECT telegram_chat_id FROM subscriptions WHERE status = ?", Long.class, Subscription.STATUS_ACTIVE);
    }

    public Subscription save(Subscription s) {
        String now = Instant.now().toString();
        if (s.getCreatedAt() == null || s.getCreatedAt().isEmpty()) {
            s.setCreatedAt(now);
        }
        s.setUpdatedAt(now);

        String sql = """
            INSERT INTO subscriptions (telegram_user_id, telegram_chat_id, status, plan_price_rub,
                started_at, expires_at, payment_provider, external_payment_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setLong(i++, s.getTelegramUserId());
            ps.setLong(i++, s.getTelegramChatId());
            ps.setString(i++, s.getStatus());
            ps.setInt(i++, s.getPlanPriceRub());
            ps.setString(i++, s.getStartedAt());
            ps.setString(i++, s.getExpiresAt());
            ps.setString(i++, s.getPaymentProvider());
            ps.setString(i++, s.getExternalPaymentId());
            ps.setString(i++, s.getCreatedAt());
            ps.setString(i, s.getUpdatedAt());
            return ps;
        }, keyHolder);
        s.setId(keyHolder.getKey().longValue());
        return s;
    }

    public void update(Subscription s) {
        s.setUpdatedAt(Instant.now().toString());
        jdbc.update(
            "UPDATE subscriptions SET telegram_chat_id=?, status=?, plan_price_rub=?, started_at=?, " +
            "expires_at=?, payment_provider=?, external_payment_id=?, updated_at=? WHERE id=?",
            s.getTelegramChatId(), s.getStatus(), s.getPlanPriceRub(), s.getStartedAt(),
            s.getExpiresAt(), s.getPaymentProvider(), s.getExternalPaymentId(), s.getUpdatedAt(), s.getId());
    }
}
