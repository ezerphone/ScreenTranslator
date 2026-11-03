package com.screentranslate.translator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import java.util.Properties

/**
 * Offline Marian (OPUS-MT) translator running on ONNX Runtime.
 *
 * Expects in [modelDir]: encoder.onnx, decoder.onnx, decoder_past.onnx (optimum export,
 * int8-quantized), source.tsv, vocab.tsv and config.properties — see tools/prepare_model.py.
 * Uses greedy decoding with a key/value cache.
 */
class MarianTranslator(modelDir: File, intraOpThreads: Int = 0) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val config = Properties().apply {
        File(modelDir, "config.properties").inputStream().use { load(it) }
    }
    private val padId = config.getProperty("pad_id").trim().toInt()
    private val eosId = config.getProperty("eos_id").trim().toInt()
    private val unkId = config.getProperty("unk_id").trim().toInt()
    private val decoderStartId = config.getProperty("decoder_start_id").trim().toInt()
    private val maxLength = config.getProperty("max_length", "512").trim().toInt()

    val tokenizer = SentencePieceTokenizer(
        File(modelDir, "source.tsv"),
        File(modelDir, "vocab.tsv"),
        unkId = unkId,
        eosId = eosId,
        padId = padId,
    )

    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (intraOpThreads > 0) setIntraOpNumThreads(intraOpThreads)
    }
    private val encoder = env.createSession(File(modelDir, "encoder.onnx").path, options)
    private val decoder = env.createSession(File(modelDir, "decoder.onnx").path, options)
    private val decoderPast = env.createSession(File(modelDir, "decoder_past.onnx").path, options)

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
        var encoderResult: OrtSession.Result? = null
        var firstResult: OrtSession.Result? = null // holds the cross-attention cache
        var lastResult: OrtSession.Result? = null  // holds the self-attention cache
        val output = ArrayList<Int>()

        try {
            encoderResult = encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to mask))
            val hidden = encoderResult.get("last_hidden_state").get() as OnnxTensor

            val encoderPast = HashMap<String, OnnxTensor>()
            var decoderPastValues: Map<String, OnnxTensor> = emptyMap()
            var token = decoderStartId

            for (step in 0 until maxNewTokens) {
                val session = if (step == 0) decoder else decoderPast
                val tokenTensor = longTensor(longArrayOf(token.toLong()), longArrayOf(1, 1))

                val candidates = HashMap<String, OnnxTensor>()
                candidates["input_ids"] = tokenTensor
                candidates["encoder_attention_mask"] = mask
                candidates["encoder_hidden_states"] = hidden
                candidates.putAll(encoderPast)
                candidates.putAll(decoderPastValues)
                val wanted = session.inputNames
                val inputs = candidates.filterKeys { it in wanted }

                val result = try {
                    session.run(inputs)
                } finally {
                    tokenTensor.close()
                }

                val next = argmaxLastPosition(result.get("logits").get() as OnnxTensor)

                val newDecoderPast = HashMap<String, OnnxTensor>()
                for (entry in result) {
                    val name = entry.key
                    if (!name.startsWith("present")) continue
                    val pastName = "past_key_values" + name.removePrefix("present")
                    val value = entry.value as OnnxTensor
                    if (name.contains(".encoder.")) {
                        if (step == 0) encoderPast[pastName] = value
                    } else {
                        newDecoderPast[pastName] = value
                    }
                }
                decoderPastValues = newDecoderPast

                if (step == 0) {
                    firstResult = result
                } else {
                    // The previous step's cache is no longer referenced.
                    if (lastResult != null && lastResult !== firstResult) lastResult.close()
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
        decoderPast.close()
        options.close()
    }
}
