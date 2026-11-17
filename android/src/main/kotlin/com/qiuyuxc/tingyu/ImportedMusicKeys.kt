package com.qiuyuxc.tingyu

import org.json.JSONArray
import org.json.JSONObject

/** Read-only compatibility with credentials imported by public version 0.12.10. */
internal class ImportedMusicKeys(val entries: List<Entry> = emptyList()) : Ncmx.Keys {
    data class Entry(val masterKey: Boolean, val value: String) {
        init {
            if (masterKey) ChunkSealKeys.decode(value)
        }
    }

    override fun forKeyId(keyId: Int): List<String> =
        entries.filterNot { it.masterKey }.map { it.value }

    override fun forMasterKeyId(keyId: Int): List<ByteArray> =
        entries.filter { it.masterKey }.map { ChunkSealKeys.decode(it.value) }

    fun add(entry: Entry): ImportedMusicKeys =
        if (entry in entries) this else ImportedMusicKeys(entries + entry)

    fun json(): String =
        JSONArray()
            .apply {
                entries.forEach { entry ->
                    put(JSONObject().put("master", entry.masterKey).put("value", entry.value))
                }
            }
            .toString()

    companion object {
        fun parse(value: String): ImportedMusicKeys {
            val array = JSONArray(value)
            return ImportedMusicKeys(
                (0 until array.length())
                    .map { index ->
                        val entry = array.getJSONObject(index)
                        Entry(entry.getBoolean("master"), entry.getString("value"))
                    }
                    .distinct()
            )
        }
    }
}
