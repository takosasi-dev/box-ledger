// JSON の読み書き。ライブラリを足さずに、バックアップに要る分だけを持つ。
// 読んだ値の形: Map<String, Any?> / List<Any?> / String / BigDecimal / Boolean / null。
// 数値を BigDecimal で持つのは、外貨の金額(59.99)を Double に通して崩さないため。
package io.github.takosasidev.boxledger.core.backup

import java.math.BigDecimal

class JsonException(message: String) : Exception(message)

object Json {

    /** 入れ子の深さの上限。外から来るファイルなので、深い入れ子でスタックを使い切らせない。 */
    private const val MAX_DEPTH = 32

    /** 壊れた JSON は [JsonException]。 */
    fun parse(text: String): Any? {
        // 先頭の BOM は読み飛ばす(Windows の道具が付けることがある)
        val p = Parser(text.removePrefix("﻿"))
        p.skipSpace()
        val value = p.value(0)
        p.skipSpace()
        if (!p.atEnd()) p.fail("余分な文字があります")
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { write(it, value, 0) }.append('\n').toString()

    private fun write(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is String -> string(sb, value)
            is BigDecimal -> sb.append(value.toPlainString())
            is Int, is Long -> sb.append(value)
            // NaN と無限大は JSON に無いので null にする
            is Double -> if (value.isFinite()) sb.append(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()) else sb.append("null")
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    pad(sb, indent + 1)
                    string(sb, k.toString())
                    sb.append(": ")
                    write(sb, v, indent + 1)
                }
                sb.append('\n')
                pad(sb, indent)
                sb.append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(",\n")
                    pad(sb, indent + 1)
                    write(sb, v, indent + 1)
                }
                sb.append('\n')
                pad(sb, indent)
                sb.append(']')
            }
            else -> throw IllegalArgumentException("JSON にできない値: ${value::class}")
        }
    }

    private fun pad(sb: StringBuilder, indent: Int) {
        repeat(indent) { sb.append("  ") }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(message: String): Nothing = throw JsonException("JSON を読めません(${i} 文字目): $message")

        fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("入れ子が深すぎます")
            if (atEnd()) fail("途中で終わっています")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("思いがけない文字 '$c'")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("思いがけない文字")
            i += w.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skipSpace()
            if (!atEnd() && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                if (atEnd() || s[i] != '"') fail("キーがありません")
                val key = str()
                skipSpace()
                if (atEnd() || s[i] != ':') fail("':' がありません")
                i++
                skipSpace()
                out[key] = value(depth + 1)
                skipSpace()
                if (atEnd()) fail("途中で終わっています")
                when (s[i++]) {
                    ',' -> Unit
                    '}' -> return out
                    else -> fail("',' か '}' がありません")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skipSpace()
            if (!atEnd() && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                skipSpace()
                out.add(value(depth + 1))
                skipSpace()
                if (atEnd()) fail("途中で終わっています")
                when (s[i++]) {
                    ',' -> Unit
                    ']' -> return out
                    else -> fail("',' か ']' がありません")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (atEnd()) fail("文字列が閉じていません")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("文字列が閉じていません")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u の後ろが足りません")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("\\u の後ろが 16 進数ではありません")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("知らないエスケープ '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): BigDecimal {
            val start = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            val raw = s.substring(start, i)
            // 指数が巨大な数(1e999999999)は、後の計算でメモリを食い尽くすので受けない
            if (raw.length > 40) fail("数値が長すぎます")
            val n = raw.toBigDecimalOrNull() ?: fail("数値を読めません")
            if (n.scale() < -400 || n.scale() > 400) fail("数値の桁が大きすぎます")
            return n
        }
    }
}
