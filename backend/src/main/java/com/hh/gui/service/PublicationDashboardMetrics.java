package com.hh.gui.service;

import com.hh.gui.config.SchemaMigrator;
import com.hh.gui.model.SearchKind;
import com.hh.gui.repository.PublicationMetricsRepository;
import com.hh.gui.repository.PublicationMetricsRepository.TelegramCandidate;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.util.TelegramPostParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PublicationDashboardMetrics {
    private final SchemaMigrator schema;
    private final PublicationMetricsRepository publications;
    private final SearchRepository searches;
    private final MultiGauge published;
    private final MultiGauge cohort;

    public PublicationDashboardMetrics(SchemaMigrator schema, PublicationMetricsRepository publications,
                                       SearchRepository searches, MeterRegistry meters) {
        this.schema = schema;
        this.publications = publications;
        this.searches = searches;
        this.published = MultiGauge.builder("hh_dashboard_publications")
            .description("Публикации из БД за окно. Вакансии в Telegram, отдельные посты в VK").register(meters);
        this.cohort = MultiGauge.builder("hh_dashboard_telegram_cohort")
            .description("Исходы вакансий, собранных из Telegram редакционным поиском за 7 суток").register(meters);
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT5M")
    public void refresh() {
        if (!schema.isReady()) return;
        refresh(Instant.now());
    }

    void refresh(Instant now) {
        String day = cutoff(now.minus(Duration.ofDays(1)));
        String week = cutoff(now.minus(Duration.ofDays(7)));
        var counts = publications.countSince(day, week);
        Map<String, Cohort> byChannel = new LinkedHashMap<>();
        for (var search : searches.findAllEnabled()) {
            if (search.getKind() != SearchKind.EDITORIAL || search.getTelegramChannels() == null) continue;
            for (String channel : search.getTelegramChannels()) {
                if (channel != null && !channel.isBlank()) {
                    byChannel.putIfAbsent(channel.toLowerCase(Locale.ROOT), new Cohort());
                }
            }
        }
        for (var vacancy : publications.editorialTelegramSince(week)) {
            String channel = TelegramPostParser.channelFromHhId(vacancy.hhId());
            if (channel == null) continue;
            var source = byChannel.get(channel.toLowerCase(Locale.ROOT));
            if (source != null) source.add(vacancy);
        }
        published.register(List.of(
            publication("telegram", "24h", counts.telegramDay()),
            publication("telegram", "7d", counts.telegramWeek()),
            publication("vk", "24h", counts.vkDay()),
            publication("vk", "7d", counts.vkWeek())
        ), true);
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        byChannel.forEach((channel, values) -> {
            rows.add(stage(channel, "collected", values.collected));
            rows.add(stage(channel, "evaluated", values.evaluated));
            rows.add(stage(channel, "approved", values.approved));
            rows.add(stage(channel, "fraud", values.fraud));
            rows.add(stage(channel, "published_telegram", values.telegram));
            rows.add(stage(channel, "published_vk", values.vk));
        });
        cohort.register(rows, true);
    }

    private static String cutoff(Instant time) {
        // ISO-строки в БД имеют переменную точность. Префикс включает всю граничную секунду.
        return time.truncatedTo(ChronoUnit.SECONDS).toString().substring(0, 19);
    }

    private static MultiGauge.Row<?> publication(String platform, String window, int value) {
        return MultiGauge.Row.of(Tags.of("platform", platform, "window", window), value);
    }

    private static MultiGauge.Row<?> stage(String channel, String stage, int value) {
        return MultiGauge.Row.of(Tags.of("channel", channel, "stage", stage), value);
    }

    private static final class Cohort {
        private int collected;
        private int evaluated;
        private int approved;
        private int fraud;
        private int telegram;
        private int vk;

        private void add(TelegramCandidate vacancy) {
            collected++;
            switch (vacancy.verdict()) {
                case "yes" -> { approved++; evaluated++; }
                case "no" -> evaluated++;
                case "fraud" -> { fraud++; evaluated++; }
                case null, default -> { }
            }
            if (vacancy.publishedInTelegram()) telegram++;
            if (vacancy.publishedInVk()) vk++;
        }
    }
}
