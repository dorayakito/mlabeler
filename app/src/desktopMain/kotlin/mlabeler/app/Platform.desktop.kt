package mlabeler.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.PointerIcon
import mlabeler.core.audio.Audio
import mlabeler.core.audio.Wav
import java.awt.Cursor
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import java.util.concurrent.FutureTask
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

actual object Platform {
    private val os = System.getProperty("os.name").lowercase()
    actual val name: String = System.getProperty("os.name")
    actual val isMac: Boolean = os.contains("mac")
    actual val isMobile: Boolean = false
    actual val cores: Int get() = Runtime.getRuntime().availableProcessors()
    actual val systemLanguage: String = Locale.getDefault().language

    actual val portableDir: String? = System.getProperty("mlabeler.portable")?.takeIf { it.isNotBlank() }
    // Windows older than 10 (version 6.x); -Dmlabeler.legacy=1 pretends it, for trying it out
    actual val legacyWindows: Boolean = System.getProperty("mlabeler.legacy") == "1" || run {
        val os = System.getProperty("os.name").orEmpty()
        os.startsWith("Windows") && (System.getProperty("os.version")?.substringBefore('.')?.toIntOrNull() ?: 10) < 10
    }

    actual fun dataDir(): String {
        val home = System.getProperty("user.home")
        System.getenv("MLABELER_HOME")?.let { return it }
        return when {
            os.contains("win") -> File(System.getenv("APPDATA") ?: home, "mLabeler").path
            isMac -> File(home, "Library/Application Support/mLabeler").path
            else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "mlabeler").path
        }
    }

    actual fun homeDir(): String = System.getProperty("user.home")

    actual fun places(): List<Pair<String, String>> {
        val home = homeDir()
        val list = mutableListOf("~" to home)
        for (n in listOf("Desktop", "Documents", "Music", "Downloads")) {
            val f = File(home, n)
            if (f.isDirectory) list += n to f.path
        }
        File.listRoots().forEach { list += it.path to it.path }
        return list
    }

    actual val hasNativeFolderPicker: Boolean = true

    actual fun pickFileNative(title: String, extensions: List<String>, start: String?): String? = onEventThread {
        // LWJGL's macOS NFD backend creates NSOpenPanel on the calling Java thread, but AppKit requires
        // the process main thread (the Swing event thread is not sufficient). Use Swing's Java chooser.
        if (isMac) return@onEventThread pickFileSwing(title, extensions, start)
        runCatching { return@onEventThread NativeFolderDialog.pickFile(extensions, start) }.onFailure { System.err.println("native file dialog: $it") }
        val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (start != null) d.directory = start
        if (extensions.isNotEmpty()) d.setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        d.isVisible = true
        val dir = d.directory ?: return@onEventThread null
        val file = d.file ?: return@onEventThread null
        File(dir, file).path
    }

    actual fun pickFolderNative(title: String, start: String?): String? = onEventThread {
        // the system folder dialog (Explorer on Windows, with the address bar; GTK/portal on Linux)
        // LWJGL's macOS NFD backend creates NSOpenPanel on the calling Java thread, but AppKit requires
        // the process main thread. Swing's Java chooser avoids that native thread restriction.
        if (isMac) return@onEventThread pickFolderSwing(title, start)
        runCatching { return@onEventThread NativeFolderDialog.pick(start) }.onFailure { System.err.println("native folder dialog: $it") }
        // last resort: an Open dialog where any file in the folder opens the folder
        val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (start != null) d.directory = start
        d.isVisible = true
        val dir = d.directory ?: return@onEventThread null
        File(dir).path
    }

    private fun pickFileSwing(title: String, extensions: List<String>, start: String?): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            start?.let { File(it) }?.let { if (it.isDirectory) currentDirectory = it else selectedFile = it }
            if (extensions.isNotEmpty()) fileFilter = FileNameExtensionFilter(extensions.joinToString(", "), *extensions.toTypedArray())
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }

    private fun pickFolderSwing(title: String, start: String?): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            start?.let { File(it) }?.takeIf { it.isDirectory }?.let { currentDirectory = it }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }

    /** Desktop file choosers must be created on AWT's event thread. */
    private fun <T> onEventThread(action: () -> T): T {
        if (EventQueue.isDispatchThread()) return action()
        val task = FutureTask<T> { action() }
        EventQueue.invokeAndWait(task)
        return task.get()
    }

    private val ffmpeg: String? by lazy {
        val names = if (os.contains("win")) listOf("ffmpeg.exe") else listOf("ffmpeg")
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar) + listOf("/opt/homebrew/bin", "/usr/local/bin")
        dirs.flatMap { d -> names.map { File(d, it) } }.firstOrNull { it.canExecute() }?.path
    }

    actual fun decodeAudio(path: String): Audio? {
        val exe = ffmpeg ?: return null
        val p = ProcessBuilder(exe, "-v", "error", "-i", path, "-f", "wav", "-acodec", "pcm_s16le", "-ac", "1", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        if (bytes.size < 44) return null
        // ffmpeg writes an unknown data size when piping; patch it to the real length
        val fixed = bytes.copyOf()
        val dataAt = String(fixed, 0, minOf(fixed.size, 200), Charsets.ISO_8859_1).indexOf("data")
        if (dataAt > 0) {
            val n = fixed.size - dataAt - 8
            for (i in 0 until 4) fixed[dataAt + 4 + i] = (n shr (8 * i)).toByte()
        }
        return Wav.decode(fixed)
    }

    actual fun openInFileManager(path: String) {
        runCatching { Desktop.getDesktop().open(File(path)) }
    }
    actual fun applyScreen(orientation: String, fullscreen: Boolean) = Unit
}

actual val resizeHorizontalIcon: PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

actual class AudioOut actual constructor() {
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var startSample = 0
    @Volatile private var length = 0
    @Volatile private var loopMode = false

    actual fun play(audio: Audio, from: Int, to: Int, loop: Boolean) {
        stop()
        val fmt = AudioFormat(audio.sampleRate.toFloat(), 16, 1, true, false)
        val l = AudioSystem.getSourceDataLine(fmt)
        l.open(fmt, audio.sampleRate / 10 * 2)
        line = l
        startSample = from
        length = to - from
        loopMode = loop
        stopFlag = false
        l.start()
        thread = Thread {
            val buf = ByteArray(4096)
            try {
                do {
                    var pos = from
                    while (pos < to && !stopFlag) {
                        val n = minOf((to - pos), buf.size / 2)
                        for (i in 0 until n) {
                            val v = (audio.samples[pos + i].coerceIn(-1f, 1f) * 32767).toInt()
                            buf[i * 2] = v.toByte()
                            buf[i * 2 + 1] = (v shr 8).toByte()
                        }
                        l.write(buf, 0, n * 2)
                        pos += n
                    }
                } while (loop && !stopFlag)
                if (!stopFlag) l.drain()
            } catch (_: Exception) {
            } finally {
                runCatching { l.stop(); l.close() }
                if (line === l) line = null
            }
        }.apply { isDaemon = true; name = "mlabeler-audio"; start() }
    }

    actual fun stop() {
        stopFlag = true
        line?.let { runCatching { it.stop(); it.flush() } }
        thread?.join(300)
        thread = null
        line = null
    }

    actual val isPlaying: Boolean get() = line != null

    actual fun position(): Int {
        val l = line ?: return -1
        if (length <= 0) return -1
        val played = l.longFramePosition
        val p = if (loopMode) (played % length).toInt() else minOf(played, length.toLong()).toInt()
        return startSample + p
    }

    actual fun release() = stop()
}

@Composable
actual fun StorageAccess(content: @Composable () -> Unit) = content()

actual fun builtinAsset(path: String): ByteArray? =
    Platform::class.java.classLoader?.getResourceAsStream(path)?.use { it.readBytes() }
