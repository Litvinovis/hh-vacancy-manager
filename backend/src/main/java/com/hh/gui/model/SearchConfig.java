package com.hh.gui.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A single user-owned, DB-persisted search (up to 3 per user, enforced by
 * SearchService). Replaces the old YAML-driven AppConfig.SearchConfig —
 * this one is editable at runtime through the personal cabinet, no restart.
 */
public class SearchConfig {
    private Long id;
    private Long userId;
    private String name;
    private List<String> queries;
    private int area;
    private String schedule;
    private int salaryMin;
    private List<String> priorityDistricts;
    private List<String> skills;
    private List<String> notSuitable;
    private List<String> excludeWords;
    private String aiNotes;
    private boolean enabled;
    private String createdAt;
    private String updatedAt;
    private boolean isGlobal;
    private String sourceUrl;
    private Integer runIntervalHours;
    private String lastRunAt;

    public SearchConfig() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getQueries() { return queries; }
    public void setQueries(List<String> queries) { this.queries = queries; }

    public int getArea() { return area; }
    public void setArea(int area) { this.area = area; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    public int getSalaryMin() { return salaryMin; }
    public void setSalaryMin(int salaryMin) { this.salaryMin = salaryMin; }

    public List<String> getPriorityDistricts() { return priorityDistricts; }
    public void setPriorityDistricts(List<String> priorityDistricts) { this.priorityDistricts = priorityDistricts; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public List<String> getNotSuitable() { return notSuitable; }
    public void setNotSuitable(List<String> notSuitable) { this.notSuitable = notSuitable; }

    public List<String> getExcludeWords() { return excludeWords; }
    public void setExcludeWords(List<String> excludeWords) { this.excludeWords = excludeWords; }

    public String getAiNotes() { return aiNotes; }
    public void setAiNotes(String aiNotes) { this.aiNotes = aiNotes; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    // Explicit name on both accessors: the "is"-prefixed getter would otherwise make
    // Jackson infer the JSON property name as "global" (not "isGlobal") for
    // serialization, while still binding deserialization to "global" via setGlobal —
    // two different property names for the same field, silently breaking every caller
    // (frontend, curl, admin panel) that sends {"isGlobal": true}.
    @JsonProperty("isGlobal")
    public boolean isGlobal() { return isGlobal; }
    @JsonProperty("isGlobal")
    public void setGlobal(boolean global) { isGlobal = global; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public Integer getRunIntervalHours() { return runIntervalHours; }
    public void setRunIntervalHours(Integer runIntervalHours) { this.runIntervalHours = runIntervalHours; }

    public String getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(String lastRunAt) { this.lastRunAt = lastRunAt; }

    public boolean isRemote() {
        return "remote".equalsIgnoreCase(schedule) || area == 113;
    }
}
