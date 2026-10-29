package com.qiuminal.juicedict.engine

import com.qiuminal.juicedict.engine.mdict.MdxDictionary
import com.qiuminal.juicedict.engine.mdict.MdxLog
import com.qiuminal.juicedict.engine.mdict.MdxProbe
import com.qiuminal.juicedict.engine.mdict.MdxResourceFiles
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Metadata of an MDX file, read without opening the dictionary.
 *
 * Mirrors the upstream `MdxProbe.Info`, which is `internal`; this type is what the app
 * sees when it lists installed dictionaries.
 */
data class MdxProbeInfo(
    val title: String,
    val description: String,
    /** Entry count from the key-section preamble; 0 when unreadable. */
    val entryCount: Long,
)

/**
 * The app-facing way into the vendored engine.
 *
 * Upstream (`qiuminal/juicedict`) keeps `MdxDictionary` and `MdxProbe` `internal` and
 * assembles the StarDict facade inside its own repository class, which belongs to the
 * Android layer. That leaves no public way to build an engine from plain `File`s, so
 * this file is the only local addition to the module: the entry points below wrap the
 * upstream internals and nothing more. No parser file is touched; see `PROVENANCE.md`.
 *
 * ## Threading
 *
 * A returned [DictionaryEngine] is **not** thread-safe, despite what the upstream
 * `DictionaryEngine` KDoc says: `StarDictIndex` is a plain `HashMap`, and
 * `DictZipReader` / `PlainDictReader` do a non-atomic `seek` + `readFully` behind an
 * LRU. Upstream hides that behind `synchronized(mutex)` in its own
 * `DictionaryRepository`. Callers here must do the same: one lock per engine, held
 * around `lookupSmart` and `article`.
 */
object DictionaryEngines {

    /**
     * Receives `(tag, message)` diagnostics from the parser — the same sink the
     * vendored engine writes to, installed here because the engine's own `MdxLog` is
     * `internal`. Null (the default) drops the messages. Recoverable problems only:
     * a dictionary that fails to open is still reported as `null` / an exception, and
     * the message is an extra, never the contract.
     */
    var logSink: ((tag: String, message: String) -> Unit)?
        get() = MdxLog.sink
        set(value) {
            MdxLog.sink = value
        }

    /**
     * Opens the StarDict dictionary whose files are `<baseName>.ifo` / `.idx`|`.idx.gz` /
     * `.syn` (optional) / `.dict`|`.dict.dz` inside [dir].
     *
     * Returns null instead of throwing for a missing, malformed or oversized dictionary:
     * one broken dictionary must not take the caller's whole lookup path down. A `.jidx`
     * index cache next to the dictionary is used when valid and rewritten otherwise.
     */
    fun openStarDict(id: String, dir: File, baseName: String): DictionaryEngine? {
        return try {
            val ifo = Ifo.parse(File(dir, "$baseName.ifo").readText())
            val index = StarDictIndex.loadCache(File(dir, "$baseName.jidx"), ifo)
                ?: loadIndex(dir, baseName, ifo)
            val data = when {
                File(dir, "$baseName.dict.dz").isFile -> DictZipReader(File(dir, "$baseName.dict.dz"))
                File(dir, "$baseName.dict").isFile -> PlainDictReader(File(dir, "$baseName.dict"))
                else -> return null
            }
            StarDict(id, ifo, index, data)
        } catch (t: Throwable) {
            MdxLog.warn(TAG, "could not open StarDict $baseName in ${dir.path}", t)
            null
        }
    }

    /**
     * Parses `.idx` (or gzipped `.idx.gz`) plus the optional `.syn`, then writes the
     * `.jidx` cache. Losing the cache is only a slower next start, so a write failure is
     * not reported.
     */
    private fun loadIndex(dir: File, baseName: String, ifo: Ifo): StarDictIndex {
        val idxFile = File(dir, "$baseName.idx")
        val idxGzFile = File(dir, "$baseName.idx.gz")
        val idxStream: InputStream = when {
            idxFile.isFile -> idxFile.inputStream()
            idxGzFile.isFile -> GZIPInputStream(idxGzFile.inputStream())
            else -> error("missing $baseName.idx / $baseName.idx.gz")
        }
        val synStream = File(dir, "$baseName.syn").takeIf { it.isFile }?.inputStream()
        val index = try {
            idxStream.use { StarDictIndex.load(ifo, it, synStream) }
        } finally {
            synStream?.close()
        }
        runCatching { StarDictIndex.writeCache(index, File(dir, "$baseName.jidx"), ifo) }
        return index
    }

    /**
     * Opens `<base>.mdx` with its resource packs. [mddFiles] are searched in the given
     * order, so numbered packs (`<base>.1.mdd`, …) must be passed after `<base>.mdd`.
     *
     * Throws when [mdxFile] is not a usable article dictionary (not MDict, a resource
     * pack, password-protected, or truncated): an import has already been vetted by
     * [probeMdx] by then, and a clear exception beats a half-working dictionary.
     */
    fun openMdx(
        id: String,
        mdxFile: File,
        mddFiles: List<File>,
        title: String,
        description: String,
    ): DictionaryEngine = MdxDictionary(
        id = id,
        mdxFile = mdxFile,
        mddFiles = mddFiles,
        title = title,
        description = description,
    )

    /**
     * The `.mdd` resource packs that belong to `<baseName>.mdx` inside [dir]:
     * `<baseName>.mdd` first, then `<baseName>.1.mdd`, `.2.mdd`, … in numeric order —
     * the order [openMdx] expects. Empty when the dictionary ships no resources.
     */
    fun mddFilesFor(dir: File, baseName: String): List<File> = MdxResourceFiles.pairsFor(dir, baseName)

    /** Header-only read of an MDX file on disk; null when it is not an MDict file. */
    fun probeMdx(file: File): MdxProbeInfo? = MdxProbe.read(file)?.toInfo()

    /**
     * Same as [probeMdx], but from the first bytes of a file already in memory — the
     * import path vets a picked file before copying it. Pass at least 1 MiB to keep the
     * key-section preamble in range; otherwise the header's own (possibly stale) entry
     * count is reported.
     */
    fun probeMdxHead(headBytes: ByteArray): MdxProbeInfo? = MdxProbe.readFromHead(headBytes)?.toInfo()

    private fun MdxProbe.Info.toInfo() = MdxProbeInfo(title, description, entryCount)

    private const val TAG = "DictionaryEngines"
}
