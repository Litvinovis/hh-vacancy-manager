package com.hh.gui.repository;

import com.hh.gui.model.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TagRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<Tag> rowMapper = (rs, rowNum) -> {
        Tag t = new Tag();
        t.setId(rs.getLong("id"));
        t.setVacancyId(rs.getLong("vacancy_id"));
        t.setName(rs.getString("name"));
        return t;
    };

    @Autowired
    public TagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Tag> findByVacancyId(Long vacancyId) {
        return jdbc.query("SELECT * FROM tags WHERE vacancy_id = ?", rowMapper, vacancyId);
    }

    public List<String> findNamesByVacancyId(Long vacancyId) {
        return jdbc.queryForList("SELECT name FROM tags WHERE vacancy_id = ?", String.class, vacancyId);
    }

    public void save(Long vacancyId, String name) {
        jdbc.update("INSERT INTO tags (vacancy_id, name) VALUES (?, ?)", vacancyId, name);
    }

    public void deleteByVacancyId(Long vacancyId) {
        jdbc.update("DELETE FROM tags WHERE vacancy_id = ?", vacancyId);
    }

    public void saveAll(Long vacancyId, List<String> names) {
        if (names == null || names.isEmpty()) return;
        jdbc.batchUpdate(
            "INSERT INTO tags (vacancy_id, name) VALUES (?, ?)",
            names, names.size(),
            (ps, name) -> {
                ps.setLong(1, vacancyId);
                ps.setString(2, name);
            });
    }

    public List<Object[]> topTags(int limit, Long userId) {
        RowMapper<Object[]> mapper = (rs, rowNum) -> new Object[]{rs.getString("name"), rs.getInt("cnt")};
        if (userId != null) {
            return jdbc.query(
                "SELECT t.name, COUNT(*) as cnt FROM tags t JOIN vacancies v ON v.id = t.vacancy_id " +
                "WHERE v.user_id = ? GROUP BY t.name ORDER BY cnt DESC LIMIT ?",
                mapper, userId, limit);
        }
        return jdbc.query(
            "SELECT name, COUNT(*) as cnt FROM tags GROUP BY name ORDER BY cnt DESC LIMIT ?",
            mapper, limit);
    }
}
