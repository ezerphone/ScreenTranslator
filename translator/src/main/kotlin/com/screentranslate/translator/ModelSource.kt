package com.screentranslate.translator

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Where a model's files come from: a folder (tests) or the APK's assets (the app). */
interface ModelSource {
    fun open(name: String): InputStream

    /** The file as a direct (memory-mapped) buffer, so large models are not copied to the heap. */
    fun map(name: String): ByteBuffer
}

class DirectoryModelSource(private val dir: File) : ModelSource {
    override fun open(name: String): InputStream = FileInputStream(File(dir, name))

    override fun map(name: String): ByteBuffer =
        RandomAccessFile(File(dir, name), "r").use { file ->
            file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
}
