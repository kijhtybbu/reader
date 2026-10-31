package com.qiuminal.juicedict.engine

import com.qiuminal.juicedict.engine.mdict.MdxDictionary
import com.qiuminal.juicedict.engine.mdict.MdxLog
import com.qiuminal.juicedict.engine.mdict.MdxProbe
import com.qiuminal.juicedict.engine.mdict.MdxResourceFiles
import com.qiuminal.juicedict.engine.mdict.MdxResourcePack
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
 * Read-only access to the `.mdd` resource packs that belong to one MDX dictionary:
 * images, stylesheets, fonts and pronunciation audio, addressed by MDD path.
 *
 * Kept separate from [DictionaryEngine] on purpose: a renderer fetches resources while
 * resolving a stylesheet or an image, and that must not contend for the lock the lookup
 * path holds.
 *
 * ## Threading
 *
 * Instances are **not** thread-safe. Behind `read` sits a shared `MdxFileReader` whose
 * `seek` + `readFully` is non-atomic, plus LRU caches over key and record blocks, so two
 * threads reading at once can interleave the seek of one with the read of the other.
 * Callers must serialize per instance — one lock per `MdxResources` — rather than
 * serializing all resource reads globally; separate dictionaries are independent.
 *
 * `close` releases the file handles; using the instance afterwards is undefined.
 */
interface MdxResources : AutoCloseable {
    /** File names of the packs that opened, in the order [read] searches them. */
    val names: List<String>

    /**
     * The bytes stored at MDD [path], or null when no pack carries it.
     *
     * `path` may be written either way the referring HTML writes it: with or without
     * leading `\`, with `/` or `\` separators, and in any case. Missing resources are
     * routine (the HTML names files the pack does not ship), so null is not an error.
     */
    fun read(path: String): ByteArray?
}

/**
 * What [DictionaryEngines.checkMdxResourcePack] found in a `.mdd` file.
 *
 * [DictionaryEngines.openMdxResources] deliberately swallows per-pack failures (one broken
 * pack must not make the others unreadable), which is the wrong answer for an import: the
 * user needs to know whether the pack is encrypted or simply corrupt. This type carries
 * that distinction without exposing the parser's exception types.
 */
sealed interface MdxPackCheck {
    /** The file opened as a resource pack; [resourceCount] is its key count. */
    data class Ok(val resourceCount: Int) : MdxPackCheck

    /** A password is required (the header's `Encrypted` bit). */
    data object Encrypted : MdxPackCheck

    /** An MDict container of the other kind — a `.mdx` article dictionary, most likely. */
    data object NotResourcePack : MdxPackCheck

    /**
     * The pack is an MDict resource container but its key/record sections cannot be read
     * (truncated, or a failure whose message the constructor did not label). A file that is
     * not an MDict container at all is [NotResourcePack] instead.
     */
    data class Corrupt(val message: String?) : MdxPackCheck
}

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

    /**
     * Opens [mddFiles] as one resource set, searched in the given order — the order
     * [mddFilesFor] returns, so `<base>.mdd` wins over `<base>.1.mdd`.
     *
     * A pack that cannot be opened (not an MDict file, an `.mdx` by mistake, password
     * protected, truncated) is reported through [logSink] and skipped: one broken pack
     * must not make the others unreadable, because MDict splits a dictionary's resources
     * across numbered packs (《字源 (2012)》 keeps 43MB of scans in `.1.mdd`). Returns
     * null only when [mddFiles] is empty or nothing opened.
     */
    fun openMdxResources(mddFiles: List<File>): MdxResources? {
        val packs = ArrayList<MdxResourcePack>(mddFiles.size)
        for (file in mddFiles) {
            try {
                packs.add(MdxResourcePack(file))
            } catch (e: Exception) {
                // Deliberately not `Throwable`: a pack whose declared size is nonsense can
                // throw OOM while decompressing a key block, and swallowing that would keep
                // the process running in a state where every later allocation is a coin flip.
                // The parsers themselves only throw IllegalArgumentException/IllegalStateException.
                MdxLog.warn(TAG, "could not open MDict resource pack ${file.name}", e)
            }
        }
        return if (packs.isEmpty()) null else MdxResourcePacks(packs)
    }

    /**
     * Opens and immediately closes [file] as a resource pack, reporting what it is.
     *
     * The import path copies `.mdd` files one at a time and has to say *why* a pack is
     * unusable: [MdxPackCheck.Encrypted] is a different user action (find an unencrypted
     * dictionary) than [MdxPackCheck.Corrupt] (the copy or the source file is broken).
     * Classification follows [MdxResourcePack]'s constructor failures, whose messages are
     * the only thing the vendored parser exposes; an unrecognised failure is reported as
     * [MdxPackCheck.Corrupt] with the original message.
     */
    fun checkMdxResourcePack(file: File): MdxPackCheck {
        return try {
            MdxResourcePack(file).use { MdxPackCheck.Ok(it.resourceCount) }
        } catch (e: Exception) {
            // Same reasoning as `openMdxResources`: not `Throwable`. A pack whose declared
            // size is nonsense can throw OOM while inflating a key block, and reporting
            // that as "corrupt file" would leave the process in a fragile state.
            when {
                e.message?.contains("password", ignoreCase = true) == true -> MdxPackCheck.Encrypted
                e.message?.contains("resource pack", ignoreCase = true) == true ->
                    MdxPackCheck.NotResourcePack

                else -> MdxPackCheck.Corrupt(e.message)
            }
        }
    }

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

/** First pack that carries a path answers; the rest are never touched for that read. */
private class MdxResourcePacks(
    private val packs: List<MdxResourcePack>,
) : MdxResources {

    override val names: List<String> = packs.map { it.name }

    override fun read(path: String): ByteArray? {
        for (pack in packs) {
            // An empty record (size 0 in the pack) must not mask a real one further down:
            // "this pack does not have the bytes" is the same answer as "no such path".
            val bytes = pack.read(path)?.takeIf { it.isNotEmpty() } ?: continue
            return bytes
        }
        return null
    }

    override fun close() {
        // A pack failing to close is not worth failing the caller's release path over.
        for (pack in packs) runCatching { pack.close() }
    }
}
