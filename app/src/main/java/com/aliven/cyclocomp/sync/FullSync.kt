package com.aliven.cyclocomp.sync

import android.content.Context
import com.aliven.cyclocomp.analytics.PlanSettings
import com.aliven.cyclocomp.analytics.TrainingSettings
import com.aliven.cyclocomp.recording.RideRecorder

/**
 * One button that makes this phone and the repo agree, in both directions:
 *
 * 1. pull rides, screenshot workouts and deletions made on any phone,
 * 2. turn watch GPX / screenshot files waiting in `inbox/` into rides data (works on any phone),
 * 3. upload whatever the repo is missing or has an older copy of,
 * 4. share the heart-rate settings (the most recently saved copy wins).
 *
 * Because every phone runs the same steps, it does not matter which phone recorded a ride or
 * which one imports the watch data.
 */
object FullSync {
    private const val SETTINGS_PATH = "settings/training.json"

    fun run(context: Context, config: SyncConfig): List<String> {
        RideRecorder.init(context.applicationContext)
        val repo = SyncSettings.repo(config)
        val ridesDir = RideRecorder.ridesDir()
        val lines = mutableListOf<String>()

        try {
            lines.addAll(pullSettings(context, repo))
            lines.addAll(pullPlan(context, repo))

            val remote = RidesPuller.fetchRemote(repo)
            lines.addAll(RidesPuller.run(context, repo, ridesDir, remote))

            lines.addAll(InboxImporter.run(context, repo))

            val files = RideRecorder.rides() + WorkoutStore.files(ridesDir)
            val report = RideSync.uploadRides(context, repo, files, remote.map { it.name }.toSet())
            lines.add(RideSync.describe(report))
            lines.addAll(RideSync.pushTombstones(context, repo))

            lines.addAll(RoutesSync.run(context, repo))

            lines.addAll(pushSettings(context, repo))
            lines.addAll(pushPlan(context, repo))
        } catch (e: StoreException) {
            lines.add(e.message ?: "GitHub error")
        } catch (e: Exception) {
            lines.add("Sync stopped: ${e.message ?: e.javaClass.simpleName}")
        }
        return lines
    }

    private const val PLAN_PATH = "settings/plan.json"

    private fun pullPlan(context: Context, repo: DataStore): List<String> {
        val text = repo.getRaw(PLAN_PATH)?.toString(Charsets.UTF_8) ?: return emptyList()
        val remote = PlanSettingsJson.fromJson(text) ?: return emptyList()
        if (!PlanSettings.isValid(remote.goal)) return emptyList()
        if (!TrainingSettingsJson.remoteIsNewer(PlanSettings.updatedMs(context), remote.updatedMs)) return emptyList()
        PlanSettings.save(context, remote.goal, remote.updatedMs)
        return listOf("Training goal updated from the repo (${remote.goal.weeklyTargetKm} km/week)")
    }

    private fun pushPlan(context: Context, repo: DataStore): List<String> {
        val localUpdated = PlanSettings.updatedMs(context)
        val goal = PlanSettings.load(context)
        if (localUpdated == 0L || goal == null) return emptyList() // never saved here: nothing to share
        val remote = repo.getRaw(PLAN_PATH)?.toString(Charsets.UTF_8)?.let { PlanSettingsJson.fromJson(it) }
        if (remote != null && remote.updatedMs >= localUpdated) return emptyList()
        repo.putOrUpdate(
            PLAN_PATH,
            PlanSettingsJson.toJson(PlanSettingsJson.Values(goal, localUpdated)).toByteArray(Charsets.UTF_8),
            "Update training goal",
        )
        return listOf("Training goal shared with the repo")
    }

    private fun pullSettings(context: Context, repo: DataStore): List<String> {
        val text = repo.getRaw(SETTINGS_PATH)?.toString(Charsets.UTF_8) ?: return emptyList()
        val remote = TrainingSettingsJson.fromJson(text) ?: return emptyList()
        if (!TrainingSettings.isValid(remote.restHr, remote.maxHr)) return emptyList()
        if (!TrainingSettingsJson.remoteIsNewer(TrainingSettings.updatedMs(context), remote.updatedMs)) return emptyList()
        TrainingSettings.save(context, remote.restHr, remote.maxHr, remote.updatedMs)
        return listOf("Heart-rate settings updated from the repo (${remote.restHr}/${remote.maxHr})")
    }

    private fun pushSettings(context: Context, repo: DataStore): List<String> {
        val localUpdated = TrainingSettings.updatedMs(context)
        if (localUpdated == 0L) return emptyList() // never saved here: nothing to share
        val remote = repo.getRaw(SETTINGS_PATH)?.toString(Charsets.UTF_8)?.let { TrainingSettingsJson.fromJson(it) }
        if (remote != null && remote.updatedMs >= localUpdated) return emptyList()
        val values = TrainingSettingsJson.Values(
            TrainingSettings.restHr(context), TrainingSettings.maxHr(context), localUpdated,
        )
        repo.putOrUpdate(SETTINGS_PATH, TrainingSettingsJson.toJson(values).toByteArray(Charsets.UTF_8), "Update heart-rate settings")
        return listOf("Heart-rate settings shared with the repo")
    }
}
