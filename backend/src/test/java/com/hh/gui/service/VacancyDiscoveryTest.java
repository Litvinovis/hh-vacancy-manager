package com.hh.gui.service;

import com.hh.gui.ai.VacancyAiAnalyzer;
import com.hh.gui.client.ScraperClient;
import com.hh.gui.client.TelegramClient;
import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.Vacancy;
import com.hh.gui.repository.VacancyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Telegram discovery: turning channel posts into saved candidates.
 *
 * Moved here when discovery was split out of VacancyPipelineService — these used to
 * build a twelve-argument pipeline to assert on what a post gets parsed into.
 */
class VacancyDiscoveryTest {

    private static Builder discovery() { return new Builder(); }

    private static final class Builder {
        private TelegramClient telegram;
        private VacancyRepository repo;
        private MeterRegistry registry = new SimpleMeterRegistry();

        private ScraperClient scraper;
        private VacancyAiAnalyzer analyzer;

        Builder telegram(TelegramClient v) { this.telegram = v; return this; }
        Builder scraper(ScraperClient v) { this.scraper = v; return this; }
        Builder analyzer(VacancyAiAnalyzer v) { this.analyzer = v; return this; }
        Builder repo(VacancyRepository v) { this.repo = v; return this; }
        Builder metricsRegistry(MeterRegistry v) { this.registry = v; return this; }

        VacancyDiscovery build() {
            TelegramMetrics tgMetrics = new TelegramMetrics(registry);
            ChannelEngagementTracker engagement = new ChannelEngagementTracker(null, telegram, null, tgMetrics);
            com.hh.gui.ai.AiMetrics aiMetrics = new com.hh.gui.ai.AiMetrics(registry, new RuntimeConfig());
            return new VacancyDiscovery(null, scraper, telegram, analyzer, repo, tgMetrics, engagement, new ScrapeCooldown(), aiMetrics);
        }
    }

    private static Vacancy vacancy(String title, String reason, int score) {
        Vacancy v = new Vacancy();
        v.setHhId("1");
        v.setTitle(title);
        v.setCompany("ООО Ромашка");
        v.setAiScore(score);
        v.setAiVerdict("yes");
        v.setAiReason(reason);
        v.setUrl("https://hh.ru/vacancy/1");
        return v;
    }

    private static SearchJob tgJob() {
        SearchJob job = new SearchJob();
        job.personName = "Все пользователи";
        job.searchName = "Без техстека";
        job.isGlobal = true;
        return job;
    }

    private static TelegramClient.TelegramMessage tgMsg(String id, String text) {
        return new TelegramClient.TelegramMessage(id, text, "2026-08-15T09:00:00.000Z",
            "https://t.me/testchan/" + id, "testchan", "telegram", null, java.util.Map.of());
    }

    private static TelegramClient.TelegramMessage tgMsg(String id, String text, Integer views, java.util.Map<String, Integer> reactions) {
        return new TelegramClient.TelegramMessage(id, text, "2026-08-15T09:00:00.000Z",
            "https://t.me/testchan/" + id, "testchan", "telegram", views, reactions);
    }

    private static class FakeTelegramClient extends TelegramClient {
        final java.util.Map<String, ChannelResult> byChannel;
        FakeTelegramClient(java.util.Map<String, ChannelResult> byChannel) { this.byChannel = byChannel; }
        @Override
        public ChannelResult fetchChannel(String username, int limit) {
            return byChannel.getOrDefault(username, new ChannelResult(true, null, List.of()));
        }
    }

    private static class FakeTgRepo extends VacancyRepository {
        final Set<String> known;
        final List<Vacancy> saved = new ArrayList<>();
        FakeTgRepo(Set<String> known) {
            super(null);
            this.known = known;
        }
        @Override
        public Set<String> findExistingHhIds(Collection<String> hhIds, String person, String searchName) {
            Set<String> result = new java.util.HashSet<>(hhIds);
            result.retainAll(known);
            return result;
        }
        @Override
        public Vacancy save(Vacancy v) {
            saved.add(v);
            return v;
        }
    }

    @Test
    void discoverFromTelegram_hhLinkInPost_goesThroughNormalHhPipelineAsPathA() {
        // Пост со ссылкой на hh.ru — это Path A: сохраняем обычный scrape-pending стаб
        // по hh_id из ссылки, а не текст поста как готовое описание.
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("1", "Менеджер по продажам\nПодробности: https://ufa.hh.ru/vacancy/123456789")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        int saved = discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals(1, saved);
        Vacancy v = repo.saved.get(0);
        assertEquals("123456789", v.getHhId(), "hh_id должен быть извлечён из ссылки в посте, а не из id сообщения");
        assertEquals("hh", v.getSource());
        assertEquals("pending", v.getScrapeStatus(), "Path A должен идти через обычный скрейпинг, не готовую вакансию");
    }

    @Test
    void discoverFromTelegram_noFirstPartyLink_savedAsOriginalTelegramContentPathB() {
        // Пост без ссылки на биржу — Path B: текст поста сам по себе источник, скрейпинг
        // не нужен (scrape_status сразу 'ok'), готов к AI-анализу.
        String text = "Оператор чата, удалённо\nЗарплата от 40000\nОбращаться в лс";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_42", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        int saved = discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals(1, saved);
        Vacancy v = repo.saved.get(0);
        assertEquals("tg_testchan_42", v.getHhId());
        assertEquals("telegram", v.getSource());
        assertEquals("ok", v.getScrapeStatus(), "Path B уже содержит весь текст — скрейпить нечего");
        assertEquals("pending", v.getAiVerdict());
        assertEquals(text, v.getDescription(), "полный текст поста должен уйти в AI-анализ как описание");
        assertFalse(v.getDedupKey().isEmpty(), "без явного работодателя dedup_key всё равно должен строиться (см. extractTgEmployer)");
    }

    @Test
    void discoverFromTelegram_pathB_recordsCollectedMetricTaggedByChannel() {
        String text = "Оператор чата, удалённо";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_42", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).metricsRegistry(registry).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals(1.0, registry.find("telegram_collected_total").tag("channel", "testchan").counter().count());
    }

    @Test
    void discoverFromTelegram_channelNameContainsUnderscores_stillParsedCorrectly() {
        // "tg_<channel>_<id>" is ambiguous to a naive split when the channel itself has
        // underscores — must not chop "rabota_is_doma_vakansii" at the first one.
        String channel = "rabota_is_doma_vakansii";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of(channel, new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_" + channel + "_777", "Оператор чата")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).metricsRegistry(registry).build();

        discovery.fromTelegram(tgJob(), List.of(channel));

        assertEquals(1.0, registry.find("telegram_collected_total").tag("channel", channel).counter().count());
    }

    @Test
    void discoverFromTelegram_pathA_doesNotRecordCollectedMetric() {
        // Path A hh_id is the real numeric hh.ru id, not "tg_<channel>_<id>" — no channel
        // to tag, and it falls into the ordinary hh.ru pipeline anyway (see
        // VacancyPipelineService.extractTgChannelFromHhId javadoc).
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("1", "Менеджер по продажам\nПодробности: https://ufa.hh.ru/vacancy/123456789")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).metricsRegistry(registry).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertTrue(registry.find("telegram_collected_total").meters().isEmpty());
    }

    @Test
    void discoverFromTelegram_recordsViewsAndReactionsAggregatedPerChannel() {
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(
                tgMsg("tg_testchan_1", "Оператор чата 1", 10, java.util.Map.of("❤", 2)),
                tgMsg("tg_testchan_2", "Оператор чата 2", 15, java.util.Map.of("❤", 1, "🔥", 3))))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).metricsRegistry(registry).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals(25.0, registry.find("telegram_channel_views_recent").tag("channel", "testchan").gauge().value(),
            "суммарные просмотры по обоим постам в этом скрейпе");
        assertEquals(3.0, registry.find("telegram_channel_reactions_recent")
            .tag("channel", "testchan").tag("emoji", "❤").gauge().value());
        assertEquals(3.0, registry.find("telegram_channel_reactions_recent")
            .tag("channel", "testchan").tag("emoji", "🔥").gauge().value());
    }

    @Test
    void discoverFromTelegram_hashtagLeadLine_titleIsTheRoleNameNotTheHashtags() {
        // Живой пример: frilanser_vacansii и похожие каналы открывают каждый пост строкой
        // хэштегов (#вакансия #smm #удаленно) — без пропуска этой строки заголовком
        // вакансии становился набор хэштегов, а не должность.
        String text = "​#вакансия #smm #онлайншкола #удаленно\n\n SMM-специалист в онлайн-школу вязания Sviteroff\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_99", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals(1, repo.saved.size());
        assertEquals("SMM-специалист в онлайн-школу вязания Sviteroff", repo.saved.get(0).getTitle());
    }

    @Test
    void discoverFromTelegram_markdownHeadingTitle_stripsHeadingAndVacancyPrefix() {
        // Живой пример: некоторые каналы форматируют первую строку как markdown-заголовок
        // с общим префиксом ("### Вакансия: Асессор") — заголовком должна остаться
        // только сама должность, без "###" и "Вакансия:".
        String text = "### Вакансия: Асессор\n\nКаждый день миллионы людей смотрят...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_100", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals(1, repo.saved.size());
        assertEquals("Асессор", repo.saved.get(0).getTitle());
    }

    @Test
    void discoverFromTelegram_titleNamesEmployer_extractsItInsteadOfChannelFallback() {
        // company идёт прямо в опубликованный пост (formatVacancyEntry) — "@channel"
        // вместо реального работодателя видит каждый читатель канала. Заголовок вида
        // "Роль в/для КомпанияName" — самый частый способ узнать работодателя без
        // явного "Компания:" в тексте.
        String text = "Брендинг-дизайнер в Emerging Travel Group\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_55", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals("Emerging Travel Group", repo.saved.get(0).getCompany());
    }

    @Test
    void discoverFromTelegram_titleHasNoNamedEmployer_fallsBackToChannel() {
        // "для международных проектов" — lowercase after "для", не похоже на название
        // компании: должен остаться безопасный fallback на канал, а не мусор вроде
        // "международных проектов" в поле работодателя.
        String text = "SMM-специалист для международных проектов\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_56", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals("@testchan", repo.saved.get(0).getCompany());
    }

    @Test
    void discoverFromTelegram_titleNamesPlatformNotCompany_fallsBackToChannel() {
        // "Менеджер по продажам в Telegram" — Telegram здесь платформа работы, а не
        // работодатель; без блэклиста это стало бы company="Telegram" для десятков
        // разных, никак не связанных вакансий.
        String text = "Менеджер по продажам в Telegram\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_57", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals("@testchan", repo.saved.get(0).getCompany());
    }

    @Test
    void discoverFromTelegram_wordCompanyWithoutColon_doesNotMisfireAsEmployerLabel() {
        // "Компания развивает собственные бренды..." — слово "компания" в обычном
        // предложении, не лейбл поля. Без требования двоеточия это захватывалось
        // regex'ом как "работодатель: развивает собственные бренды..." — бессмыслица.
        String text = "UGC-креатор в WAPS\n\nКомпания развивает собственные бренды на маркетплейсах.";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_58", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals("WAPS", repo.saved.get(0).getCompany(), "должен взять компанию из заголовка, не смысловое совпадение по слову «компания»");
    }

    @Test
    void discoverFromTelegram_sameTitleNoEmployer_sharesDedupKeyAcrossReposts() {
        // Живой пример: канал-агрегатор репостит одну и ту же вакансию несколько раз,
        // каждый раз с чуть другой формулировкой (разные markdown-заголовки, вводные
        // фразы) — line-similarity между копиями измерена ~0.53, ниже порога 0.85, так
        // что description-hash дедуп их не ловит. Без извлечённого работодателя ключ
        // должен строиться по title+channel, чтобы такие повторы схлопывались обычным
        // дедупом (dedupeByKey/findFirstScrapedByDedupKey), как раньше.
        String text1 = "Асессор\n\nКаждый день миллионы людей смотрят контент...";
        String text2 = "Асессор\n\nОбязанности:\n- Разметка контента\n- Фильтрация...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_61", text1), tgMsg("tg_testchan_62", text2)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals(2, repo.saved.size());
        assertEquals(repo.saved.get(0).getDedupKey(), repo.saved.get(1).getDedupKey(),
            "разный текст, но тот же заголовок и тот же канал без явного работодателя — один dedup_key");
    }

    @Test
    void discoverFromTelegram_organizationAsDutiesSubheading_doesNotMisfireAsEmployer() {
        // Живой пример: "Организация:" использовано как подзаголовок раздела обязанностей
        // ("организовывать процесс"), а не как метка "название организации-работодателя".
        // "организация" убрана из ключевых слов, и на всякий случай ловим ещё и общий
        // признак — захваченное значение начинается с маркера списка ("—").
        String text = "Ищу ассистента с навыками создания контента\n\n"
            + "Обязанности:\n— вести соцсети;\n\nОрганизация:\n— ставить задачи и контролировать выполнение;\n— следить за дедлайнами;";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_63", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals("@testchan", repo.saved.get(0).getCompany(),
            "не должен принять фрагмент списка обязанностей за название работодателя");
    }

    @Test
    void discoverFromTelegram_labeledSalary_extractsFromAndCurrency() {
        String text = "SMM-специалист\n\nЗаработная плата от 40000 рублей\n\nЗадачи: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_70", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        Vacancy v = repo.saved.get(0);
        assertEquals(40000, v.getSalaryFrom());
        assertNull(v.getSalaryTo());
        assertEquals("RUR", v.getCurrency());
    }

    @Test
    void discoverFromTelegram_bareSalaryRangeNearTitle_extractsFromAndTo() {
        String text = "Асессор\n60 000 – 250 000 ₽\n\nОбязанности: ...";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_71", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        Vacancy v = repo.saved.get(0);
        assertEquals(60000, v.getSalaryFrom());
        assertEquals(250000, v.getSalaryTo());
    }

    @Test
    void discoverFromTelegram_bareNumberDeepInText_notMistakenForSalary() {
        // "350₽" встречается в конце поста как цена продвижения самого поста в канале —
        // это НЕ зарплата вакансии; вне первых SALARY_SCAN_LINES строк и без метки не
        // должно приниматься, лучше "не указана", чем неверная цифра.
        String text = "Оператор чата\n\nОбязанности: отвечать на сообщения\n\nТребования: без опыта\n\n"
            + "Реклама: 2 часа в топ - 350₽";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_72", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        Vacancy v = repo.saved.get(0);
        assertNull(v.getSalaryFrom());
        assertNull(v.getSalaryTo());
    }

    @Test
    void discoverFromTelegram_noSalaryMentioned_leavesSalaryUnset() {
        String text = "Копирайтер\n\nОбязанности: пишет тексты\n\nОплата по договорённости";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_73", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        Vacancy v = repo.saved.get(0);
        assertNull(v.getSalaryFrom());
        assertNull(v.getSalaryTo());
    }

    @Test
    void discoverFromTelegram_pathA_urlIsRealHhVacancyLinkNotTelegramPost() {
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("1", "Менеджер по продажам\nПодробности: https://ufa.hh.ru/vacancy/123456789")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals("https://ufa.hh.ru/vacancy/123456789", repo.saved.get(0).getUrl());
    }

    @Test
    void discoverFromTelegram_pathB_externalLinkPreferredOverTelegramPostLink() {
        // Живой пример: канал-агрегатор (kadrout) постит только тизер со ссылкой на
        // полное описание на своём сайте — не job-борд, который мы умеем скрейпить
        // (остаётся Path B), но ссылка полезнее для читателя, чем усечённый tg-пост.
        String text = "SEO-копирайтер\n\nОбязанности: ...\n\nПосмотреть вакансию полностью: https://kadrout.ru/vacancies/38184/seo?utm_source=tg.";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_80", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals("https://kadrout.ru/vacancies/38184/seo?utm_source=tg", repo.saved.get(0).getUrl(),
            "внешняя ссылка на первоисточник предпочтительнее ссылки на сам telegram-пост, конечная точка обрезана как пунктуация");
    }

    @Test
    void discoverFromTelegram_pathB_noExternalLink_fallsBackToTelegramPostLink() {
        String text = "Копирайтер\n\nОбязанности: пишет тексты\n\nПишите в лс";
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_81", text)))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals("https://t.me/testchan/tg_testchan_81", repo.saved.get(0).getUrl());
    }

    @Test
    void discoverFromTelegram_excludeWordMatch_dropsCandidateBeforeSaving() {
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("1", "Риэлтор без опыта, удалённо, доход от 100000")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of());
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();
        SearchJob job = tgJob();
        job.excludeWords = List.of("риэлтор");

        int saved = discovery.fromTelegram(job, List.of("testchan"));

        assertEquals(0, saved, "риэлторские посты должны отсеиваться так же, как для hh.ru-источника");
        assertTrue(repo.saved.isEmpty());
    }

    @Test
    void discoverFromTelegram_alreadyKnownMessageId_skipsSavingAgain() {
        FakeTelegramClient tg = new FakeTelegramClient(java.util.Map.of("testchan", new TelegramClient.ChannelResult(
            true, null, List.of(tgMsg("tg_testchan_7", "Продавец-консультант удалённо")))));
        FakeTgRepo repo = new FakeTgRepo(Set.of("tg_testchan_7"));
        VacancyDiscovery discovery = discovery().telegram(tg).repo(repo).build();

        int saved = discovery.fromTelegram(tgJob(), List.of("testchan"));

        assertEquals(0, saved, "уже сохранённое на прошлом прогоне сообщение не должно сохраняться повторно");
    }

    // ── discoverFromUrl: ранний стоп не должен терять уже собранные новые хиты ──

    private static ScraperClient.SearchHit hit(String hhId) {
        return new ScraperClient.SearchHit(hhId, "Вакансия " + hhId, "ООО Ромашка", null, null, null,
            "https://hh.ru/vacancy/" + hhId);
    }

    private static SearchJob urlJob() {
        SearchJob job = new SearchJob();
        job.personName = "Все пользователи";
        job.searchName = "Интересная удалёнка";
        job.isGlobal = true;
        return job;
    }

    /** Отдаёт заранее заданные страницы по номеру вызова (0, 1, 2...) и считает обращения. */
    private static class FakeScraper extends ScraperClient {
        final List<SearchPageResult> pages;
        int calls = 0;
        FakeScraper(RuntimeConfig config, SearchPageResult... pages) {
            super(config);
            this.pages = List.of(pages);
        }
        @Override
        public SearchPageResult searchByUrl(String url, int pageNum) {
            SearchPageResult result = pages.get(calls);
            calls++;
            return result;
        }
    }

    /** Известность по фиксированному набору hh_id; сохранённое копится в saved. */
    private static class FakeRepo extends VacancyRepository {
        final Set<String> known;
        final List<Vacancy> saved = new ArrayList<>();
        FakeRepo(Set<String> known) {
            super(null);
            this.known = known;
        }
        @Override
        public Set<String> findExistingHhIds(Collection<String> hhIds, String person, String searchName) {
            Set<String> result = new java.util.HashSet<>(hhIds);
            result.retainAll(known);
            return result;
        }
        @Override
        public Vacancy save(Vacancy v) {
            saved.add(v);
            return v;
        }
    }

    /** Прескрин «всё подходит»: пустой список вердиктов = ни одного отсева. */
    private static class FakeAnalyzer extends VacancyAiAnalyzer {
        FakeAnalyzer(RuntimeConfig config) {
            super(config, null, null);
        }
        @Override
        public List<AiResult> prescreenHits(List<ScraperClient.SearchHit> hits, SearchJob job) {
            return List.of();
        }
    }

    @Test
    void discoverFromUrl_walksAllRequestedPages_regardlessOfKnownRatio() {
        // Регрессия (версия 1, PR #45): break из середины обхода страницы выбрасывал
        // уже собранные newHits при трёх известных подряд в конце страницы.
        // Регрессия (версия 2, 2026-07-12): заменили это на стоп по доле известных
        // на всей странице — но живые данные показали, что и это не подходит для
        // этой выдачи: переопубликованные клоны перемешивают старое и новое не
        // только внутри страницы, но и между страницами, так что одна "насыщенная"
        // страница (например, 100% известных) может стоять прямо перед страницей,
        // где полно нового. Итог: пагинация всегда проходит все запрошенные страницы
        // (до MAX_URL_SEARCH_PAGES), без какой-либо остановки по доле известных —
        // каждая новая вакансия на каждой просмотренной странице сохраняется.
        RuntimeConfig config = new RuntimeConfig();
        List<ScraperClient.SearchHit> page0 = List.of(
            hit("101"), hit("901"), hit("902"), hit("903")); // 1 новая среди известных — не должно ничего останавливать
        List<ScraperClient.SearchHit> page1 = List.of(
            hit("904"), hit("905"), hit("906"), hit("907")); // страница целиком известна — раньше остановило бы пагинацию
        List<ScraperClient.SearchHit> page2 = List.of(hit("102")); // но за ней всё равно есть новое
        FakeScraper scraper = new FakeScraper(config,
            new ScraperClient.SearchPageResult(true, null, page0, null),
            new ScraperClient.SearchPageResult(true, null, page1, null),
            new ScraperClient.SearchPageResult(true, null, page2, null));
        FakeRepo repo = new FakeRepo(Set.of("901", "902", "903", "904", "905", "906", "907"));
        VacancyDiscovery discovery = discovery().scraper(scraper).analyzer(new FakeAnalyzer(config)).repo(repo).build();

        int saved = discovery.fromUrl(urlJob(), "https://hh.ru/search/vacancy?text=x", 3);

        assertEquals(2, saved, "новые вакансии и до, и после полностью известной страницы должны сохраниться");
        assertEquals(List.of("101", "102"), repo.saved.stream().map(Vacancy::getHhId).toList());
        assertEquals(3, scraper.calls, "должны быть запрошены все 3 страницы, включая ту, что идёт после 100%-известной");
    }

    @Test
    void fromUrl_recordsCollectedMetric_taggedHhRu() {
        // Grafana wants "how many vacancies came from hh.ru" — this is the counter
        // that answers it (TelegramMetrics.recordCollected is the equivalent for
        // Telegram source channels, tracked separately since it's a different question).
        RuntimeConfig config = new RuntimeConfig();
        io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        FakeScraper scraper = new FakeScraper(config,
            new ScraperClient.SearchPageResult(true, null, List.of(hit("201"), hit("202")), null));
        FakeRepo repo = new FakeRepo(Set.of());
        VacancyDiscovery discovery = discovery().scraper(scraper).analyzer(new FakeAnalyzer(config))
            .repo(repo).metricsRegistry(registry).build();

        discovery.fromUrl(urlJob(), "https://hh.ru/search/vacancy?text=x", 1);

        assertEquals(2.0, registry.find("vacancies_collected_total").tag("source", "hh.ru").counter().count());
    }

    // ── filterExcludedHits (URL-discovery's title-exclusion filter — mirrors filterExcluded) ──

    @SuppressWarnings("unchecked")
    private List<ScraperClient.SearchHit> filterExcludedHits(List<ScraperClient.SearchHit> hits, List<String> excludeWords) throws Exception {
        Method m = VacancyDiscovery.class.getDeclaredMethod("filterExcludedHits", List.class, List.class);
        m.setAccessible(true);
        return (List<ScraperClient.SearchHit>) m.invoke(discovery().build(), hits, excludeWords);
    }

    private ScraperClient.SearchHit hit(String hhId, String title) {
        return new ScraperClient.SearchHit(hhId, title, "ООО Ромашка", null, "Уфа", null, "https://hh.ru/vacancy/" + hhId);
    }

    @Test
    void filterExcludedHits_noExcludeWords_keepsAll() throws Exception {
        List<ScraperClient.SearchHit> hits = List.of(hit("1", "Продавец"), hit("2", "Кассир"));
        assertEquals(2, filterExcludedHits(hits, List.of()).size());
        assertEquals(2, filterExcludedHits(hits, null).size());
    }

    @Test
    void filterExcludedHits_dropsTitlesContainingExcludedWord_caseInsensitive() throws Exception {
        List<ScraperClient.SearchHit> hits = List.of(
            hit("1", "Продавец-консультант"),
            hit("2", "МЕНЕДЖЕР по продажам (страховка)"),
            hit("3", "Кассир"));
        List<ScraperClient.SearchHit> result = filterExcludedHits(hits, List.of("страховка"));
        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(h -> h.hhId().equals("2")));
    }

    @Test
    void filterExcludedHits_dropsByEmployerName_evenWithGenericTitle() throws Exception {
        // Живой пример: риелторские агентства нанимают под нейтральным заголовком
        // ("Менеджер / Помощник руководителя") — само слово встречается только в
        // названии работодателя.
        List<ScraperClient.SearchHit> hits = List.of(
            new ScraperClient.SearchHit("1", "Менеджер / Помощник руководителя", "Агентство Недвижимости Инфинити", null, "Уфа", null, "https://hh.ru/vacancy/1"),
            hit("2", "Кассир"));
        List<ScraperClient.SearchHit> result = filterExcludedHits(hits, List.of("риэлтор", "риелтор", "агентство недвижимости"));
        assertEquals(1, result.size());
        assertEquals("2", result.get(0).hhId());
    }

    // ── filterExcluded (Vacancy-версия того же фильтра, используется после скрейпинга) ──

    private List<Vacancy> filterExcluded(List<Vacancy> vacancies, List<String> excludeWords) throws Exception {
        Method m = VacancyDiscovery.class.getDeclaredMethod("filterExcluded", List.class, List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Vacancy> result = (List<Vacancy>) m.invoke(discovery().build(), vacancies, excludeWords);
        return result;
    }

    @Test
    void filterExcluded_dropsByEmployerName_evenWithGenericTitle() throws Exception {
        Vacancy realtor = vacancy("Менеджер / Помощник руководителя", "", 0);
        realtor.setCompany("Агентство Недвижимости Инфинити");
        Vacancy other = vacancy("Кассир", "", 0);
        other.setCompany("ООО Ромашка");

        List<Vacancy> result = filterExcluded(List.of(realtor, other), List.of("риэлтор", "риелтор", "агентство недвижимости"));

        assertEquals(1, result.size());
        assertEquals("Кассир", result.get(0).getTitle());
    }
}
