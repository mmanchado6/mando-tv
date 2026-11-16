package local.mandotv.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import local.mandotv.protocol.RemoteMessages
import local.mandotv.remote.VoiceSource
import java.io.IOException

/**
 * Micrófono del móvil para hablar a la tele. Solo graba mientras dura [record] (mientras se
 * mantiene pulsado el botón de voz) y entrega el audio en memoria, trozo a trozo: nunca se
 * escribe en disco ni se conserva después de enviarlo.
 *
 * Formato que espera el protocolo: PCM de 16 bits, 8 kHz, mono.
 */
class MicCapture : VoiceSource {
    // El permiso RECORD_AUDIO se comprueba en cada pulsación, justo antes de llegar aquí
    // (puede haberse dado «solo esta vez»); si faltara, AudioRecord falla y no se graba nada.
    @SuppressLint("MissingPermission")
    override suspend fun record(onChunk: (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IOException("el micrófono no admite 8 kHz")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 2),
        )
        val chunk = ByteArray(CHUNK)
        var filled = 0
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("no se pudo abrir el micrófono")
            recorder.startRecording()
            // Lecturas cortas (~60 ms) para reaccionar enseguida al soltar el botón.
            while (isActive) {
                val n = recorder.read(chunk, filled, minOf(READ, CHUNK - filled))
                if (n < 0) throw IOException("error del micrófono ($n)")
                filled += n
                if (filled == CHUNK) {
                    onChunk(chunk.copyOf())
                    filled = 0
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            if (filled > 0) onChunk(chunk.copyOf(filled)) // lo último que se dijo antes de soltar
            chunk.fill(0)
        }
    }

    private companion object {
        const val RATE = RemoteMessages.VOICE_SAMPLE_RATE
        /** Un mensaje completo (1,28 s de audio): con trozos menores la tele no reconoce nada. */
        const val CHUNK = RemoteMessages.VOICE_CHUNK
        const val READ = 1024
    }
}
