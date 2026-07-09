package com.hh.gui.model;

import java.util.List;

/**
 * One independently-configured (person, search) unit of work: its own query
 * list for collection and its own criteria + free-text notes for AI scoring.
 * Replaces the old single-profile-per-person model, which couldn't express
 * e.g. "remote across Russia — interestingness matters a lot" vs "near home —
 * interestingness matters little" for the same person.
 */
public class SearchJob {
    public Long userId;
    public Long searchId;
    public String personName;
    public String searchName;
    public String city;
    public String experienceSummary;
    public List<String> queries;
    public int area;
    public String schedule;
    public int salaryMin;
    public List<String> excludeWords;
    public List<String> priorityDistricts;
    public List<String> skills;
    public List<String> notSuitable;
    public String aiNotes;
    public boolean isGlobal;
    public String sourceUrl;
    public Integer runIntervalHours;
    public String lastRunAt;

    public boolean isRemote() {
        return "remote".equalsIgnoreCase(schedule) || area == 113;
    }
}
