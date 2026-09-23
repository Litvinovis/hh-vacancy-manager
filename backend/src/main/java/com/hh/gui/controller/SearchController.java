package com.hh.gui.controller;

import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.User;
import com.hh.gui.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Personal-cabinet search CRUD — always scoped to the logged-in user (from
 * AuthInterceptor's "currentUser" request attribute), never a client-supplied
 * id, so a non-admin can only ever see/edit their own searches.
 */
@RestController
@RequestMapping("/api/searches")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public List<SearchConfig> list(@RequestAttribute("currentUser") User currentUser) {
        return searchService.listForUser(currentUser.getId());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SearchConfig search, @RequestAttribute("currentUser") User currentUser) {
        if (search.isGlobal() && !currentUser.isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "Только администратор может создавать общие поиски"));
        }
        try {
            return ResponseEntity.ok(searchService.create(currentUser.getId(), search, currentUser.isAdmin()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }

    private final tools.jackson.databind.ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
        .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /**
     * Тело читается как Map, чтобы знать, какие поля клиент прислал на самом деле. Личный
     * кабинет отправляет только свои поля, а сервис перезаписывал и админские настройки
     * публикации — одно сохранение общего поиска в интерфейсе стирало Telegram-каналы,
     * темп очереди канала и публичный формат (найдено 24.09.2026).
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                     @RequestAttribute("currentUser") User currentUser) {
        Optional<SearchConfig> updated;
        try {
            SearchConfig search = mapper.convertValue(body, SearchConfig.class);
            updated = searchService.update(id, currentUser.getId(), currentUser.isAdmin(), search, body.keySet());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
        if (updated.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Поиск не найден"));
        }
        return ResponseEntity.ok(updated.get());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestAttribute("currentUser") User currentUser) {
        boolean deleted = searchService.delete(id, currentUser.getId(), currentUser.isAdmin());
        if (!deleted) {
            return ResponseEntity.status(404).body(Map.of("error", "Поиск не найден"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
