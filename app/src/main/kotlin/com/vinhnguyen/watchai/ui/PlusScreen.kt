package io.github.alisial786.watchgpt.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alisial786.watchgpt.AppGraph
import io.github.alisial786.watchgpt.R
import io.github.alisial786.watchgpt.buddy.Act
import io.github.alisial786.watchgpt.buddy.Genes
import io.github.alisial786.watchgpt.buddy.Mood
import io.github.alisial786.watchgpt.buddy.Reaction
import io.github.alisial786.watchgpt.buddy.ui.WatchGPTView
import io.github.alisial786.watchgpt.plus.Plus
import kotlinx.coroutines.launch

/**
 * WatchGPT Plus: what it is (extras for people who want to support WatchGPT), the ways to get it as
 * RevenueCat offers them, and restoring it. Everything WatchGPT does stays free.
 */
@Composable
fun PlusScreen(
    graph: AppGraph,
    onChooseWatchGPT: () -> Unit,
) {
    val p = LocalPalette.current
    val plus = graph.plus
    val active by plus.active.collectAsStateWithLifecycle()
    val offers by plus.offers.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(plus) { plus.refresh() }

    Text(
        if (active) {
            "Thank you for supporting WatchGPT. Everything below is yours."
        } else {
            "WatchGPT does everything for free. Plus is for people who want to support it, with a few extras."
        },
        style = MaterialTheme.typography.bodyLarge,
        color = p.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
    )
    Group("What Plus adds", footer = "Privacy and safety are never part of Plus: they're the same for everyone.") {
        Item(
            "Choose your WatchGPT",
            subtitle = "Pick any WatchGPT you like. It's the one on your watch too.",
            painter = painterResource(R.drawable.sym_sentiment_satisfied),
            onClick = onChooseWatchGPT,
            trailing = { Pill(if (active) "Choose" else "Look", onClick = onChooseWatchGPT, filled = false) },
        )
        Item(
            "New things first",
            subtitle = "Early access to what comes next, starting with WatchGPT on your computer.",
            painter = painterResource(R.drawable.sym_bolt),
        )
        Item(
            "A thank-you mark",
            subtitle = "Plus next to your WatchGPT in the menu.",
            painter = painterResource(R.drawable.sym_star),
            last = true,
        )
    }

    when {
        !plus.available ->
            Group { Item("Not set up in this build", subtitle = "Built without a RevenueCat key, so there's nothing to buy.", last = true) }

        active ->
            Group { Item("You have WatchGPT Plus", subtitle = "Thank you!", painter = painterResource(R.drawable.sym_check_circle), last = true) }

        offers.isEmpty() ->
            Group { Item("Loading the prices…", last = true) }

        else ->
            Group("Get Plus") {
                offers.forEachIndexed { i, offer ->
                    Item(
                        offer.title,
                        subtitle = listOfNotNull(offer.trial, offer.price + (offer.per?.let { " a $it" } ?: "")).joinToString(" · then "),
                        last = i == offers.lastIndex,
                        trailing = {
                            Pill(if (offer.trial != null) "Try free" else "Get it", onClick = {
                                if (busy || activity == null) return@Pill
                                busy = true
                                scope.launch {
                                    said =
                                        when (val result = plus.buy(activity, offer)) {
                                            Plus.Bought.Done -> "Welcome to WatchGPT Plus."
                                            Plus.Bought.Cancelled -> null
                                            is Plus.Bought.Failed -> result.why
                                        }
                                    busy = false
                                }
                            })
                        },
                    )
                }
            }
    }
    said?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
    if (plus.available) {
        Group {
            Item(
                "Restore purchases",
                subtitle = "Plus from another phone or an earlier install",
                last = true,
                onClick = {
                    scope.launch { said = if (plus.restore()) "Plus is back. Thank you!" else "No Plus to restore on this account." }
                },
            )
        }
    }
    Text(
        "Paid through the store and RevenueCat, which see an anonymous id for this install and the purchase, never your " +
            "conversations. Cancel any time in the store.",
        style = MaterialTheme.typography.bodySmall,
        color = p.textTertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/**
 * Choosing your WatchGPT (a Plus extra): a grid of Companions, each playing a little when tapped. With
 * Plus a tap makes it yours, here and on the watch; without, it shows Plus.
 */
@Composable
fun LookScreen(
    graph: AppGraph,
    onPlus: () -> Unit,
) {
    val p = LocalPalette.current
    val active by graph.plus.active.collectAsStateWithLifecycle()
    val chosen by graph.chosenWatchGPT.collectAsStateWithLifecycle()
    val own by produceState<Long?>(null) { value = graph.buddySeed() }
    var batch by rememberSaveable { mutableIntStateOf(0) }
    val seeds = remember(batch) { List(LOOKS) { Genes.seedFor("buddy look ${batch * LOOKS + it}") } }

    Text(
        if (active) "Tap a WatchGPT to make it yours. It's the one on your watch from the next conversation." else "Have a look. With WatchGPT Plus, any of them can be yours.",
        style = MaterialTheme.typography.bodyLarge,
        color = p.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
    )
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        seeds.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { seed ->
                    val mine = active && chosen == seed
                    Look(seed, mine, Modifier.weight(1f)) {
                        if (active) graph.chooseWatchGPT(seed) else onPlus()
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("More Companions", onClick = { batch++ }, filled = false)
        if (active && chosen != null) Pill("Back to my own WatchGPT", onClick = { graph.chooseWatchGPT(null) }, filled = false)
    }
    if (own != null && !active) {
        Text(
            "Yours now is made from your account, so no one else has it.",
            style = MaterialTheme.typography.bodySmall,
            color = p.textTertiary,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

private const val LOOKS = 12

@Composable
private fun Look(
    seed: Long,
    mine: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val p = LocalPalette.current
    var next by remember { mutableIntStateOf(0) }
    val mood = MOODS[next % MOODS.size]
    Box(
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(p.surface)
            .then(if (mine) Modifier.border(2.dp, p.text, CircleShape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = "Choose this WatchGPT") {
                next++
                onTap()
            },
        contentAlignment = Alignment.Center,
    ) {
        WatchGPTView(
            genes = remember(seed) { Genes.of(seed) },
            act = Act.REST,
            reaction = mood?.let { Reaction(it, 0.8f) },
            reactionId = next,
            level = 0f,
            paper = p.surface,
            eyes = p.eyes,
            modifier = Modifier.fillMaxSize().padding(10.dp),
        )
        if (mine) {
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(10.dp).clip(CircleShape).background(p.text))
        }
    }
}

private val MOODS: List<Mood?> = listOf(null) + Mood.entries.filter { it != Mood.NEUTRAL }
