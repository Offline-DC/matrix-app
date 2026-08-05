package com.offline.dpadmessenger.backend.smarttxt

import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * A minimal, **read-only** reader for an ObjectBox store (`objectbox/data.mdb`) —
 * enough to lift OpenBubbles' message history out of it during the migration
 * ([ObHistoryImporter]) without linking ObjectBox itself.
 *
 * ### Why hand-rolled
 *
 * OpenBubbles keeps messages in ObjectBox; Smart Txt keeps them in SQLite
 * ([com.offline.dpadmessenger.backend.core.store.MessageStore]). The two documented
 * ways across are (a) ship `libobjectbox-jni.so` and open OB's store with the real
 * engine — ~5 MB of native code plus version-coupling to whatever ObjectBox release
 * OB was built against — or (b) patch OpenBubbles to export a bundle, which does
 * nothing for the phones already in the field. Neither is worth it for a one-shot,
 * read-only pass over a file we are about to delete.
 *
 * ### The format
 *
 * ObjectBox stores on LMDB, unmodified on-disk: `data.mdb` opens as a stock LMDB
 * environment (magic `0xBEEFC0DE`) and everything lives in the *main* database — no
 * named sub-DBs. Keys carry a 4-byte big-endian header that names the logical table:
 *
 *  - `0x18000000 or (4 * entityId)` + object id → the entity's rows (flatbuffers)
 *  - `0x08000000 or (4 * relationId)` + fromId + toId → a standalone relation
 *    (`+2` is the same relation indexed backwards; we only read the forward side)
 *  - `0x20…` / `0x08…` others → secondary indexes, which we ignore
 *
 * Entity id 0 is ObjectBox's own **meta model**: one flatbuffer per entity type
 * describing its name, property ids, types and — critically — each property's
 * flatbuffer vtable slot. So the schema travels *inside* the file and we never need
 * OB's `objectbox-model.json` or a build-time codegen step; [readModel] parses it and
 * everything downstream is by property NAME. An OB update that adds or reorders
 * fields therefore costs nothing here.
 *
 * ### Safety
 *
 * Every read is bounds-checked and the caller gets `null`/`0`/`false` rather than an
 * exception for anything malformed: this parses a file that was copied out from under
 * a possibly-running app, so a torn page is a realistic input and must degrade to
 * "fewer messages imported", never to a failed migration. Nothing here writes.
 *
 * Verified against a real 3.5 MB OpenBubbles store (2026-07-09 device dump): this
 * walker returns byte-identical key/value pairs to liblmdb for all 12,239 entries.
 */
internal class ObjectBoxStore private constructor(
    private val raf: RandomAccessFile,
    private val pageSize: Int,
    private val rootPage: Long,
    private val lastPage: Long,
) : Closeable {

    /** Entity types declared in the store's meta model, keyed by name ("Message"). */
    val entities: Map<String, ObEntity> by lazy { readModel() }

    /** Walk every row of [entity], newest-irrelevant (LMDB order = ascending id).
     *  [block] gets the object id and its flatbuffer; decode + FILTER inside it —
     *  a long-lived store can hold tens of thousands of rows and materialising them
     *  all would blow the heap on a 1 GB phone. */
    fun forEachRow(entity: ObEntity, block: (id: Long, row: ObRow) -> Unit) {
        val prefix = beInt(DATA_NS or (4 * entity.id))
        scan(prefix) { key, value ->
            val id = beLong(key, prefix.size)
            block(id, ObRow(entity, value))
        }
    }

    /** Forward pairs of a standalone relation (e.g. `Chat.handles` → chatId to handleId).
     *  Empty when [entity] has no relation called [name]. */
    fun relationPairs(entity: ObEntity, name: String, targetEntityId: Int = 0): List<Pair<Long, Long>> {
        // Prefer the declared name, but fall back to "whatever relation on this entity
        // points at that target" — a rename upstream should not silently cost us the
        // participant list, which is the one thing every room id is built from.
        // Target first when we know it: the name is the part that goes missing
        // across OpenBubbles versions, the target entity is not.
        val rel = entity.relations.firstOrNull { targetEntityId != 0 && it.targetEntityId == targetEntityId }
            ?: entity.relations.firstOrNull { it.name.isNotEmpty() && it.name == name }
            ?: run {
                Log.w(TAG, "relation '$name' not declared on ${entity.name}; " +
                    "it has ${entity.relations.size}: ${entity.relations.joinToString { it.name }}")
                return emptyList()
            }
        // Forward pairs live at `4 * relationId`; the same relation indexed backwards
        // lives 2 slots up. Which side ObjectBox fills is not something to assume, so
        // read forward and, only if it is empty, read the backlink and swap.
        val forward = pairsAt(RELATION_NS or (4 * rel.id), swap = false)
        if (forward.isNotEmpty()) {
            Log.i(TAG, "relation ${entity.name}.${rel.name.ifEmpty { "(unnamed)" }}#${rel.id}" +
                "→e${rel.targetEntityId}: ${forward.size} pair(s) (forward)")
            return forward
        }
        val reverse = pairsAt(RELATION_NS or (4 * rel.id + 2), swap = true)
        Log.i(TAG, "relation ${entity.name}.${rel.name}#${rel.id}: forward empty, " +
            "backlink has ${reverse.size} pair(s)")
        if (reverse.isEmpty()) probeRelationNamespaces(rel.id)
        return reverse
    }

    /**
     * Both sides of a relation came back empty. That has two very different causes and
     * the fix differs, so say which: either this OpenBubbles genuinely never wrote the
     * relation (nothing found anywhere → recover membership some other way), or it
     * wrote it under a slot our `4 * relationId` mapping didn't predict (something
     * found → the mapping is what needs fixing, and this line names the right slot).
     *
     * Cheap: an empty prefix scan is a couple of page reads, and this only runs on the
     * failure path.
     */
    private fun probeRelationNamespaces(expectedId: Int) {
        val found = (0 until 64)
            .mapNotNull { slot ->
                val n = pairsAt(RELATION_NS or slot, swap = false).size
                if (n > 0) "slot$slot(=rel${slot / 4}${if (slot % 4 == 2) " backlink" else ""})=$n" else null
            }
        if (found.isEmpty()) {
            Log.w(TAG, "relation probe: NO relation rows anywhere in this store — " +
                "OpenBubbles never wrote them; membership must come from elsewhere")
        } else {
            Log.w(TAG, "relation probe: expected relation #$expectedId at slot ${4 * expectedId}, " +
                "but rows live at: ${found.joinToString()} — the id→slot mapping is wrong")
        }
    }

    /** All (from, to) pairs stored under one relation key namespace. */
    private fun pairsAt(ns: Int, swap: Boolean): List<Pair<Long, Long>> {
        val prefix = beInt(ns)
        val out = ArrayList<Pair<Long, Long>>()
        scan(prefix) { key, _ ->
            // key = prefix + fromId + toId, split evenly (ObjectBox writes both ids
            // with the same width, so a 12-byte key is 4+4 and a 20-byte one 8+8).
            val idBytes = key.size - prefix.size
            if (idBytes >= 2 && idBytes % 2 == 0) {
                val half = idBytes / 2
                val a = beLong(key, prefix.size, half)
                val b = beLong(key, prefix.size + half, half)
                out += if (swap) b to a else a to b
            }
        }
        return out
    }

    override fun close() {
        runCatching { raf.close() }
    }

    // ── the meta model ──────────────────────────────────────────────────

    /**
     * Entity id 0 holds one flatbuffer per entity type. Field numbering (established
     * by decoding a real store, since this is ObjectBox's private meta schema):
     * `0` = uid, `1` = entity id, `3` = name, `4` = properties, `10` = relations.
     * A property record: `1` = id, `6` = name, `7` = type in its low 16 bits,
     * `8` = the flatbuffer vtable OFFSET in its low 16 bits (slot = (offset-4)/2).
     */
    private fun readModel(): Map<String, ObEntity> {
        val out = LinkedHashMap<String, ObEntity>()
        // The meta store is the one table NOT in the 0x18 data namespace — its keys
        // are a bare 4 zero bytes followed by the record id.
        val prefix = beInt(META_NS)
        scan(prefix) { _, value ->
            runCatching {
                val t = FlatTable.root(value) ?: return@runCatching
                val name = t.string(3) ?: return@runCatching       // record 0 is the model header
                val id = t.int(1) ?: return@runCatching
                val props = t.tables(4).mapNotNull { p ->
                    val pname = p.string(6) ?: return@mapNotNull null
                    val typeWord = p.int(7) ?: return@mapNotNull null
                    val offsetWord = p.int(8) ?: return@mapNotNull null
                    val slot = ((offsetWord and 0xFFFF) - 4) / 2
                    if (slot < 0) null
                    else ObProperty(pname, type = typeWord and 0xFFFF, slot = slot)
                }
                // Relation record: `0` = id, `1` = uid, `2` = source entity,
                // `3` = TARGET entity, `4` = name — and the NAME IS OPTIONAL.
                //
                // OpenBubbles 1.15.0 writes this record with four fields and no name
                // at all (1.9-era stores carry "handles"). Requiring the name is what
                // cost a real device its whole history on 2026-08-05: the Chat→Handle
                // relation was dropped from the model, every chat came out with no
                // participants, no room could be keyed, and all 20 messages fell out
                // as `noRoom`. The id and the target are always present and are the
                // only parts we need — so a nameless relation is a normal relation,
                // and callers match on the target entity instead.
                val rels = t.tables(10).mapNotNull { r ->
                    val rid = r.int(0) ?: return@mapNotNull null
                    ObRelation(rid, r.string(4).orEmpty(), r.int(3) ?: 0)
                }
                if (props.isNotEmpty()) out[name] = ObEntity(id, name, props, rels)
            }
        }
        Log.i(TAG, "model: " + out.values.joinToString {
            "${it.name}#${it.id}(${it.properties.size}p" +
                (if (it.relations.isEmpty()) "" else ", rel " + it.relations.joinToString("/") { r ->
                    "${r.name.ifEmpty { "(unnamed)" }}#${r.id}→e${r.targetEntityId}"
                }) + ")"
        })
        return out
    }

    // ── LMDB B-tree walk ────────────────────────────────────────────────

    /**
     * Visit every key/value whose key starts with [prefix], in key order.
     *
     * Descends only into subtrees whose key range can intersect the prefix, using the
     * branch separators — so pulling 600 messages out of a 40 MB store reads a handful
     * of pages, not the whole file.
     */
    private fun scan(prefix: ByteArray, block: (key: ByteArray, value: ByteArray) -> Unit) {
        if (rootPage > lastPage) return
        val upper = prefixUpperBound(prefix)
        runCatching { walk(rootPage, prefix, upper, 0, block) }
            .onFailure { Log.w(TAG, "scan aborted (truncated or corrupt store?)", it) }
    }

    private fun walk(
        pgno: Long,
        prefix: ByteArray,
        upper: ByteArray?,
        depth: Int,
        block: (ByteArray, ByteArray) -> Unit,
    ) {
        if (depth > MAX_DEPTH || pgno > lastPage) return
        val page = readPages(pgno, 1) ?: return
        val flags = u16(page, 10)
        val lower = u16(page, 12)
        if (lower < PAGE_HEADER || lower > page.size) return
        val n = (lower - PAGE_HEADER) / 2
        val isBranch = flags and P_BRANCH != 0
        val isLeaf = flags and P_LEAF != 0
        if (!isBranch && !isLeaf) return
        // P_LEAF2 pages hold fixed-size keys with no data — only ever produced for
        // DUPSORT sub-pages, which ObjectBox does not use. Skipping is correct and
        // keeps the node reader from misreading their (absent) headers.
        if (flags and P_LEAF2 != 0) return

        for (i in 0 until n) {
            val off = u16(page, PAGE_HEADER + 2 * i)
            if (off <= 0 || off + NODE_HEADER > page.size) continue
            val lo = u16(page, off)
            val hi = u16(page, off + 2)
            val nodeFlags = u16(page, off + 4)
            val ksize = u16(page, off + 6)
            val keyAt = off + NODE_HEADER
            if (keyAt + ksize > page.size) continue

            if (isBranch) {
                // A branch node's key is the smallest key in its subtree — except
                // node 0, whose key is a placeholder meaning "everything below".
                val loKey = if (i == 0 || ksize == 0) null else page.copyOfRange(keyAt, keyAt + ksize)
                val hiKey = branchKey(page, n, i + 1)
                // Wholly below the prefix, or wholly above it: don't read the subtree.
                if (hiKey != null && compare(hiKey, prefix) <= 0) continue
                if (upper != null && loKey != null && compare(loKey, upper) >= 0) return
                val child = lo.toLong() or (hi.toLong() shl 16) or (nodeFlags.toLong() shl 32)
                walk(child, prefix, upper, depth + 1, block)
            } else {
                val key = page.copyOfRange(keyAt, keyAt + ksize)
                if (compare(key, prefix) < 0) continue
                if (upper != null && compare(key, upper) >= 0) return
                if (!startsWith(key, prefix)) continue
                val size = lo or (hi shl 16)
                val dataAt = keyAt + ksize
                val value = if (nodeFlags and F_BIGDATA != 0) {
                    // Oversized value: the node holds the page number of an overflow run.
                    if (dataAt + 8 > page.size) continue
                    val opg = beLongLE(page, dataAt)
                    val pages = (size + PAGE_HEADER + pageSize - 1) / pageSize
                    val blob = readPages(opg, pages) ?: continue
                    if (PAGE_HEADER + size > blob.size) continue
                    blob.copyOfRange(PAGE_HEADER, PAGE_HEADER + size)
                } else {
                    if (dataAt + size > page.size) continue
                    page.copyOfRange(dataAt, dataAt + size)
                }
                block(key, value)
            }
        }
    }

    /** The key of branch node [i], or null when [i] is past the end / is the sentinel. */
    private fun branchKey(page: ByteArray, n: Int, i: Int): ByteArray? {
        if (i <= 0 || i >= n) return null
        val off = u16(page, PAGE_HEADER + 2 * i)
        if (off <= 0 || off + NODE_HEADER > page.size) return null
        val ksize = u16(page, off + 6)
        val at = off + NODE_HEADER
        if (ksize == 0 || at + ksize > page.size) return null
        return page.copyOfRange(at, at + ksize)
    }

    private fun readPages(pgno: Long, count: Int): ByteArray? = runCatching {
        if (pgno < 0 || count <= 0 || pgno + count > lastPage + 1) return null
        val buf = ByteArray(pageSize * count)
        raf.seek(pgno * pageSize)
        raf.readFully(buf)
        buf
    }.getOrNull()

    companion object {
        private const val TAG = "ObStore"

        // LMDB on-disk constants (lmdb.h / mdb.c).
        private const val PAGE_HEADER = 16      // offsetof(MDB_page, mp_ptrs)
        private const val NODE_HEADER = 8       // lo, hi, flags, ksize
        private const val P_BRANCH = 0x01
        private const val P_LEAF = 0x02
        private const val P_LEAF2 = 0x20
        private const val F_BIGDATA = 0x01
        private val MAGIC = 0xBEEFC0DE.toInt()
        private const val MAX_DEPTH = 32

        // ObjectBox key namespaces (the 4-byte big-endian key header).
        private const val META_NS = 0x00000000
        private const val DATA_NS = 0x18000000
        private const val RELATION_NS = 0x08000000

        /**
         * Open `data.mdb` read-only, or null if it isn't a readable LMDB file.
         *
         * Meta lives on pages 0 and 1; the valid one with the higher transaction id
         * is current (LMDB alternates them, so a copy taken mid-commit still has one
         * good, self-consistent snapshot). The page size is not in the header — it is
         * the free DB's `md_pad`, exactly as `mdb_env_open2` recovers it.
         */
        fun open(file: File): ObjectBoxStore? {
            if (!file.isFile || file.length() < 2048) {
                Log.w(TAG, "open: ${file.name} missing or too small (${file.length()}B)")
                return null
            }
            val raf = runCatching { RandomAccessFile(file, "r") }.getOrElse {
                Log.w(TAG, "open: cannot read ${file.name}: ${it.message}"); return null
            }
            return runCatching {
                // Bootstrap with a nominal 4 KB read to learn the real page size.
                val head = ByteArray(4096)
                raf.seek(0); raf.readFully(head)
                val bootPageSize = i32(head, 40).takeIf { it in 512..65536 } ?: 4096
                var best: ByteArray? = null
                var bestTxn = -1L
                for (p in 0..1) {
                    val meta = ByteArray(bootPageSize)
                    raf.seek(p.toLong() * bootPageSize)
                    if (!runCatching { raf.readFully(meta) }.isSuccess) continue
                    if (meta.size < 152 || i32(meta, 16) != MAGIC) continue
                    val txn = beLongLE(meta, 144)
                    if (txn >= bestTxn) { bestTxn = txn; best = meta }
                }
                val meta = best ?: run {
                    Log.w(TAG, "open: no valid LMDB meta page in ${file.name}")
                    raf.close(); return null
                }
                val pageSize = i32(meta, 40)
                val root = beLongLE(meta, 128)     // mm_dbs[MAIN].md_root
                val last = beLongLE(meta, 136)     // mm_last_pg
                if (pageSize !in 512..65536 || root < 0 || last < 0) {
                    Log.w(TAG, "open: implausible header (psize=$pageSize root=$root last=$last)")
                    raf.close(); return null
                }
                Log.i(TAG, "open ${file.name}: ${file.length()}B psize=$pageSize root=$root txn=$bestTxn")
                ObjectBoxStore(raf, pageSize, root, last)
            }.getOrElse {
                Log.w(TAG, "open ${file.name} failed", it)
                runCatching { raf.close() }
                null
            }
        }

        // ── little byte helpers (all bounds-checked by the callers above) ──

        private fun u16(b: ByteArray, o: Int): Int =
            if (o + 2 > b.size) 0 else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun i32(b: ByteArray, o: Int): Int =
            if (o + 4 > b.size) 0
            else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        /** 64-bit LITTLE-endian read (LMDB's own integers). */
        private fun beLongLE(b: ByteArray, o: Int): Long {
            if (o + 8 > b.size) return -1
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }

        /** 64-bit BIG-endian read of [len] bytes (ObjectBox's ids inside a key). */
        private fun beLong(b: ByteArray, o: Int, len: Int = b.size - o): Long {
            var v = 0L
            for (i in 0 until len) {
                if (o + i >= b.size) break
                v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            }
            return v
        }

        private fun beInt(v: Int): ByteArray = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
        )

        private fun startsWith(k: ByteArray, p: ByteArray): Boolean {
            if (k.size < p.size) return false
            for (i in p.indices) if (k[i] != p[i]) return false
            return true
        }

        /** Unsigned lexicographic compare — LMDB's default key order. */
        private fun compare(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        /** Smallest key that sorts after every key starting with [prefix], or null
         *  when the prefix is all-0xFF (nothing sorts above it). */
        private fun prefixUpperBound(prefix: ByteArray): ByteArray? {
            val u = prefix.copyOf()
            for (i in u.indices.reversed()) {
                if (u[i] != 0xFF.toByte()) { u[i] = (u[i] + 1).toByte(); return u.copyOf(i + 1) }
            }
            return null
        }
    }
}

/** One property of an ObjectBox entity: its name, type code, and flatbuffer slot. */
internal class ObProperty(val name: String, val type: Int, val slot: Int) {
    companion object {
        // ObjectBox PropertyType codes we care about.
        const val BOOL = 1
        const val INT = 5
        const val LONG = 6
        const val STRING = 9
        const val DATE = 10
        const val RELATION = 11
    }
}

/** One entity type from the store's meta model. [relations] are standalone
 *  (many-to-many) relations. */
/** A standalone (many-to-many) relation declared on an entity. */
internal class ObRelation(val id: Int, val name: String, val targetEntityId: Int)

internal class ObEntity(
    val id: Int,
    val name: String,
    val properties: List<ObProperty>,
    val relations: List<ObRelation>,
) {
    private val byName: Map<String, ObProperty> = properties.associateBy { it.name }
    fun property(name: String): ObProperty? = byName[name]
}

/** A decoded row. Accessors are by property NAME and return a benign default when the
 *  property is absent from this OpenBubbles build, or from this particular row (a
 *  flatbuffer omits any field left at its default). */
internal class ObRow(private val entity: ObEntity, private val buf: ByteArray) {
    private val table: FlatTable? = FlatTable.root(buf)

    fun string(name: String): String? {
        val p = entity.property(name) ?: return null
        if (p.type != ObProperty.STRING) return null
        return table?.string(p.slot)
    }

    /** Longs, dates and to-one relation ids all arrive as 64-bit. 0 = absent. */
    fun long(name: String): Long {
        val p = entity.property(name) ?: return 0L
        val t = table ?: return 0L
        return when (p.type) {
            ObProperty.LONG, ObProperty.DATE, ObProperty.RELATION -> t.long(p.slot) ?: 0L
            ObProperty.INT -> (t.int(p.slot) ?: 0).toLong()
            else -> 0L
        }
    }

    fun bool(name: String): Boolean {
        val p = entity.property(name) ?: return false
        if (p.type != ObProperty.BOOL) return false
        return table?.byte(p.slot) == 1
    }
}

/**
 * The sliver of FlatBuffers needed to read an ObjectBox row: a table is a vtable
 * offset followed by fields, and every accessor is bounds-checked so a torn buffer
 * yields null instead of an exception.
 */
internal class FlatTable private constructor(private val b: ByteArray, private val pos: Int) {

    private val vtable: Int = pos - i32(pos)
    private val vtableSize: Int = if (vtable in 0..(b.size - 2)) u16(vtable) else 0

    /** Absolute position of field [slot], or -1 when the row doesn't carry it. */
    private fun field(slot: Int): Int {
        val o = 4 + 2 * slot
        if (o + 2 > vtableSize) return -1
        val rel = u16(vtable + o)
        if (rel == 0) return -1
        val at = pos + rel
        return if (at in 0 until b.size) at else -1
    }

    fun string(slot: Int): String? {
        val p = field(slot).takeIf { it >= 0 } ?: return null
        val s = p + i32(p)
        if (s < 0 || s + 4 > b.size) return null
        val n = i32(s)
        if (n < 0 || s + 4 + n > b.size) return null
        return runCatching { String(b, s + 4, n, Charsets.UTF_8) }.getOrNull()
    }

    fun int(slot: Int): Int? = field(slot).takeIf { it >= 0 && it + 4 <= b.size }?.let { i32(it) }

    fun long(slot: Int): Long? = field(slot).takeIf { it >= 0 && it + 8 <= b.size }?.let {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[it + i].toLong() and 0xFF)
        v
    }

    fun byte(slot: Int): Int? = field(slot).takeIf { it >= 0 }?.let { b[it].toInt() and 0xFF }

    /** A vector of sub-tables (the meta model's property and relation lists). */
    fun tables(slot: Int): List<FlatTable> {
        val p = field(slot).takeIf { it >= 0 } ?: return emptyList()
        val s = p + i32(p)
        if (s < 0 || s + 4 > b.size) return emptyList()
        val n = i32(s)
        if (n < 0 || n > 4096 || s + 4 + 4 * n > b.size) return emptyList()
        val out = ArrayList<FlatTable>(n)
        for (i in 0 until n) {
            val at = s + 4 + 4 * i
            val t = at + i32(at)
            if (t in 0 until b.size) runCatching { FlatTable(b, t) }.getOrNull()?.let { out += it }
        }
        return out
    }

    private fun u16(o: Int): Int =
        if (o < 0 || o + 2 > b.size) 0 else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun i32(o: Int): Int =
        if (o < 0 || o + 4 > b.size) 0
        else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    companion object {
        /** The root table of a buffer: a 32-bit offset to it sits at byte 0. */
        fun root(b: ByteArray): FlatTable? {
            if (b.size < 8) return null
            val p = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
            if (p < 4 || p >= b.size) return null
            return runCatching { FlatTable(b, p) }.getOrNull()
        }
    }
}
