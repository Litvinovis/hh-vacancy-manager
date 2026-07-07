package com.hh.gui.service;

import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.User;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the flat list of (user, search) jobs from the DB — single source of
 * truth used by both manual API triggers and the scheduler. DB-driven (not an
 * injected startup-time bean like the old YAML version) so a search added or
 * edited through the personal cabinet takes effect on the next pipeline run,
 * no restart required.
 */
@Component
public class SearchProfileFactory {

    private static final Logger log = LoggerFactory.getLogger(SearchProfileFactory.class);

    private final SearchRepository searchRepo;
    private final UserRepository userRepo;

    public SearchProfileFactory(SearchRepository searchRepo, UserRepository userRepo) {
        this.searchRepo = searchRepo;
        this.userRepo = userRepo;
    }

    public List<SearchJob> build() {
        List<SearchJob> jobs = new ArrayList<>();
        for (SearchConfig search : searchRepo.findAllEnabled()) {
            Optional<User> userOpt = userRepo.findById(search.getUserId());
            if (userOpt.isEmpty() || !userOpt.get().isActive()) {
                log.warn("Поиск '{}' (id={}) ссылается на несуществующего/неактивного пользователя, пропущен",
                    search.getName(), search.getId());
                continue;
            }
            User user = userOpt.get();

            SearchJob job = new SearchJob();
            job.userId = user.getId();
            job.searchId = search.getId();
            job.personName = user.getDisplayName();
            job.searchName = search.getName();
            job.city = user.getCity();
            job.experienceSummary = user.getExperienceSummary();
            job.queries = search.getQueries();
            job.area = search.getArea();
            job.schedule = search.getSchedule();
            job.salaryMin = search.getSalaryMin();
            job.excludeWords = search.getExcludeWords();
            job.priorityDistricts = search.getPriorityDistricts();
            job.skills = search.getSkills();
            job.notSuitable = search.getNotSuitable();
            job.aiNotes = search.getAiNotes();
            jobs.add(job);
        }
        return jobs;
    }
}
