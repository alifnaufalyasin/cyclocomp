package com.aliven.cyclocomp.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aliven.cyclocomp.core.VoiceCueEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Speaks the navigation cues chosen by [VoiceCueEngine] with the phone's own speech engine. It lives
 * as long as the ride service, so directions are still spoken with the screen off. Music is turned
 * down while a cue is spoken, then comes back.
 */
class VoiceGuide(context: Context) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private val engine = VoiceCueEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: String? = null // newest cue said before the speech engine was ready
    private var job: Job? = null

    fun start() {
        if (!NavSettings.voiceCues(app) || tts != null) return
        tts = TextToSpeech(app, this)
        job = scope.launch { NavSession.state.collect { onState(it) } }
    }

    fun stop() {
        job?.cancel()
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        audio?.abandonAudioFocusRequest(focus)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        // The cue texts are English, so an English voice is needed; without one the guide stays silent.
        val lang = t.setLanguage(Locale.US)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) return
        t.setAudioAttributes(attrs)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { audio?.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { audio?.abandonAudioFocusRequest(focus) }
        })
        ready = true
        waiting?.let { speak(it) }
        waiting = null
    }

    private fun onState(st: NavState) {
        val cues = engine.update(
            route = st.route,
            progress = st.progress,
            arrived = st.arrived,
            rerouting = st.loading && st.route != null,
            offCourse = st.courseName != null && (st.progress?.offRouteM ?: 0.0) > NavSession.OFF_COURSE_M,
        )
        // Several cues at once would only be stale by the time they are read; the last one counts.
        cues.lastOrNull()?.let { if (ready) speak(it) else waiting = it }
    }

    private fun speak(text: String) {
        val t = tts ?: return
        audio?.requestAudioFocus(focus)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue")
    }
}
