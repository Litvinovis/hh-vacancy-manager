package com.hh.gui.service;

import com.hh.gui.model.SearchJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Runs manually-triggered pipeline work (run / analyze-pending / reanalyze) in a
 * single background thread and exposes its progress, instead of holding the HTTP
 * request open for the whole multi-minute run — a browser/proxy timeout used to
 * kill the connection and the user never saw the result (the work itself kept
 * going, invisibly). One manual job at a time: a second trigger while one is
 * running is rejected, mirroring how the UI buttons are disabled.
 *
 * Scheduled runs (PipelineScheduler) don't go through this — they have no client
 * waiting on progress and already overlap manual runs exactly as before.
 */
@Component
public class PipelineJobRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineJobRunner.class);

    /** What kind of manual work is running — the UI shows a different label per type. */
    public enum Type { RUN, ANALYZE_PENDING, REANALYZE }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pipeline-manual");
        t.setDaemon(true);
        return t;
    });

    private final Object lock = new Object();
    private boolean running = false;
    private Type type;
    private String currentJobLabel = "";
    private int jobsTotal;
    private int jobsDone;
    private String startedAt;
    private String finishedAt;
    private Map<String, Integer> counters = new LinkedHashMap<>();
    private String error;

    /**
     * Start `work` over `jobs` in the background. Per-job counter maps are summed
     * key-by-key into the aggregate exposed via status(). Returns false (and does
     * nothing) if a manual job is already running.
     */
    public boolean start(Type type, List<SearchJob> jobs, Function<SearchJob, Map<String, Integer>> work) {
        synchronized (lock) {
            if (running) return false;
            running = true;
            this.type = type;
            this.jobsTotal = jobs.size();
            this.jobsDone = 0;
            this.currentJobLabel = "";
            this.startedAt = Instant.now().toString();
            this.finishedAt = null;
            this.counters = new LinkedHashMap<>();
            this.error = null;
        }
        executor.submit(() -> execute(jobs, work));
        return true;
    }

    private void execute(List<SearchJob> jobs, Function<SearchJob, Map<String, Integer>> work) {
        try {
            for (SearchJob job : jobs) {
                synchronized (lock) {
                    currentJobLabel = job.personName + " · " + job.searchName;
                }
                try {
                    Map<String, Integer> result = work.apply(job);
                    synchronized (lock) {
                        result.forEach((k, v) -> counters.merge(k, v, Integer::sum));
                    }
                } catch (Exception e) {
                    // One broken job shouldn't hide the others' results — same per-job
                    // isolation the scheduler uses.
                    log.error("Ручной запуск {} · {} завершился ошибкой: {}", job.personName, job.searchName, e.getMessage(), e);
                    synchronized (lock) {
                        error = job.personName + " · " + job.searchName + ": " + e.getMessage();
                    }
                }
                synchronized (lock) {
                    jobsDone++;
                }
            }
        } finally {
            synchronized (lock) {
                running = false;
                currentJobLabel = "";
                finishedAt = Instant.now().toString();
            }
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    /** Snapshot for the polling UI: state of the current run, or of the last finished one. */
    public Map<String, Object> status() {
        synchronized (lock) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("running", running);
            s.put("type", type != null ? type.name().toLowerCase() : null);
            s.put("currentJob", currentJobLabel);
            s.put("jobsTotal", jobsTotal);
            s.put("jobsDone", jobsDone);
            s.put("startedAt", startedAt);
            s.put("finishedAt", finishedAt);
            s.put("counters", new LinkedHashMap<>(counters));
            s.put("error", error);
            return s;
        }
    }
}
