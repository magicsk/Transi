package eu.magicsk.transi

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * A [SeekBarPreference] drawn as a Material slider (`@layout/preference_slider`); its value, range, step, change
 * listener and storage stay the seek bar's.
 */
class SliderPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SeekBarPreference(context, attrs) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val slider = holder.findViewById(R.id.preferenceSlider) as? Slider ?: return
        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.valueFrom = min.toFloat()
        slider.valueTo = max.toFloat()
        val current = value.coerceIn(min, max)
        // The slider rejects a value off its steps; a stored value off them (an older default) slides freely until set.
        val step = seekBarIncrement
        slider.stepSize = if (step > 0 && (max - min) % step == 0 && (current - min) % step == 0) step.toFloat() else 0f
        slider.value = current.toFloat()
        slider.isEnabled = isEnabled
        slider.contentDescription = title
        slider.addOnChangeListener { _, newValue, fromUser -> if (fromUser && updatesContinuously) set(newValue) }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = set(slider.value)
        })
    }

    private fun set(newValue: Float) {
        val rounded = newValue.roundToInt()
        if (rounded != value && callChangeListener(rounded)) value = rounded
    }
}
