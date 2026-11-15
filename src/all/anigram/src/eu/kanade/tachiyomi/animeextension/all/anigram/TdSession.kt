package eu.kanade.tachiyomi.animeextension.all.anigram

import android.content.Context
import android.os.Build
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArchivoTg(val fileId: Int, val size: Long, val nombre: String, val mime: String)

/**
 * Sesión única de TDLib dentro de la extensión: login, lectura de mensajes y archivos por rangos.
 * La base de datos de TDLib vive en la carpeta de datos de la app: se queda en el teléfono.
 */
object TdSession {
    private const val TAG = "AniGram"
    private const val CHUNK = 1024 * 1024L

    private var nativaCargada = false

    @Volatile private var client: Client? = null

    @Volatile var estado: String = "SIN_INICIAR"
        private set

    private var apiId = 0
    private var apiHash = ""
    private var baseDir: File? = null

    @Volatile private var servidor: TgProxyServer? = null

    @Volatile var ultimoFileId: Int = -1

    @Volatile var ultimaLimpieza: String = "(aun no se ha limpiado nada)"

    private val idsUsados = mutableSetOf<Int>()
    private val idsBorrados = mutableSetOf<Int>()
    private val idsAvisados = mutableSetOf<Int>()
    private val lecturasEnCurso = AtomicInteger(0)

    private var ultimaClave: String? = null
    private var ultimoArchivo: ArchivoTg? = null
    private var limpiezaInicialHecha = false

    private val eventosLog: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val inicioLog = System.currentTimeMillis()

    fun evento(texto: String) {
        eventosLog.add("t=${(System.currentTimeMillis() - inicioLog) / 1000}s $texto")
        while (eventosLog.size > 40) eventosLog.removeAt(0)
    }

    fun eventosSnapshot(): List<String> = synchronized(eventosLog) { eventosLog.toList() }.reversed()

    @Synchronized
    fun cargarNativa(ctx: Context) {
        if (nativaCargada) return
        // Orden obligatorio: primero se inicializan las clases Java y despues se carga la libreria.
        // Al reves, el JNI_OnLoad de TDLib entra en inicializacion recursiva y aborta el proceso.
        Class.forName("org.drinkless.tdlib.TdApi")
        Class.forName("org.drinkless.tdlib.Client")
        val pkg = AniGram::class.java.`package`!!.name
        val dir = ctx.packageManager.getApplicationInfo(pkg, 0).nativeLibraryDir
        System.load(File(dir, "libtdjni.so").absolutePath)
        nativaCargada = true
    }

    @Synchronized
    fun iniciar(ctx: Context, id: Int, hash: String) {
        if (client != null) return
        cargarNativa(ctx)
        apiId = id
        apiHash = hash
        baseDir = File(ctx.filesDir, "anigram_tdlib")
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        estado = "INICIANDO"
        client = Client.create(
            { update -> alActualizar(update) },
            { t -> Log.e(TAG, "Error en el manejador de updates", t) },
            { t -> Log.e(TAG, "Excepcion de TDLib", t) },
        )
    }

    private fun alActualizar(obj: TdApi.Object) {
        if (obj is TdApi.UpdateAuthorizationState) manejarAuth(obj.authorizationState)
    }

    private fun manejarAuth(s: TdApi.AuthorizationState) {
        // Se mapea por tipo: R8 puede quitar la info de clases internas y simpleName no sirve
        estado = when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> "WaitTdlibParameters"
            is TdApi.AuthorizationStateWaitPhoneNumber -> "WaitPhoneNumber"
            is TdApi.AuthorizationStateWaitCode -> "WaitCode"
            is TdApi.AuthorizationStateWaitPassword -> "WaitPassword"
            is TdApi.AuthorizationStateReady -> "Ready"
            is TdApi.AuthorizationStateLoggingOut -> "LoggingOut"
            is TdApi.AuthorizationStateClosing -> "Closing"
            is TdApi.AuthorizationStateClosed -> "Closed"
            else -> s.javaClass.name.substringAfterLast('.')
        }
        Log.d(TAG, "Estado de autorizacion: $estado")
        if (s is TdApi.AuthorizationStateWaitTdlibParameters) {
            Thread { enviarParametros() }.start()
        }
        if (s is TdApi.AuthorizationStateReady && !limpiezaInicialHecha) {
            limpiezaInicialHecha = true
            // Al arrancar se vacia lo que quedo descargado de sesiones anteriores
            Thread {
                try {
                    call(TdApi.OptimizeStorage(0, 0, 0, 0, null, null, null, false, 0))
                    evento("LIMPIEZA INICIAL ok")
                } catch (t: Throwable) {
                    evento("LIMPIEZA INICIAL error: ${t.message}")
                }
            }.start()
        }
    }

    private fun enviarParametros() {
        // Puede que el primer update llegue antes de que Client.create devuelva el cliente
        var espera = 0
        while (client == null && espera < 5000) {
            Thread.sleep(50)
            espera += 50
        }
        val base = baseDir ?: return
        val p = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(base, "db").absolutePath
            filesDirectory = File(base, "files").absolutePath
            databaseEncryptionKey = ByteArray(0) // pendiente: cifrar la base de datos local
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = this@TdSession.apiId
            apiHash = this@TdSession.apiHash
            systemLanguageCode = "es"
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = "anigram"
        }
        client?.send(p) { r ->
            if (r is TdApi.Error) estado = "ERROR_PARAMETROS ${r.code}: ${r.message}"
        }
    }

    fun <T : TdApi.Object> call(fn: TdApi.Function<T>, timeoutSec: Long = 120): T {
        val c = client ?: error("TDLib no esta iniciado")
        val latch = CountDownLatch(1)
        var res: TdApi.Object? = null
        c.send(fn) { r ->
            res = r
            latch.countDown()
        }
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) error("TDLib no respondio a ${fn.javaClass.simpleName}")
        val r = res!!
        if (r is TdApi.Error) error("TDLib ${r.code}: ${r.message}")
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    /** Espera a que TDLib termine de arrancar (hasta Ready o hasta pedir login) y devuelve el estado. */
    fun esperarEstado(ms: Long = 15000): String {
        var t = 0L
        while ((estado == "INICIANDO" || estado == "WaitTdlibParameters" || estado == "SIN_INICIAR") && t < ms) {
            Thread.sleep(100)
            t += 100
        }
        return estado
    }

    fun enviarTelefono(numero: String) {
        call(TdApi.SetAuthenticationPhoneNumber(numero.trim(), TdApi.PhoneNumberAuthenticationSettings()))
    }

    fun enviarCodigo(codigo: String) {
        call(TdApi.CheckAuthenticationCode(codigo.trim()))
    }

    fun enviarPassword(pw: String) {
        call(TdApi.CheckAuthenticationPassword(pw))
    }

    fun cerrarSesion() {
        call(TdApi.LogOut())
    }

    // ---------- archivos de video ----------

    private fun archivoDe(msg: TdApi.Message): ArchivoTg = when (val c = msg.content) {
        is TdApi.MessageVideo -> c.video.video.let {
            ArchivoTg(it.id, maxOf(it.size, it.expectedSize), c.video.fileName.orEmpty(), c.video.mimeType.orEmpty())
        }
        is TdApi.MessageDocument -> c.document.document.let {
            ArchivoTg(it.id, maxOf(it.size, it.expectedSize), c.document.fileName.orEmpty(), c.document.mimeType.orEmpty())
        }
        else -> error("El mensaje no contiene un video ni un documento (${c.javaClass.simpleName})")
    }

    fun archivoDeMensaje(chatId: Long, mensajeId: Long): ArchivoTg = archivoDe(call(TdApi.GetMessage(chatId, mensajeId)))

    @Volatile private var carpetaPortadas: File? = null

    /** Carpeta donde se guardan las portadas ya descargadas (cache del telefono). */
    fun prepararCarpetas(ctx: Context) {
        if (carpetaPortadas == null) carpetaPortadas = File(ctx.cacheDir, "anigram_portadas").also { it.mkdirs() }
    }

    /**
     * Portada en tamaño medio (unos 500 px): se descarga una sola vez de Telegram y se guarda en disco;
     * las siguientes veces se sirve de ahi, sin tocar la red.
     */
    fun bytesDePortada(chatId: Long, mensajeId: Long): ByteArray {
        val archivo = carpetaPortadas?.let { File(it, "${chatId}_$mensajeId.jpg") }
        if (archivo != null && archivo.length() > 0) return archivo.readBytes()
        val msg = call(TdApi.GetMessage(chatId, mensajeId))
        val foto = (msg.content as? TdApi.MessagePhoto)?.photo ?: error("el mensaje no es una foto")
        val ordenadas = foto.sizes.sortedBy { maxOf(it.width, it.height) }
        val elegida = ordenadas.firstOrNull { maxOf(it.width, it.height) >= 500 }
            ?: ordenadas.lastOrNull() ?: error("la foto no tiene tamanos")
        val f = call(TdApi.DownloadFile(elegida.photo.id, 16, 0, 0, true), 60)
        val ruta = f.local.path
        if (ruta.isNullOrEmpty()) error("la portada no se descargo")
        val bytes = File(ruta).readBytes()
        if (archivo != null) {
            try {
                archivo.parentFile?.mkdirs()
                archivo.writeBytes(bytes)
            } catch (t: Throwable) {
                Log.w(TAG, "no se pudo guardar la portada: ${t.message}")
            }
        }
        return bytes
    }

    /**
     * Prepara el archivo de un episodio. Si es el mismo que el anterior se reutiliza lo descargado;
     * si es otro, primero se cortan las lecturas del anterior y se borra lo que habia descargado.
     */
    fun resolverArchivo(clave: String, obtener: () -> ArchivoTg): ArchivoTg {
        val previo = ultimoArchivo
        if (previo != null && ultimaClave == clave) {
            ultimoFileId = previo.fileId
            ultimaLimpieza = "(mismo episodio: se conserva la cache)"
            evento("MISMO episodio id=${previo.fileId}")
            return previo
        }
        evento("CAMBIO de episodio: abortar lecturas, cancelar y limpiar")
        TgStats.abortarTodas()
        cancelarDescarga()
        esperarLecturas(8000)
        val viejos = synchronized(idsUsados) { idsUsados.toList() }
        limpiarCache()
        barrerTarde(viejos)
        val archivo = obtener()
        synchronized(idsUsados) { idsUsados.add(archivo.fileId) }
        synchronized(idsBorrados) { idsBorrados.remove(archivo.fileId) }
        ultimoFileId = archivo.fileId
        ultimaClave = clave
        ultimoArchivo = archivo
        evento("ABRIR id=${archivo.fileId} ${archivo.nombre}")
        return archivo
    }

    /** Descarga solo el rango pedido (bloqueante) y devuelve sus bytes. */
    fun leerRango(fileId: Int, offset: Long, count: Long): ByteArray {
        if (synchronized(idsBorrados) { fileId in idsBorrados }) {
            if (synchronized(idsAvisados) { idsAvisados.add(-fileId) }) {
                evento("LECTURA BLOQUEADA del archivo $fileId (ya borrado)")
            }
            throw IllegalStateException("Archivo $fileId ya fue borrado")
        }
        if (fileId != ultimoFileId && synchronized(idsAvisados) { idsAvisados.add(fileId) }) {
            evento("lectura de archivo viejo $fileId (actual $ultimoFileId)")
        }
        lecturasEnCurso.incrementAndGet()
        try {
            call(TdApi.DownloadFile(fileId, 32, offset, count, true), 300)
            return call(TdApi.ReadFilePart(fileId, offset, count)).data
        } finally {
            lecturasEnCurso.decrementAndGet()
        }
    }

    /** Espera (con tope) a que terminen las lecturas que ya estaban dentro de TDLib. */
    private fun esperarLecturas(ms: Long) {
        var t = 0L
        while (lecturasEnCurso.get() > 0 && t < ms) {
            Thread.sleep(100)
            t += 100
        }
        evento("lecturas en curso al limpiar: ${lecturasEnCurso.get()} (esperado $t ms)")
    }

    /** Segundo borrado unos segundos despues, por si algo recreo el archivo en medio. */
    private fun barrerTarde(ids: List<Int>) {
        if (ids.isEmpty()) return
        Thread {
            try {
                Thread.sleep(5000)
                for (id in ids) {
                    if (id == ultimoFileId) continue // nunca tocar el episodio actual
                    try {
                        call(TdApi.DeleteFile(id))
                        evento("BARRIDO tardio DeleteFile $id ok")
                    } catch (t: Throwable) {
                        evento("BARRIDO tardio DeleteFile $id: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "barrerTarde: ${t.message}")
            }
        }.start()
    }

    /** Borra los archivos usados antes y devuelve un resumen. No lanza excepciones. */
    fun limpiarCache(): String {
        if (client == null) return "TDLib no iniciado"
        val ids = synchronized(idsUsados) { idsUsados.toList().also { idsUsados.clear() } }
        var borrados = 0
        val errores = mutableListOf<String>()
        for (id in ids) {
            try {
                call(TdApi.DeleteFile(id))
                synchronized(idsBorrados) { idsBorrados.add(id) }
                evento("DeleteFile $id ok")
                borrados++
            } catch (t: Throwable) {
                errores.add("DeleteFile $id: ${t.message}")
                evento("DeleteFile $id ERROR: ${t.message}")
            }
        }
        try {
            val tipos = arrayOf<TdApi.FileType>(TdApi.FileTypeVideo(), TdApi.FileTypeDocument())
            call(TdApi.OptimizeStorage(0, 0, 0, 0, tipos, null, null, false, 0))
        } catch (t: Throwable) {
            errores.add("Optimize: ${t.message}")
        }
        val r = "DeleteFile ok=$borrados/${ids.size}" +
            if (errores.isEmpty()) "" else ", errores: ${errores.joinToString("; ")}"
        Log.d(TAG, r)
        ultimaLimpieza = r
        evento("LIMPIEZA: $r")
        return r
    }

    /** Tamaño aparente y real (bloques ocupados en disco) de la cache, en MB. */
    fun tamanoCache(): String {
        val base = baseDir ?: return "TDLib no iniciado"
        var aparente = 0L
        var real = 0L
        fun recorrer(f: File) {
            if (f.isDirectory) {
                f.listFiles()?.forEach { recorrer(it) }
            } else {
                aparente += f.length()
                real += try {
                    android.system.Os.stat(f.absolutePath).st_blocks * 512L
                } catch (t: Throwable) {
                    f.length()
                }
            }
        }
        recorrer(File(base, "files"))
        val mb = 1024L * 1024L
        return "archivos: aparente ${aparente / mb} MB, real en disco ${real / mb} MB"
    }

    /** Lista los archivos de la carpeta de descargas de TDLib con su tamaño aparente y real. */
    fun listarArchivos(): String {
        val base = baseDir ?: return "TDLib no iniciado"
        val raiz = File(base, "files")
        val filas = mutableListOf<String>()
        val mb = 1024L * 1024L
        fun recorrer(f: File) {
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.forEach { recorrer(it) }
            } else {
                val real = try {
                    android.system.Os.stat(f.absolutePath).st_blocks * 512L
                } catch (t: Throwable) {
                    f.length()
                }
                filas.add("${f.relativeTo(raiz).path}  aparente ${f.length() / mb} MB, real ${real / mb} MB")
            }
        }
        recorrer(raiz)
        return if (filas.isEmpty()) "(vacio)" else filas.take(40).joinToString("\n")
    }

    /** Lo que TDLib dice haber descargado del ultimo archivo. */
    fun infoArchivo(id: Int): String {
        val f = call(TdApi.GetFile(id))
        val mb = 1024L * 1024L
        return "TDLib: bajo ${f.local.downloadedSize / mb} de ${f.size / mb} MB, " +
            "activa=${f.local.isDownloadingActive}, completa=${f.local.isDownloadingCompleted}"
    }

    /** Detiene la descarga activa del ultimo archivo (no borra lo ya bajado). */
    fun cancelarDescarga() {
        val id = ultimoFileId
        if (id < 0) return
        try {
            call(TdApi.CancelDownloadFile(id, false), 10)
        } catch (t: Throwable) {
            Log.w(TAG, "CancelDownloadFile fallo: ${t.message}")
        }
    }

    fun puerto(): Int = servidor?.localPort ?: -1

    fun tamanoChunk() = CHUNK

    @Synchronized
    fun servidor(): TgProxyServer {
        servidor?.let { return it }
        val s = try {
            TgProxyServer(8765).also { it.start() }
        } catch (t: Throwable) {
            TgProxyServer(0).also { it.start() }
        }
        servidor = s
        return s
    }
}
