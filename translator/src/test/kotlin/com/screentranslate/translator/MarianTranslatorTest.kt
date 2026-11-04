package com.screentranslate.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs every bundled model on a JVM and compares against the Python/transformers reference
 * produced by tools/prepare_model.py:
 *  - token ids must match exactly (tokenizer correctness)
 *  - at least half of the translations must match the full-precision model exactly
 *  - Chinese and Arabic are also run through English to Hebrew, as the app does
 */
class MarianTranslatorTest {

    private val modelRoot = File(System.getProperty("modelDir").orEmpty())
    private val referenceDir = File(System.getProperty("referenceDir").orEmpty())

    private fun modelNames(): List<String> =
        modelRoot.listFiles()?.filter { File(it, "config.properties").exists() }?.map { it.name }?.sorted().orEmpty()

    @Test
    fun everyModelMatchesItsReference() {
        val names = modelNames()
        assumeTrue("models not prepared", names.isNotEmpty())

        val hebrew = MarianTranslator(DirectoryModelSource(File(modelRoot, "en-he")))
        hebrew.use {
            for (name in names) checkModel(name, hebrew)
        }
    }

    private fun checkModel(name: String, hebrew: MarianTranslator) {
        val reference = File(referenceDir, "$name.tsv")
        assertTrue("missing reference for $name", reference.exists())
        val translator = if (name == "en-he") hebrew else MarianTranslator(DirectoryModelSource(File(modelRoot, name)))
        try {
            val lines = reference.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            var exact = 0
            println("===== $name (target: ${translator.target}) =====")
            for (line in lines) {
                val (sentence, referenceIds, referenceTranslation) = line.split('\t')

                assertEquals(
                    "[$name] token ids for: $sentence",
                    referenceIds,
                    translator.tokenizer.encode(sentence).joinToString(" ")
                )

                val start = System.nanoTime()
                val ours = translator.translate(sentence)
                val ms = (System.nanoTime() - start) / 1_000_000
                if (ours == referenceTranslation) exact++
                val toHebrew = if (translator.target == "he") "" else "\nHE : ${hebrew.translate(ours)}"
                println("SRC: $sentence\nREF: $referenceTranslation\nOUR: $ours   (${ms}ms)$toHebrew\n")
                assertTrue("[$name] empty translation for: $sentence", ours.isNotBlank())
            }
            println("[$name] exact matches with the full-precision reference: $exact / ${lines.size}")
            assertTrue("[$name] translation quality dropped: only $exact exact", exact * 2 >= lines.size)
        } finally {
            if (translator !== hebrew) translator.close()
        }
    }
}
