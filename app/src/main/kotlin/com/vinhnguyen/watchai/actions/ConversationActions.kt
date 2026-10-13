package io.github.alisial786.watchgpt.actions

import io.github.alisial786.watchgpt.brain.BrainLogger
import io.github.alisial786.watchgpt.brain.LogEvent
import io.github.alisial786.watchgpt.brain.ToolSpec
import io.github.alisial786.watchgpt.brain.Toolbox

/** Ending the conversation by voice ("bye", "that's all"): WatchGPT says goodbye and goes back to waiting for "Hey WatchGPT". */
class ConversationActions(
    private val end: () -> Unit,
    private val logger: BrainLogger = BrainLogger.None,
) : Toolbox {
    override fun tools(): List<ToolSpec> = SPECS

    override suspend fun run(
        name: String,
        argumentsJson: String,
    ): String {
        if (name != END) return "error: there is no action called $name"
        end()
        logger.log(LogEvent.ToolUsed(END, "ok"))
        return "ok: the conversation ends right after your goodbye; say it in two or three words"
    }

    companion object {
        const val END = "end_conversation"

        val SPECS =
            listOf(
                ToolSpec(
                    END,
                    "End the conversation when the user says goodbye or that they're done ('bye', 'that's all', 'stop listening', " +
                        "'thanks, that's it', in any language). WatchGPT then waits for \"Hey WatchGPT\" again.",
                    """{"type":"object","properties":{},"additionalProperties":false}""",
                ),
            )
    }
}
