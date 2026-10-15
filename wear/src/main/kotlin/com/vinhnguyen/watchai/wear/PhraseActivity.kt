package io.github.alisial786.watchgpt.wear

import android.app.Application
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import io.github.alisial786.watchgpt.watchlink.WakePhrase
import io.github.alisial786.watchgpt.watchlink.WakePieces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Wake phrase": what the user says to wake WatchGPT, "Hey WatchGPT" or their own. Typed, said or written with
 * the watch's own input, or one of a few suggestions, and checked first ([WakePhrase]): one that's very short or
 * made only of everyday words isn't taken. Opened from WatchGPT's settings; it applies when listening starts.
 */
class PhraseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PhraseScreen(onClose = ::finish) } }
    }
}

internal class PhraseViewModel(
    application: Application,
) : AndroidViewModel(application) {
    sealed interface Step {
        data class Choosing(
            val current: String,
            val why: String? = null,
        ) : Step

        data object Checking : Step

        /** Changing it would drop what Learn my voice learned for [current]: asked first. */
        data class Confirm(
            val current: String,
            val next: String,
        ) : Step

        data class Saved(
            val phrase: String,
        ) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Choosing(WakeSetting.phrase(application)))
    val step: StateFlow<Step> = _step.asStateFlow()

    // How the model spells words (kws/bpe.model, about 250 KB) and how "Hey WatchGPT" is spelled: read once, off the main thread.
    private val pieces = viewModelScope.async(Dispatchers.IO) { WakePieces.read(application.assets.open("kws/bpe.model").use { it.readBytes() }) }
    private val defaults = viewModelScope.async(Dispatchers.IO) { VoiceTeacher.spellings(application.assets.open("kws/keywords.txt").bufferedReader().use { it.readText() }) }

    // A phrase waiting for the user's yes (Confirm): its text, and its lines (null for "Hey WatchGPT").
    private var waiting: Pair<String, String?>? = null

    fun choose(typed: String) {
        val app = getApplication<Application>()
        val current = WakeSetting.phrase(app)
        _step.value = Step.Checking
        viewModelScope.launch {
            val model =
                try {
                    pieces.await() to defaults.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            val (spell, default) =
                model ?: run {
                    _step.value = Step.Choosing(current, "Couldn't check it. Try again later")
                    return@launch
                }
            when (val check = WakePhrase.check(typed, spell)) {
                is WakePhrase.Check.Bad -> _step.value = Step.Choosing(current, check.why)
                is WakePhrase.Check.Ok -> settle(check, default, current)
            }
        }
    }

    private fun settle(
        check: WakePhrase.Check.Ok,
        default: List<List<String>>,
        current: String,
    ) {
        val app = getApplication<Application>()
        // Compared by spelling, not text: "Hey Watch G P T" or "hey watch gpt" is "Hey WatchGPT".
        val own = WakeSetting.phraseKeywords(app)?.let { VoiceTeacher.spellings(it) }
        if ((own ?: default).containsAll(check.spellings)) {
            // The same phrase again keeps what was learned of the user's voice; only how it's written may change.
            if (own != null) WakeSetting.renamePhrase(app, check.phrase)
            _step.value = Step.Saved(WakeSetting.phrase(app))
            return
        }
        val next = if (default.containsAll(check.spellings)) WakePhrase.DEFAULT to null else check.phrase to VoiceTeacher.keywords(check.spellings, name = WakeWord.OWN_NAME)
        waiting = next
        if (WakeSetting.keywords(app) != null) _step.value = Step.Confirm(current, next.first) else confirm()
    }

    /** Yes to the phrase waiting in [Step.Confirm] (or one that needed no asking). */
    fun confirm() {
        val (phrase, lines) = waiting ?: return
        waiting = null
        if (lines == null) CallService.phraseChanged(getApplication(), null, null) else CallService.phraseChanged(getApplication(), phrase, lines)
        _step.value = Step.Saved(phrase)
    }

    fun keep() {
        waiting = null
        _step.value = Step.Choosing(WakeSetting.phrase(getApplication()))
    }
}

/** A few that don't come up in everyday talk ("OK, watch this" rules out "OK Watch"). */
private val SUGGESTIONS = listOf(WakePhrase.DEFAULT, "Hey Jarvis", "Hello Computer")

private const val INPUT_KEY = "phrase"

@Composable
private fun PhraseScreen(
    onClose: () -> Unit,
    vm: PhraseViewModel = viewModel(),
) {
    val step by vm.step.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val input =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val typed = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)?.toString()
            if (!typed.isNullOrBlank()) vm.choose(typed)
        }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
            .verticalScroll(scroll)
            .padding(horizontal = 30.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = step) {
            is PhraseViewModel.Step.Choosing -> {
                Title("Wake phrase")
                Line("Now: \"${s.current}\"")
                s.why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { input.launch(typeIntent()) }, label = { Text("Type a new one") }, modifier = Modifier.fillMaxWidth())
                Hint("Two to five words, one of them not an everyday word, like a name")
                Spacer(Modifier.height(10.dp))
                for (suggestion in SUGGESTIONS.filterNot { it.equals(s.current, ignoreCase = true) }) {
                    FilledTonalButton(onClick = { vm.choose(suggestion) }, label = { Text(suggestion) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                }
            }

            PhraseViewModel.Step.Checking -> {
                Line("Checking…")
            }

            is PhraseViewModel.Step.Confirm -> {
                Title("Change it?")
                Line("WatchGPT learned your voice saying \"${s.current}\". \"${s.next}\" starts without that.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = vm::confirm, label = { Text("Change") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = vm::keep, label = { Text("Keep \"${s.current}\"") }, modifier = Modifier.fillMaxWidth())
            }

            is PhraseViewModel.Step.Saved -> {
                Title("Saved")
                Line("Say \"${s.phrase}\" to wake WatchGPT.")
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        context.startActivity(Intent(context, TeachActivity::class.java))
                        onClose()
                    },
                    label = { Text("Learn my voice") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("So it hears you say it better")
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onClose, label = { Text("Done") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The watch's own text input: keyboard, voice or handwriting, whichever the user picks. */
private fun typeIntent(): Intent {
    val phrase = RemoteInput.Builder(INPUT_KEY).setLabel("Wake phrase").build()
    return RemoteInputIntentHelper.createActionRemoteInputIntent().also { RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(phrase)) }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
