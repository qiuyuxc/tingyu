package com.qiuyuxc.tingyu

/** Java promotes signed bytes to int before bit operations; keep that exact behavior. */
internal infix fun Byte.and(mask: Int): Int = toInt() and mask

internal infix fun Byte.or(mask: Int): Int = toInt() or mask

internal infix fun Byte.xor(mask: Int): Byte = (toInt() xor mask).toByte()

internal infix fun Byte.xor(other: Byte): Byte = (toInt() xor other.toInt()).toByte()

internal infix fun Long.or(bits: Int): Long = this or bits.toLong()

internal operator fun Number.plus(text: String?): String = toString() + text
