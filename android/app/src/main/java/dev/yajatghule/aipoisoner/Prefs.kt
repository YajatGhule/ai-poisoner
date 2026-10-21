package dev.yajatghule.aipoisoner

import android.content.Context

/** User settings plus the score thresholds shared by the UI, overlay and notification. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun imagesEnabled(ctx: Context) = sp(ctx).getBoolean("images", true)
    fun setImagesEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("images", on).apply()

    /** Cover images rated AI with an opaque card instead of just labelling them. */
    fun coverAi(ctx: Context) = sp(ctx).getBoolean("cover", false)
    fun setCoverAi(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("cover", on).apply()

    // Stricter than the Chrome extension (0.85): on-screen crops are downscaled and cropped, which pushes
    // real photos up. At 0.95, 0% of real test photos were flagged AI (45% of AI images caught); the rest show "Unsure".
    const val IMAGE_AI_AT = 0.95f
    const val IMAGE_HUMAN_BELOW = 0.60f

    // A chosen file is the original image (not a downscaled screen crop), so the Chrome extension's cutoffs apply.
    const val IMAGE_FILE_AI_AT = 0.85f
    const val IMAGE_FILE_HUMAN_BELOW = 0.60f

    // Chosen on the held-out test set (android/voice-model/README.md):
    // v2: >= 0.80 flagged 0% of real test voices (2% with added noise); unseen engines mostly land in "Unsure".
    const val VOICE_AI_AT = 0.80f
    const val VOICE_HUMAN_BELOW = 0.40f
}

enum class Verdict(val label: String, val color: Int) {
    AI("AI", 0xFFE0604F.toInt()),
    UNSURE("Unsure", 0xFFC9A45C.toInt()),
    HUMAN("Human", 0xFF6FBF8A.toInt());

    companion object {
        fun image(p: Float) = band(p, Prefs.IMAGE_AI_AT, Prefs.IMAGE_HUMAN_BELOW)
        fun imageFile(p: Float) = band(p, Prefs.IMAGE_FILE_AI_AT, Prefs.IMAGE_FILE_HUMAN_BELOW)
        fun voice(p: Float) = band(p, Prefs.VOICE_AI_AT, Prefs.VOICE_HUMAN_BELOW)
        private fun band(p: Float, aiAt: Float, humanBelow: Float) =
            if (p >= aiAt) AI else if (p < humanBelow) HUMAN else UNSURE
    }
}
