package com.hh.gui.service;

import com.hh.gui.config.AppConfig.PersonConfig;
import com.hh.gui.config.AppConfig.SearchConfig;
import com.hh.gui.model.SearchJob;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the flat list of (person, search) jobs from the YAML config.
 * Single source of truth — used by both manual API triggers and the
 * scheduler, so editing config/profiles/default.yaml affects both.
 */
@Component
public class SearchProfileFactory {

    private final List<PersonConfig> people;

    public SearchProfileFactory(List<PersonConfig> people) {
        this.people = people;
    }

    public List<SearchJob> build() {
        List<SearchJob> jobs = new ArrayList<>();
        for (PersonConfig person : people) {
            for (SearchConfig search : person.searches) {
                SearchJob job = new SearchJob();
                job.personName = person.name;
                job.searchName = search.name;
                job.city = person.city;
                job.queries = search.queries;
                job.area = search.area;
                job.schedule = search.schedule;
                job.salaryMin = search.salaryMin;
                job.excludeWords = search.excludeWords;
                job.priorityDistricts = search.priorityDistricts;
                job.skills = search.skills;
                job.notSuitable = search.notSuitable;
                job.aiNotes = search.aiNotes;
                jobs.add(job);
            }
        }
        return jobs;
    }
}
