package io.github.alisial786.watchgpt.actions

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What the pop-up at the top of the phone shows while WatchGPT works: what it did, what it found,
 * and what waits for the user's yes. Actions post cards here as they run; nothing is stored.
 */
sealed interface WatchGPTCard {
    /** Something done: a timer, an alarm, a note, the flashlight… */
    data class Done(
        val symbol: Symbol,
        val title: String,
        val detail: String? = null,
    ) : WatchGPTCard

    /** An app on the phone, just opened. */
    data class App(
        val packageName: String,
        val title: String,
    ) : WatchGPTCard

    /** A text or a call read back, waiting for the user's yes (by voice or with the button). */
    data class Ask(
        val title: String,
        val detail: String,
        val confirm: String,
        /** Whose icon to show: the messages or phone app. */
        val packageName: String?,
    ) : WatchGPTCard

    /** Directions to a place: a small map. */
    data class Place(
        val name: String,
    ) : WatchGPTCard

    /** Messages read out, newest first. */
    data class Messages(
        val lines: List<Line>,
    ) : WatchGPTCard {
        data class Line(
            val packageName: String,
            val from: String,
            val text: String,
        )
    }
}

enum class Symbol { TIMER, ALARM, NOTE, CALENDAR, MUSIC, LIGHT, PHONE, CHECK, CANCEL, PRIVATE }

class CardHub {
    private val _cards = MutableSharedFlow<WatchGPTCard>(extraBufferCapacity = 16)
    val cards: SharedFlow<WatchGPTCard> = _cards.asSharedFlow()

    private val _decidedOnScreen = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** A yes or no given with the pop-up's buttons, for the conversation to hear about. */
    val decidedOnScreen: SharedFlow<String> = _decidedOnScreen.asSharedFlow()

    fun show(card: WatchGPTCard) {
        _cards.tryEmit(card)
    }

    fun decidedOnScreen(result: String) {
        _decidedOnScreen.tryEmit(result)
    }

    companion object {
        /** The card for what came of a yes or no. */
        fun decision(result: String): WatchGPTCard = when {
            result.startsWith("ok: cancelled") -> WatchGPTCard.Done(Symbol.CANCEL, "Cancelled", "Nothing was sent")
            result.startsWith("ok") -> WatchGPTCard.Done(Symbol.CHECK, result.removePrefix("ok: ").substringBefore(". ").replaceFirstChar { it.uppercase() })
            else -> WatchGPTCard.Done(Symbol.CANCEL, "Didn't go through", result.removePrefix("error: ").substringBefore(";").replaceFirstChar { it.uppercase() })
        }
    }
}
