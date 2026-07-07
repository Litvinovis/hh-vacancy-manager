package com.hh.gui.repository;

import com.hh.gui.model.User;
import org.springframework.beans.factory.annotation.Autowired;
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
public class UserRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<User> rowMapper = (rs, rowNum) -> {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setDisplayName(rs.getString("display_name"));
        u.setRole(rs.getString("role"));
        u.setCity(rs.getString("city"));
        u.setExperienceSummary(rs.getString("experience_summary"));
        u.setActive(rs.getInt("active") == 1);
        u.setCreatedAt(rs.getString("created_at"));
        return u;
    };

    @Autowired
    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        return count != null ? count : 0;
    }

    public User save(User u) {
        String now = Instant.now().toString();
        if (u.getCreatedAt() == null || u.getCreatedAt().isEmpty()) {
            u.setCreatedAt(now);
        }

        String sql = """
            INSERT INTO users (username, password_hash, display_name, role, city, experience_summary, active, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, u.getUsername());
            ps.setString(2, u.getPasswordHash());
            ps.setString(3, u.getDisplayName());
            ps.setString(4, u.getRole() != null ? u.getRole() : "user");
            ps.setString(5, u.getCity());
            ps.setString(6, u.getExperienceSummary());
            ps.setInt(7, u.isActive() ? 1 : 0);
            ps.setString(8, u.getCreatedAt());
            return ps;
        }, keyHolder);

        u.setId(keyHolder.getKey().longValue());
        return u;
    }

    public void update(User u) {
        jdbc.update(
            "UPDATE users SET display_name=?, role=?, city=?, experience_summary=?, active=? WHERE id=?",
            u.getDisplayName(), u.getRole(), u.getCity(), u.getExperienceSummary(), u.isActive() ? 1 : 0, u.getId());
    }

    public void updatePasswordHash(Long id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", passwordHash, id);
    }

    public void updateProfile(Long id, String city, String experienceSummary) {
        jdbc.update("UPDATE users SET city=?, experience_summary=? WHERE id=?", city, experienceSummary, id);
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM users WHERE id=?", id);
    }

    public Optional<User> findById(Long id) {
        List<User> results = jdbc.query("SELECT * FROM users WHERE id = ?", rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<User> findByUsername(String username) {
        List<User> results = jdbc.query("SELECT * FROM users WHERE username = ?", rowMapper, username);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    public List<User> findAll() {
        return jdbc.query("SELECT * FROM users ORDER BY id", rowMapper);
    }
}
