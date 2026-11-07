package com.aliven.cyclocomp.sync

/** A problem talking to the data store. [code] is the HTTP status (0 if the server was not reached). */
open class StoreException(val code: Int, message: String) : Exception(message)

/**
 * Where rides, workouts and settings are kept: a GitHub repository ([GitHubDataRepo]) or an
 * account on the self-hosted server ([PocketBaseStore]). All sync code talks to this interface, so
 * both behave the same way:
 *  - paths look like `rides/2026/20261107_060200.gpx`;
 *  - [putNew] never overwrites; an existing path is reported as [PutResult.EXISTS];
 *  - [list] returns the direct children of a folder (files and sub-folders);
 *  - [getRaw] returns null for a path that does not exist.
 */
interface DataStore {
    fun putNew(path: String, bytes: ByteArray, message: String): PutResult
    fun putOrUpdate(path: String, bytes: ByteArray, message: String)
    fun list(dir: String): List<RepoEntry>
    fun getRaw(path: String): ByteArray?

    /** Throws [StoreException] with a readable message if the store can't be used right now. */
    fun checkAccess()
}
