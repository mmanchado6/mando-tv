package local.mandotv.protocol

/**
 * Botones del mando y su código de tecla de Android (android.view.KeyEvent), que es lo que
 * entiende el protocolo. [repeats] indica si se repite al mantener pulsado.
 */
enum class TvKey(
    val code: Int,
    val repeats: Boolean = false,
    val repeatMs: Long = 120,
    /** Mantener pulsado envía una pulsación larga a la tele (como el mando físico). */
    val longPress: Boolean = false,
) {
    POWER(26),
    VOLUME_UP(24, repeats = true),
    VOLUME_DOWN(25, repeats = true),
    MUTE(164),
    CHANNEL_UP(166, repeats = true, repeatMs = 350),
    CHANNEL_DOWN(167, repeats = true, repeatMs = 350),
    NUM_0(7), NUM_1(8), NUM_2(9), NUM_3(10), NUM_4(11),
    NUM_5(12), NUM_6(13), NUM_7(14), NUM_8(15), NUM_9(16),
    RED(183), GREEN(184), YELLOW(185), BLUE(186),
    HOME(3),
    BACK(4),
    UP(19, repeats = true, repeatMs = 150),
    DOWN(20, repeats = true, repeatMs = 150),
    LEFT(21, repeats = true, repeatMs = 150),
    RIGHT(22, repeats = true, repeatMs = 150),
    OK(23, longPress = true),
    PLAY_PAUSE(85),
    STOP(86),
    NEXT(87),
    PREVIOUS(88),
    REWIND(89),
    FAST_FORWARD(90),
    SETTINGS(176),
    MENU(82),
    INPUT(178),
    // Entradas directas (KEYCODE_TV_INPUT_HDMI_1..4) y volver a la TV (KEYCODE_TV)
    HDMI_1(243), HDMI_2(244), HDMI_3(245), HDMI_4(246),
    TV(170),
    GUIDE(172),
    INFO(165),
    CAPTIONS(175),
    /** Buscar: con la voz activada, la tele responde abriendo una sesión de voz. */
    SEARCH(84),
    DEL(67),
    ENTER(66),
    NUMPAD_ENTER(160);

    companion object {
        val digits = listOf(NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9)

        /** Retardo antes de empezar a repetir al mantener pulsado. */
        const val REPEAT_DELAY_MS = 400L

        /** Tiempo para considerar una pulsación larga (el de Android). */
        const val LONG_PRESS_MS = 500L
    }
}
