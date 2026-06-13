package com.jordanrburger.scout.data

import java.io.File

/** Atomic-enough single-file store: write temp, rename over. */
class FileSnapshotStore(private val file: File) : SnapshotStore {

    override fun save(json: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        tmp.renameTo(file)
    }

    override fun load(): String? = runCatching {
        if (file.exists()) file.readText() else null
    }.getOrNull()
}
