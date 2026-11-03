package com.screentranslate.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real model on a JVM and compares against the Python/transformers reference
 * produced by tools/prepare_model.py:
 *  - token ids must match exactly (tokenizer correctness)
 *  - translations are printed side by side and must contain Hebrew
 */
class MarianTranslatorTest {

    @Test
    fun translatesLikeReference() {
        val modelDir = File(System.getProperty("modelDir").orEmpty())
        val referenceFile = File(System.getProperty("referenceFile").orEmpty())
        assumeTrue("model not prepared", File(modelDir, "encoder.onnx").exists() && referenceFile.exists())

        MarianTranslator(modelDir).use { translator ->
            val lines = referenceFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            assertTrue(lines.isNotEmpty())
            var exact = 0
            for (line in lines) {
                val parts = line.split('\t')
                val sentence = parts[0]
                val referenceIds = parts[1]
                val referenceTranslation = parts[2]

                assertEquals(
                    "token ids for: $sentence",
                    referenceIds,
                    translator.tokenizer.encode(sentence).joinToString(" ")
                )

                val start = System.nanoTime()
                val ours = translator.translate(sentence)
                val ms = (System.nanoTime() - start) / 1_000_000
                if (ours == referenceTranslation) exact++
                println("EN : $sentence\nREF: $referenceTranslation\nOUR: $ours   (${ms}ms)\n")
                assertTrue("no Hebrew in: $ours", ours.any { it in 'א'..'ת' })
            }
            println("Exact matches with the full-precision reference: $exact / ${lines.size}")
            // Quantization may change a word here and there, but a broken model changes everything.
            assertTrue("translation quality dropped: only $exact exact matches", exact * 2 >= lines.size)
        }
    }
}
