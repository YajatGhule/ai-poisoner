package dev.yajatghule.aipoisoner

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Offline streaming speech-to-text (Vosk). Feed 16 kHz mono PCM. */
class Transcriber(modelDir: File) : AutoCloseable {
    private val model = Model(modelDir.absolutePath)
    private val recognizer = Recognizer(model, 16000f)

    /** Returns finished text when a phrase ends, otherwise null and updates [partial]. */
    fun accept(pcm: ShortArray, length: Int): String? {
        return if (recognizer.acceptWaveForm(pcm, length)) {
            partial = ""
            JSONObject(recognizer.result).optString("text").takeIf { it.isNotBlank() }
        } else {
            partial = JSONObject(recognizer.partialResult).optString("partial")
            null
        }
    }

    var partial: String = ""
        private set

    override fun close() {
        recognizer.close()
        model.close()
    }
}
