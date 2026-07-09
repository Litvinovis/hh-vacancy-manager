package com.hh.gui.model;

import java.time.Instant;

public class Vacancy {
    private Long id;
    private String hhId;
    private String title;
    private String company;
    private Integer salaryFrom;
    private Integer salaryTo;
    private String currency;
    private String address;
    private String district;
    private String url;
    private Integer aiScore;
    private String aiVerdict;
    private String aiReason;
    private String description;
    private String status;
    private String rejectionReason;
    private String notes;
    private String appliedAt;
    private String createdAt;
    private String updatedAt;
    // New fields
    private String source;
    private String sourceQuery;
    private boolean remote;
    private boolean notified;
    private String publishedAt;
    private int foundByScan; // 1 = full scan, 0 = incremental

    // Multi-profile + browser-scraper fields
    private String person;
    private String searchName;
    private String employerName;
    private boolean salaryGross;
    private String experience;
    private String employment;
    private String keySkills; // comma-joined
    private boolean trustedEmployer;
    private String validThrough;
    private String scrapeStatus; // pending / ok / failed

    // Multi-user auth fields
    private Long userId;
    private Long searchId;
    private String criteriaHash;
    private String dedupKey;

    // Default constructor
    public Vacancy() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getHhId() { return hhId; }
    public void setHhId(String hhId) { this.hhId = hhId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public Integer getSalaryFrom() { return salaryFrom; }
    public void setSalaryFrom(Integer salaryFrom) { this.salaryFrom = salaryFrom; }

    public Integer getSalaryTo() { return salaryTo; }
    public void setSalaryTo(Integer salaryTo) { this.salaryTo = salaryTo; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public Integer getAiScore() { return aiScore; }
    public void setAiScore(Integer aiScore) { this.aiScore = aiScore; }

    public String getAiVerdict() { return aiVerdict; }
    public void setAiVerdict(String aiVerdict) { this.aiVerdict = aiVerdict; }

    public String getAiReason() { return aiReason; }
    public void setAiReason(String aiReason) { this.aiReason = aiReason; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getAppliedAt() { return appliedAt; }
    public void setAppliedAt(String appliedAt) { this.appliedAt = appliedAt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceQuery() { return sourceQuery; }
    public void setSourceQuery(String sourceQuery) { this.sourceQuery = sourceQuery; }

    public boolean isRemote() { return remote; }
    public void setRemote(boolean remote) { this.remote = remote; }

    public boolean isNotified() { return notified; }
    public void setNotified(boolean notified) { this.notified = notified; }

    public String getPublishedAt() { return publishedAt; }
    public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }

    public int getFoundByScan() { return foundByScan; }
    public void setFoundByScan(int foundByScan) { this.foundByScan = foundByScan; }

    public String getPerson() { return person; }
    public void setPerson(String person) { this.person = person; }

    public String getSearchName() { return searchName; }
    public void setSearchName(String searchName) { this.searchName = searchName; }

    public String getEmployerName() { return employerName; }
    public void setEmployerName(String employerName) { this.employerName = employerName; }

    public boolean isSalaryGross() { return salaryGross; }
    public void setSalaryGross(boolean salaryGross) { this.salaryGross = salaryGross; }

    public String getExperience() { return experience; }
    public void setExperience(String experience) { this.experience = experience; }

    public String getEmployment() { return employment; }
    public void setEmployment(String employment) { this.employment = employment; }

    public String getKeySkills() { return keySkills; }
    public void setKeySkills(String keySkills) { this.keySkills = keySkills; }

    public boolean isTrustedEmployer() { return trustedEmployer; }
    public void setTrustedEmployer(boolean trustedEmployer) { this.trustedEmployer = trustedEmployer; }

    public String getValidThrough() { return validThrough; }
    public void setValidThrough(String validThrough) { this.validThrough = validThrough; }

    public String getScrapeStatus() { return scrapeStatus; }
    public void setScrapeStatus(String scrapeStatus) { this.scrapeStatus = scrapeStatus; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getSearchId() { return searchId; }
    public void setSearchId(Long searchId) { this.searchId = searchId; }

    public String getCriteriaHash() { return criteriaHash; }
    public void setCriteriaHash(String criteriaHash) { this.criteriaHash = criteriaHash; }

    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
}
