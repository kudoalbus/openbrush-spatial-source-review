package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.R

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.openbrush.picoviewer.domain.drawing.PaintStroke
import java.io.File
import java.util.UUID

/** The original recovery slot remains available alongside imported drawing files. */
class DrawingWorkspaceFiles(private val context: Context) {
    data class Imported(val id: String, val notices: List<String>)
    data class Entry(val id: String, val title: String, val modifiedAt: Long)

    private val preferences = context.getSharedPreferences("drawing-workspaces", Context.MODE_PRIVATE)
    val activeId: String get() = preferences.getString("active", null) ?: DEFAULT_ID

    fun select(id: String) {
        preferences.edit().putString("active", id).apply()
    }

    private val directory = File(context.filesDir, "drawing-workspaces")

    private fun file(id: String): File = if (id == DEFAULT_ID) {
        File(context.filesDir, "drawing-workspace.json")
    } else {
        require(id == UUID.fromString(id).toString()) { "无效的画作标识" }
        File(directory, "$id.json")
    }

    fun read(id: String): List<PaintStroke> = synchronized(ioLock) {
        val target = file(id)
        val backup = File(target.path + ".bak")
        if (target.isFile || backup.isFile) AtomicFile(target).openRead().use(DrawingDocumentCodec::read)
        else {
            check(!File(target.path+".previous").isFile && !File(target.path+".previous.bak").isFile) {
                context.getString(R.string.file_load_failed)
            }
            emptyList()
        }
    }

    fun write(id: String, strokes: List<PaintStroke>) = synchronized(ioLock) {
        val bytes = DrawingDocumentCodec.encodeBytes(strokes)
        val target=file(id)
        val previous=File(target.path+".previous")
        if(target.isFile || File(target.path+".bak").isFile) {
            val saved=AtomicFile(target).openRead().use(DrawingDocumentCodec::read)
            val oldBytes=DrawingDocumentCodec.encodeBytes(saved)
            if(!oldBytes.contentEquals(bytes) || !previous.isFile)writeAtomic(previous,oldBytes)
        } else {
            writeAtomic(previous,bytes)
        }
        writeAtomic(target,bytes)
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun canRecover(id: String): Boolean = synchronized(ioLock) {
        val previous=File(file(id).path+".previous")
        runCatching { AtomicFile(previous).openRead().use(DrawingDocumentCodec::read) }.isSuccess
    }

    fun recoverPrevious(id: String) = synchronized(ioLock) {
        val target=file(id)
        val previous=File(target.path+".previous")
        val saved=AtomicFile(previous).openRead().use(DrawingDocumentCodec::read)
        // Preserve the failed document before replacing it, including an atomic-write backup.
        val damaged=File(target.path+".damaged-"+UUID.randomUUID())
        if(target.isFile || File(target.path+".bak").isFile) {
            AtomicFile(target).openRead().use { input ->
                damaged.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }
        writeAtomic(target,DrawingDocumentCodec.encodeBytes(saved))
    }

    fun entries(): List<Entry> = synchronized(ioLock) {
        val ids = directory.listFiles().orEmpty().asSequence()
            .filter { it.isFile }
            .map { it.name.removeSuffix(".bak").removeSuffix(".previous") }
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .filter { id -> runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) }
            .toSet() + DEFAULT_ID
        ids.map { id ->
            val target = file(id)
            Entry(id, title(id), maxOf(target.lastModified(), File(target.path + ".bak").lastModified()))
        }.sortedByDescending { it.modifiedAt }
    }

    fun create(): String = UUID.randomUUID().toString().also {
        write(it, emptyList())
        preferences.edit().putBoolean("untitled:$it", true).apply()
    }

    fun title(id: String): String = if (id == DEFAULT_ID) context.getString(R.string.original_workspace)
        else preferences.getString("title:$id", null) ?: if (preferences.getBoolean("untitled:$id", false)) {
            context.getString(R.string.untitled_drawing)
        } else context.getString(R.string.drawing_id, id.take(8))

    private fun saveTitle(id: String, title: String) {
        val name = title.trim().filterNot { it == '/' || it == '\\' || it.isISOControl() }.take(96)
        if (name.isNotBlank()) preferences.edit().putString("title:$id", name).apply()
    }

    fun import(uri: Uri, format: DrawingImportFormat, displayName: String?): Imported {
        val input = context.contentResolver.openInputStream(uri) ?: error(context.getString(R.string.drawing_read_failed))
        val (strokes, notices) = input.use {
            when (format) {
                DrawingImportFormat.JSON -> DrawingDocumentCodec.read(it) to emptyList()
                DrawingImportFormat.TILT -> TiltDrawingImporter.convert(TiltReader.read(it)).let { drawing ->
                    drawing.strokes to drawing.notices
                }
            }
        }
        val id = UUID.randomUUID().toString()
        write(id, strokes)
        if (displayName != null) saveTitle(id, displayName.substringBeforeLast('.'))
        return Imported(id, notices)
    }

    fun export(id: String, uri: Uri) {
        val bytes = DrawingDocumentCodec.encodeBytes(read(id))
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error(context.getString(R.string.drawing_write_failed))
        output.use { it.write(bytes) }
    }

    companion object {
        private val ioLock = Any()
        const val DEFAULT_ID = "default"
        const val WORKSPACE_ID = "drawingWorkspaceId"
    }
}
