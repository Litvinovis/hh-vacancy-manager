package com.hh.gui.model;

/**
 * Per-user status overlay for a vacancy discovered by a global (shared) search — lets
 * each user independently mark the same shared vacancy as applied/rejected without
 * duplicating the vacancy row itself. Vacancies from personal searches never get a row
 * here; their status lives directly on the vacancy (see Vacancy.status).
 */
public class UserVacancyStatus {
    private Long userId;
    private Long vacancyId;
    private String status;
    private String rejectionReason;
    private String notes;
    private String appliedAt;
    private String updatedAt;

    public UserVacancyStatus() {}

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getVacancyId() { return vacancyId; }
    public void setVacancyId(Long vacancyId) { this.vacancyId = vacancyId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getAppliedAt() { return appliedAt; }
    public void setAppliedAt(String appliedAt) { this.appliedAt = appliedAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
