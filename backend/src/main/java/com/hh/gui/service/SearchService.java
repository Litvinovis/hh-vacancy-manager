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

    /**
     * @param isAdmin gates search.isGlobal(): a non-admin's request is always forced
     *                to a personal (non-global) search regardless of what it asked for.
     *                Global searches are exempt from MAX_SEARCHES_PER_USER — they're
     *                shared, admin-managed resources, not part of anyone's personal quota.
     */
    public SearchConfig create(Long userId, SearchConfig search, boolean isAdmin) {
        boolean global = isAdmin && search.isGlobal();
        if (!global && searchRepo.countByUserId(userId) >= MAX_SEARCHES_PER_USER) {
            throw new IllegalStateException("Максимум " + MAX_SEARCHES_PER_USER + " поисков на пользователя");
        }
        search.setUserId(userId);
        search.setGlobal(global);
        search.setEnabled(true);
        SearchConfig saved = searchRepo.save(search);
        log.info("Создан {}поиск '{}' для user_id={}", global ? "общий " : "", saved.getName(), userId);
        return saved;
    }

    public List<SearchConfig> listGlobal() {
        return searchRepo.findAllGlobal();
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
        existing.setSourceUrl(updates.getSourceUrl());
        existing.setRunIntervalHours(updates.getRunIntervalHours());
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
