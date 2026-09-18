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
     *                Also gates URL-based discovery (sourceUrl/runIntervalHours) and the
     *                Telegram publishing overrides (chatId/publicFormat/delayedChatId/
     *                delayedPublishMinutes/subscriberFeed/publishPaceMinutes) and
     *                runPriority (pipeline run order) — admin-only features, a
     *                non-admin's values are silently dropped.
     *                Global searches are exempt from MAX_SEARCHES_PER_USER — they're
     *                shared, admin-managed resources, not part of anyone's personal quota.
     */

    /**
     * Стоп-слова из самой ссылки hh (параметр excluded_text) — если у поиска свои не заданы.
     * hh отсекает по своим полям, приложение — по названию и работодателю, поэтому списки
     * полезно держать одинаковыми; 18.09.2026 это делалось копипастой руками, и разъехаться
     * им ничего не мешало. Заданные вручную слова не трогаем: ссылку меняют чаще, чем правила.
     */
    static List<String> excludeWordsFromUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) return List.of();
        try {
            for (String pair : new java.net.URI(sourceUrl).getRawQuery() == null
                    ? new String[0] : new java.net.URI(sourceUrl).getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2 || !"excluded_text".equals(kv[0])) continue;
                String decoded = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                return java.util.Arrays.stream(decoded.split(","))
                    .map(String::trim)
                    .filter(w -> !w.isEmpty())
                    .toList();
            }
        } catch (Exception e) {
            log.warn("Не удалось разобрать excluded_text из ссылки поиска: {}", e.getMessage());
        }
        return List.of();
    }

    /** Заполняет стоп-слова из ссылки, когда свои не заданы. Возвращает то, что нужно сохранить. */
    private void adoptExcludeWordsFromUrl(SearchConfig search) {
        if (search.getExcludeWords() != null && !search.getExcludeWords().isEmpty()) return;
        List<String> fromUrl = excludeWordsFromUrl(search.getSourceUrl());
        if (fromUrl.isEmpty()) return;
        search.setExcludeWords(fromUrl);
        log.info("Поиск «{}»: стоп-слова взяты из ссылки hh ({} шт.)", search.getName(), fromUrl.size());
    }

    public SearchConfig create(Long userId, SearchConfig search, boolean isAdmin) {
        boolean global = isAdmin && search.isGlobal();
        if (!global && searchRepo.countByUserId(userId) >= MAX_SEARCHES_PER_USER) {
            throw new IllegalStateException("Максимум " + MAX_SEARCHES_PER_USER + " поисков на пользователя");
        }
        search.setUserId(userId);
        search.setGlobal(global);
        search.setEnabled(true);
        adoptExcludeWordsFromUrl(search);
        if (!isAdmin) {
            search.setSourceUrl(null);
            search.setRunIntervalHours(null);
            search.setChatId(null);
            search.setPublicFormat(false);
            search.setDelayedChatId(null);
            search.setDelayedPublishMinutes(null);
            search.setSubscriberFeed(false);
            search.setPublishPaceMinutes(null);
            search.setRunPriority(0);
        }
        requireDiscoverySource(search, isAdmin);
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
        if (isAdmin) {
            // URL-based discovery and the Telegram publishing overrides are admin-only:
            // a non-admin's update keeps whatever is already stored instead of accepting
            // (or wiping) them.
            existing.setSourceUrl(updates.getSourceUrl());
            existing.setRunIntervalHours(updates.getRunIntervalHours());
            existing.setChatId(updates.getChatId());
            existing.setPublicFormat(updates.isPublicFormat());
            existing.setDelayedChatId(updates.getDelayedChatId());
            existing.setDelayedPublishMinutes(updates.getDelayedPublishMinutes());
            existing.setSubscriberFeed(updates.isSubscriberFeed());
            existing.setPublishPaceMinutes(updates.getPublishPaceMinutes());
            existing.setRunPriority(updates.getRunPriority());
            existing.setTelegramChannels(updates.getTelegramChannels());
            adoptExcludeWordsFromUrl(existing);
        }
        if (updates.isEnabled() != existing.isEnabled()) {
            existing.setEnabled(updates.isEnabled());
        }
        requireDiscoverySource(existing, isAdmin);
        searchRepo.update(existing);
        return Optional.of(existing);
    }

    /**
     * A search with neither queries nor a sourceUrl silently collects nothing forever
     * (real case: a user left queries blank because the hint said they're optional
     * "when searching by link" — a link they had no field for). Validates the final
     * state, so an update can't strip a search down to a do-nothing one either.
     */
    private static void requireDiscoverySource(SearchConfig search, boolean isAdmin) {
        boolean hasQueries = search.getQueries() != null && !search.getQueries().isEmpty();
        boolean hasUrl = search.getSourceUrl() != null && !search.getSourceUrl().isBlank();
        if (!hasQueries && !hasUrl) {
            throw new IllegalStateException(isAdmin
                ? "Укажите поисковые запросы или ссылку на поиск hh.ru"
                : "Укажите хотя бы один поисковый запрос");
        }
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
