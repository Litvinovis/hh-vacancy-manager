package com.hh.gui.model;

/** Строка vk_articles: статья или опрос для сообщества VK. См. схему таблицы. */
public class VkArticle {
    private Long id;
    private String topicKey;
    private String kind = "article";
    private String title = "";
    private String body = "";
    private String pollOptions = "";
    private String status = "planned";
    private String plannedFor;
    private String generatedAt;
    private String publishedAt;
    private String vkPostId;
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String topicKey) { this.topicKey = topicKey; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getPollOptions() { return pollOptions; }
    public void setPollOptions(String pollOptions) { this.pollOptions = pollOptions; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPlannedFor() { return plannedFor; }
    public void setPlannedFor(String plannedFor) { this.plannedFor = plannedFor; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
    public String getPublishedAt() { return publishedAt; }
    public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }
    public String getVkPostId() { return vkPostId; }
    public void setVkPostId(String vkPostId) { this.vkPostId = vkPostId; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public boolean isPoll() { return "poll".equals(kind); }
}
