package com.hh.gui.service;

import com.hh.gui.repository.TagRepository;
import com.hh.gui.repository.VacancyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StatsService {

    private final VacancyRepository vacancyRepo;
    private final TagRepository tagRepo;

    @Autowired
    public StatsService(VacancyRepository vacancyRepo, TagRepository tagRepo) {
        this.vacancyRepo = vacancyRepo;
        this.tagRepo = tagRepo;
    }

    /** @param userId null for an admin's global view, otherwise scopes every count to that user's own vacancies. */
    public Map<String, Object> getStats(Long userId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", vacancyRepo.countTotal(userId));
        stats.put("byStatus", vacancyRepo.countByStatus(userId));
        Double avgScore = vacancyRepo.avgScoreNew(userId);
        stats.put("avgScore", avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : 0);
        Double avgSalary = vacancyRepo.avgSalaryNew(userId);
        stats.put("avgSalary", avgSalary != null ? Math.round(avgSalary) : 0);
        stats.put("appliedLast7d", vacancyRepo.countAppliedLast7Days(userId));

        List<Map<String, Object>> districts = vacancyRepo.topDistricts(10, userId);
        List<Map<String, Object>> topDistricts = new ArrayList<>();
        for (Map<String, Object> d : districts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", d.get("district"));
            item.put("count", d.get("cnt"));
            topDistricts.add(item);
        }
        stats.put("topDistricts", topDistricts);

        List<Object[]> tagCounts = tagRepo.topTags(20, userId);
        List<Map<String, Object>> topTags = new ArrayList<>();
        for (Object[] tc : tagCounts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tc[0]);
            item.put("count", tc[1]);
            topTags.add(item);
        }
        stats.put("topTags", topTags);

        stats.put("people", vacancyRepo.listPeople(userId).stream()
            .map(p -> Map.of("name", p.get("person"), "count", p.get("cnt"))).toList());
        stats.put("searches", vacancyRepo.listSearches(null, userId).stream()
            .map(s -> Map.of("name", s.get("search_name"), "count", s.get("cnt"))).toList());

        return stats;
    }
}
