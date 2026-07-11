package com.hh.gui.service;

import com.hh.gui.model.SearchConfig;
import com.hh.gui.repository.SearchRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Регрессия на договорённость «поиск по ссылке — админская настройка»: обычный
 * пользователь не может ни задать sourceUrl/runIntervalHours при создании, ни
 * изменить (или затереть) их при обновлении — иначе его поиск попадает в
 * планировщик URL-автозапусков наравне с админскими.
 */
class SearchServiceTest {

    private static class FakeRepo extends SearchRepository {
        SearchConfig existing;
        SearchConfig written;
        FakeRepo() { super(null); }
        @Override public long countByUserId(Long userId) { return 0; }
        @Override public SearchConfig save(SearchConfig s) { written = s; return s; }
        @Override public Optional<SearchConfig> findById(Long id) { return Optional.ofNullable(existing); }
        @Override public void update(SearchConfig s) { written = s; }
    }

    private static SearchConfig searchWithUrl() {
        SearchConfig s = new SearchConfig();
        s.setName("Тест");
        s.setQueries(List.of("оператор"));
        s.setSourceUrl("https://hh.ru/search/vacancy?text=test");
        s.setRunIntervalHours(6);
        return s;
    }

    @Test
    void create_nonAdmin_dropsSourceUrlAndInterval() {
        FakeRepo repo = new FakeRepo();
        SearchConfig saved = new SearchService(repo).create(7L, searchWithUrl(), false);
        assertNull(saved.getSourceUrl());
        assertNull(saved.getRunIntervalHours());
    }

    @Test
    void create_admin_keepsSourceUrlAndInterval() {
        FakeRepo repo = new FakeRepo();
        SearchConfig saved = new SearchService(repo).create(1L, searchWithUrl(), true);
        assertEquals("https://hh.ru/search/vacancy?text=test", saved.getSourceUrl());
        assertEquals(6, saved.getRunIntervalHours());
    }

    @Test
    void update_nonAdmin_cannotSetOrWipeSourceUrl() {
        FakeRepo repo = new FakeRepo();
        SearchService svc = new SearchService(repo);

        // Не-админ пытается добавить ссылку в свой поиск — остаётся без ссылки.
        repo.existing = new SearchConfig();
        repo.existing.setUserId(7L);
        Optional<SearchConfig> updated = svc.update(5L, 7L, false, searchWithUrl());
        assertTrue(updated.isPresent());
        assertNull(updated.get().getSourceUrl());
        assertNull(updated.get().getRunIntervalHours());

        // И наоборот: существующая (админом заданная) ссылка не затирается его апдейтом.
        repo.existing = searchWithUrl();
        repo.existing.setUserId(7L);
        SearchConfig noUrl = new SearchConfig();
        noUrl.setName("Тест");
        updated = svc.update(5L, 7L, false, noUrl);
        assertTrue(updated.isPresent());
        assertEquals("https://hh.ru/search/vacancy?text=test", updated.get().getSourceUrl());
        assertEquals(6, updated.get().getRunIntervalHours());
    }

    @Test
    void update_admin_setsSourceUrlAndInterval() {
        FakeRepo repo = new FakeRepo();
        repo.existing = new SearchConfig();
        repo.existing.setUserId(7L);
        Optional<SearchConfig> updated = new SearchService(repo).update(5L, 1L, true, searchWithUrl());
        assertTrue(updated.isPresent());
        assertEquals("https://hh.ru/search/vacancy?text=test", updated.get().getSourceUrl());
        assertEquals(6, updated.get().getRunIntervalHours());
    }

    // ── поиск без запросов и без ссылки молча не находил бы ничего — запрещён ──

    @Test
    void create_withoutQueriesAndUrl_throws() {
        // Реальный случай: пользователь оставил запросы пустыми, потому что подсказка
        // называла их необязательными «при поиске по ссылке», а поля ссылки у него нет.
        SearchConfig s = new SearchConfig();
        s.setName("Пустой");
        SearchService svc = new SearchService(new FakeRepo());
        assertThrows(IllegalStateException.class, () -> svc.create(7L, s, false));

        // Для не-админа не спасает и присланная ссылка — она отбрасывается раньше.
        assertThrows(IllegalStateException.class, () -> {
            SearchConfig withUrl = searchWithUrl();
            withUrl.setQueries(List.of());
            svc.create(7L, withUrl, false);
        });
    }

    @Test
    void create_admin_urlOnlyWithoutQueries_allowed() {
        SearchConfig s = searchWithUrl();
        s.setQueries(List.of());
        SearchConfig saved = new SearchService(new FakeRepo()).create(1L, s, true);
        assertEquals("https://hh.ru/search/vacancy?text=test", saved.getSourceUrl());
    }

    @Test
    void update_cannotStripSearchToDoNothing() {
        FakeRepo repo = new FakeRepo();
        repo.existing = new SearchConfig();
        repo.existing.setUserId(7L);
        repo.existing.setQueries(List.of("оператор"));
        SearchConfig wipe = new SearchConfig();
        wipe.setName("Тест");
        wipe.setQueries(List.of());
        assertThrows(IllegalStateException.class,
            () -> new SearchService(repo).update(5L, 7L, false, wipe));
    }
}
