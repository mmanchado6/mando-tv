package local.mandotv.ui

import kotlinx.coroutines.delay
import local.mandotv.protocol.TvKey

/**
 * Lógica de «mantener pulsado»: envía la orden al pulsar y, si la tecla se repite (volumen,
 * canal y flechas), la repite tras [TvKey.REPEAT_DELAY_MS] cada [TvKey.repeatMs] hasta que se
 * suelta el botón (es decir, hasta que se cancela la corrutina).
 *
 * [send] recibe true en las repeticiones (para una vibración más suave).
 */
suspend fun pressAndRepeat(key: TvKey, send: (repeat: Boolean) -> Boolean) {
    if (!send(false) || !key.repeats) return
    delay(TvKey.REPEAT_DELAY_MS)
    while (true) {
        if (!send(true)) return // si se pierde la conexión, se deja de repetir
        delay(key.repeatMs)
    }
}
