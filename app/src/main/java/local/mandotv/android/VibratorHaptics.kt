package local.mandotv.android

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import local.mandotv.ui.Haptics

/**
 * Vibración al pulsar usando el vibrador del móvil (permiso VIBRATE, que Android concede solo).
 * A diferencia de la «respuesta táctil» del sistema, vibra aunque esa opción esté desactivada en
 * los ajustes del móvil. Se puede apagar en los Ajustes de la app.
 */
class VibratorHaptics(context: Context, private val enabled: () -> Boolean) : Haptics {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    private fun play(effect: Int) {
        val v = vibrator ?: return
        if (!enabled() || !v.hasVibrator()) return
        val e = VibrationEffect.createPredefined(effect)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                // Uso «multimedia»: no depende del ajuste de respuesta táctil del sistema
                v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                v.vibrate(e)
            }
        }
    }

    override fun tap() = play(VibrationEffect.EFFECT_CLICK)
    override fun tick() = play(VibrationEffect.EFFECT_TICK)
    override fun long() = play(VibrationEffect.EFFECT_HEAVY_CLICK)
}
