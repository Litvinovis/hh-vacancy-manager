package com.hh.gui.model;

/**
 * A post in someone ELSE'S community that looks like a person asking about remote-work
 * job search (see CommentRadarService) — never a vacancy, never something this app
 * publishes on its own. A human reviews draftReply in the admin panel and, if it looks
 * right, posts it themselves from their own VK profile; this row only ever tracks that
 * decision (status), it never triggers a send.
 */
public class CommentRadarFinding {
    public static final String STATUS_NEW = "new";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_REJECTED = "rejected";

    public static final String PLATFORM_VK = "vk";

    private Long id;
    private String platform = PLATFORM_VK;
    private String sourceRef;
    private String postId;
    private String postLink;
    private String authorHint = "";
    private String postText;
    private String matchedKeyword = "";
    private String draftReply = "";
    private String status = STATUS_NEW;
    private String createdAt;
    private String updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }

    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }

    public String getPostLink() { return postLink; }
    public void setPostLink(String postLink) { this.postLink = postLink; }

    public String getAuthorHint() { return authorHint; }
    public void setAuthorHint(String authorHint) { this.authorHint = authorHint; }

    public String getPostText() { return postText; }
    public void setPostText(String postText) { this.postText = postText; }

    public String getMatchedKeyword() { return matchedKeyword; }
    public void setMatchedKeyword(String matchedKeyword) { this.matchedKeyword = matchedKeyword; }

    public String getDraftReply() { return draftReply; }
    public void setDraftReply(String draftReply) { this.draftReply = draftReply; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
