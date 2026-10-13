package org.privateview.quietscreen.core

import java.util.Locale

enum class RecommendationFeedback(val label: String) {
    MORE("More like this"), LESS("Not interested"), BLOCK_CHANNEL("Don't recommend this channel")
}

/** Small local vocabulary, not a transcript, query log, embedding model or network classifier. */
object InterestMetadata {
    private val channelId = Regex("UC[A-Za-z0-9_-]{22}")
    private val words = Regex("[\\p{L}][\\p{L}\\p{N}]{2,31}")
    private val spaces = Regex("\\s+")
    private val stop = ("the and for with from that this your you are was were how what why when where which " +
        "into about have has not but all can our its their they more best new full official video videos " +
        "watch channel live episode part explained review tutorial shorts youtube today amazing latest " +
        "una uno los las del para con por que les des une dans sur der die das und ein eine mit").split(' ').toSet()
    fun topics(title: String): Set<String> = words.findAll(title.take(240).lowercase(Locale.ROOT))
        .map { it.value }.filterNot { it in stop }.take(12).toSet()
    fun stableChannel(video: VideoSummary): String? = video.channelId?.takeIf(::validChannelId)
    fun validChannelId(value: String) = channelId.matches(value)
    fun channelName(video: VideoSummary): String = video.channel.take(120).trim().lowercase(Locale.ROOT).replace(spaces, " ")
    fun channel(video: VideoSummary): String = stableChannel(video) ?: channelName(video)
    fun validTopic(value: String) = value.length in 3..32 && words.matches(value) && value !in stop
    fun validChannel(value: String) = value.isNotBlank() && value.length <= 120 && value.none { it.isISOControl() }
}

class InterestProfile(topics: Map<String, Double> = emptyMap(), channels: Map<String, Double> = emptyMap(),
    dismissed: List<String> = emptyList(), blocked: List<String> = emptyList(), shown: List<String> = emptyList(),
    channelNames: Map<String, String> = emptyMap(), blockedNames: Map<String, String> = emptyMap()) {
    val topics = sanitize(topics, MAX_TOPICS, InterestMetadata::validTopic)
    val channels = sanitize(channels, MAX_CHANNELS, InterestMetadata::validChannel)
    val dismissed = ids(dismissed, MAX_DISMISSED)
    val blocked = blocked.asSequence().filter(InterestMetadata::validChannel).distinct().take(MAX_BLOCKED).toList()
    val shown = ids(shown, MAX_SHOWN)
    private val dismissedSet = this.dismissed.toSet()
    private val blockedSet = this.blocked.toSet()
    val channelNames = names(channelNames, this.channels.keys, MAX_CHANNELS)
    val blockedNames = names(blockedNames, blockedSet, MAX_BLOCKED)
    private val blockedFallbacks = this.blockedNames.values.toSet()
    private val fallbackScores = this.channelNames.entries.groupBy({ it.value }, { this.channels[it.key] ?: 0.0 })
        .mapValues { (_, scores) -> scores.maxOrNull() ?: 0.0 }
    val personalized get() = topics.isNotEmpty() || channels.isNotEmpty()
    fun permits(video: VideoSummary): Boolean {
        val name = InterestMetadata.channelName(video)
        return video.id.value !in dismissedSet && InterestMetadata.channel(video) !in blockedSet && name !in blockedSet &&
            !(InterestMetadata.stableChannel(video) == null && name in blockedFallbacks)
    }
    fun channelAffinity(video: VideoSummary): Double {
        val name = InterestMetadata.channelName(video)
        val primary = channels[InterestMetadata.channel(video)] ?: 0.0
        return maxOf(primary, channels[name] ?: 0.0,
            if (InterestMetadata.stableChannel(video) == null) fallbackScores[name] ?: 0.0 else 0.0)
    }
    fun watched(video: VideoSummary, watchedMs: Long, durationMs: Long): InterestProfile {
        val watched = watchedMs.coerceIn(0, 3_600_000)
        val fraction = if (durationMs > 0) (watched.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        if (watched in 10_000 until 30_000 && durationMs >= 120_000 && fraction < .1) return adjust(video, -1.0)
        if (watched < 30_000 && !(watched >= 10_000 && fraction >= .8)) return this
        return adjust(video, kotlin.math.sqrt((watched / 60_000.0).coerceAtMost(10.0)) + fraction * 2)
    }
    private fun adjust(video: VideoSummary, gain: Double): InterestProfile {
        val nextTopics = topics.mapValues { (_, score) -> score * .98 }.toMutableMap()
        InterestMetadata.topics(video.title).forEach { nextTopics[it] = (nextTopics[it] ?: 0.0) + gain }
        val nextChannels = channels.mapValues { (_, score) -> score * .98 }.toMutableMap()
        val channel = InterestMetadata.channel(video)
        if (channel.isNotBlank()) nextChannels[channel] = (nextChannels[channel] ?: 0.0) + gain * 2
        val nextNames = channelNames.toMutableMap()
        InterestMetadata.stableChannel(video)?.let { nextNames[it] = InterestMetadata.channelName(video) }
        return InterestProfile(nextTopics, nextChannels, dismissed, blocked, shown, nextNames, blockedNames)
    }
    fun feedback(video: VideoSummary, feedback: RecommendationFeedback): InterestProfile = when (feedback) {
        RecommendationFeedback.MORE -> adjust(video, 6.0).let {
            val primary = InterestMetadata.channel(video); val name = InterestMetadata.channelName(video)
            val kept = blocked.filterNot { key -> key == primary || key == name ||
                (InterestMetadata.stableChannel(video) == null && blockedNames[key] == name) }
            InterestProfile(it.topics, it.channels, dismissed.filterNot { id -> id == video.id.value },
                kept, shown, it.channelNames, blockedNames)
        }
        RecommendationFeedback.LESS -> adjust(video, -2.0).let {
            InterestProfile(it.topics, it.channels, listOf(video.id.value) + dismissed, blocked, shown, it.channelNames, blockedNames)
        }
        RecommendationFeedback.BLOCK_CHANNEL -> {
            val aliases = blockedNames.toMutableMap()
            InterestMetadata.stableChannel(video)?.let { aliases[it] = InterestMetadata.channelName(video) }
            InterestProfile(topics, channels, listOf(video.id.value) + dismissed,
                listOf(InterestMetadata.channel(video)) + blocked, shown, channelNames, aliases)
        }
    }
    fun exposed(videos: List<VideoSummary>) = InterestProfile(topics, channels, dismissed, blocked,
        videos.take(24).map { it.id.value } + shown, channelNames, blockedNames)
    /** At most two public topic words are sent, only while personalization is on. */
    fun discoveryQuery(): String? = topics.entries.sortedByDescending { it.value }.take(2)
        .map { it.key }.joinToString(" ").takeIf { it.isNotBlank() }
    companion object {
        private val videoId = Regex("[A-Za-z0-9_-]{11}")
        const val MAX_TOPICS = 64
        const val MAX_CHANNELS = 32
        const val MAX_DISMISSED = 80
        const val MAX_BLOCKED = 24
        const val MAX_SHOWN = 96
        private fun ids(values: List<String>, limit: Int) = values.asSequence()
            .filter { videoId.matches(it) }.distinct().take(limit).toList()
        private fun sanitize(values: Map<String, Double>, limit: Int, valid: (String) -> Boolean) = values.entries
            .filter { valid(it.key) && it.value.isFinite() && it.value > .05 }
            .sortedByDescending { it.value }.take(limit).associate { it.key to it.value.coerceAtMost(100.0) }
        private fun names(values: Map<String, String>, keys: Set<String>, limit: Int) = values.asSequence()
            .filter { it.key in keys && InterestMetadata.validChannelId(it.key) && InterestMetadata.validChannel(it.value) }
            .take(limit).associate { it.key to it.value }
    }
}

/** Metadata is parsed once per candidate; never run this from a playback sampling loop. */
object InterestRanking {
    fun rank(videos: List<VideoSummary>, interests: InterestProfile, enabled: Boolean,
        context: VideoSummary? = null, rotation: Int = 0): List<VideoSummary> {
        val contextTopics = context?.let { InterestMetadata.topics(it.title) }.orEmpty()
        val contextChannel = context?.let(InterestMetadata::channel)
        val contextName = context?.let(InterestMetadata::channelName)
        val seen = interests.shown.toSet()
        return videos.take(60).filter { interests.permits(it) && it.id != context?.id && !it.live }
            .mapIndexed { index, video ->
                val topics = InterestMetadata.topics(video.title)
                val channel = InterestMetadata.channel(video)
                val affinity = if (enabled) topics.sumOf { kotlin.math.sqrt(interests.topics[it] ?: 0.0) }.coerceAtMost(16.0) +
                    kotlin.math.sqrt(interests.channelAffinity(video)) * 2 else 0.0
                val relevance = if (context == null) 0.0 else topics.count { it in contextTopics } * 5.0 +
                    if (channel.isNotBlank() && (channel == contextChannel ||
                        (contextName == InterestMetadata.channelName(video) &&
                            (InterestMetadata.stableChannel(context) == null || InterestMetadata.stableChannel(video) == null)))) 8.0 else 0.0
                val novelty = if (video.id.value in seen) -12.0 else 0.0
                // Stable bounded rotation breaks provider-order ties without a random generator.
                Triple(video, affinity + relevance + novelty, Math.floorMod(index - rotation, videos.size.coerceAtLeast(1)))
            }.sortedWith(compareByDescending<Triple<VideoSummary, Double, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }
}
