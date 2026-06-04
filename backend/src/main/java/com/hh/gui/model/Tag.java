package com.hh.gui.model;

public class Tag {
    private Long id;
    private Long vacancyId;
    private String name;

    public Tag() {}

    public Tag(Long vacancyId, String name) {
        this.vacancyId = vacancyId;
        this.name = name;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVacancyId() { return vacancyId; }
    public void setVacancyId(Long vacancyId) { this.vacancyId = vacancyId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
