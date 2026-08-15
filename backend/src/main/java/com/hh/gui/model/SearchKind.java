package com.hh.gui.model;

/**
 * What question a search is actually asking. The two answer to different people and
 * are judged by different standards, which is why they can't share one set of criteria.
 *
 * <p>{@link #PERSONAL} — "does this job suit this person?" Judged against a real
 * candidate: their city, commute, salary floor, background, what they'd hate doing.
 * The audience is that one person.
 *
 * <p>{@link #EDITORIAL} — "is this good material for the channel?" Judged as content
 * for an audience of strangers: is the posting clear, real, and the kind of thing this
 * channel exists to publish. Nobody's commute is involved.
 *
 * <p>Until this existed, both went through the personal prompt. A channel search left
 * city, districts, experience and salary floor empty and smuggled its editorial policy
 * into the free-text notes — so the model was asked whether a vacancy suited a person
 * with no city, no background and a 0₽ floor. The distinction is the fix.
 */
public enum SearchKind {
    PERSONAL,
    EDITORIAL;

    /** Tolerant of nulls and unknown values — an unreadable column must not break a
     *  pipeline run, and PERSONAL is the safe default (it's what every search was
     *  before this column existed). */
    public static SearchKind fromDb(String raw) {
        if (raw == null || raw.isBlank()) return PERSONAL;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PERSONAL;
        }
    }

    public boolean isEditorial() {
        return this == EDITORIAL;
    }
}
