package com.hh.gui.controller;

import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import com.hh.gui.service.ClickRateLimiter;
import com.hh.gui.service.ClickTrackingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
        return new ClickTrackingController(svc, new ClickRateLimiter());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.10");
        return req;
    }

    @Test
    void redirect_knownToken_returns302WithLocation() throws Exception {
        var response = controller().redirect("good-token", request());

        assertEquals(302, response.getStatusCode().value());
        assertEquals("https://ufa.hh.ru/vacancy/123456789", response.getHeaders().getFirst("Location"));
    }

    @Test
    void redirect_unknownToken_returns404() throws Exception {
        var response = controller().redirect("bad-token", request());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void redirect_rateLimitExceeded_returns429WithoutTouchingRepo() throws Exception {
        ClickTrackingController controller = controller();
        MockHttpServletRequest req = request();

        for (int i = 0; i < ClickRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            controller.redirect("good-token", req);
        }
        var response = controller.redirect("good-token", req);

        assertEquals(429, response.getStatusCode().value());
    }

    @Test
    void redirect_differentIps_rateLimitedIndependently() throws Exception {
        ClickTrackingController controller = controller();
        MockHttpServletRequest reqA = request();
        MockHttpServletRequest reqB = new MockHttpServletRequest();
        reqB.setRemoteAddr("203.0.113.99");

        for (int i = 0; i < ClickRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            controller.redirect("good-token", reqA);
        }
        var response = controller.redirect("good-token", reqB);

        assertEquals(302, response.getStatusCode().value(), "лимит одного IP не должен затрагивать другой");
    }
}
