package aniyomi.lib.megaextractor

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Persiste la sesión de MEGA (si el usuario inició sesión) en un archivo de
 * SharedPreferences con nombre FIJO -- a propósito, y no scopeado por fuente
 * como hace `keiyoushi.utils.getPreferences(sourceId)` (que arma el nombre
 * del archivo como `"source_$sourceId"`, uno distinto por extensión).
 *
 * Esto funciona porque todas las extensiones corren en el mismo proceso y
 * comparten el mismo `Application` Context: no son apps Android separadas
 * en tiempo de ejecución pese a instalarse como APKs individuales. Ya hay
 * precedente de esto mismo en el repo -- `lib/randomua` y
 * `lib/javcoverfetcher` (ambas librerías, no extensiones) ya usan
 * `Injekt.get<Application>()` directo, sin declarar ninguna dependencia
 * extra en su build.gradle.kts -- así que cualquier extensión que
 * instancie [MegaExtractor] ve la MISMA sesión que haya iniciado
 * cualquier otra. La idea es que solo la extensión "MEGA" tenga UI de
 * login, y el resto (FallenSubs, ElAbismoDeDarkrai, etc.) se benefician
 * solas apenas exista una sesión guardada acá.
 *
 * Se guardan sid + master key ya derivados, NO la contraseña en texto
 * plano: si el sid deja de ser válido (cambio de contraseña, revocación
 * manual desde mega.nz, etc.) hay que volver a iniciar sesión a mano, pero
 * la contraseña de la cuenta nunca queda guardada en el dispositivo.
 */
internal object MegaSessionStore {

    private const val PREFS_NAME = "megaextractor_session"
    private const val KEY_EMAIL = "email"
    private const val KEY_SID = "sid"
    private const val KEY_MASTER_KEY = "master_key"
    private const val KEY_OWN_HANDLE = "own_handle"

    private val prefs: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun save(session: MegaAccountSession) {
        prefs.edit()
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_SID, MegaCrypto.megaBase64Encode(session.sid))
            .putString(KEY_MASTER_KEY, MegaCrypto.megaBase64Encode(session.masterKey))
            .putString(KEY_OWN_HANDLE, MegaCrypto.megaBase64Encode(session.ownHandle))
            .apply()
    }

    fun load(): MegaAccountSession? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val sidB64 = prefs.getString(KEY_SID, null) ?: return null
        val keyB64 = prefs.getString(KEY_MASTER_KEY, null) ?: return null
        // Si falta (sesión guardada antes de este campo existir), se
        // invalida la sesión completa a propósito: sin esto no se puede
        // resolver bien un compound key, mejor forzar un re-login limpio
        // que arrastrar una sesión a medias.
        val ownHandleB64 = prefs.getString(KEY_OWN_HANDLE, null) ?: return null
        return try {
            MegaAccountSession(
                email = email,
                sid = MegaCrypto.megaBase64Decode(sidB64),
                masterKey = MegaCrypto.megaBase64Decode(keyB64),
                ownHandle = MegaCrypto.megaBase64Decode(ownHandleB64),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
