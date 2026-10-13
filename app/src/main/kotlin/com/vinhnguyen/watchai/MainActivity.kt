package io.github.alisial786.watchgpt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.alisial786.watchgpt.security.protectScreen
import io.github.alisial786.watchgpt.ui.AbilitiesViewModel
import io.github.alisial786.watchgpt.ui.BenchmarkViewModel
import io.github.alisial786.watchgpt.ui.BrainsViewModel
import io.github.alisial786.watchgpt.ui.ChatViewModel
import io.github.alisial786.watchgpt.ui.NoticeScreen
import io.github.alisial786.watchgpt.ui.Pages
import io.github.alisial786.watchgpt.ui.TalkViewModel
import io.github.alisial786.watchgpt.ui.VoiceLabViewModel
import io.github.alisial786.watchgpt.ui.WatchAiTheme
import io.github.alisial786.watchgpt.ui.WatchGPTApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Answers, codes and account details: no screenshots or recents thumbnails in release builds.
        protectScreen(!BuildConfig.DEBUG)
        io.github.alisial786.watchgpt.organizer.Organizer.get(this).requestSync()
        if (savedInstanceState == null && intent.getBooleanExtra("organizer", false)) {
            io.github.alisial786.watchgpt.organizer.OrganizerActivity.open(this, BuildConfig.DEBUG && intent.getBooleanExtra("watch_preview", false))
        }
        val graph = (application as WatchAiApp).graph
        val factory =
            viewModelFactory {
                initializer { ChatViewModel(graph) }
                initializer { BrainsViewModel(graph) }
                initializer { BenchmarkViewModel(graph) }
                initializer { VoiceLabViewModel(graph) }
                initializer { TalkViewModel(graph) }
                initializer { AbilitiesViewModel(graph) }
            }

        setContent {
            WatchAiTheme {
                var accepted by rememberSaveable { mutableStateOf(graph.settings.noticeAccepted) }
                if (!accepted) {
                    NoticeScreen(onAccept = {
                        graph.settings.noticeAccepted = true
                        accepted = true
                    })
                    return@WatchAiTheme
                }
                val pages =
                    Pages(
                        chat = viewModel(factory = factory),
                        brains = viewModel(factory = factory),
                        abilities = viewModel(factory = factory),
                        benchmark = if (BuildConfig.DEBUG) viewModel<BenchmarkViewModel>(factory = factory) else null,
                        voiceLab = if (BuildConfig.DEBUG) viewModel<VoiceLabViewModel>(factory = factory) else null,
                    )
                val testPage by graph.testPage.collectAsState()
                WatchGPTApp(graph, talk = viewModel(factory = factory), pages = pages, testPage = testPage)
            }
        }
    }
}
