package io.github.alisial786.watchgpt

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.alisial786.watchgpt.actions.ActionLogStore
import io.github.alisial786.watchgpt.actions.AppLimits
import io.github.alisial786.watchgpt.actions.CardHub
import io.github.alisial786.watchgpt.actions.Contacts
import io.github.alisial786.watchgpt.actions.ConversationActions
import io.github.alisial786.watchgpt.actions.Memory
import io.github.alisial786.watchgpt.actions.MemoryActions
import io.github.alisial786.watchgpt.actions.MessageInbox
import io.github.alisial786.watchgpt.actions.NoteStore
import io.github.alisial786.watchgpt.actions.Pending
import io.github.alisial786.watchgpt.actions.PhoneActions
import io.github.alisial786.watchgpt.actions.PhoneClock
import io.github.alisial786.watchgpt.actions.PhoneControls
import io.github.alisial786.watchgpt.actions.PhoneShortcuts
import io.github.alisial786.watchgpt.actions.ReachActions
import io.github.alisial786.watchgpt.actions.Safety
import io.github.alisial786.watchgpt.actions.UserTurns
import io.github.alisial786.watchgpt.actions.screen.ScreenActions
import io.github.alisial786.watchgpt.brain.Availability
import io.github.alisial786.watchgpt.brain.BrainRouter
import io.github.alisial786.watchgpt.brain.ChatTurn
import io.github.alisial786.watchgpt.brain.Toolbox
import io.github.alisial786.watchgpt.brain.Toolboxes
import io.github.alisial786.watchgpt.brain.chatgpt.ChatGptBrain
import io.github.alisial786.watchgpt.brain.chatgpt.ChatGptHttp
import io.github.alisial786.watchgpt.brain.chatgpt.ChatGptSettings
import io.github.alisial786.watchgpt.brain.chatgpt.auth.AuthSession
import io.github.alisial786.watchgpt.brain.chatgpt.auth.ChatGptSignIn
import io.github.alisial786.watchgpt.brain.chatgpt.auth.CodexOAuthClient
import io.github.alisial786.watchgpt.brain.guard.ActionLog
import io.github.alisial786.watchgpt.brain.guard.Budget
import io.github.alisial786.watchgpt.brain.guard.Guard
import io.github.alisial786.watchgpt.brain.guard.Level
import io.github.alisial786.watchgpt.brain.guard.Presence
import io.github.alisial786.watchgpt.brain.guard.PrivateReply
import io.github.alisial786.watchgpt.buddy.Genes
import io.github.alisial786.watchgpt.ondevice.AppForeground
import io.github.alisial786.watchgpt.ondevice.EngineHolder
import io.github.alisial786.watchgpt.ondevice.GeminiNanoBrain
import io.github.alisial786.watchgpt.ondevice.GemmaBrain
import io.github.alisial786.watchgpt.ondevice.GemmaReader
import io.github.alisial786.watchgpt.ondevice.ModelRepository
import io.github.alisial786.watchgpt.ondevice.OnDeviceSettings
import io.github.alisial786.watchgpt.plus.Plus
import io.github.alisial786.watchgpt.security.KeystoreVault
import io.github.alisial786.watchgpt.security.TimberBrainLogger
import io.github.alisial786.watchgpt.ui.TestPage
import io.github.alisial786.watchgpt.voice.LanguageId
import io.github.alisial786.watchgpt.voice.LocalSpeech
import io.github.alisial786.watchgpt.watch.WatchCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Everything the app needs, created once. No DI framework: the graph is small. */
class AppGraph(
    context: Context,
) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logger = TimberBrainLogger()
    val vault = KeystoreVault(appContext, logger)
    val settings = AppSettings(appContext)

    private val oauth = CodexOAuthClient(ChatGptHttp.authClient())
    val session = AuthSession(vault, oauth, logger = logger)
    val signIn = ChatGptSignIn(oauth, session, logger = logger)
    val chatGpt =
        ChatGptBrain(
            session,
            ChatGptHttp.streamClient(),
            settings = ChatGptSettings(diagnostics = BuildConfig.DEBUG),
            logger = logger,
        )

    val onDeviceSettings = OnDeviceSettings(appContext)
    val models = ModelRepository(appContext)
    val engines = EngineHolder(appContext, onDeviceSettings, scope, logger)
    val gemma = GemmaBrain(models, onDeviceSettings, engines, logger)
    val foreground =
        AppForeground {
            ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }
    val nano = GeminiNanoBrain(appContext, onDeviceSettings, foreground, logger)

    val router = BrainRouter(listOf(nano, gemma, chatGpt))
    val reports = ReportStore(vault)

    // Notes get their own vault and key: signing out of ChatGPT never touches them.
    private val notesVault = KeystoreVault(appContext, logger, alias = "watchai_notes_master_v1", dirName = "notes")
    val notes = NoteStore(notesVault)
    val controls = PhoneControls(appContext, scope)
    val phoneClock = PhoneClock(appContext) { foreground.isForeground() }

    /** When the user last said or typed something: a message or a call only goes out on their later yes. */
    val userTurns = UserTurns()

    /** The one message or call waiting for a yes, by voice or with the phone's pop-up. */
    val pending = Pending(userTurns)

    /** What the phone's pop-up shows while WatchGPT works. */
    val cards = CardHub()

    val actions = PhoneActions(appContext, notes, controls, phoneClock, logger = logger, cards = cards, pending = pending)

    /** Messages the user got, from notifications (with their OK); only in memory. */
    val inbox = MessageInbox()
    val contacts = Contacts(appContext)

    /** Which language a text is in, told on the phone (for the phone's answers and the voice that says them). */
    val languages = LanguageId(appContext)

    /** Gemma on this phone: reads private data before ChatGPT gets it, and judges taps and apps. */
    val reader = GemmaReader(models, onDeviceSettings, engines, logger, languageOf = languages::of)

    /** The apps WatchGPT may use: only the ones the user turned on; settings, stores, passwords and payments never. */
    val limits = AppLimits(appContext, reader)

    val shortcuts = PhoneShortcuts(appContext, logger, { foreground.isForeground() }, cards, limits, pending)

    /** Using apps on the phone for the user, once they switch on WatchGPT's accessibility service. */
    val screen = ScreenActions(appContext, pending, limits, reader, logger, cards)

    // What WatchGPT remembers (conversations, the facts the user asked it to keep) gets its own vault and key too.
    private val memoryVault = KeystoreVault(appContext, logger, alias = "watchai_memory_master_v1", dirName = "memory")
    val memory = Memory(memoryVault, scope)
    val memoryActions = MemoryActions(memory, userTurns, logger)

    /** Everything the AI may use in the app (chat and the phone's own voice): the phone's actions, messages and calls, shortcuts, apps, memory. */
    val tools: Toolbox =
        Toolboxes(listOf(actions, ReachActions(appContext, contacts, inbox, pending, logger, cards = cards, limits = limits), shortcuts, screen, memoryActions))

    /** What a conversation starts with: what the user asked WatchGPT to keep, and the last conversations unless they turned that off. */
    suspend fun remembered(except: String? = null): String? = memory.context(history = settings.rememberChats, except = except)

    /** A finished turn of a conversation for the history, unless the user turned it off; [local] if only the phone's own model heard it. */
    fun keepTurn(
        conversation: String,
        where: String,
        turn: ChatTurn,
        local: Boolean = false,
    ) {
        if (settings.rememberChats) memory.add(conversation, where, turn.role == ChatTurn.Role.USER, turn.text, local)
    }

    /** Texts, replies and calls WatchGPT may send in an hour, across all conversations. */
    val budget = Budget(max = OUTBOUND_PER_HOUR, windowMs = HOUR_MS, now = SystemClock::elapsedRealtime)

    // The log of what WatchGPT did gets its own vault and key too.
    private val logVault = KeystoreVault(appContext, logger, alias = "watchai_log_master_v1", dirName = "log")
    val actionLog = ActionLogStore(logVault, scope)

    /** Test builds: a screen to open, set from adb (debug TestHooks); nothing sets it in release builds. */
    val testPage = MutableStateFlow<TestPage?>(null)

    /** How many times WatchGPT was opened as the assistant (ACTION_ASSIST): the home screen starts listening for each. */
    val assistRequests = MutableStateFlow(0)

    /** The phone's own voice, for private answers. */
    val speech by lazy { LocalSpeech(appContext) }

    /**
     * The gate for one conversation: every tool call of it goes through here (docs/security/agent-safety.md).
     * [presence] tells whether the owner is there for a yes.
     */
    fun guard(
        tools: Toolbox,
        presence: Presence,
        reply: PrivateReply,
    ): Guard = Guard(
        inner = tools,
        levels = Safety.LEVELS,
        presence = presence,
        budget = budget,
        confirmTool = ReachActions.CONFIRM,
        reader = reader,
        readable = Safety.READABLE,
        log = actionLog,
        reply = reply,
        stopTool = ConversationActions.END,
        onStop = pending::cancel,
        // On (the default): private things stay on the phone; off: ChatGPT reads messages, notes and the
        // calendar, cleaned. What's on an app's screen stays on the phone either way.
        toCloud = if (settings.privateOnPhone) emptySet() else Safety.MAY_GO_TO_CLOUD,
    )

    /** Keeps Gemma loaded while a conversation runs, so reading private data on the phone is quick. Cancel the job to let it go. */
    fun warmReader(): Job = scope.launch {
        if (gemma.availability() != Availability.Ready) return@launch
        val held = gemma.preload()
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { held.release() }
        }
    }

    /** The user tapped Send, Call or Cancel in the pop-up: that tap is their answer. */
    suspend fun decideOnScreen(yes: Boolean): String {
        userTurns.heard()
        val result = pending.decide(yes)
        // A tap proves the user is there, so it isn't held back; it still counts, and it's logged.
        if (yes && result.startsWith("ok")) budget.spend()
        actionLog.record(
            ActionLog.Entry(
                System.currentTimeMillis(),
                ReachActions.CONFIRM,
                if (yes) Level.OUTBOUND else Level.LOCAL,
                if (result.startsWith("error")) ActionLog.Outcome.FAILED else ActionLog.Outcome.DONE,
            ),
        )
        cards.show(CardHub.decision(result))
        cards.decidedOnScreen(result)
        return result
    }

    /** WatchGPT Plus through RevenueCat: extras only (choose your WatchGPT's look, early access). */
    val plus = Plus(appContext, BuildConfig.REVENUECAT_KEY, scope, BuildConfig.DEBUG)

    /** The WatchGPT a Plus user picked (a seed), or null for the one made from their account. */
    val chosenWatchGPT = MutableStateFlow(settings.chosenWatchGPT)

    fun chooseWatchGPT(seed: Long?) {
        settings.chosenWatchGPT = seed
        chosenWatchGPT.value = seed
    }

    /** This user's WatchGPT: the one they picked with Plus, else from their ChatGPT account, or this install until they sign in. */
    suspend fun buddySeed(): Long = chosenWatchGPT.value?.takeIf { plus.active.value }
        ?: Genes.seedFor(runCatching { session.bearer().accountId }.getOrNull() ?: settings.installId)
    val watchCalls = WatchCalls(this)

    /**
     * "Delete everything": tokens, keys, reports, notes, what WatchGPT remembers, models, settings, and reminders
     * (on the watch too, and their calendar copies). Null when done, else why something was left.
     */
    suspend fun deleteEverything(): String? {
        runCatching { session.signOut() }
        vault.wipe()
        notesVault.wipe()
        actionLog.clear()
        logVault.wipe()
        memory.clear()
        limits.clear()
        memoryVault.wipe()
        models.specs.forEach { models.delete(it) }
        onDeviceSettings.clearAll()
        settings.clearAll()
        appContext.noBackupFilesDir.resolve("litertlm-cache").deleteRecursively()
        // Last, and on its own: a reminder storage problem must not stop the rest of the wipe.
        return runCatching { io.github.alisial786.watchgpt.organizer.Organizer.get(appContext).deleteAll() }.exceptionOrNull()?.let { it.message ?: "Some reminders could not be deleted." }
    }

    private companion object {
        const val OUTBOUND_PER_HOUR = 10
        const val HOUR_MS = 3_600_000L
    }
}

class AppSettings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var noticeAccepted: Boolean
        get() = prefs.getBoolean("notice_accepted_v1", false)
        set(value) = prefs.edit { putBoolean("notice_accepted_v1", value) }

    /** Off by default: answers stay on the phone unless the user allows ChatGPT. */
    var cloudAllowed: Boolean
        get() = prefs.getBoolean("cloud_allowed", false)
        set(value) = prefs.edit { putBoolean("cloud_allowed", value) }

    /** A random id for this install, for WatchGPT until the user signs in. Never sent anywhere. */
    val installId: String
        get() = prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString("install_id", id) } }

    /** On by default: with the offline model downloaded, Gemma reads messages, notes and the calendar first and ChatGPT gets only what the question needs. */
    var privateOnPhone: Boolean
        get() = prefs.getBoolean("private_on_phone", true)
        set(value) = prefs.edit { putBoolean("private_on_phone", value) }

    /** The WatchGPT picked with Plus (a seed), or null. */
    var chosenWatchGPT: Long?
        get() = if (prefs.contains("chosen_buddy")) prefs.getLong("chosen_buddy", 0) else null
        set(value) = prefs.edit { if (value == null) remove("chosen_buddy") else putLong("chosen_buddy", value) }

    /** On by default: conversations are kept 30 days on the phone (History), and the next conversation knows the last ones. */
    var rememberChats: Boolean
        get() = prefs.getBoolean("remember_chats", true)
        set(value) = prefs.edit { putBoolean("remember_chats", value) }

    /** Silence that ends a call; zero is "until I stop". Short by default: every open call holds the microphone. */
    var conversationTimeoutSeconds: Int
        get() = prefs.getInt("conversation_timeout", 30).coerceIn(0, 900)
        set(value) = prefs.edit { putInt("conversation_timeout", value.coerceIn(0, 900)) }

    /** The call's idle limit: "until I stop" still ends a call after half an hour of nothing at all (one left open by mistake). */
    val conversationIdleMs: Long get() = conversationTimeoutSeconds.let { if (it > 0) it * 1000L else UNTIL_STOPPED_IDLE_MS }

    var voice: String
        get() = prefs.getString("voice", null) ?: "cove"
        set(value) = prefs.edit { putString("voice", value) }

    fun clearAll() = prefs.edit { clear() }

    private companion object {
        const val UNTIL_STOPPED_IDLE_MS = 30 * 60_000L
    }
}
