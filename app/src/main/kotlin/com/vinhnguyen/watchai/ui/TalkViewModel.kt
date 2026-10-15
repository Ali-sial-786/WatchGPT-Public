package io.github.alisial786.watchgpt.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alisial786.watchgpt.AppGraph
import io.github.alisial786.watchgpt.PhoneTalkService
import io.github.alisial786.watchgpt.actions.ConversationActions
import io.github.alisial786.watchgpt.actions.OwnerPresence
import io.github.alisial786.watchgpt.actions.Pending
import io.github.alisial786.watchgpt.actions.SleepActions
import io.github.alisial786.watchgpt.actions.VoiceReply
import io.github.alisial786.watchgpt.actions.WatchGPTCard
import io.github.alisial786.watchgpt.brain.Toolboxes
import io.github.alisial786.watchgpt.brain.chatgpt.ChatGptHttp
import io.github.alisial786.watchgpt.brain.chatgpt.auth.AuthState
import io.github.alisial786.watchgpt.buddy.Reaction
import io.github.alisial786.watchgpt.voice.ChatGptRealtimeSession
import io.github.alisial786.watchgpt.voice.VoicePhase
import io.github.alisial786.watchgpt.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Talking to WatchGPT on the phone, like on the watch: tap WatchGPT, talk, tap again or say bye. ChatGPT
 * voice on the user's own plan, with everything WatchGPT can do. A conversation from the watch shows
 * here too, and a tap ends it. It keeps going when the user switches apps (the talk service holds
 * the mic); without that service, it ends after a short while in the background.
 */
class TalkViewModel(
    private val graph: AppGraph,
) : ViewModel() {
    /** Opened as the assistant: see [AppGraph.assistRequests]. */
    val assistRequests: StateFlow<Int> get() = graph.assistRequests

    /** Uses up the waiting assist requests; true when there was one. */
    fun takeAssist(): Boolean = graph.assistRequests.getAndUpdate { 0 } > 0

    data class State(
        val phase: VoicePhase = VoicePhase.IDLE,
        val detail: String? = null,
        /** The conversation is the watch's; the phone only shows it. */
        val onWatch: Boolean = false,
        val reaction: Reaction? = null,
        val reactionId: Int = 0,
    )

    /** What a tap on WatchGPT did. */
    enum class Tap { TALKING, ENDED, NEEDS_SIGN_IN, NEEDS_MIC }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _island = MutableStateFlow<Island>(Island.Hidden)

    /** The pop-up at the top: the conversation, or a card for what WatchGPT just did. */
    val island: StateFlow<Island> = _island.asStateFlow()
    private var card: WatchGPTCard? = null
    private var cardJob: Job? = null
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    val auth: StateFlow<AuthState> = graph.session.state

    private val lock = Mutex()
    private var session: ChatGptRealtimeSession? = null
    private var jobs: List<Job> = emptyList()
    private var stopLater: Job? = null

    /** Private answers being said in the phone's own voice; they end with the conversation. */
    private val readouts = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        viewModelScope.launch { runCatching { graph.session.isSignedIn() } }
        viewModelScope.launch { graph.cards.cards.collect { showCard(it) } }
        // A yes or no given with the pop-up's buttons: this phone's conversation hears about it.
        viewModelScope.launch { graph.cards.decidedOnScreen.collect { session?.tell("The user answered on the phone's screen: $it") } }
        viewModelScope.launch { _state.collect { refreshIsland() } }
        // The watch called: it's shown here, and this phone's own conversation makes way.
        viewModelScope.launch {
            graph.watchCalls.call.collect { call ->
                if (call != null) {
                    lock.withLock { endLocked() }
                    show(call.voice, onWatch = true)
                } else if (session == null) {
                    _state.update { State(reaction = it.reaction, reactionId = it.reactionId) }
                    _level.value = 0f
                }
            }
        }
    }

    suspend fun buddySeed(): Long = graph.buddySeed()

    fun tap(micAllowed: Boolean): Tap = when {
        graph.watchCalls.call.value != null -> {
            graph.scope.launch { graph.watchCalls.hangUp() }
            Tap.ENDED
        }

        session != null -> {
            end()
            Tap.ENDED
        }

        // Still loading counts as signed in: the conversation says so itself if it isn't.
        auth.value is AuthState.SignedOut -> Tap.NEEDS_SIGN_IN

        !micAllowed -> Tap.NEEDS_MIC

        else -> {
            start()
            Tap.TALKING
        }
    }

    private fun start() {
        viewModelScope.launch {
            lock.withLock {
                endLocked()
                // The conversation starts knowing what WatchGPT remembers, and its turns go to the history.
                val conversation = UUID.randomUUID().toString()
                val remembered = graph.remembered()
                val guard =
                    graph.guard(
                        Toolboxes(listOf(graph.tools, ConversationActions({ session?.endSoon() }, graph.logger))),
                        OwnerPresence(graph.appContext) { false },
                        spoken = true,
                        reply = VoiceReply(graph.speech, graph.cards, readouts, graph.languages) { session },
                    )
                val s =
                    ChatGptRealtimeSession(
                        graph.appContext,
                        graph.session,
                        ChatGptHttp.authClient(),
                        graph.chatGpt,
                        guard,
                        graph.logger,
                        voice = graph.settings.voice,
                        idleHangUpMs = graph.settings.conversationIdleMs,
                        onIdle = { end() },
                        instructions = listOfNotNull(ChatGptRealtimeSession.VOICE_INSTRUCTIONS, remembered).joinToString("\n"),
                        onUserWords = graph.userTurns::heard,
                        onTurn = { graph.keepTurn(conversation, ON_PHONE, it, health = SleepActions.READ_SLEEP in guard.sentToCloud) },
                        remembered = { graph.remembered(except = conversation) },
                    )
                session = s
                jobs =
                    listOf(
                        graph.warmReader(),
                        viewModelScope.launch { s.state.collect { show(it, onWatch = false) } },
                        viewModelScope.launch { s.level.collect { _level.value = it } },
                    )
                s.start()
                PhoneTalkService.start(graph.appContext) { end() }
            }
        }
    }

    fun end() {
        viewModelScope.launch { lock.withLock { endLocked() } }
    }

    /** The pop-up's Send, Call or Cancel: the tap is the user's answer. */
    fun decide(yes: Boolean) {
        graph.scope.launch { graph.decideOnScreen(yes) }
    }

    fun dismissCard() {
        cardJob?.cancel()
        card = null
        refreshIsland()
    }

    private fun showCard(next: WatchGPTCard) {
        card = next
        cardJob?.cancel()
        // A text or call waits for its answer as long as the yes may come; the rest go after a few seconds.
        val keep =
            when (next) {
                is WatchGPTCard.Ask -> Pending.TTL_MS
                is WatchGPTCard.Place, is WatchGPTCard.Messages -> LONG_CARD_MS
                else -> CARD_MS
            }
        cardJob =
            viewModelScope.launch {
                delay(keep)
                card = null
                refreshIsland()
            }
        refreshIsland()
    }

    private fun refreshIsland() {
        val s = _state.value
        _island.value =
            card?.let { Island.Showing(it) }
                ?: if (s.phase != VoicePhase.IDLE && s.phase != VoicePhase.ERROR) Island.Live(s.phase, s.onWatch) else Island.Hidden
    }

    private suspend fun endLocked() {
        val current = session ?: return
        session = null
        PhoneTalkService.stop(graph.appContext)
        current.stop()
        graph.memory.flush()
        readouts.coroutineContext.cancelChildren()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _level.value = 0f
        _state.update { State(reaction = it.reaction, reactionId = it.reactionId) }
    }

    /** WatchGPT follows the conversation: listening, thinking, talking. */
    private fun show(
        voice: VoiceState,
        onWatch: Boolean,
    ) {
        _state.update { it.copy(phase = voice.phase, detail = voice.detail, onWatch = onWatch) }
    }

    /** Leaving the app: with the talk service holding the mic the conversation goes on, otherwise it ends soon. */
    fun onBackground() {
        stopLater?.cancel()
        if (session == null || PhoneTalkService.running) return
        stopLater =
            viewModelScope.launch {
                delay(BACKGROUND_GRACE_MS)
                lock.withLock { endLocked() }
            }
    }

    fun onForeground() {
        stopLater?.cancel()
        stopLater = null
    }

    override fun onCleared() {
        val current = session ?: return
        session = null
        PhoneTalkService.stop(graph.appContext)
        graph.scope.launch { current.stop() }
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 30_000L
        const val ON_PHONE = "on the phone"
        const val CARD_MS = 6_000L
        const val LONG_CARD_MS = 12_000L
    }
}
