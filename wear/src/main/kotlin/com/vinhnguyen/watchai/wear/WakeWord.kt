package io.github.alisial786.watchgpt.wear

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import io.github.alisial786.watchgpt.watchlink.Leveller
import io.github.alisial786.watchgpt.watchlink.Pcm

/**
 * The wake phrase, heard on the watch itself: sherpa-onnx keyword spotting with a small English model
 * (assets/kws: "Hey WatchGPT" in keywords.txt or the user's own phrase, see [PhraseActivity], and their spellings once taught, see
 * [VoiceTeacher]). Loading takes about 20 s on a Galaxy Watch5 and listening keeps about 0.7 of
 * a core busy, so it's loaded once and fed only in short windows. The native side isn't
 * thread-safe (two threads decoding at once crashed the app, 27.09): every call takes the lock.
 */
internal class WakeWord(
    context: Context,
    keywords: String?,
) {
    private val spotter = KeywordSpotter(context.assets, CONFIG)
    private var stream = open(keywords)

    // sherpa-onnx adds keywords.txt to every stream: with the user's own phrase, "Hey WatchGPT" still fires, by its name.
    private var listening = nameOf(keywords)

    // Released (the wake phrase was switched off): a call still on its way must not reach the native side.
    private var released = false

    /** Feeds [n] samples of 16 kHz mono audio, [gain] times louder; true once the phrase was heard (it then starts over). */
    @Synchronized
    fun heard(
        pcm: ShortArray,
        n: Int,
        gain: Float = 1f,
    ): Boolean {
        if (released) return false
        stream.acceptWaveform(FloatArray(n) { (pcm[it] * gain / 32_768f).coerceIn(-1f, 1f) }, SAMPLE_RATE)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotEmpty()) {
                spotter.reset(stream)
                if (keyword == listening) return true
            }
        }
        return false
    }

    /** Listens for [keywords] (keywords.txt lines, "/" between them) from now on; null for the default phrase. */
    @Synchronized
    fun use(keywords: String?) {
        if (released) return
        val next = open(keywords)
        stream.release()
        stream = next
        listening = nameOf(keywords)
    }

    /**
     * Runs [pcm] through the same model with other [keywords] (keywords.txt lines) and returns the
     * phrase that fired, or null. Its own stream, so listening isn't disturbed. For teaching and
     * debug tuning; evened out like listening is.
     */
    @Synchronized
    fun firstKeyword(
        keywords: String,
        pcm: ShortArray,
    ): String? {
        if (released) return null
        val name = nameOf(keywords)
        val gain = Leveller.gainFor(peakLevel(pcm))
        val trial = spotter.createStream(keywords)
        try {
            settle(trial)
            // The clip, then half a second of quiet so a phrase at the very end can finish.
            val padded = pcm + ShortArray(SAMPLE_RATE / 2)
            for (start in padded.indices step SAMPLE_RATE / 10) {
                val end = minOf(start + SAMPLE_RATE / 10, padded.size)
                trial.acceptWaveform(FloatArray(end - start) { (padded[start + it] * gain / 32_768f).coerceIn(-1f, 1f) }, SAMPLE_RATE)
                while (spotter.isReady(trial)) {
                    spotter.decode(trial)
                    val keyword = spotter.getResult(trial).keyword
                    if (keyword == name) return keyword
                    if (keyword.isNotEmpty()) spotter.reset(trial)
                }
            }
            return null
        } finally {
            trial.release()
        }
    }

    /** Forgets what it heard so far, before a new listening window. */
    @Synchronized
    fun reset() {
        if (released) return
        spotter.reset(stream)
        settle(stream)
    }

    // An empty string means keywords.txt.
    private fun open(keywords: String?) = spotter.createStream(keywords.orEmpty()).also { settle(it) }

    /**
     * A moment of quiet first: a phrase that starts with the model's very first sound is often
     * missed (the mic opens just as the user starts talking). 23 of 28 test voices instead of 20.
     */
    private fun settle(s: OnlineStream) {
        s.acceptWaveform(QUIET, SAMPLE_RATE)
        while (spotter.isReady(s)) spotter.decode(s)
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        stream.release()
        spotter.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** What keywords.txt and lines learned for "Hey WatchGPT" are called (after "@"). */
        const val DEFAULT_NAME = "HEY_WATCHGPT"

        /** What the lines of the user's own phrase are called, and those learned for it. */
        const val OWN_NAME = "WAKE_PHRASE"

        /** The name of the phrase [keywords] listen for: their own, or "Hey WatchGPT" for keywords.txt. */
        fun nameOf(keywords: String?): String = if (keywords?.contains("@$OWN_NAME") == true) OWN_NAME else DEFAULT_NAME

        /** The loudest 100 ms of [pcm] (RMS, 0..1). */
        fun peakLevel(pcm: ShortArray): Float = (0 until pcm.size / (SAMPLE_RATE / 10)).maxOfOrNull { c -> Pcm.level(pcm.copyOfRange(c * SAMPLE_RATE / 10, (c + 1) * SAMPLE_RATE / 10)) } ?: Pcm.level(pcm)

        private const val MODEL = "epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        val QUIET = FloatArray(SAMPLE_RATE * 3 / 10)

        /** The model files, shared with [VoiceTeacher], which decodes with the same model. */
        val MODEL_CONFIG =
            OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = "kws/encoder-$MODEL", decoder = "kws/decoder-$MODEL", joiner = "kws/joiner-$MODEL"),
                tokens = "kws/tokens.txt",
                numThreads = 1,
                modelType = "zipformer2",
            )

        val FEATURES = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)

        private val CONFIG =
            KeywordSpotterConfig(
                featConfig = FEATURES,
                modelConfig = MODEL_CONFIG,
                // Must exist and be valid: the native side ends the process otherwise.
                keywordsFile = "kws/keywords.txt",
            )
    }
}
