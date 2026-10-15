package io.github.alisial786.watchgpt.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import io.github.alisial786.watchgpt.buddy.Act
import io.github.alisial786.watchgpt.buddy.ui.WatchGPTSurface
import io.github.alisial786.watchgpt.watchlink.WakePhrase
import io.github.alisial786.watchgpt.wear.PhoneVoiceLink.Phase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TAG = "WatchLink"

class WearActivity : ComponentActivity() {
    private val ambient = MutableStateFlow(false)

    private val ambientObserver =
        AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    ambient.value = true
                }

                override fun onExitAmbient() {
                    ambient.value = false
                }
            },
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        io.github.alisial786.watchgpt.organizer.Organizer.get(this).requestSync()
        if (savedInstanceState == null && intent.getBooleanExtra("organizer", false)) {
            io.github.alisial786.watchgpt.organizer.OrganizerActivity.open(this)
        }
        // Wrist down: stay on the face in ambient mode instead of going back to the watch face.
        lifecycle.addObserver(ambientObserver)
        // Opening the app is the "press to talk": from the launcher, the tile or the side button.
        // Tests open it with "quiet" to only switch "Hey WatchGPT" back on after an install.
        if (savedInstanceState == null && !intent.getBooleanExtra(QUIET, false)) talkNow()
        setContent {
            val isAmbient by ambient.collectAsStateWithLifecycle()
            MaterialTheme { WatchScreen(ambient = isAmbient) }
        }
    }

    override fun onStart() {
        super.onStart()
        PhoneVoiceLink.get(this).onScreen = true
        CallService.faceShown(this)
        // "Hey WatchGPT" is on but not running (the watch restarted, or Android stopped it): the app is on screen, so it can start.
        if (WakeSetting.isOn(this) && !CallService.armed.value && micAllowed()) CallService.wakeOn(this)
    }

    override fun onStop() {
        PhoneVoiceLink.get(this).onScreen = false
        super.onStop()
    }

    /** Something came over the face mid-conversation (media controls, the volume panel): the log says when. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val phase = PhoneVoiceLink.get(this).state.value.phase
        if (phase != Phase.IDLE && phase != Phase.ERROR) Log.i(TAG, if (hasFocus) "face back in front" else "something came over the face")
    }

    /** Opened again while already running (single task): talk again, unless a conversation is on. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("organizer", false)) {
            io.github.alisial786.watchgpt.organizer.OrganizerActivity.open(this)
        } else if (!intent.getBooleanExtra(QUIET, false)) {
            talkNow()
        }
    }

    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // Opened by a button or gesture without the mic allowed: ask, and talk once it is, instead of doing nothing.
    // The old Fragment library Play services brings in only breaks this for a FragmentActivity; this isn't one.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) talkNow() }

    /** Starts a conversation if none is on; without the mic permission it asks for it first. */
    private fun talkNow() {
        if (!micAllowed()) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val link = PhoneVoiceLink.get(this)
        val phase = link.state.value.phase
        if (phase == Phase.IDLE || phase == Phase.ERROR) lifecycleScope.launch { link.start() }
    }

    private companion object {
        const val QUIET = "quiet"
    }
}

class WatchViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val link = PhoneVoiceLink.get(application)

    fun toggle() {
        val state = link.state.value
        // Answers as media can't be talked over: a tap stops the answer instead of ending the conversation.
        if (state.tapStopsAnswer) {
            link.interrupt()
            return
        }
        viewModelScope.launch {
            val phase = link.state.value.phase
            if (phase == Phase.IDLE || phase == Phase.ERROR) link.start() else link.stop()
        }
    }

    fun setWake(on: Boolean) {
        if (on) CallService.wakeOn(getApplication()) else CallService.wakeOff(getApplication())
    }

    val always = MutableStateFlow(WakeSetting.isAlways(application))

    private val audio = application.getSystemService(AudioManager::class.java)
    private var turned = 0f
    private var hideVolume: Job? = null

    /** The answer's volume as just set, "4 of 7", for a moment after the bezel turned; null otherwise. */
    val volumeShown = MutableStateFlow<String?>(null)

    /**
     * The bezel (or crown) turned during a conversation: answers louder or quieter. They play as a call, for the echo
     * cancelling that lets the user talk over them, so it's the call volume, which the watch's own settings only offer
     * during a phone call; or as media, if the user chose that on the phone. Android keeps it for the next conversation.
     */
    fun turn(pixels: Float) {
        turned += pixels
        if (abs(turned) < VOLUME_STEP_PX) return
        val louder = turned > 0
        turned = 0f
        val asMedia = link.state.value.answersAsMedia
        val stream = if (asMedia) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL
        runCatching { audio.adjustStreamVolume(stream, if (louder) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0) }
        // Which volume it is, so the user can see whether answers play as media or as a call.
        volumeShown.value = "${if (asMedia) "Media" else "Call"} volume ${audio.getStreamVolume(stream)} of ${audio.getStreamMaxVolume(stream)}"
        Log.i(TAG, "bezel: ${volumeShown.value}")
        hideVolume?.cancel()
        hideVolume =
            viewModelScope.launch {
                delay(VOLUME_SHOWN_MS)
                volumeShown.value = null
            }
    }

    private companion object {
        const val VOLUME_STEP_PX = 30f
        const val VOLUME_SHOWN_MS = 1_500L
    }

    fun setAlways(on: Boolean) {
        always.value = on
        CallService.listenAlways(getApplication(), on)
    }

    // No onCleared clean-up: a conversation outlives the screen (the call service keeps it) and
    // ends from the face, the notification's End action, or the phone.
}

/** The whole watch app for now: the face. Tap it to start or end a conversation with the phone. */
@Composable
private fun WatchScreen(
    ambient: Boolean,
    vm: WatchViewModel = viewModel(),
) {
    val state by vm.link.state.collectAsStateWithLifecycle()
    val wakeOn by CallService.armed.collectAsStateWithLifecycle()
    val wakeListening by WakeListener.listening.collectAsStateWithLifecycle()
    val always by vm.always.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val taught by remember { WakeSetting.taught(context) }.collectAsStateWithLifecycle()
    val phraseShown by remember { WakeSetting.phraseShown(context) }.collectAsStateWithLifecycle()
    val phrase = phraseShown ?: WakePhrase.DEFAULT
    val activity = LocalActivity.current
    val active = state.phase != Phase.IDLE && state.phase != Phase.ERROR
    // The screen stays on for the whole conversation.
    DisposableEffect(active) {
        val window = activity?.window
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.toggle() }
    // Notifications only bring the face up over the watch face; "Hey WatchGPT" works without them.
    val wakePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) vm.setWake(true)
        }
    val onTap = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.toggle()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val genes by vm.link.genes.collectAsStateWithLifecycle()
    val screen = LocalConfiguration.current
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    // Talking, the bezel is the answer's volume; otherwise it scrolls to the settings.
    LaunchedEffect(active) { focus.requestFocus() }
    val volumeShown by vm.volumeShown.collectAsStateWithLifecycle()
    // One screen: WatchGPT and a line of text. The one setting is a scroll away (bezel or swipe).
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (active) {
                    Modifier
                        .onRotaryScrollEvent {
                            vm.turn(it.verticalScrollPixels)
                            true
                        }.focusRequester(focus)
                        .focusable()
                } else {
                    Modifier.rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
                },
            )
            .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.fillMaxWidth().height(screen.screenHeightDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val shownAct = act(state.phase, wakeOn && wakeListening)
            WatchGPTSurface(
                genes = genes,
                act = shownAct,
                reaction = state.reaction,
                reactionId = state.reactionId,
                level = if (ambient) 0f else state.level,
                paper = Color.Black,
                ambient = ambient,
                // Every frame costs the watch's small cores: 10 a second, 15 while WatchGPT talks (the mouth), 24 while a reaction plays.
                fps = if (shownAct == Act.SPEAK) 15 else 10,
                busyFps = 24,
                modifier =
                Modifier
                    .size((screen.screenWidthDp * 0.74f).dp)
                    .clickable(onClickLabel = tapLabel(state), role = Role.Button, onClick = onTap),
            )
            Text(
                volumeShown ?: label(state, wakeOn && wakeListening, phrase),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            val earbuds =
                when {
                    !active -> null
                    !state.micOnWatch -> "Using your earbuds"
                    !state.answersOnWatch -> "Answers in your earbuds"
                    else -> null
                }
            earbuds?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!active && !ambient) {
            FilledTonalButton(
                onClick = { io.github.alisial786.watchgpt.organizer.OrganizerActivity.open(context) },
                label = { Text("Reminders") },
                secondaryLabel = { Text("Your tasks & agenda") },
                modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            SwitchButton(
                checked = wakeOn,
                onCheckedChange = { on ->
                    val missing = WAKE_PERMISSIONS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (on && missing.isNotEmpty()) wakePermission.launch(missing.toTypedArray()) else vm.setWake(on)
                },
                label = { Text(phrase) },
                modifier = Modifier.padding(horizontal = 36.dp),
            )
            Spacer(Modifier.height(4.dp))
            // Their own words instead of "Hey WatchGPT"; chosen before or after switching it on.
            FilledTonalButton(
                onClick = { context.startActivity(Intent(context, PhraseActivity::class.java)) },
                label = { Text("Change phrase") },
                secondaryLabel = { Text("Your own words") },
                modifier = Modifier.padding(horizontal = 36.dp).fillMaxWidth(),
            )
            if (wakeOn) {
                Spacer(Modifier.height(4.dp))
                // Without it the phrase needs a raised wrist first; with it there's no hand needed at all.
                SwitchButton(
                    checked = always,
                    onCheckedChange = vm::setAlways,
                    label = { Text("Always listening") },
                    secondaryLabel = { Text("Uses more battery") },
                    modifier = Modifier.padding(horizontal = 36.dp),
                )
                Spacer(Modifier.height(4.dp))
                // When the phrase misses the user: five sentences read out teach it their voice.
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(context, TeachActivity::class.java)) },
                    label = { Text("Learn my voice") },
                    secondaryLabel = { Text(if (taught == true) "Learned" else "Read 5 sentences") },
                    modifier = Modifier.padding(horizontal = 36.dp).fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** What the conversation looks like on WatchGPT. */
private fun act(
    phase: Phase,
    wakeListening: Boolean,
): Act = when (phase) {
    Phase.IDLE -> if (wakeListening) Act.AWAKE else Act.REST
    Phase.CONNECTING -> Act.CONNECT
    Phase.LISTENING -> Act.LISTEN
    Phase.THINKING -> Act.THINK
    Phase.SPEAKING -> Act.SPEAK
    Phase.ERROR -> Act.ERROR
}

/** What tapping the face does now. */
private fun tapLabel(state: PhoneVoiceLink.State) = when {
    state.phase == Phase.IDLE || state.phase == Phase.ERROR -> "Start talking"
    state.tapStopsAnswer -> "Stop the answer"
    else -> "End the conversation"
}

/** What to do now; a long phrase doesn't fit under the face. */
private fun idle(
    wakeListening: Boolean,
    phrase: String,
) = when {
    !wakeListening -> "Tap to talk"
    phrase.length <= MAX_SHOWN -> "Tap or say \"$phrase\""
    else -> "Tap or say your phrase"
}

private const val MAX_SHOWN = 24

private val WAKE_PERMISSIONS = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

private fun label(
    state: PhoneVoiceLink.State,
    wakeListening: Boolean,
    phrase: String,
): String = when (state.phase) {
    Phase.IDLE -> idle(wakeListening, phrase)
    Phase.CONNECTING -> state.detail ?: "Connecting…"
    Phase.LISTENING -> "Listening"
    Phase.THINKING -> "Thinking…"
    Phase.SPEAKING -> if (state.tapStopsAnswer) "Speaking · tap to interrupt" else "Speaking"
    Phase.ERROR -> state.detail ?: "Something went wrong"
}
