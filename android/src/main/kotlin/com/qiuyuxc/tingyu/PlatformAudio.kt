package com.qiuyuxc.tingyu

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal enum class AudioQuality(
    val id: String,
    val label: String,
    val netease: String,
    val kugou: String,
) {
    STANDARD("standard", "标准", "standard", "128"),
    HIGHER("higher", "较高", "higher", ""),
    HIGH("high", "极高", "exhigh", "320"),
    LOSSLESS("lossless", "无损", "lossless", "flac"),
    HIRES("hires", "Hi-Res", "hires", "high"),
    SURROUND("surround", "高清臻音", "jyeffect", ""),
    SPATIAL("spatial", "沉浸环绕声", "sky", ""),
    DOLBY("dolby", "杜比全景声", "dolby", ""),
    VIVID("vivid", "臻音全景声", "vivid", ""),
    MASTER("master", "超清母带", "jymaster", ""),
    KUGOU_SUPER("kugou-super", "超品", "", "super"),
    KUGOU_CLEAR("kugou-clear", "蝰蛇超清", "", "viper_clear"),
    KUGOU_ATMOS("kugou-atmos", "蝰蛇全景声", "", "viper_atmos"),
    KUGOU_TAPE("kugou-tape", "蝰蛇母带", "", "viper_tape");

    fun platformId(platform: MusicPlatform) =
        if (platform == MusicPlatform.NETEASE) netease else kugou

    fun label(platform: MusicPlatform) =
        if (platform == MusicPlatform.KUGOU && this == HIGH) "高品" else label

    fun membershipTier(platform: MusicPlatform): MembershipTier? =
        when {
            platform == MusicPlatform.NETEASE ->
                when (this) {
                    LOSSLESS,
                    HIRES -> MembershipTier.VIP
                    SURROUND,
                    SPATIAL,
                    DOLBY,
                    MASTER -> MembershipTier.SVIP
                    else -> null
                }
            this == LOSSLESS || this == HIRES -> MembershipTier.VIP
            this in listOf(KUGOU_SUPER, KUGOU_CLEAR, KUGOU_ATMOS, KUGOU_TAPE) -> MembershipTier.SVIP
            else -> null
        }

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: STANDARD

        fun forPlatform(platform: MusicPlatform) = entries.filter {
            it != VIVID && it.platformId(platform).isNotBlank()
        }

        fun preference(platform: MusicPlatform, quality: AudioQuality) =
            quality.takeIf { it in forPlatform(platform) } ?: LOSSLESS

        fun forTrack(track: PlatformTrack) =
            track.qualities.filter { it.quality in forPlatform(track.platform) }

        /** Song detail fields are partial metadata, not a complete Netease quality catalog. */
        fun choices(platform: MusicPlatform, track: PlatformTrack? = null): List<QualityAccess> {
            require(track == null || track.platform == platform)
            if (track != null && platform == MusicPlatform.KUGOU) return forTrack(track)
            return forPlatform(platform).map { quality ->
                track?.qualities?.firstOrNull { it.quality == quality }
                    ?: QualityAccess(quality, TrackAccess.UNKNOWN)
            }
        }

        fun resolve(track: PlatformTrack, preferred: AudioQuality): AudioQuality {
            // Resolve rights with the current account before lowering its saved preference.
            return preference(track.platform, preferred)
        }

        fun fromPlatform(platform: MusicPlatform, value: String): AudioQuality? =
            forPlatform(platform).firstOrNull { it.platformId(platform) == value }
    }
}

internal enum class TrackAccess(val label: String) {
    UNKNOWN(""),
    FREE(""),
    VIP("VIP"),
    VIP_QUALITY("VIP音质"),
    PAID("付费"),
    UNAVAILABLE("暂不可用"),
}

internal data class QualityAccess(val quality: AudioQuality, val access: TrackAccess)

internal enum class MembershipTier(val label: String, val rank: Int) {
    MUSIC_PACKAGE("音乐包", 0),
    VIP("VIP", 1),
    SVIP("SVIP", 2),
}

internal data class MembershipSubscription(
    val kind: MembershipTier,
    val active: Boolean?,
    val expiresAt: Long? = null,
    val level: Int? = null,
    val tier: String = if (kind == MembershipTier.MUSIC_PACKAGE) kind.label else "黑胶 ${kind.label}",
) {
    fun activeAt(now: Long = System.currentTimeMillis()): Boolean? =
        if (expiresAt != null && expiresAt <= now) false else active

    fun expiryText(now: Long = System.currentTimeMillis()): String =
        membershipExpiryText(activeAt(now), expiresAt, now)
}

internal data class PlatformMembership(
    val tier: String,
    val active: Boolean?,
    val expiresAt: Long? = null,
    val level: Int? = null,
    val checkedAt: Long = System.currentTimeMillis(),
    val subscriptions: List<MembershipSubscription> = emptyList(),
) {
    fun activeAt(now: Long = System.currentTimeMillis()): Boolean? =
        if (subscriptions.isNotEmpty()) displayAt(now).active
        else if (expiresAt != null && expiresAt <= now) false else active

    fun freshAt(now: Long = System.currentTimeMillis()): Boolean =
        checkedAt <= now && now - checkedAt < 15 * 60 * 1000

    fun displayAt(now: Long = System.currentTimeMillis()): PlatformMembership =
        if (subscriptions.isEmpty()) copy(active = activeAt(now))
        else fromSubscriptions(subscriptions, now, checkedAt)

    fun badgeTier(now: Long = System.currentTimeMillis()): MembershipTier? {
        if (displayAt(now).active != true) return null
        return subscriptions.filter { it.activeAt(now) == true }.maxByOrNull { it.kind.rank }?.kind
            ?: if (tier.contains("超级") || tier.contains("SVIP", ignoreCase = true))
                MembershipTier.SVIP
            else MembershipTier.VIP
    }

    fun expiryText(now: Long = System.currentTimeMillis()): String =
        displayAt(now).let { membershipExpiryText(it.active, it.expiresAt, now) }

    fun retainKnownSubscriptions(previous: PlatformMembership?): PlatformMembership {
        if (subscriptions.isEmpty() || previous?.subscriptions.isNullOrEmpty()) return this
        val merged = subscriptions.map { subscription ->
            if (subscription.active == null && subscription.expiresAt == null)
                previous.subscriptions.firstOrNull { it.kind == subscription.kind } ?: subscription
            else subscription
        }
        return fromSubscriptions(merged, checkedAt = checkedAt)
    }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("tier", tier)
            put("active", active ?: JSONObject.NULL)
            put("expiresAt", expiresAt ?: JSONObject.NULL)
            put("level", level ?: JSONObject.NULL)
            put("checkedAt", checkedAt)
            put(
                "subscriptions",
                JSONArray().apply {
                    subscriptions.forEach { subscription ->
                        put(
                            JSONObject().apply {
                                put("kind", subscription.kind.name)
                                put("tier", subscription.tier)
                                put("active", subscription.active ?: JSONObject.NULL)
                                put("expiresAt", subscription.expiresAt ?: JSONObject.NULL)
                                put("level", subscription.level ?: JSONObject.NULL)
                            }
                        )
                    }
                },
            )
        }

    companion object {
        fun fromJson(json: JSONObject): PlatformMembership {
            val subscriptions = json.optJSONArray("subscriptions")
            return PlatformMembership(
                json.getString("tier"),
                if (json.isNull("active")) null else json.getBoolean("active"),
                json.optLong("expiresAt").takeIf { it > 0 },
                json.optInt("level").takeIf { it > 0 },
                json.getLong("checkedAt"),
                (0 until (subscriptions?.length() ?: 0)).mapNotNull { index ->
                    val item = subscriptions?.optJSONObject(index) ?: return@mapNotNull null
                    val kind =
                        MembershipTier.entries.firstOrNull { it.name == item.optString("kind") }
                            ?: return@mapNotNull null
                    MembershipSubscription(
                        kind,
                        if (item.isNull("active")) null else item.getBoolean("active"),
                        item.optLong("expiresAt").takeIf { it > 0 },
                        item.optInt("level").takeIf { it > 0 },
                        item.getString("tier"),
                    )
                },
            )
        }

        fun fromSubscriptions(
            subscriptions: List<MembershipSubscription>,
            now: Long = System.currentTimeMillis(),
            checkedAt: Long = now,
        ): PlatformMembership {
            val current =
                subscriptions.filter { it.activeAt(now) == true }.maxByOrNull { it.kind.rank }
            val last =
                current
                    ?: subscriptions.filter { it.expiresAt != null }.maxByOrNull { it.expiresAt!! }
            val active =
                when {
                    current != null -> true
                    subscriptions.any { it.activeAt(now) == false } -> false
                    else -> null
                }
            return PlatformMembership(
                current?.tier ?: if (active == false) "普通账号" else "会员状态待同步",
                active,
                last?.expiresAt,
                current?.level ?: subscriptions.mapNotNull { it.level }.maxOrNull(),
                checkedAt,
                subscriptions,
            )
        }
    }
}

private fun membershipExpiryText(active: Boolean?, expiresAt: Long?, now: Long): String =
    when {
        expiresAt != null ->
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(expiresAt)) +
                if (expiresAt > now) " 到期" else " 已到期"
        active == true -> "平台未提供到期时间"
        active == false -> "未开通会员"
        else -> "会员信息暂未获取"
    }

internal object KugouQualityFallback {
    fun candidates(
        track: PlatformTrack,
        preferred: AudioQuality,
        membership: PlatformMembership?,
        now: Long = System.currentTimeMillis(),
    ): List<AudioQuality> {
        require(track.platform == MusicPlatform.KUGOU)
        val quality = AudioQuality.preference(track.platform, preferred)
        val hires = track.qualities.firstOrNull { it.quality == AudioQuality.HIRES }
        val tryHires =
            quality.ordinal > AudioQuality.HIRES.ordinal &&
                hires?.access != TrackAccess.UNAVAILABLE &&
                (membership?.activeAt(now) != false || hires?.access == TrackAccess.FREE)
        return buildList {
            add(quality)
            if (tryHires) add(AudioQuality.HIRES)
            if (quality != AudioQuality.STANDARD) add(AudioQuality.STANDARD)
        }
            .distinct()
    }
}

/** Missing privileges remain unknown; membership dates never imply playback rights. */
internal object PlatformAudioJson {
    fun neteaseAccess(song: JSONObject, privilege: JSONObject? = null): TrackAccess {
        val rights = privilege ?: song.optJSONObject("privilege")
        if (rights?.optInt("st", 0)?.let { it < 0 } == true) return TrackAccess.UNAVAILABLE
        val fee = if (song.has("fee")) song.optInt("fee", -1) else rights?.optInt("fee", -1) ?: -1
        return when (fee) {
            1 -> TrackAccess.VIP
            4 -> TrackAccess.PAID
            0 -> TrackAccess.FREE
            8 -> TrackAccess.VIP_QUALITY
            else -> TrackAccess.UNKNOWN
        }
    }

    fun kugouAccess(song: JSONObject): TrackAccess {
        val rights = song.optJSONObject("privilege") ?: song
        if (rights.optInt("pay_type", rights.optInt("PayType", -1)) == 3) return TrackAccess.PAID
        val privilege = rights.optInt("privilege", rights.optInt("Privilege", -1))
        if (privilege > 0 || rights.optInt("is_vip", -1) == 1) return TrackAccess.VIP
        if (rights.optInt("status", -1) == 0 && rights.optInt("fail_process", 0) != 0)
            return TrackAccess.UNAVAILABLE
        return if (privilege == 0) TrackAccess.FREE else TrackAccess.UNKNOWN
    }

    fun kugouQualities(resource: JSONObject): List<QualityAccess> {
        val goods = resource.optJSONArray("relate_goods")
        val audio = resource.optJSONObject("audio_info") ?: resource
        return AudioQuality.forPlatform(MusicPlatform.KUGOU).mapNotNull { quality ->
            val item = goods?.let {
                (0 until it.length())
                    .mapNotNull { index -> it.optJSONObject(index) }
                    .firstOrNull { it.optString("quality") == quality.kugou }
            }
            if (item != null) return@mapNotNull QualityAccess(quality, kugouAccess(item))
            val key = quality.kugou
            val hash =
                audio.optString(
                    "hash_$key",
                    if (quality == AudioQuality.STANDARD) audio.optString("hash") else "",
                )
            if (!hash.matches(Regex("(?i)[0-9a-f]{32}"))) return@mapNotNull null
            val rights =
                JSONObject()
                    .put("privilege", audio.optInt("privilege_$key", audio.optInt("privilege", -1)))
                    .put("pay_type", audio.optInt("pay_type", -1))
            QualityAccess(quality, kugouAccess(rights))
        }
    }

    fun neteaseQualities(song: JSONObject, privilege: JSONObject? = null): List<QualityAccess> {
        val fields =
            mapOf(
                AudioQuality.STANDARD to listOf("l"),
                AudioQuality.HIGHER to listOf("m"),
                AudioQuality.HIGH to listOf("h"),
                AudioQuality.LOSSLESS to listOf("sq"),
                AudioQuality.HIRES to listOf("hr"),
                AudioQuality.SURROUND to listOf("je", "jyeffect"),
                AudioQuality.SPATIAL to listOf("sk", "sky"),
                AudioQuality.DOLBY to listOf("db", "dolby"),
                AudioQuality.VIVID to listOf("vivid"),
                AudioQuality.MASTER to listOf("jm", "jymaster"),
            )
        return AudioQuality.forPlatform(MusicPlatform.NETEASE).mapNotNull { quality ->
            val audio =
                fields[quality]?.firstNotNullOfOrNull { song.optJSONObject(it) }
                    ?: return@mapNotNull null
            if (audio.optLong("br", audio.optLong("bitrate")) <= 0) return@mapNotNull null
            QualityAccess(quality, neteaseAccess(song, privilege))
        }
    }

    fun neteaseMembership(
        json: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): PlatformMembership = neteaseMembership(listOf(json), now)

    /**
     * Front and client responses can each omit a tier; combine their explicit subscription fields.
     */
    fun neteaseMembership(
        responses: List<JSONObject>,
        now: Long = System.currentTimeMillis(),
    ): PlatformMembership {
        val fields =
            mapOf(
                MembershipTier.MUSIC_PACKAGE to listOf("musicPackage"),
                MembershipTier.VIP to listOf("associator", "redVip", "vipInfo"),
                MembershipTier.SVIP to
                    listOf("redplus", "redPlus", "svip", "svipInfo", "superVipInfo"),
            )
        val subscriptions =
            MembershipTier.entries.mapNotNull { kind ->
                val candidates = responses.flatMap { json ->
                    val data = json.optJSONObject("data") ?: json
                    fields.getValue(kind).mapNotNull { key ->
                        val value = data.optJSONObject(key) ?: return@mapNotNull null
                        val expiry = timestamp(value, "expireTime", "expireDate", "endTime")
                        val status = number(value, "vipCode", "vipStatus", "isVip", "status")
                        val active =
                            when {
                                expiry != null -> expiry > now
                                status != null -> status > 0
                                value.opt("isVip") is Boolean -> value.getBoolean("isVip")
                                else -> null
                            }
                        MembershipSubscription(
                            kind,
                            active,
                            expiry,
                            (number(value, "vipLevel", "level")
                                    ?: number(data, "redVipLevel", "vipLevel"))
                                ?.takeIf { it > 0 },
                            value.optString("vipName").takeIf { it.isNotBlank() && it != "null" }
                                ?: if (kind == MembershipTier.MUSIC_PACKAGE) kind.label
                                else "黑胶 ${kind.label}",
                        )
                    }
                }
                candidates.maxWithOrNull(
                    compareBy<MembershipSubscription> { it.activeAt(now) == true }
                        .thenBy { it.active != null || it.expiresAt != null }
                        .thenBy { it.expiresAt ?: 0 }
                )
                    ?: if (kind != MembershipTier.MUSIC_PACKAGE) MembershipSubscription(kind, null)
                    else null
            }
        return PlatformMembership.fromSubscriptions(subscriptions, now)
    }

    fun kugouMembership(
        json: JSONObject,
        now: Long = System.currentTimeMillis(),
    ): PlatformMembership {
        val data = json.optJSONObject("data") ?: json
        val entries = mutableListOf(data)
        listOf("vip_info", "busi_vip", "concept", "union_vip", "vip", "svip", "dvip", "qvip")
            .forEach { key ->
                data.optJSONObject(key)?.let { entry ->
                    entries += entry
                    entry
                        .keys()
                        .asSequence()
                        .mapNotNull { entry.optJSONObject(it) }
                        .forEach { entries += it }
                }
                data.optJSONArray(key)?.let { array ->
                    (0 until array.length())
                        .mapNotNull { array.optJSONObject(it) }
                        .forEach { entries += it }
                }
            }
        val current =
            entries
                .filter {
                    number(it, "vip_type", "is_vip", "vip", "status")?.let { type -> type > 0 } ==
                        true ||
                        timestamp(
                            it,
                            "vip_end_time",
                            "end_time",
                            "expire_time",
                            "vip_end_date",
                            "endtime",
                            "vip_expire_time",
                        ) != null
                }
                .maxByOrNull {
                    timestamp(
                        it,
                        "vip_end_time",
                        "end_time",
                        "expire_time",
                        "vip_end_date",
                        "endtime",
                        "vip_expire_time",
                    ) ?: 0
                }
        val expiry = current?.let {
            timestamp(
                it,
                "vip_end_time",
                "end_time",
                "expire_time",
                "vip_end_date",
                "endtime",
                "vip_expire_time",
            )
        }
        val type =
            current?.let { number(it, "vip_type", "is_vip", "vip") }
                ?: number(data, "vip_type", "is_vip", "vip")
        val active =
            when {
                expiry != null -> expiry > now
                type != null -> type > 0
                else -> null
            }
        val name =
            current?.let { value ->
                listOf("vip_name", "product_name", "name").firstNotNullOfOrNull {
                    value.optString(it).takeIf { text -> text.isNotBlank() && text != "null" }
                }
            } ?: if (type == 6) "超级 VIP" else "概念版 VIP"
        return PlatformMembership(
            if (active == true) name else if (active == false) "普通账号" else "会员状态待同步",
            active,
            expiry,
            current?.let { number(it, "vip_level", "membership_level") }?.takeIf { it > 0 },
        )
    }

    private fun number(json: JSONObject, vararg keys: String) = keys.firstNotNullOfOrNull {
        json.optInt(it, -1).takeIf { n -> n >= 0 }
    }

    private fun timestamp(json: JSONObject, vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            val value =
                json.optString(key).takeIf { it.isNotBlank() && it != "null" }
                    ?: return@firstNotNullOfOrNull null
            val numeric = value.toLongOrNull()
            if (numeric != null && value.length != 8) {
                when {
                    numeric > 100000000000L -> numeric
                    numeric > 1000000000L -> numeric * 1000
                    else -> null
                }
            } else {
                listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "yyyyMMdd", "yyyy-MM-dd'T'HH:mm:ssX")
                    .firstNotNullOfOrNull { pattern ->
                        runCatching {
                            val position = ParsePosition(0)
                            val date =
                                SimpleDateFormat(pattern, Locale.CHINA)
                                    .apply { isLenient = false }
                                    .parse(value, position)
                            date?.time?.takeIf { position.index == value.length }
                        }
                            .getOrNull()
                    }
            }
        }
}
