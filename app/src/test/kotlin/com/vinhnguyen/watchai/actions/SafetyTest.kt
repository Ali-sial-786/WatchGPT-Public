package io.github.alisial786.watchgpt.actions

import com.google.common.truth.Truth.assertThat
import io.github.alisial786.watchgpt.actions.screen.ScreenActions
import org.junit.Test

class SafetyTest {
    @Test
    fun `sleep goes to ChatGPT only with its own switch, in a spoken conversation`() {
        assertThat(Safety.toCloud(privateOnPhone = true, sleepToChatGpt = false, spoken = true)).isEmpty()
        // "Private things stay on this phone" off isn't enough: sleep is health data.
        assertThat(Safety.toCloud(privateOnPhone = false, sleepToChatGpt = false, spoken = true)).isEqualTo(Safety.MAY_GO_TO_CLOUD)
        assertThat(Safety.MAY_GO_TO_CLOUD).doesNotContain(SleepActions.READ_SLEEP)
        // And sleep's switch sends only sleep.
        assertThat(Safety.toCloud(privateOnPhone = true, sleepToChatGpt = true, spoken = true)).containsExactly(SleepActions.READ_SLEEP)
        assertThat(Safety.toCloud(privateOnPhone = false, sleepToChatGpt = true, spoken = true)).isEqualTo(Safety.MAY_GO_TO_CLOUD + SleepActions.READ_SLEEP)
        // The chat shows it on the phone's screen: no voice to say it in.
        assertThat(Safety.toCloud(privateOnPhone = true, sleepToChatGpt = true, spoken = false)).isEmpty()
        assertThat(Safety.toCloud(privateOnPhone = false, sleepToChatGpt = true, spoken = false)).isEqualTo(Safety.MAY_GO_TO_CLOUD)
    }

    @Test
    fun `what's on an app's screen never goes to ChatGPT`() {
        assertThat(Safety.toCloud(privateOnPhone = false, sleepToChatGpt = true, spoken = true)).doesNotContain(ScreenActions.READ)
    }
}
