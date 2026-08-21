package com.hh.gui.controller;

import com.hh.gui.config.RuntimeConfig;
import com.hh.gui.model.SearchJob;
import com.hh.gui.model.User;
import com.hh.gui.repository.SearchRepository;
import com.hh.gui.repository.UserRepository;
import com.hh.gui.service.PipelineJobRunner;
import com.hh.gui.service.SearchProfileFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of this controller isn't the individual pipeline-trigger endpoints (those
 * mostly just delegate) — it's that jobsFor() and triggerableJobsFor() are DIFFERENT
 * sets for a non-admin: a regular user may VIEW a global (shared) search but must not
 * be able to force-TRIGGER a re-run of it, since that affects every other user's
 * results too. That distinction has no test coverage anywhere else.
 */
class PipelineControllerTest {

    static class FakeProfileFactory extends SearchProfileFactory {
        final List<SearchJob> jobs;
        FakeProfileFactory(List<SearchJob> jobs) { super(null, null); this.jobs = jobs; }
        @Override
        public List<SearchJob> build() { return jobs; }
        @Override
        public Optional<SearchJob> buildForSearchId(Long searchId) {
            return jobs.stream().filter(j -> searchId.equals(j.searchId)).findFirst();
        }
    }

    /** Records how many jobs a trigger endpoint actually started, without running any
     *  real pipeline work (the real work.apply(job) would hit hh.ru over the network). */
    static class RecordingJobRunner extends PipelineJobRunner {
        List<SearchJob> lastStartedJobs;
        boolean rejectStart = false;
        @Override
        public boolean start(Type type, List<SearchJob> jobs, Function<SearchJob, Map<String, Integer>> work) {
            if (rejectStart) return false;
            lastStartedJobs = jobs;
            return true;
        }
    }

    private static SearchJob job(Long searchId, Long userId, boolean global, String person, String searchName) {
        SearchJob j = new SearchJob();
        j.searchId = searchId;
        j.userId = userId;
        j.isGlobal = global;
        j.personName = person;
        j.searchName = searchName;
        return j;
    }

    private static User user(Long id, boolean admin) {
        User u = new User();
        u.setId(id);
        u.setRole(admin ? "admin" : "user");
        return u;
    }

    private RecordingJobRunner jobRunner;

    private PipelineController controller(List<SearchJob> jobs) {
        jobRunner = new RecordingJobRunner();
        return new PipelineController(null, null, new FakeProfileFactory(jobs), new RuntimeConfig(),
            null, jobRunner, null);
    }

    // ── GET /api/pipeline/jobs — visibility (jobsFor) ──

    @Test
    void listJobs_regularUser_seesOwnAndGlobalButNotOthers() {
        List<SearchJob> jobs = List.of(
            job(1L, 10L, false, "Own", "s1"),
            job(2L, 20L, false, "Other", "s2"),
            job(3L, 30L, true, "Все пользователи", "shared")
        );
        var response = controller(jobs).listJobs(user(10L, false));

        assertEquals(2, response.getBody().size());
        assertTrue(response.getBody().stream().anyMatch(j -> "s1".equals(j.get("searchName"))));
        assertTrue(response.getBody().stream().anyMatch(j -> "shared".equals(j.get("searchName"))));
    }

    @Test
    void listJobs_admin_seesEverything() {
        List<SearchJob> jobs = List.of(
            job(1L, 10L, false, "A", "s1"),
            job(2L, 20L, false, "B", "s2")
        );
        var response = controller(jobs).listJobs(user(99L, true));

        assertEquals(2, response.getBody().size());
    }

    // ── POST /api/pipeline/run — trigger rights (triggerableJobsFor) ──

    @Test
    void runPipeline_regularUser_globalSearchExcludedFromTrigger() {
        List<SearchJob> jobs = List.of(
            job(1L, 10L, false, "Own", "s1"),
            job(2L, 30L, true, "Все пользователи", "shared")
        );
        controller(jobs).runPipeline(null, null, user(10L, false));

        assertEquals(1, jobRunner.lastStartedJobs.size(), "глобальный поиск виден, но триггерить его обычный юзер не должен");
        assertEquals(1L, jobRunner.lastStartedJobs.get(0).searchId);
    }

    @Test
    void runPipeline_admin_globalSearchIncludedInTrigger() {
        List<SearchJob> jobs = List.of(
            job(1L, 10L, false, "Own", "s1"),
            job(2L, 30L, true, "Все пользователи", "shared")
        );
        controller(jobs).runPipeline(null, null, user(99L, true));

        assertEquals(2, jobRunner.lastStartedJobs.size());
    }

    @Test
    void runPipeline_otherUsersPersonalSearch_neverVisibleOrTriggerableByRegularUser() {
        List<SearchJob> jobs = List.of(job(2L, 20L, false, "Other", "s2"));
        controller(jobs).runPipeline(null, null, user(10L, false));

        assertTrue(jobRunner.lastStartedJobs.isEmpty());
    }

    @Test
    void runPipeline_personFilter_narrowsToMatchingJobOnly() {
        List<SearchJob> jobs = List.of(
            job(1L, 10L, false, "Alice", "s1"),
            job(2L, 10L, false, "Bob", "s2")
        );
        controller(jobs).runPipeline("Alice", null, user(10L, false));

        assertEquals(1, jobRunner.lastStartedJobs.size());
        assertEquals("Alice", jobRunner.lastStartedJobs.get(0).personName);
    }

    @Test
    void runPipeline_alreadyRunning_returns409() {
        var c = controller(List.of(job(1L, 10L, false, "A", "s1")));
        jobRunner.rejectStart = true;

        var response = c.runPipeline(null, null, user(10L, false));

        assertEquals(409, response.getStatusCode().value());
    }

    // ── POST /api/pipeline/reanalyze and /analyze-pending — same triggerableJobsFor gate ──

    @Test
    void reanalyze_regularUser_excludesGlobalJobs() {
        List<SearchJob> jobs = List.of(
            job(1L, 10L, false, "Own", "s1"),
            job(2L, 30L, true, "Все пользователи", "shared")
        );
        controller(jobs).reanalyze(null, null, user(10L, false));

        assertEquals(1, jobRunner.lastStartedJobs.size());
    }

    @Test
    void analyzePending_admin_includesGlobalJobs() {
        List<SearchJob> jobs = List.of(
            job(1L, 10L, false, "Own", "s1"),
            job(2L, 30L, true, "Все пользователи", "shared")
        );
        controller(jobs).analyzePending(null, null, user(99L, true));

        assertEquals(2, jobRunner.lastStartedJobs.size());
    }
}
