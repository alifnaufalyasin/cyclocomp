package com.aliven.cyclocomp.sync

import android.content.Context
import com.aliven.cyclocomp.core.CourseParser
import com.aliven.cyclocomp.core.RouteNames
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What a phone must do to share its saved routes with the others through the store. A deleted route
 * leaves a `<name>.gpx.deleted` marker, like rides do, so nobody pulls it back.
 */
object RoutePlanner {
    const val TOMBSTONE = ".deleted"

    class Plan(
        val download: List<String>,
        val deleteLocal: List<String>,
        val upload: List<String>,
        val uploadTombstones: List<String>,
    )

    fun plan(
        remoteNames: Set<String>,
        localNames: Set<String>,
        deletedHere: Set<String>,
        pendingTombstones: Set<String>,
    ): Plan {
        val tombstoned = remoteNames.filter { it.endsWith(TOMBSTONE) }.map { it.removeSuffix(TOMBSTONE) }.toSet()
        val remoteRoutes = remoteNames.filter { RouteNames.isSafe(it) }.toSet()
        val local = localNames.filter { RouteNames.isSafe(it) }.toSet()
        return Plan(
            download = (remoteRoutes - local - tombstoned - deletedHere).sorted(),
            deleteLocal = (local intersect tombstoned).sorted(),
            upload = (local - remoteRoutes - tombstoned).sorted(),
            uploadTombstones = (pendingTombstones.filter { RouteNames.isSafe(it) }.toSet() - tombstoned).sorted(),
        )
    }
}

/** Routes saved on this phone (GPX files in the app's private folder). */
object RouteStore {
    fun dir(context: Context): File = File(context.filesDir, "routes").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> RouteNames.isSafe(f.name) }.orEmpty().sortedBy { it.name.lowercase() }

    /** Checks the file really contains a route, then saves it under a unique name. */
    fun importGpx(context: Context, displayName: String, bytes: ByteArray): Result<File> {
        val course = CourseParser.parse(bytes.inputStream())
            ?: return Result.failure(IllegalArgumentException("That file has no route in it (no route or track points)."))
        val existing = list(context).map { it.name }.toSet()
        val title = displayName.ifBlank { course.name }
        val target = File(dir(context), RouteNames.fileName(title, existing))
        write(target, bytes)
        return Result.success(target)
    }

    fun delete(context: Context, file: File) {
        file.delete()
        RoutesSync.recordDeletion(context, file.name)
    }

    fun write(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Keeps every phone's routes the same, as part of Sync now. */
object RoutesSync {
    private const val PREFS = "cyclocomp_routes_sync"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun set(context: Context, key: String): Set<String> = prefs(context).getStringSet(key, emptySet()).orEmpty().toSet()

    /** The user deleted a route here: never pull it back, and tell the other phones at the next sync. */
    fun recordDeletion(context: Context, name: String) {
        prefs(context).edit()
            .putStringSet("deleted_here", set(context, "deleted_here") + name)
            .putStringSet("pending_tombstones", set(context, "pending_tombstones") + name)
            .apply()
    }

    fun run(context: Context, store: DataStore): List<String> {
        val dir = RouteStore.dir(context)
        val remoteNames = store.list("routes").filter { it.type == "file" }.map { it.name }.toSet()
        val local = RouteStore.list(context).map { it.name }.toSet()
        val plan = RoutePlanner.plan(remoteNames, local, set(context, "deleted_here"), set(context, "pending_tombstones"))

        var downloaded = 0
        var uploaded = 0
        var removed = 0
        val problems = mutableListOf<String>()

        fun attempt(what: String, block: () -> Unit): Boolean = try {
            block()
            true
        } catch (e: StoreException) {
            problems.add("$what: ${e.message}")
            if (e.code == 401 || e.code == 403) throw e
            false
        } catch (e: Exception) {
            problems.add("$what: ${e.message ?: e.javaClass.simpleName}")
            false
        }

        for (name in plan.download) {
            attempt("route $name") {
                val bytes = store.getRaw("routes/$name") ?: return@attempt
                RouteStore.write(File(dir, name), bytes)
                downloaded++
            }
        }
        for (name in plan.deleteLocal) {
            if (File(dir, name).delete()) removed++
        }
        for (name in plan.upload) {
            attempt("route $name") {
                store.putNew("routes/$name", File(dir, name).readBytes(), "Add route $name")
                uploaded++
            }
        }
        for (name in plan.uploadTombstones) {
            val ok = attempt("route $name deletion") {
                store.putNew("routes/$name${RoutePlanner.TOMBSTONE}", "deleted".toByteArray(), "Mark route $name deleted")
            }
            if (ok) prefs(context).edit().putStringSet("pending_tombstones", set(context, "pending_tombstones") - name).apply()
        }

        return buildList {
            add("Routes: $downloaded new, $uploaded uploaded, $removed removed")
            addAll(problems)
        }
    }
}
