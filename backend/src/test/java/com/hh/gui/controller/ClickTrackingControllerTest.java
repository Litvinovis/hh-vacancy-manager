package com.hh.gui.controller;

import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.service.ClickTrackingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClickTrackingControllerTest {

    static class FakeRepo extends VacancyRepository {
        FakeRepo() { super(null); }
        @Override
        public Optional<Vacancy> findByClickToken(String token) {
            if (!"good-token".equals(token)) return Optional.empty();
            Vacancy v = new Vacancy();
            v.setId(1L);
            v.setUrl("https://ufa.hh.ru/vacancy/123456789");
            return Optional.of(v);
        }
        @Override
        public void recordClick(Long vacancyId) {}
    }

    private ClickTrackingController controller() throws Exception {
        ClickTrackingService svc = new ClickTrackingService(new FakeRepo(), new SimpleMeterRegistry());
        Field f = ClickTrackingService.class.getDeclaredField("publicBaseUrl");
        f.setAccessible(true);
        f.set(svc, "https://vacancies.example.com");
        return new ClickTrackingController(svc);
    }

    @Test
    void redirect_knownToken_returns302WithLocation() throws Exception {
        var response = controller().redirect("good-token");

        assertEquals(302, response.getStatusCode().value());
        assertEquals("https://ufa.hh.ru/vacancy/123456789", response.getHeaders().getFirst("Location"));
    }

    @Test
    void redirect_unknownToken_returns404() throws Exception {
        var response = controller().redirect("bad-token");

        assertEquals(404, response.getStatusCode().value());
    }
}
