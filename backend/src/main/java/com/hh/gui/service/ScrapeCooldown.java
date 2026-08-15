package com.hh.gui.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Freeze switch for hh.ru scraping after the site starts refusing us.
 *
 * Shared deliberately: both the scraping step and URL-based discovery hit hh.ru with
 * a browser, so a block discovered by one must stop the other too. It lived inside
 * VacancyPipelineService until discovery was split out, at which point "who owns this
 * state" needed a real answer — it's the scraper's state, not the pipeline's.
 *
 * Singleton scope is load-bearing, not incidental: the whole point is that concurrent
 * runs share one view of whether we're currently blocked.
 */
@Service
public class ScrapeCooldown {

    private static final Logger log = LoggerFactory.getLogger(ScrapeCooldown.class);

    // Backing off for half an hour on the first block and doubling from there: hammering
    // a site that just refused us is exactly what a bot does. Capped so a bad night
    // can't freeze scraping for days.
    private static final long BASE_MS = 30L * 60 * 1000;
    private static final long MAX_MS = 4L * 60 * 60 * 1000;

    private volatile long cooldownUntil = 0;
    private int strikes = 0;

    public boolean isCoolingDown() {
        return System.currentTimeMillis() < cooldownUntil;
    }

    /** Minutes still left on the freeze, 0 when not cooling down — for operator-facing logs. */
    public long remainingMinutes() {
        return Math.max(0, (cooldownUntil - System.currentTimeMillis()) / 60000);
    }

    public synchronized void enter() {
        // Different searches run concurrently (see runFullPipeline's per-job lock), so
        // one real hh.ru block can be discovered independently by several in-flight runs
        // within the same second — without this guard each of them struck the counter,
        // jumping straight to a multi-hour freeze for what was a single event.
        if (isCoolingDown()) return;
        strikes++;
        long cooldown = Math.min(BASE_MS << (strikes - 1), MAX_MS);
        cooldownUntil = System.currentTimeMillis() + cooldown;
        log.warn("Скрейпинг заморожен на {} мин (подряд блокировок: {})", cooldown / 60000, strikes);
    }

    public synchronized void onSuccess() {
        strikes = 0;
    }
}
