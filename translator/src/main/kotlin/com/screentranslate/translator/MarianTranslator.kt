package com.screentranslate.translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Properties

/**
 * Offline Marian (OPUS-MT) translator running on ONNX Runtime.
 *
 * Expects from [source]: encoder.onnx, decoder.onnx (optimum's merged decoder, which handles the
 * first step and the cached steps in one file), source.tsv, vocab.tsv, target_vocab.tsv and
 * config.properties — see tools/prepare_model.py. Uses greedy decoding with a key/value cache.
 * Not thread-safe: use one thread at a time.
 */
class MarianTranslator(source: ModelSource, intraOpThreads: Int = 0) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val config = Properties().apply { source.open("config.properties").use { load(it) } }
    private fun int(key: String) = config.getProperty(key).trim().toInt()

    private val padId = int("pad_id")
    private val eosId = int("eos_id")
    private val unkId = int("unk_id")
    private val decoderStartId = int("decoder_start_id")
    private val layers = int("decoder_layers")
    private val heads = int("decoder_heads")
    private val headDim = int("d_model") / heads
    private val maxLength = config.getProperty("max_length", "512").trim().toInt()

    /** Language the model writes ("he", "en"). */
    val target: String = config.getProperty("target", "he").trim()

    val tokenizer = SentencePieceTokenizer(
        source.open("source.tsv"),
        source.open("vocab.tsv"),
        source.open("target_vocab.tsv"),
        unkId = unkId,
        eosId = eosId,
        padId = padId,
    )

    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (intraOpThreads > 0) setIntraOpNumThreads(intraOpThreads)
    }
    private val encoder = env.createSession(source.map("encoder.onnx"), options)
    private val decoder = env.createSession(source.map("decoder.onnx"), options)

    private val pastNames: List<String> = (0 until layers).flatMap { i ->
        listOf("decoder", "encoder").flatMap { kind ->
            listOf("key", "value").map { "past_key_values.$i.$kind.$it" }
        }
    }.filter { it in decoder.inputNames }

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)

    @Synchronized
    fun translate(text: String, maxInputTokens: Int = 256): String {
        var ids = tokenizer.encode(text)
        if (ids.size <= 1) return ""
        if (ids.size > maxInputTokens) ids = ids.subList(0, maxInputTokens - 1) + eosId
        val n = ids.size
        val maxNewTokens = minOf(maxLength, n * 3 + 10)

        val inputIds = longTensor(LongArray(n) { ids[it].toLong() }, longArrayOf(1, n.toLong()))
        val mask = longTensor(LongArray(n) { 1L }, longArrayOf(1, n.toLong()))
        // First step: no cache yet. The merged decoder still wants the past inputs, so pass empty ones.
        val emptyPast = OnnxTensor.createTensor(
            env, FloatBuffer.allocate(0), longArrayOf(1, heads.toLong(), 0, headDim.toLong())
        )
        val noCache = OnnxTensor.createTensor(env, booleanArrayOf(false))
        val withCache = OnnxTensor.createTensor(env, booleanArrayOf(true))

        var encoderResult: OrtSession.Result? = null
        var firstResult: OrtSession.Result? = null // holds the cross-attention cache
        var lastResult: OrtSession.Result? = null  // holds the self-attention cache
        val output = ArrayList<Int>()

        try {
            encoderResult = encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to mask))
            val hidden = encoderResult.get("last_hidden_state").get() as OnnxTensor

            val past = HashMap<String, OnnxTensor>()
            pastNames.forEach { past[it] = emptyPast }
            var token = decoderStartId

            for (step in 0 until maxNewTokens) {
                val tokenTensor = longTensor(longArrayOf(token.toLong()), longArrayOf(1, 1))
                val candidates = HashMap<String, OnnxTensor>(past)
                candidates["input_ids"] = tokenTensor
                candidates["encoder_attention_mask"] = mask
                candidates["encoder_hidden_states"] = hidden
                candidates["use_cache_branch"] = if (step == 0) noCache else withCache
                val wanted = decoder.inputNames

                val result = try {
                    decoder.run(candidates.filterKeys { it in wanted })
                } finally {
                    tokenTensor.close()
                }

                val next = argmaxLastPosition(result.get("logits").get() as OnnxTensor)

                for (entry in result) {
                    val name = entry.key
                    if (!name.startsWith("present")) continue
                    val pastName = "past_key_values" + name.removePrefix("present")
                    // The cross-attention cache is computed once, on the first step.
                    if (name.contains(".encoder.") && step > 0) continue
                    past[pastName] = entry.value as OnnxTensor
                }

                if (step == 0) {
                    firstResult = result
                } else if (lastResult != null && lastResult !== firstResult) {
                    lastResult.close() // the previous step's cache is no longer referenced
                }
                lastResult = result

                if (next == eosId) break
                output.add(next)
                token = next
            }
        } finally {
            if (lastResult != null && lastResult !== firstResult) lastResult.close()
            firstResult?.close()
            encoderResult?.close()
            inputIds.close()
            mask.close()
            emptyPast.close()
            noCache.close()
            withCache.close()
        }
        return tokenizer.decode(output)
    }

    private fun argmaxLastPosition(logits: OnnxTensor): Int {
        val shape = logits.info.shape // [batch, seq, vocab]
        val vocabSize = shape[2].toInt()
        val seq = shape[1].toInt()
        val buffer = logits.floatBuffer
        val offset = (seq - 1) * vocabSize
        var best = eosId
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) {
            if (i == padId) continue // Marian never generates <pad>
            val v = buffer.get(offset + i)
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best
    }

    override fun close() {
        encoder.close()
        decoder.close()
        options.close()
    }
}
