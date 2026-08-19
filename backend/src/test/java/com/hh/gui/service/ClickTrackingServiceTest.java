package com.hh.gui.service;

import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClickTrackingServiceTest {

    static class FakeRepo extends VacancyRepository {
        final Map<Long, String> tokens = new HashMap<>();
        final java.util.List<Long> clicksRecorded = new java.util.ArrayList<>();
        FakeRepo() { super(null); }
        @Override
        public void setClickToken(Long id, String token) { tokens.put(id, token); }
        @Override
        public Optional<Vacancy> findByClickToken(String token) {
            return tokens.entrySet().stream()
                .filter(e -> e.getValue().equals(token))
                .findFirst()
                .map(e -> {
                    Vacancy v = new Vacancy();
                    v.setId(e.getKey());
                    v.setUrl("https://ufa.hh.ru/vacancy/123456789");
                    v.setSearchName("Без техстека");
                    return v;
                });
        }
        @Override
        public void recordClick(Long vacancyId) { clicksRecorded.add(vacancyId); }
    }

    private ClickTrackingService service(FakeRepo repo, String baseUrl) throws Exception {
        ClickTrackingService svc = new ClickTrackingService(repo, new SimpleMeterRegistry());
        Field f = ClickTrackingService.class.getDeclaredField("publicBaseUrl");
        f.setAccessible(true);
        f.set(svc, baseUrl);
        return svc;
    }

    @Test
    void trackingUrl_disabledWithoutBaseUrl_returnsEmpty() throws Exception {
        ClickTrackingService svc = service(new FakeRepo(), "");
        Vacancy v = new Vacancy();
        v.setId(1L);

        assertTrue(svc.trackingUrl(v).isEmpty());
        assertFalse(svc.isEnabled());
    }

    @Test
    void trackingUrl_generatesAndPersistsTokenOnFirstUse() throws Exception {
        FakeRepo repo = new FakeRepo();
        ClickTrackingService svc = service(repo, "https://vacancies.example.com");
        Vacancy v = new Vacancy();
        v.setId(42L);

        Optional<String> url = svc.trackingUrl(v);

        assertTrue(url.isPresent());
        assertTrue(url.get().startsWith("https://vacancies.example.com/go/"));
        assertNotNull(v.getClickToken(), "токен должен быть проставлен на самом объекте, не только в БД");
        assertEquals(v.getClickToken(), repo.tokens.get(42L));
    }

    @Test
    void trackingUrl_existingToken_reusedNotRegenerated() throws Exception {
        FakeRepo repo = new FakeRepo();
        ClickTrackingService svc = service(repo, "https://vacancies.example.com");
        Vacancy v = new Vacancy();
        v.setId(7L);
        v.setClickToken("already-set-token");

        String url = svc.trackingUrl(v).orElseThrow();

        assertTrue(url.endsWith("/go/already-set-token"));
        assertTrue(repo.tokens.isEmpty(), "уже существующий токен не должен вызывать повторную запись в БД");
    }

    @Test
    void trackingUrl_stripsTrailingSlashFromBaseUrl() throws Exception {
        FakeRepo repo = new FakeRepo();
        ClickTrackingService svc = service(repo, "https://vacancies.example.com/");
        Vacancy v = new Vacancy();
        v.setId(1L);

        String url = svc.trackingUrl(v).orElseThrow();

        assertFalse(url.contains("//go/"), "не должно быть двойного слэша между базовым URL и /go/");
    }

    @Test
    void resolveAndRecordClick_knownToken_recordsClickAndReturnsRealUrl() throws Exception {
        FakeRepo repo = new FakeRepo();
        ClickTrackingService svc = service(repo, "https://vacancies.example.com");
        Vacancy v = new Vacancy();
        v.setId(5L);
        String token = svc.trackingUrl(v).orElseThrow().substring("https://vacancies.example.com/go/".length());

        Optional<String> resolved = svc.resolveAndRecordClick(token);

        assertEquals(Optional.of("https://ufa.hh.ru/vacancy/123456789"), resolved);
        assertEquals(java.util.List.of(5L), repo.clicksRecorded);
    }

    @Test
    void resolveAndRecordClick_unknownToken_returnsEmpty_noClickRecorded() throws Exception {
        FakeRepo repo = new FakeRepo();
        ClickTrackingService svc = service(repo, "https://vacancies.example.com");

        Optional<String> resolved = svc.resolveAndRecordClick("nonexistent-token");

        assertTrue(resolved.isEmpty());
        assertTrue(repo.clicksRecorded.isEmpty());
    }
}
