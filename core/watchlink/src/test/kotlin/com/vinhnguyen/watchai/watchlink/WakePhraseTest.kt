package io.github.alisial786.watchgpt.watchlink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakePhraseTest {
    // The wake word model's own sentencepiece file (kws/bpe.model in the app); expected spellings are sentencepiece's.
    private val pieces = WakePieces.read(javaClass.getResourceAsStream("/kws/bpe.model")!!.use { it.readBytes() })

    private fun spell(text: String) = pieces.spell(text.split(' '))?.joinToString(" ")

    private fun ok(typed: String) = WakePhrase.check(typed, pieces) as WakePhrase.Check.Ok

    private fun why(typed: String) = (WakePhrase.check(typed, pieces) as WakePhrase.Check.Bad).why

    @Test
    fun `words are spelled as sentencepiece spells them`() {
        assertThat(spell("HEY JARVIS")).isEqualTo("▁HE Y ▁JA R VI S")
        assertThat(spell("OK COMPUTER")).isEqualTo("▁O K ▁COMP U TER")
        assertThat(spell("HEY MISTER SMITH")).isEqualTo("▁HE Y ▁MI S TER ▁S M I TH")
        assertThat(spell("WHAT'S UP BUDDY")).isEqualTo("▁WHAT ' S ▁UP ▁BU D D Y")
        // A word start the model has no piece for comes out on its own.
        assertThat(spell("MERRY CHRISTMAS")).isEqualTo("▁ME R RY ▁ CH R IST MA S")
        assertThat(spell("HEY T V")).isEqualTo("▁HE Y ▁T ▁ V")
    }

    @Test
    fun `equal splits are settled as sentencepiece settles them`() {
        // "L LL" and "LL L" score the same; sentencepiece's single-precision sums keep the second.
        assertThat(spell("NALLLT")).isEqualTo("▁NA LL L T")
    }

    @Test
    fun `a letter the model has no piece for can't be spelled`() {
        assertThat(pieces.spell(listOf("HÉ"))).isNull()
    }

    @Test
    fun `the default phrase comes out as the shipped keywords`() {
        val phrase = ok(WakePhrase.DEFAULT)
        assertThat(phrase.phrase).isEqualTo("Hey WatchGPT")
        assertThat(phrase.words).isEqualTo(5)
        // wear/src/main/assets/kws/keywords.txt, line by line.
        assertThat(phrase.spellings.map { it.joinToString(" ") })
            .containsExactly("▁HE Y ▁WA T CH ▁G ▁P ▁T", "▁HE Y ▁WA T CH ▁G P T", "▁HE Y ▁WA T CH ▁G E E ▁P E E ▁T E E")
            .inOrder()
    }

    @Test
    fun `the suggestions are accepted`() {
        for (suggestion in listOf("Hey WatchGPT", "Hey Jarvis", "Hello Computer")) ok(suggestion)
    }

    @Test
    fun `a phrase of plain words has one spelling`() {
        val phrase = ok("hey jarvis")
        assertThat(phrase.phrase).isEqualTo("Hey jarvis")
        assertThat(phrase.words).isEqualTo(2)
        assertThat(phrase.spellings.map { it.joinToString(" ") }).containsExactly("▁HE Y ▁JA R VI S")
        // Typed in capitals, the words are still words.
        assertThat(ok("HEY JARVIS").spellings).hasSize(1)
    }

    @Test
    fun `letters said one by one are spelled three ways`() {
        assertThat(ok("OK Jarvis").spellings.map { it.joinToString(" ") })
            .containsExactly("▁O ▁K ▁JA R VI S", "▁O K ▁JA R VI S", "▁O ▁K A Y ▁JA R VI S")
            .inOrder()
        // A word with no vowel can't be said as one, in any case.
        assertThat(ok("hey gpt watch").spellings.first().joinToString(" ")).isEqualTo("▁HE Y ▁G ▁P ▁T ▁WA T CH")
    }

    @Test
    fun `the default phrase typed or said another way spells the same`() {
        val default = ok(WakePhrase.DEFAULT).spellings
        for (typed in listOf("Hey Watch GPT", "hey watch gpt", "Hey Watch G P T", "Hey Watch G.P.T.", "HeyWatchGPT")) {
            assertThat(ok(typed).spellings).isEqualTo(default)
        }
    }

    @Test
    fun `longer capitals with a vowel are words, and abbreviations are said in full`() {
        val nasa = ok("Hey NASA")
        assertThat(nasa.words).isEqualTo(2)
        assertThat(nasa.spellings).hasSize(1)
        assertThat(ok("Hello Dr Watson").spellings.single().joinToString(" ")).isEqualTo("▁HE LL O ▁DO C T OR ▁WA T S ON")
        // Typed as one word, it's split at the capitals.
        assertThat(ok("HeyJarvis").spellings.single().joinToString(" ")).isEqualTo("▁HE Y ▁JA R VI S")
    }

    @Test
    fun `punctuation, accents and curly apostrophes are tidied away`() {
        assertThat(ok("  Hé, Jarvis! ").phrase).isEqualTo("He Jarvis")
        assertThat(ok("What’s up Jarvis").spellings.single().joinToString(" ")).isEqualTo("▁WHAT ' S ▁UP ▁JA R VI S")
        assertThat(ok("hey-there computer").phrase).isEqualTo("Hey there computer")
    }

    @Test
    fun `a phrase that would wake by mistake is refused`() {
        assertThat(why("Jarvis")).contains("at least two words")
        assertThat(why("hey there my good old watch")).contains("at most five words")
        // Each letter is a word: G P T makes six, and the message says so.
        assertThat(why("hey there my GPT")).contains("count one each")
        // Said all day, however long.
        for (everyday in listOf("Hey watch", "OK Watch", "hey watch this", "Good morning", "I don't know", "Excuse me", "Sounds good", "Hey how are you", "Can you hear me", "Hey BUDDY")) {
            assertThat(why(everyday)).contains("People say that all the time")
        }
        assertThat(why("Hey AI")).contains("too short")
        // Said "tee vee" it's long enough, but run together ("▁T V") it isn't.
        assertThat(why("Hey TV")).contains("too short")
        assertThat(why("Hey R2D2")).contains("numbers as words")
        assertThat(why("Привет Jarvis")).contains("English letters")
        assertThat(why("hey & you")).contains("English letters")
    }

    @Test
    fun `a broken model file is refused`() {
        val bytes = javaClass.getResourceAsStream("/kws/bpe.model")!!.use { it.readBytes() }
        val failure = runCatching { WakePieces.read(bytes.copyOf(1_000)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { WakePieces.read(ByteArray(0)) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `only the model's normal pieces are read`() {
        assertThat("▁HE" in pieces).isTrue()
        assertThat("<unk>" in pieces).isFalse()
        assertThat("<blk>" in pieces).isFalse()
    }
}
