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
    channelNames: Map<String, String> = emptyMap(), blockedNames: Map<String, String> = emptyMap(),
    pairs: Map<String, Double> = emptyMap()) {
    val pairs = sanitize(pairs, 16) { pair ->
        val terms = pair.split(' '); terms.size == 2 && terms.distinct().size == 2 && terms.all(InterestMetadata::validTopic)
    }
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
    fun watched(video: VideoSummary, watchedMs: Long, durationMs: Long): InterestProfile =
        watched(video, WatchOutcome(watchedMs, durationMs))
    fun watched(video: VideoSummary, outcome: WatchOutcome, weight: Double = 1.0): InterestProfile {
        val gain = outcome.gain * weight.coerceIn(0.0, 1.0)
        return if (gain > 0) adjust(video, gain) else this
    }
    private fun adjust(video: VideoSummary, gain: Double): InterestProfile {
        val nextTopics = topics.mapValues { (_, score) -> score * .98 }.toMutableMap()
        InterestMetadata.topics(video.title).forEach { nextTopics[it] = (nextTopics[it] ?: 0.0) + gain }
        val nextChannels = channels.mapValues { (_, score) -> score * .98 }.toMutableMap()
        val channel = InterestMetadata.channel(video)
        if (channel.isNotBlank()) nextChannels[channel] = (nextChannels[channel] ?: 0.0) + gain * 2
        val nextNames = channelNames.toMutableMap()
        InterestMetadata.stableChannel(video)?.let { nextNames[it] = InterestMetadata.channelName(video) }
        val nextPairs = pairs.mapValues { (_, score) -> score * .98 }.toMutableMap()
        val terms = InterestMetadata.topics(video.title).toList()
        // Adjacent retained title terms provide bounded co-occurrence evidence, never global combinations.
        terms.zipWithNext().forEach { (a, b) ->
            val pair = listOf(a, b).sorted().joinToString(" ")
            nextPairs[pair] = (nextPairs[pair] ?: 0.0) + gain
        }
        return InterestProfile(nextTopics, nextChannels, dismissed, blocked, shown, nextNames, blockedNames, nextPairs)
    }
    fun feedback(video: VideoSummary, feedback: RecommendationFeedback): InterestProfile = when (feedback) {
        RecommendationFeedback.MORE -> adjust(video, 6.0).let {
            val primary = InterestMetadata.channel(video); val name = InterestMetadata.channelName(video)
            val kept = blocked.filterNot { key -> key == primary || key == name ||
                (InterestMetadata.stableChannel(video) == null && blockedNames[key] == name) }
            InterestProfile(it.topics, it.channels, dismissed.filterNot { id -> id == video.id.value },
                kept, shown, it.channelNames, blockedNames, it.pairs)
        }
        RecommendationFeedback.LESS -> adjust(video, -2.0).let {
            InterestProfile(it.topics, it.channels, listOf(video.id.value) + dismissed, blocked, shown, it.channelNames, blockedNames, it.pairs)
        }
        RecommendationFeedback.BLOCK_CHANNEL -> {
            val aliases = blockedNames.toMutableMap()
            InterestMetadata.stableChannel(video)?.let { aliases[it] = InterestMetadata.channelName(video) }
            InterestProfile(topics, channels, listOf(video.id.value) + dismissed,
                listOf(InterestMetadata.channel(video)) + blocked, shown, channelNames, aliases, pairs)
        }
    }
    fun exposed(videos: List<VideoSummary>) = InterestProfile(topics, channels, dismissed, blocked,
        videos.take(24).map { it.id.value } + shown, channelNames, blockedNames, pairs)
    /** At most two public topic words are sent, only while personalization is on. */
    fun discoveryQuery(rotation: Int = 0): String? {
        val strongest = topics.entries.sortedByDescending { it.value }.take(3)
        if (strongest.isEmpty()) return null
        val seed = strongest[Math.floorMod(rotation, strongest.size)].key
        return pairs.entries.filter { (pair, _) -> pair.split(' ').all { it in topics } && seed in pair.split(' ') }
            .maxByOrNull { it.value }?.key ?: seed
    }
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

/** Standalone adapter; the coordinator shares a prepared pool for both presentation contexts. */
object InterestRanking {
    fun rank(videos: List<VideoSummary>, interests: InterestProfile, enabled: Boolean,
        context: VideoSummary? = null, rotation: Int = 0): List<VideoSummary> {
        return RecommendedVideos.select(GenrePreferences(), enabled,
            RecommendationPool(mapOf(Genre.SCIENCE to videos.take(60))), emptySet(), limit = 60,
            interests = interests, context = context, rotation = rotation).map { it.video }
    }
}
