package com.offline.dpadmessenger.backend.gmessages

import java.io.ByteArrayOutputStream

/**
 * Minimal protocol-buffers wire-format reader/writer.
 *
 * Google Messages' pairing endpoints speak binary protobuf, but the only
 * messages we need for the QR relay handshake are small and flat (a handful
 * of strings, bytes fields, ints, and one level of nesting). Rather than
 * pull in protobuf-javalite + the protobuf-gradle-plugin + codegen — which
 * is heavyweight and was a recurring source of build friction in the
 * sibling :signal module — we hand-encode the exact fields we use.
 *
 * Wire format (developers.google.com/protocol-buffers/docs/encoding):
 *   tag      = (fieldNumber << 3) or wireType
 *   wireType = 0 varint, 1 i64, 2 length-delimited, 5 i32
 *
 * Only varint (0) and length-delimited (2) appear in the pairing messages.
 * Verified against the canonical encoder's output in ProtobufTest.
 */
internal object Protobuf {
    const val WIRE_VARINT = 0
    const val WIRE_LEN = 2
    const val WIRE_I64 = 1
    const val WIRE_I32 = 5
}

/** Builds a protobuf message field by field. Fields may be written in any
 *  order; absent/default fields are simply not written (proto3 semantics). */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        if (value == 0L) return this // proto3: omit default
        tag(field, Protobuf.WIRE_VARINT)
        writeVarint(value)
        return this
    }

    fun int32(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bytes(field: Int, value: ByteArray?): ProtoWriter {
        if (value == null || value.isEmpty()) return this
        tag(field, Protobuf.WIRE_LEN)
        writeVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String?): ProtoWriter {
        if (value.isNullOrEmpty()) return this
        return bytes(field, value.toByteArray(Charsets.UTF_8))
    }

    /** Embed a nested message (length-delimited). */
    fun message(field: Int, nested: ProtoWriter): ProtoWriter = bytes(field, nested.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = writeVarint(((field.toLong()) shl 3) or wire.toLong())

    private fun writeVarint(v: Long) {
        var x = v
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) out.write(b or 0x80) else { out.write(b); break }
        }
    }
}

/** Walks a protobuf message field by field. Unknown fields are skipped, so
 *  this tolerates the many fields Google sends that we don't model. */
internal class ProtoReader(private val buf: ByteArray) {
    private var pos = 0

    /** A single decoded field. For [Protobuf.WIRE_LEN] fields, [bytes] holds
     *  the payload; for varints, [value] holds the number. */
    data class Field(val number: Int, val wire: Int, val value: Long, val bytes: ByteArray?)

    fun hasNext(): Boolean = pos < buf.size

    fun readField(): Field {
        val tag = readVarint()
        val number = (tag ushr 3).toInt()
        val wire = (tag and 0x7).toInt()
        return when (wire) {
            Protobuf.WIRE_VARINT -> Field(number, wire, readVarint(), null)
            Protobuf.WIRE_LEN -> {
                val len = readVarint().toInt()
                val b = buf.copyOfRange(pos, pos + len)
                pos += len
                Field(number, wire, 0, b)
            }
            Protobuf.WIRE_I64 -> { val b = buf.copyOfRange(pos, pos + 8); pos += 8; Field(number, wire, 0, b) }
            Protobuf.WIRE_I32 -> { val b = buf.copyOfRange(pos, pos + 4); pos += 4; Field(number, wire, 0, b) }
            else -> error("unsupported wire type $wire at pos $pos")
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    companion object {
        /** Convenience: collect all top-level fields into a map keyed by
         *  field number. Last-wins for repeated fields (none here are). */
        fun fields(buf: ByteArray): Map<Int, Field> {
            val r = ProtoReader(buf)
            val m = HashMap<Int, Field>()
            while (r.hasNext()) {
                val f = r.readField()
                m[f.number] = f
            }
            return m
        }
    }
}
