package io.hyperaudio.bridge.hook

import android.content.Context
import android.widget.LinearLayout
import androidx.annotation.Keep

/** HyperOS invokes this method reflectively on each detail content view. */
@Keep
class DetailContent(context: Context, private val refreshState: () -> Unit) : LinearLayout(context) {
    fun setDetailShowing(showing: Boolean) { if (showing) refreshState() }
}
