package com.screentranslate.translator

import java.io.InputStream
import java.text.Normalizer

/**
 * Minimal SentencePiece (unigram) tokenizer for Marian / OPUS-MT models.
 *
 * Inputs are plain-text dumps made by tools/prepare_model.py:
 *  - pieces: `piece \t score \t type` per line (from source.spm)
 *  - vocab:  `token \t id` per line (source side, from vocab.json)
 *  - targetVocab: same format, used for decoding (differs only for models with separate vocabs)
 */
class SentencePieceTokenizer(
    pieces: InputStream,
    vocab: InputStream,
    targetVocab: InputStream,
    private val unkId: Int,
    private val eosId: Int,
    private val padId: Int,
) {
    private val pieceScores = HashMap<String, Float>()
    private var maxPieceLength = 1 // in code points
    private val unkScore: Float
    private val sourceVocab = HashMap<String, Int>()
    private val idToToken: Array<String?>

    init {
        var minScore = 0f
        pieces.bufferedReader(Charsets.UTF_8).useLines { it.forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || parts[0].isEmpty()) return@forEach
            val piece = parts[0]
            val score = parts[1].toFloat()
            val type = parts[2].toInt()
            if (type == TYPE_NORMAL || type == TYPE_USER_DEFINED) {
                pieceScores[piece] = score
                maxPieceLength = maxOf(maxPieceLength, piece.codePointCount(0, piece.length))
            }
            if (score < minScore) minScore = score
        } }
        unkScore = minScore - 10f

        readVocab(vocab) { token, id -> sourceVocab[token] = id }
        val target = HashMap<String, Int>()
        readVocab(targetVocab) { token, id -> target[token] = id }
        idToToken = arrayOfNulls((target.values.maxOrNull() ?: 0) + 1)
        for ((token, id) in target) idToToken[id] = token
    }

    private inline fun readVocab(input: InputStream, add: (String, Int) -> Unit) {
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val tab = line.lastIndexOf('\t')
                if (tab > 0) add(line.substring(0, tab), line.substring(tab + 1).toInt())
            }
        }
    }

    /** Approximates SentencePiece's nmt_nfkc normalizer plus whitespace folding. */
    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var lastWasSpace = true
        for (ch in nfkc) {
            if (ch.isWhitespace() || ch.isISOControl()) {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(ch)
                lastWasSpace = false
            }
        }
        return sb.toString().trim()
    }

    /** Viterbi segmentation into the most likely sequence of pieces. */
    fun encodePieces(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        val s = SPACE + normalized.replace(' ', SPACE)
        val cps = s.codePoints().toArray()
        val n = cps.size

        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backLength = IntArray(n + 1)
        val backUnknown = BooleanArray(n + 1)
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            var singleMatched = false
            val limit = minOf(maxPieceLength, n - i)
            for (len in 1..limit) {
                val score = pieceScores[String(cps, i, len)] ?: continue
                if (len == 1) singleMatched = true
                val candidate = best[i] + score
                if (candidate > best[i + len]) {
                    best[i + len] = candidate
                    backLength[i + len] = len
                    backUnknown[i + len] = false
                }
            }
            if (!singleMatched) {
                val candidate = best[i] + unkScore
                if (candidate > best[i + 1]) {
                    best[i + 1] = candidate
                    backLength[i + 1] = 1
                    backUnknown[i + 1] = true
                }
            }
        }

        // Backtrack; like SentencePiece, a run of unknown characters becomes one piece.
        val pieces = ArrayList<String>()
        var pos = n
        var previousUnknown = false
        while (pos > 0) {
            val len = backLength[pos]
            val piece = String(cps, pos - len, len)
            val unknown = backUnknown[pos]
            if (unknown && previousUnknown) {
                pieces[pieces.size - 1] = piece + pieces[pieces.size - 1]
            } else {
                pieces.add(piece)
            }
            previousUnknown = unknown
            pos -= len
        }
        pieces.reverse()
        return pieces
    }

    /** Token ids as the model expects them, ending with </s>. */
    fun encode(text: String): List<Int> =
        encodePieces(text).map { sourceVocab[it] ?: unkId } + eosId

    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == padId || id == eosId || id == unkId) continue
            sb.append(idToToken.getOrNull(id) ?: continue)
        }
        return sb.toString().replace(SPACE, ' ').trim()
    }

    companion object {
        private const val SPACE = '▁'
        private const val TYPE_NORMAL = 1
        private const val TYPE_USER_DEFINED = 4
    }
}
