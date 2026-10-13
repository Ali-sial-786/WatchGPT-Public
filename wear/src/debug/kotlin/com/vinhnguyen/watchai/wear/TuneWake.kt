package io.github.alisial786.watchgpt.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.alisial786.watchgpt.watchlink.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: tunes "Hey WatchGPT" without a new build. Runs every WAV (16 kHz mono) whose
 * name starts with the prefix through the model with the keywords in a pushed file (keywords.txt
 * lines) and logs what fired for each. Synthetic speech only; "Hey WatchGPT" must be on.
 *
 *   adb push try.txt /sdcard/Android/data/io.github.alisial786.watchgpt/files/try.txt
 *   adb shell am broadcast -n io.github.alisial786.watchgpt/io.github.alisial786.watchgpt.wear.TuneWake --es keywords try.txt --es prefix t_
 */
class TuneWake : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val dir = context.getExternalFilesDir(null) ?: return
        val keywords = File(dir, intent.getStringExtra("keywords") ?: return)
        val prefix = intent.getStringExtra("prefix") ?: "t_"
        if (keywords.parentFile?.canonicalPath != dir.canonicalPath || !keywords.isFile) {
            Log.w(TAG, "no keywords file in ${dir.path}")
            return
        }
        // Longer than a receiver may take; the call service keeps the process alive meanwhile.
        scope.launch {
            val model = CallService.wakeModel()
            if (model == null) {
                Log.w(TAG, "\"Hey WatchGPT\" is off or still loading")
                return@launch
            }
            val lines = keywords.readText()
            val clips = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
            for (clip in clips) {
                val pcm = Wav.read16kMono(clip.readBytes()) ?: continue
                Log.i(TAG, "tune ${clip.name} -> ${model.firstKeyword(lines, pcm) ?: "-"}")
            }
            Log.i(TAG, "tune done: ${clips.size} clips")
        }
    }

    private companion object {
        const val TAG = "TuneWake"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
