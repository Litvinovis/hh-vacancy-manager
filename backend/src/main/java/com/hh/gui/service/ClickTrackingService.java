package com.hh.gui.service;

import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Backs the /go/{token} click-tracking redirect (see ClickTrackingController) —
 * how many readers actually followed a post's "Откликнуться" link, not just how
 * many saw the post (views/reactions, already tracked — see ChannelEngagementTracker).
 *
 * Inert without app.public-base-url configured (empty by default): there is
 * currently no public domain hh-gui is reachable at from outside the LAN, so a
 * /go/{token} link would be a dead end for a real Telegram reader. {@link
 * #trackingUrl} is NOT yet wired into VacancyPostFormatter/ChannelPublisher's
 * actual post-sending call sites — that's the one remaining connecting step,
 * deliberately left for once a domain exists, so this doesn't ship a link readers
 * can't actually follow.
 */
@Service
public class ClickTrackingService {

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    private final VacancyRepository vacancyRepo;
    private final MeterRegistry registry;
    private final SecureRandom random = new SecureRandom();

    public ClickTrackingService(VacancyRepository vacancyRepo, MeterRegistry registry) {
        this.vacancyRepo = vacancyRepo;
        this.registry = registry;
    }

    public boolean isEnabled() {
        return publicBaseUrl != null && !publicBaseUrl.isBlank();
    }

    /**
     * The /go/{token} URL to use in place of a vacancy's real destination — generates
     * and persists a token on first use (most rows never need one, so this is lazy,
     * not done at save time). Empty when app.public-base-url isn't configured, so a
     * caller can fall back to the plain external URL as if this service didn't exist.
     */
    public Optional<String> trackingUrl(Vacancy v) {
        if (!isEnabled()) return Optional.empty();
        String token = v.getClickToken();
        if (token == null || token.isBlank()) {
            token = generateToken();
            vacancyRepo.setClickToken(v.getId(), token);
            v.setClickToken(token);
        }
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return Optional.of(base + "/go/" + token);
    }

    /** Resolves a /go/{token} hit to the vacancy's real URL and records the click —
     *  empty if the token is unknown (stale or tampered link, not guessed at). */
    public Optional<String> resolveAndRecordClick(String token) {
        Optional<Vacancy> found = vacancyRepo.findByClickToken(token);
        if (found.isEmpty()) return Optional.empty();
        Vacancy v = found.get();
        vacancyRepo.recordClick(v.getId());
        String search = v.getSearchName() != null ? v.getSearchName() : "";
        registry.counter("vacancy_click_total", "application", "hh-gui", "search", search).increment();
        return Optional.ofNullable(v.getUrl());
    }

    private String generateToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
