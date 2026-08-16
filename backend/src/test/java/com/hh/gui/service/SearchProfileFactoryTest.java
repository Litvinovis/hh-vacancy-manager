package com.hh.gui.service;

import com.hh.gui.model.SearchConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.SearchKind;
import com.hh.gui.model.User;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchProfileFactory is the single source of truth every pipeline run and manual
 * trigger builds its SearchJob list from (see its own javadoc) — a silently dropped or
 * misassigned field here corrupts every downstream discovery/AI/publish decision for
 * that search without a single test failing anywhere else, since every other test
 * builds SearchJob by hand. Exhaustive field-by-field mapping is the point, not
 * over-testing: it's exactly the class of gap #166/#167 targeted.
 */
class SearchProfileFactoryTest {

    private static class FakeSearchRepo extends SearchRepository {
        List<SearchConfig> enabled = new ArrayList<>();
        java.util.Map<Long, SearchConfig> byId = new java.util.HashMap<>();
        FakeSearchRepo() { super(null); }
        @Override
        public List<SearchConfig> findAllEnabled() { return enabled; }
        @Override
        public Optional<SearchConfig> findById(Long id) { return Optional.ofNullable(byId.get(id)); }
    }

    private static class FakeUserRepo extends UserRepository {
        List<User> all = new ArrayList<>();
        java.util.Map<Long, User> byId = new java.util.HashMap<>();
        FakeUserRepo() { super(null); }
        @Override
        public List<User> findAll() { return all; }
        @Override
        public Optional<User> findById(Long id) { return Optional.ofNullable(byId.get(id)); }
    }

    private static User activeUser(long id) {
        User u = new User();
        u.setId(id);
        u.setDisplayName("Игорь");
        u.setCity("Уфа");
        u.setExperienceSummary("5 лет в поддержке");
        u.setActive(true);
        return u;
    }

    private static SearchConfig fullSearch(long id, long userId, boolean global, boolean enabled) {
        SearchConfig s = new SearchConfig();
        s.setId(id);
        s.setUserId(userId);
        s.setName("Без техстека");
        s.setQueries(List.of("оператор ПК", "модератор"));
        s.setArea(113);
        s.setSchedule("remote");
        s.setSalaryMin(40000);
        s.setExcludeWords(List.of("продажи"));
        s.setPriorityDistricts(List.of("Центр"));
        s.setSkills(List.of("коммуникация"));
        s.setNotSuitable(List.of("холодные звонки"));
        s.setAiNotes("Не любит рутину");
        s.setEnabled(enabled);
        s.setGlobal(global);
        s.setSourceUrl("https://hh.ru/search/vacancy?text=x");
        s.setRunIntervalHours(6);
        s.setLastRunAt("2026-08-15T10:00:00Z");
        s.setChatId("-100123");
        s.setPublicFormat(true);
        s.setKind(SearchKind.EDITORIAL);
        s.setDelayedChatId("-100456");
        s.setDelayedPublishMinutes(15);
        s.setSubscriberFeed(true);
        s.setPublishPaceMinutes(5);
        s.setTelegramChannels(List.of("kadrout", "vacancysmm"));
        return s;
    }

    @Test
    void build_mapsEveryFieldFromSearchAndUserOntoJob() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        FakeUserRepo userRepo = new FakeUserRepo();
        User user = activeUser(7L);
        SearchConfig search = fullSearch(10L, 7L, false, true);
        searchRepo.enabled = List.of(search);
        userRepo.all = List.of(user);

        List<SearchJob> jobs = new SearchProfileFactory(searchRepo, userRepo).build();

        assertEquals(1, jobs.size());
        SearchJob job = jobs.get(0);
        assertEquals(7L, job.userId);
        assertEquals(10L, job.searchId);
        assertEquals("Игорь", job.personName, "персональный поиск должен нести displayName пользователя, не общую метку");
        assertEquals("Без техстека", job.searchName);
        assertEquals("Уфа", job.city);
        assertEquals("5 лет в поддержке", job.experienceSummary);
        assertEquals(List.of("оператор ПК", "модератор"), job.queries);
        assertEquals(113, job.area);
        assertEquals("remote", job.schedule);
        assertEquals(40000, job.salaryMin);
        assertEquals(List.of("продажи"), job.excludeWords);
        assertEquals(List.of("Центр"), job.priorityDistricts);
        assertEquals(List.of("коммуникация"), job.skills);
        assertEquals(List.of("холодные звонки"), job.notSuitable);
        assertEquals("Не любит рутину", job.aiNotes);
        assertFalse(job.isGlobal);
        assertEquals("https://hh.ru/search/vacancy?text=x", job.sourceUrl);
        assertEquals(6, job.runIntervalHours);
        assertEquals("2026-08-15T10:00:00Z", job.lastRunAt);
        assertEquals("-100123", job.chatId);
        assertTrue(job.publicFormat);
        assertEquals(SearchKind.EDITORIAL, job.kind);
        assertEquals("-100456", job.delayedChatId);
        assertEquals(15, job.delayedPublishMinutes);
        assertTrue(job.subscriberFeed);
        assertEquals(5, job.publishPaceMinutes);
        assertEquals(List.of("kadrout", "vacancysmm"), job.telegramChannels);
    }

    @Test
    void build_globalSearch_personNameIsSharedLabelNotOwnerDisplayName() {
        // The admin who set up a global search isn't who it's "for" — stamping their
        // own name on shared results would be wrong and misleading in reports.
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        FakeUserRepo userRepo = new FakeUserRepo();
        User admin = activeUser(1L);
        admin.setDisplayName("Админ Админыч");
        SearchConfig global = fullSearch(20L, 1L, true, true);
        searchRepo.enabled = List.of(global);
        userRepo.all = List.of(admin);

        List<SearchJob> jobs = new SearchProfileFactory(searchRepo, userRepo).build();

        assertEquals(1, jobs.size());
        assertEquals("Все пользователи", jobs.get(0).personName);
        assertTrue(jobs.get(0).isGlobal);
    }

    @Test
    void build_userMissing_searchSkippedNotCrashed() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        FakeUserRepo userRepo = new FakeUserRepo();
        searchRepo.enabled = List.of(fullSearch(10L, 999L, false, true)); // references no-such user
        userRepo.all = List.of();

        List<SearchJob> jobs = new SearchProfileFactory(searchRepo, userRepo).build();

        assertTrue(jobs.isEmpty());
    }

    @Test
    void build_userInactive_searchSkipped() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        FakeUserRepo userRepo = new FakeUserRepo();
        User inactive = activeUser(7L);
        inactive.setActive(false);
        searchRepo.enabled = List.of(fullSearch(10L, 7L, false, true));
        userRepo.all = List.of(inactive);

        List<SearchJob> jobs = new SearchProfileFactory(searchRepo, userRepo).build();

        assertTrue(jobs.isEmpty());
    }

    @Test
    void build_onlyReturnsWhatFindAllEnabledGives_disabledSearchesNeverReachIt() {
        // build() has no enabled-flag filtering of its own — it trusts findAllEnabled()
        // entirely. A disabled search simply never appears in the fake's list, same as
        // the real query's WHERE clause would exclude it.
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        FakeUserRepo userRepo = new FakeUserRepo();
        userRepo.all = List.of(activeUser(7L));
        searchRepo.enabled = List.of(); // the disabled search never lands here

        List<SearchJob> jobs = new SearchProfileFactory(searchRepo, userRepo).build();

        assertTrue(jobs.isEmpty());
    }

    @Test
    void buildForSearchId_ignoresEnabledFlag_usedForExplicitManualTrigger() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        FakeUserRepo userRepo = new FakeUserRepo();
        User user = activeUser(7L);
        SearchConfig disabledSearch = fullSearch(10L, 7L, false, false); // enabled=false
        searchRepo.byId.put(10L, disabledSearch);
        userRepo.byId.put(7L, user);

        Optional<SearchJob> job = new SearchProfileFactory(searchRepo, userRepo).buildForSearchId(10L);

        assertTrue(job.isPresent(), "явный ручной триггер по searchId должен работать даже для выключенного поиска");
        assertEquals("Без техстека", job.get().searchName);
    }

    @Test
    void buildForSearchId_searchNotFound_returnsEmpty() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        FakeUserRepo userRepo = new FakeUserRepo();

        assertTrue(new SearchProfileFactory(searchRepo, userRepo).buildForSearchId(999L).isEmpty());
    }

    @Test
    void buildForSearchId_userInactive_returnsEmpty() {
        FakeSearchRepo searchRepo = new FakeSearchRepo();
        FakeUserRepo userRepo = new FakeUserRepo();
        User inactive = activeUser(7L);
        inactive.setActive(false);
        searchRepo.byId.put(10L, fullSearch(10L, 7L, false, true));
        userRepo.byId.put(7L, inactive);

        assertTrue(new SearchProfileFactory(searchRepo, userRepo).buildForSearchId(10L).isEmpty());
    }
}
