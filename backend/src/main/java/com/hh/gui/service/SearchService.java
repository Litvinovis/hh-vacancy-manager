package com.hh.gui.service;

import com.hh.gui.model.SearchConfig;
import com.hh.gui.repository.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_SEARCHES_PER_USER = 3;

    private final SearchRepository searchRepo;

    public SearchService(SearchRepository searchRepo) {
        this.searchRepo = searchRepo;
    }

    public List<SearchConfig> listForUser(Long userId) {
        return searchRepo.findByUserId(userId);
    }

    public SearchConfig create(Long userId, SearchConfig search) {
        if (searchRepo.countByUserId(userId) >= MAX_SEARCHES_PER_USER) {
            throw new IllegalStateException("Максимум " + MAX_SEARCHES_PER_USER + " поисков на пользователя");
        }
        search.setUserId(userId);
        search.setEnabled(true);
        SearchConfig saved = searchRepo.save(search);
        log.info("Создан поиск '{}' для user_id={}", saved.getName(), userId);
        return saved;
    }

    /** @return empty if the search doesn't exist or isn't owned by userId (unless isAdmin). */
    public Optional<SearchConfig> update(Long id, Long userId, boolean isAdmin, SearchConfig updates) {
        Optional<SearchConfig> existingOpt = searchRepo.findById(id);
        if (existingOpt.isEmpty()) return Optional.empty();
        SearchConfig existing = existingOpt.get();
        if (!isAdmin && !existing.getUserId().equals(userId)) return Optional.empty();

        existing.setName(updates.getName());
        existing.setQueries(updates.getQueries());
        existing.setArea(updates.getArea());
        existing.setSchedule(updates.getSchedule());
        existing.setSalaryMin(updates.getSalaryMin());
        existing.setPriorityDistricts(updates.getPriorityDistricts());
        existing.setSkills(updates.getSkills());
        existing.setNotSuitable(updates.getNotSuitable());
        existing.setExcludeWords(updates.getExcludeWords());
        existing.setAiNotes(updates.getAiNotes());
        if (updates.isEnabled() != existing.isEnabled()) {
            existing.setEnabled(updates.isEnabled());
        }
        searchRepo.update(existing);
        return Optional.of(existing);
    }

    /** @return false if the search doesn't exist or isn't owned by userId (unless isAdmin). */
    public boolean delete(Long id, Long userId, boolean isAdmin) {
        Optional<SearchConfig> existingOpt = searchRepo.findById(id);
        if (existingOpt.isEmpty()) return false;
        if (!isAdmin && !existingOpt.get().getUserId().equals(userId)) return false;
        searchRepo.delete(id);
        return true;
    }
}
