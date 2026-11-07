package com.aliven.cyclocomp.core

/**
 * Decides what to say aloud during navigation. It only returns text; speaking is done elsewhere, so
 * the rules can be tested without a phone. Call [update] with every new position.
 *
 * Each turn is announced twice: once ahead ("In 150 meters, turn left onto Main Street") and once
 * when it is close ("Turn left onto Main Street"). A turn first seen already close is only announced
 * once. Arriving, leaving the route and rerouting are announced once per occurrence.
 */
class VoiceCueEngine(
    private val advanceM: Double = ADVANCE_M,
    private val nowM: Double = NOW_M,
) {
    private var route: NavRoute? = null
    private var advanceDone = -1
    private var nowDone = -1
    private var arrivedSaid = false
    private var reroutingSaid = false
    private var offCourseSaid = false

    fun update(
        route: NavRoute?,
        progress: NavProgress?,
        arrived: Boolean,
        rerouting: Boolean,
        offCourse: Boolean,
    ): List<String> {
        if (route !== this.route) {
            // A new route (first fix, destination change, reroute): its steps count from the start again.
            this.route = route
            advanceDone = -1
            nowDone = -1
            arrivedSaid = false
        }
        val out = mutableListOf<String>()

        if (rerouting && !reroutingSaid) out.add("Rerouting")
        reroutingSaid = rerouting
        if (offCourse && !offCourseSaid) out.add("You are off the course")
        offCourseSaid = offCourse

        if (arrived) {
            if (!arrivedSaid) out.add("You have arrived")
            arrivedSaid = true
            return out
        }
        if (route == null || progress == null) return out

        val idx = progress.stepIndex + 1
        val next = route.steps.getOrNull(idx) ?: return out
        val d = progress.toNextManeuverM
        val text = TurnText.of(next)
        if (d <= nowM) {
            if (nowDone != idx) out.add(text)
            nowDone = idx
            advanceDone = idx
        } else if (d <= advanceM && advanceDone != idx) {
            out.add("In ${spokenDistance(d)}, ${text.replaceFirstChar { it.lowercase() }}")
            advanceDone = idx
        }
        return out
    }

    companion object {
        const val ADVANCE_M = 150.0
        const val NOW_M = 40.0

        /** "150 meters" / "1.2 kilometers", written out so a speech engine does not read "m" as a letter. */
        fun spokenDistance(m: Double): String =
            if (m >= 1000) String.format(java.util.Locale.US, "%.1f kilometers", m / 1000)
            else "${(Math.round(m / 10) * 10).toInt()} meters"
    }
}
