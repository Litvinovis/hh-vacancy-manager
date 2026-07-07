package com.hh.gui.service;

import com.hh.gui.config.AppConfig;
import com.hh.gui.config.AppConfig.SearchEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a VacancyPipelineService.SearchProfile from the YAML profile config.
 * Single source of truth for search queries — used by both manual API triggers
 * and the scheduled pipeline, so editing config/profiles/default.yaml affects both.
 */
@Component
public class SearchProfileFactory {

    private final AppConfig.SearchProfile yamlProfile;

    public SearchProfileFactory(AppConfig.SearchProfile yamlProfile) {
        this.yamlProfile = yamlProfile;
    }

    public VacancyPipelineService.SearchProfile build() {
        VacancyPipelineService.SearchProfile sp = new VacancyPipelineService.SearchProfile();
        sp.city = yamlProfile.getCity();

        List<VacancyPipelineService.SearchQuery> allQueries = new ArrayList<>();
        for (SearchEntry entry : yamlProfile.getSearches()) {
            boolean remote = entry.isRemote();
            for (String query : entry.queries) {
                VacancyPipelineService.SearchQuery sq = new VacancyPipelineService.SearchQuery();
                sq.query = query;
                sq.area = entry.area;
                sq.schedule = entry.schedule;
                sq.salaryMin = entry.salaryMin;
                sq.isRemote = remote;
                sq.excludeWords = entry.excludeWords;
                allQueries.add(sq);
            }
        }
        sp.queries = allQueries;

        Map<String, Object> data = yamlProfile.getData();
        @SuppressWarnings("unchecked")
        List<String> priorityDistricts = (List<String>) data.getOrDefault(
            "priority_districts", List.of("Шакша", "Калининский"));
        sp.priorityDistricts = priorityDistricts;

        @SuppressWarnings("unchecked")
        List<String> skills = (List<String>) data.getOrDefault(
            "skills", List.of("Работа с клиентами", "Касса", "Консультирование"));
        sp.skills = skills;

        @SuppressWarnings("unchecked")
        List<String> notSuitable = (List<String>) data.getOrDefault(
            "not_suitable", List.of("Физический труд", "Кол-центр", "Вахта", "Склад", "Производство", "Супермаркет"));
        sp.notSuitable = notSuitable;

        return sp;
    }
}
