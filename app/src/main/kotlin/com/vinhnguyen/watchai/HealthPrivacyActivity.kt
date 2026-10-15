package io.github.alisial786.watchgpt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.alisial786.watchgpt.ui.LocalPalette
import io.github.alisial786.watchgpt.ui.Page
import io.github.alisial786.watchgpt.ui.WatchAiTheme

/**
 * What WatchGPT does with the sleep it reads from Health Connect ([io.github.alisial786.watchgpt.actions.SleepActions]):
 * opened from Health Connect's permission screen and its app settings. Health Connect lets no app in without one.
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { WatchAiTheme { HealthPrivacy(onBack = ::finish) } }
    }
}

@Composable
private fun HealthPrivacy(onBack: () -> Unit) {
    Page("Your sleep and WatchGPT", onBack = onBack) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Part(
                "What it reads",
                "Only your sleep: when you fell asleep and woke up, and your sleep stages (awake, light, deep, REM), as Samsung Health " +
                    "or another sleep app shares them in Health Connect. Nothing else there, and nothing is written to it.",
            )
            Part("When", "Only when you ask about your sleep, on your watch or in WatchGPT, and at most two weeks back.")
            Part(
                "Where it goes",
                "Nowhere else, unless you choose. This phone reads it and tells you itself: in its own voice (on your watch when you " +
                    "asked there) or on its screen. It doesn't go to ChatGPT, to us or to anyone else, and WatchGPT doesn't keep it: the " +
                    "list of what WatchGPT did only notes that it read your sleep.",
            )
            Part(
                "In ChatGPT's voice, if you turn it on",
                "Only if you turn on Say it in ChatGPT's voice (What WatchGPT can do, Health): when you ask out loud, on your watch " +
                    "or this phone, your sleep goes to ChatGPT, run by OpenAI, under your own ChatGPT account, and ChatGPT tells you in " +
                    "its voice; OpenAI's terms and your ChatGPT data settings apply. In the chat this phone still shows it itself. What's " +
                    "said after that in the conversation is kept in History on this phone, but never given to ChatGPT again. It never " +
                    "goes to us. Turning it off counts at once.",
            )
            Part(
                "In the background",
                "Conversations already count as WatchGPT being open. If you also allow access in the background, it can read your sleep " +
                    "when Android counts it as closed, for example if you leave the chat while it answers. It still reads only when you ask.",
            )
            Part("Taking it back", "Any time, in Health Connect: App permissions, then WatchGPT.")
            Part(
                "Who's responsible, and your rights",
                "Vinh Nguyen (VYDE), vyde.apps@gmail.com. WatchGPT reads your sleep with your explicit consent, given here in Health " +
                    "Connect (Art. 9(2)(a) GDPR), and sends it to ChatGPT only with your explicit consent too, given by turning on Say it " +
                    "in ChatGPT's voice. You can take either back at any time. For access, deletion or complaints, write to that " +
                    "address; you can also complain to your data protection authority. This is the sleep part of WatchGPT's privacy " +
                    "policy (PRIVACY.md, with the app).",
            )
        }
    }
}

@Composable
private fun Part(
    title: String,
    body: String,
) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = p.text)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
    }
}
