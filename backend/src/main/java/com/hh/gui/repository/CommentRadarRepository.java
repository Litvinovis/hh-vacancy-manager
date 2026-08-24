package com.hh.gui.repository;

import com.hh.gui.model.CommentRadarFinding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

@Repository
public class CommentRadarRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<CommentRadarFinding> rowMapper = (rs, rowNum) -> {
        CommentRadarFinding f = new CommentRadarFinding();
        f.setId(rs.getLong("id"));
        f.setPlatform(rs.getString("platform"));
        f.setSourceRef(rs.getString("source_ref"));
        f.setPostId(rs.getString("post_id"));
        f.setPostLink(rs.getString("post_link"));
        f.setAuthorHint(rs.getString("author_hint"));
        f.setPostText(rs.getString("post_text"));
        f.setMatchedKeyword(rs.getString("matched_keyword"));
        f.setDraftReply(rs.getString("draft_reply"));
        f.setStatus(rs.getString("status"));
        f.setCreatedAt(rs.getString("created_at"));
        f.setUpdatedAt(rs.getString("updated_at"));
        return f;
    };

    public CommentRadarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Checked by CommentRadarService BEFORE prescreen/AI so a post already seen on a
     *  previous scan never burns AI tokens twice — cheaper than relying on the UNIQUE
     *  constraint to reject a duplicate INSERT after the fact. */
    public boolean existsByPlatformAndPostId(String platform, String postId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM comment_radar_findings WHERE platform = ? AND post_id = ?",
            Integer.class, platform, postId);
        return count != null && count > 0;
    }

    public List<CommentRadarFinding> findByStatus(String status, int limit) {
        return jdbc.query(
            "SELECT * FROM comment_radar_findings WHERE status = ? ORDER BY created_at DESC LIMIT ?",
            rowMapper, status, limit);
    }

    public CommentRadarFinding save(CommentRadarFinding f) {
        String now = Instant.now().toString();
        f.setCreatedAt(now);
        f.setUpdatedAt(now);

        String sql = """
            INSERT INTO comment_radar_findings (platform, source_ref, post_id, post_link,
                author_hint, post_text, matched_keyword, draft_reply, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, f.getPlatform());
            ps.setString(i++, f.getSourceRef());
            ps.setString(i++, f.getPostId());
            ps.setString(i++, f.getPostLink());
            ps.setString(i++, f.getAuthorHint());
            ps.setString(i++, f.getPostText());
            ps.setString(i++, f.getMatchedKeyword());
            ps.setString(i++, f.getDraftReply());
            ps.setString(i++, f.getStatus());
            ps.setString(i++, f.getCreatedAt());
            ps.setString(i, f.getUpdatedAt());
            return ps;
        }, keyHolder);
        f.setId(keyHolder.getKey().longValue());
        return f;
    }

    /** draftReply is only overwritten when the admin actually edited it before marking
     *  sent/rejected — null leaves whatever the AI drafted (or a blank prescreen-only
     *  row) untouched. */
    public int updateStatus(long id, String status, String draftReply) {
        String now = Instant.now().toString();
        if (draftReply != null) {
            return jdbc.update("UPDATE comment_radar_findings SET status=?, draft_reply=?, updated_at=? WHERE id=?",
                status, draftReply, now, id);
        }
        return jdbc.update("UPDATE comment_radar_findings SET status=?, updated_at=? WHERE id=?",
            status, now, id);
    }
}
