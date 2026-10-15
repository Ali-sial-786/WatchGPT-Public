package io.github.alisial786.watchgpt.watchlink

import java.text.Normalizer

/**
 * How the wake word model spells words: the pieces of its sentencepiece model (bpe.model, next to the model) and
 * their scores. The model only listens for pieces, so a phrase the user types has to be spelled in them the way its
 * training text was: the split whose scores add up highest (a unigram model, despite the file name). The sum is in
 * single precision, as sentencepiece does it, so even ties come out the same.
 */
public class WakePieces private constructor(
    private val scores: Map<String, Float>,
) {
    private val longest = scores.keys.maxOf { it.length }

    /** Whether [piece] is one of the model's pieces. */
    public operator fun contains(piece: String): Boolean = piece in scores

    /** The pieces for [words] (upper case), word starts marked with ▁ as in keywords.txt; null if a letter has none. */
    public fun spell(words: List<String>): List<String>? {
        val text = words.joinToString("") { WORD_START + it }
        val best = FloatArray(text.length + 1)
        val from = IntArray(text.length + 1) { -1 }
        for (start in 0 until text.length) {
            if (start > 0 && from[start] < 0) continue
            for (end in start + 1..minOf(text.length, start + longest)) {
                val score = scores[text.substring(start, end)] ?: continue
                val total = best[start] + score
                if (from[end] < 0 || total > best[end]) {
                    best[end] = total
                    from[end] = start
                }
            }
        }
        if (from[text.length] < 0) return null
        val pieces = ArrayDeque<String>()
        var end = text.length
        while (end > 0) {
            pieces.addFirst(text.substring(from[end], end))
            end = from[end]
        }
        return pieces.toList()
    }

    public companion object {
        public const val WORD_START: String = "▁"

        /** Reads the normal pieces of a sentencepiece model file: not <unk> or the model's own symbols. */
        public fun read(model: ByteArray): WakePieces {
            val scores = HashMap<String, Float>()
            val file = Proto(model, 0, model.size)
            while (file.more()) {
                val tag = file.varint().toInt()
                if (tag != PIECE_TAG) {
                    file.skip(tag and WIRE_MASK)
                    continue
                }
                val entry = file.message()
                var piece: String? = null
                var score = 0f
                var type = NORMAL
                while (entry.more()) {
                    when (val field = entry.varint().toInt()) {
                        PIECE_TEXT -> piece = entry.text()
                        PIECE_SCORE -> score = entry.float()
                        PIECE_TYPE -> type = entry.varint().toInt()
                        else -> entry.skip(field and WIRE_MASK)
                    }
                }
                if (piece != null && type == NORMAL) scores[piece] = score
            }
            require(scores.isNotEmpty()) { "no pieces in the model file" }
            return WakePieces(scores)
        }

        // Field number and wire type: ModelProto.pieces (1, a message), and its piece (1, text), score (2, a float)
        // and type (3, a number).
        private const val PIECE_TAG = 1 shl 3 or 2
        private const val PIECE_TEXT = 1 shl 3 or 2
        private const val PIECE_SCORE = 2 shl 3 or 5
        private const val PIECE_TYPE = 3 shl 3
        private const val WIRE_MASK = 7
        private const val NORMAL = 1
    }

    /** Just enough protocol buffers to read the pieces. */
    private class Proto(
        private val bytes: ByteArray,
        private var at: Int,
        private val end: Int,
    ) {
        fun more() = at < end

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(at < end && shift < 64) { "broken model file" }
                val b = bytes[at++].toInt()
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        fun message(): Proto {
            val length = length()
            return Proto(bytes, at, at + length).also { at += length }
        }

        fun text(): String {
            val length = length()
            return String(bytes, at, length, Charsets.UTF_8).also { at += length }
        }

        fun float(): Float {
            require(at + 4 <= end) { "broken model file" }
            val bits = (0 until 4).fold(0) { acc, i -> acc or ((bytes[at + i].toInt() and 0xff) shl (8 * i)) }
            at += 4
            return Float.fromBits(bits)
        }

        fun skip(wire: Int) {
            val size =
                when (wire) {
                    0 -> 0.also { varint() }
                    1 -> 8
                    2 -> length()
                    5 -> 4
                    else -> throw IllegalArgumentException("broken model file")
                }
            require(size <= end - at) { "broken model file" }
            at += size
        }

        private fun length(): Int {
            val length = varint()
            require(length in 0..(end - at).toLong()) { "broken model file" }
            return length.toInt()
        }
    }
}

/**
 * A wake phrase the user chose instead of "Hey WatchGPT": checked, and spelled for the wake word model. The model
 * hears letters said one by one ("G P T") differently from a word, so a run of capitals in a mixed-case phrase
 * ("WatchGPT", "OK") is spelled three ways, as the default phrase is in keywords.txt: letters apart, run together,
 * and by their names ("GEE PEE TEE").
 */
public object WakePhrase {
    public const val DEFAULT: String = "Hey WatchGPT"

    public const val MIN_WORDS: Int = 2
    public const val MAX_WORDS: Int = 5

    /** Fewer pieces than this woke the watch in everyday talk ("hey be", "a bit": the broad test of 28.09). */
    public const val MIN_PIECES: Int = 5

    /** More than this doesn't fit one breath, and Learn my voice keeps no longer spellings. */
    public const val MAX_PIECES: Int = 20

    public sealed interface Check {
        /** [phrase] as shown on the screens; [spellings] in pieces, the way to say it most often first. */
        public data class Ok(
            val phrase: String,
            val spellings: List<List<String>>,
        ) : Check {
            /** How many words it's said in, letters one by one (as Learn my voice hears it). */
            val words: Int get() = spellings.first().count { it.startsWith(WakePieces.WORD_START) }
        }

        public data class Bad(
            val why: String,
        ) : Check
    }

    public fun check(
        typed: String,
        pieces: WakePieces,
    ): Check {
        val plain = Normalizer.normalize(typed, Normalizer.Form.NFD).replace(MARKS, "").replace(QUOTES, "'")
        if (plain.any { it.isDigit() }) return Check.Bad("Write numbers as words, like \"two\"")
        val raw = plain.split(NOT_WORD).map { it.trim('\'') }.filter { it.isNotEmpty() }
        if (raw.any { word -> word.any { !it.isAsciiLetter() && it != '\'' } }) return Check.Bad("Use English letters only")
        val words = words(raw)
        val spoken = words.sumOf { if (it.letters) it.text.length else 1 }
        if (spoken < MIN_WORDS) return Check.Bad("Use at least two words, like \"Hey Jarvis\"")
        if (spoken > MAX_WORDS) {
            return Check.Bad(if (words.any { it.letters }) "Use at most five words. Letters said one by one, like G P T, count one each" else "Use at most five words")
        }
        if (words.all { it.text in COMMON }) return Check.Bad("People say that all the time: it would wake by mistake. Add a less common word, like a name")
        val ways =
            listOf(
                words.flatMap { if (it.letters) it.text.map(Char::toString) else it.said },
                words.flatMap { it.said },
                words.flatMap { word -> if (word.letters) word.text.flatMap { NAMES.getValue(it) } else word.said },
            ).distinct()
        val spellings = ways.map { pieces.spell(it) ?: return Check.Bad("Use English letters only") }.distinct()
        if (spellings.any { it.size < MIN_PIECES }) return Check.Bad("That's too short: it would wake by mistake. Add a word")
        if (spellings.any { it.size > MAX_PIECES }) return Check.Bad("That's too long to say in one go")
        val shown = raw.joinToString(" ").replaceFirstChar { it.uppercase() }
        return Check.Ok(shown, spellings)
    }

    /** A word as typed (upper case), whether it's said letter by letter, and the words it's said as. */
    private class Word(
        val text: String,
        val letters: Boolean,
        val said: List<String> = listOf(text),
    )

    /**
     * The words of a typed phrase: camel case split ("WatchGPT"), a run of single letters put together ("G P T",
     * "G.P.T." are "GPT"), and abbreviations said in full ("Dr" is "DOCTOR").
     */
    private fun words(raw: List<String>): List<Word> {
        val mixed = raw.any { word -> word.any { it.isLowerCase() } }
        // A phrase typed as one word ("HeyJarvis") is split at every capital; otherwise "McDonald" stays whole.
        val split = if (raw.size == 1) ANY_CASE_CHANGE else CASE_CHANGE
        val parts = raw.flatMap { word -> word.split(split).filter { it.isNotEmpty() } }
        val words = mutableListOf<Word>()
        var i = 0
        while (i < parts.size) {
            var run = i
            while (run < parts.size && parts[run].length == 1 && parts[run][0].isLetter()) run++
            if (run - i >= 2) {
                words += Word(parts.subList(i, run).joinToString("").uppercase(), letters = true)
                i = run
                continue
            }
            val text = parts[i].uppercase()
            val full = ABBREVIATIONS[text]
            words += if (full != null) Word(text, letters = false, said = listOf(full)) else Word(text, isLetters(parts[i], mixed))
            i++
        }
        return words
    }

    /**
     * Said letter by letter: two or three capitals in a phrase that also has small letters ("GPT", "OK", "USA"), or a
     * word of up to five letters with no vowel at all ("TV", "gpt", "HTML"), which can't be said as one. Longer
     * capitals with a vowel ("NASA", "BUDDY") are words.
     */
    private fun isLetters(
        word: String,
        mixed: Boolean,
    ): Boolean = word.length in 2..5 && word.all { it.isLetter() } && ((mixed && word.length <= 3 && word.all { it.isUpperCase() }) || word.none { it.uppercaseChar() in VOWELS })

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private const val VOWELS = "AEIOUY"
    private val MARKS = Regex("\\p{Mn}+")
    private val QUOTES = Regex("[‘’ʼ`]")
    private val NOT_WORD = Regex("[\\s\\-_.,!?;:\"()]+")

    // "WatchGPT" is "Watch" and "GPT", "GPTWatch" "GPT" and "Watch"; "McDonald" and "iPhone" stay one word.
    private val CASE_CHANGE = Regex("(?<=[a-z'])(?=[A-Z]{2})|(?<=[A-Z])(?=[A-Z][a-z])")
    private val ANY_CASE_CHANGE = Regex("(?<=[a-z'])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    /** Said in full, so spelled in full. */
    private val ABBREVIATIONS =
        mapOf("MR" to "MISTER", "MRS" to "MISSUS", "MS" to "MIZ", "DR" to "DOCTOR", "ST" to "SAINT", "JR" to "JUNIOR", "SR" to "SENIOR")

    /**
     * Words people say all day: a phrase made only of these ("Hey watch", "Good morning", "I don't know") would wake
     * WatchGPT in ordinary talk, however many pieces it has. The most frequent English words, greetings and the ways
     * people call each other; one less common word (a name, "computer", "GPT") is enough.
     */
    private val COMMON: Set<String> =
        """
        A ABOUT ABOVE ACTUALLY AFTER AGAIN AGO ALL ALMOST ALONG ALREADY ALRIGHT ALSO ALWAYS AM AN AND ANOTHER ANY ANYBODY
        ANYONE ANYTHING ANYWAY ARE AREN'T AROUND AS ASK AT AWAY AWESOME BABE BABY BACK BAD BE BECAUSE BEEN BEFORE BEING
        BELOW BEST BETTER BETWEEN BIG BIT BOTH BRING BRO BUDDY BUT BY BYE CALL CAME CAN CAN'T CANNOT CHECK CHEERS COME
        COMING COOL COULD COULDN'T DAD DAY DEAR DID DIDN'T DO DOES DOESN'T DOING DON'T DONE DOWN DUDE EACH EARLY EASY
        EAT ELSE ENOUGH EVEN EVENING EVER EVERY EVERYBODY EVERYONE EVERYTHING EXCUSE FIND FINE FIRST FOR FRIEND FROM GET
        GETS GETTING GIRL GIVE GO GOES GOING GONNA GOOD GOODBYE GOT GOTTA GREAT GUY GUYS HAD HAS HAVE HAVEN'T
        HAVING HE HE'S HEAR HELLO HELP HER HERE HERE'S HEY HI HIM HIS HOLD HOME HONEY HOW HOW'S I I'D I'LL I'M I'VE IF IN
        INTO IS ISN'T IT IT'S ITS JUST KEEP KID KIDS KIND KNOW LADY LAST LATE LATER LET LET'S LIKE LISTEN LITTLE LOOK
        LOOKING LOT LOTS LOVE MAKE MAN MANY MATE MAY MAYBE ME MEAN MIGHT MINE MINUTE MOM MOMENT MORE MORNING MOST MUCH
        MUM MUST MY NAME NEED NEVER NEW NEXT NICE NIGHT NO NOPE NOT NOTHING NOW OF OFF OH OK OKAY OLD ON ONCE ONE ONLY
        OOPS OR OTHER OUR OUT OVER OWN PEOPLE PHONE PLEASE PRETTY PUT QUICK QUITE REALLY RIGHT SAID SAME SAW SAY SEE
        SEEN SHE SHE'S SHOULD SHOW SINCE SIR SO SOME SOMEBODY SOMEONE SOMETHING SOON SORRY SOUNDS STILL STOP SUCH SURE
        SWEETIE TAKE TALK TELL THAN THANK THANKS THAT THAT'S THE THEIR THEM THEN THERE THERE'S THESE THEY THEY'RE THING
        THINGS THINK THIS THOSE THROUGH TIME TO TODAY TOMORROW TONIGHT TOO TRY TURN TWO UH UM UNDER UNTIL UP US USE
        VERY WAIT WANNA WANT WAS WASN'T WATCH WAY WE WE'RE WELL WENT WERE WHAT WHAT'S WHEN WHERE WHICH WHILE WHO WHY
        WILL WITH WON'T WOULD WOW YEAH YEAR YES YET YO YOU YOU'RE YOUR YOURSELF
        """.trim().split(Regex("\\s+")).toSet()

    /** How the letters are said, spelled as the model's training text would ("G" is "GEE", as in keywords.txt). */
    private val NAMES: Map<Char, List<String>> =
        mapOf(
            'A' to "A", 'B' to "BEE", 'C' to "SEE", 'D' to "DEE", 'E' to "E", 'F' to "EF", 'G' to "GEE",
            'H' to "AITCH", 'I' to "I", 'J' to "JAY", 'K' to "KAY", 'L' to "EL", 'M' to "EM", 'N' to "EN",
            'O' to "O", 'P' to "PEE", 'Q' to "CUE", 'R' to "ARE", 'S' to "ESS", 'T' to "TEE", 'U' to "YOU",
            'V' to "VEE", 'W' to "DOUBLE YOU", 'X' to "EX", 'Y' to "WHY", 'Z' to "ZEE",
        ).mapValues { it.value.split(' ') }
}
