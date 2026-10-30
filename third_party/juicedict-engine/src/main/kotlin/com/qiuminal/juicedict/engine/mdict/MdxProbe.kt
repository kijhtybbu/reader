package com.qiuminal.juicedict.engine.mdict

import java.io.File

/**
 * Cheap metadata read for an MDict file that is *not* worth opening yet.
 *
 * [com.qiuminal.juicedict.data.DictionaryRepository.listDictionaries] runs on every
 * lookup (through `listEnabled()`), so it must never build a word index. A full
 * [MdxWordIndex.load] walks and inflates every key block — ~76 of them and a few
 * hundred milliseconds for 《辞海第七版》. This probe instead reads the header and the
 * first 16 bytes of the key section, which is where the entry count lives.
 */
internal object MdxProbe {

    data class Info(
        val title: String,
        val description: String,
        /** Entry count from the key-section preamble; 0 when unreadable. */
        val entryCount: Long,
    )

    private const val MAX_HEADER_BYTES = 1L shl 20

    /** 2.0: u64be blocks | u64be entries; 1.2: u32be blocks | u32be entries. */
    private fun entryCountOffset(head: MdxHeader): Int = if (head.version >= 2f) 8 else 4

    private fun numberWidth(head: MdxHeader): Int = if (head.version >= 2f) 8 else 4

    private fun preambleProbeBytes(head: MdxHeader): Int = 2 * numberWidth(head)

    fun read(file: File): Info? {
        val reader = runCatching { MdxFileReader(file) }.getOrNull() ?: return null
        try {
            val headBytes = reader.read(0L, minOf(reader.length, MAX_HEADER_BYTES).toInt())
            val head = MdxHeader.parse(headBytes) ?: return null
            return Info(
                title = head.title,
                description = head.description,
                entryCount = entryCount(reader, head),
            )
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { reader.close() }
        }
    }

    /**
     * Same as [read], but from a prefix of the file already in memory.
     *
     * The import path needs to vet a document-tree URI before copying gigabytes into
     * the dictionary directory; when the key-section preamble falls outside
     * [headBytes] the header's own (possibly stale) count is used instead.
     */
    fun readFromHead(headBytes: ByteArray): Info? {
        val head = MdxHeader.parse(headBytes) ?: return null
        val preambleEnd = head.keySectionOffset + preambleProbeBytes(head)
        val count = if (head.kind == MdxKind.ARTICLE && preambleEnd <= headBytes.size) {
            readNumber(
                headBytes,
                head.keySectionOffset.toInt() + entryCountOffset(head),
                numberWidth(head)
            ).takeIf { it > 0 } ?: head.entryCount
        } else {
            head.entryCount
        }
        return Info(head.title, head.description, count)
    }

    /**
     * The key section's own `num_entries`, which is authoritative.
     *
     * The header's `num_entries` attribute is a stale hint in several shipped files
     * (辞海第七版 declares none at all while its index holds 137390 entries), so it is
     * only the fallback.
     */
    private fun readNumber(bytes: ByteArray, at: Int, width: Int): Long =
        if (width == 8) MdxBytes.u64be(bytes, at) else MdxBytes.u32be(bytes, at).toLong()

    private fun entryCount(reader: MdxFileReader, head: MdxHeader): Long {
        if (head.kind != MdxKind.ARTICLE) return 0L
        val probeBytes = preambleProbeBytes(head)
        if (head.keySectionOffset + probeBytes > reader.length) return head.entryCount
        return try {
            val preamble = reader.read(head.keySectionOffset, probeBytes)
            readNumber(preamble, entryCountOffset(head), numberWidth(head))
                .takeIf { it > 0 } ?: head.entryCount
        } catch (t: Throwable) {
            head.entryCount
        }
    }
}
