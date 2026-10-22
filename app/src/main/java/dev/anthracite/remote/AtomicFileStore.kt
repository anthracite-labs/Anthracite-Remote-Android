package dev.anthracite.remote

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces an app-private file only after its complete replacement has reached
 * stable storage. The temporary file is created beside the destination so the
 * final rename is an atomic filesystem operation on Android's app storage.
 */
internal object AtomicFileStore {
    fun write(file: File, bytes: ByteArray) {
        replace(file) { temporary ->
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }
    }

    /** Visible to JVM tests so interrupted writes can be simulated. */
    internal fun replace(file: File, writeTemporary: (File) -> Unit) {
        val parent = file.parentFile ?: throw IOException("credential file has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("could not create credential directory")
        if (!parent.isDirectory) throw IOException("credential parent is not a directory")

        val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            // Files in noBackupFilesDir are app-private. Set the temporary mode
            // defensively as well so secrets are never widened before rename.
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            writeTemporary(temporary)
            if (!temporary.renameTo(file)) throw IOException("could not atomically replace credentials")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun deleteWithTemporaryFiles(file: File) {
        deleteOwned(file)
        val parent = file.parentFile ?: return
        parent.listFiles { candidate ->
            candidate.name.startsWith(".${file.name}.") && candidate.name.endsWith(".tmp")
        }?.forEach(::deleteOwned)
    }

    private fun deleteOwned(file: File) {
        if (file.exists() && !file.delete() && file.exists()) {
            throw IOException("could not delete credential file ${file.name}")
        }
    }
}
