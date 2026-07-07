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
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        List<SearchConfig> searches = searchRepo.findAllEnabled();
        // One bulk load instead of a findById() per search — this runs on every manual
        // pipeline trigger plus GET /api/pipeline/jobs (called on every frontend page
        // load), so avoiding N+1 here isn't just tidiness, it's N+1 on a hot path.
        Map<Long, User> usersById = userRepo.findAll().stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));

        List<SearchJob> jobs = new ArrayList<>();
        for (SearchConfig search : searches) {
            User user = usersById.get(search.getUserId());
            if (user == null || !user.isActive()) {
                log.warn("Поиск '{}' (id={}) ссылается на несуществующего/неактивного пользователя, пропущен",
                    search.getName(), search.getId());
                continue;
            }
            jobs.add(toJob(search, user));
        }
        return jobs;
    }

    /**
     * One job for a single search id, regardless of its enabled flag — used by the
     * manual "discover from URL" trigger, where the caller explicitly picked this
     * search. Empty if the search or its owning user don't exist, or the user is
     * inactive.
     */
    public Optional<SearchJob> buildForSearchId(Long searchId) {
        Optional<SearchConfig> searchOpt = searchRepo.findById(searchId);
        if (searchOpt.isEmpty()) return Optional.empty();
        SearchConfig search = searchOpt.get();
        Optional<User> userOpt = userRepo.findById(search.getUserId());
        if (userOpt.isEmpty() || !userOpt.get().isActive()) return Optional.empty();
        return Optional.of(toJob(search, userOpt.get()));
    }

    private SearchJob toJob(SearchConfig search, User user) {
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
        return job;
    }
}
