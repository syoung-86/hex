package anomaly.hex.android.commands

import java.util.Locale

/**
 * M3's command vocabulary. Deliberately local and deterministic: the
 * transcript is matched against a small grammar, never sent anywhere. A
 * command that does not match is Unknown, so the caller can say so instead of
 * pasting something unintended.
 */
sealed interface VoiceCommand {
    data class SetTimer(val seconds: Int) : VoiceCommand
    data class SetAlarm(val hour: Int, val minute: Int) : VoiceCommand
    data class OpenApp(val name: String) : VoiceCommand
    data class Dial(val number: String) : VoiceCommand
    data class Navigate(val place: String) : VoiceCommand
    data class Search(val query: String) : VoiceCommand
    data object Home : VoiceCommand
    data object Back : VoiceCommand
    data object Screenshot : VoiceCommand
    data object PlayPause : VoiceCommand
    data object VolumeUp : VoiceCommand
    data object VolumeDown : VoiceCommand
    data class Unknown(val text: String) : VoiceCommand
}

object CommandParser {
    private val NUMBER_WORDS = mapOf(
        "zero" to 0,
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10,
        "eleven" to 11,
        "twelve" to 12,
        "thirteen" to 13,
        "fourteen" to 14,
        "fifteen" to 15,
        "sixteen" to 16,
        "seventeen" to 17,
        "eighteen" to 18,
        "nineteen" to 19,
        "twenty" to 20,
        "thirty" to 30,
        "forty" to 40,
        "fifty" to 50,
        "sixty" to 60,
        "ninety" to 90,
        "a" to 1,
        "an" to 1,
    )

    private val TIMER = Regex(
        """^(?:set\s+|start\s+)?(?:a\s+|an\s+)?timer(?:\s+for)?\s+(?<rest>.+)$""",
    )
    private val ALARM = Regex(
        """^(?:set\s+)?(?:an\s+)?alarm(?:\s+for|\s+at)?\s+(?<time>.+)$""",
    )
    private val WAKE = Regex("""^wake me up(?:\s+at|\s+for)?\s+(?<time>.+)$""")
    private val OPEN = Regex("""^(?:open|launch|start|run)\s+(?<app>.+)$""")
    private val DIAL = Regex("""^(?:call|dial|phone)\s+(?<number>.+)$""")
    private val NAVIGATE = Regex(
        """^(?:navigate|directions|drive|take me)\s+(?:to\s+)?(?<place>.+)$""",
    )
    private val SEARCH = Regex("""^(?:search|google|look up)\s+(?:for\s+)?(?<query>.+)$""")

    fun parse(raw: String): VoiceCommand {
        val text = normalize(raw)
        if (text.isEmpty()) return VoiceCommand.Unknown(raw)

        exact(text)?.let { return it }

        TIMER.find(text)?.let { match ->
            val rest = match.groups["rest"]?.value ?: return@let
            parseDuration(rest)?.let { return VoiceCommand.SetTimer(it) }
        }
        for (regex in listOf(ALARM, WAKE)) {
            regex.find(text)?.let { match ->
                val rest = match.groups["time"]?.value ?: return@let
                parseClock(rest)?.let { (hour, minute) ->
                    return VoiceCommand.SetAlarm(hour, minute)
                }
            }
        }
        OPEN.find(text)?.let { match ->
            match.groups["app"]?.value?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return VoiceCommand.OpenApp(it.removePrefix("the ").removeSuffix(" app"))
            }
        }
        DIAL.find(text)?.let { match ->
            val spoken = match.groups["number"]?.value ?: return@let
            normalizeNumber(spoken)?.let { return VoiceCommand.Dial(it) }
        }
        NAVIGATE.find(text)?.let { match ->
            match.groups["place"]?.value?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return VoiceCommand.Navigate(it)
            }
        }
        SEARCH.find(text)?.let { match ->
            match.groups["query"]?.value?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return VoiceCommand.Search(it)
            }
        }
        return VoiceCommand.Unknown(text)
    }

    private fun exact(text: String): VoiceCommand? = when (text) {
        "home", "go home", "go to home" -> VoiceCommand.Home
        "back", "go back" -> VoiceCommand.Back
        "screenshot", "take a screenshot", "take screenshot" -> VoiceCommand.Screenshot
        "play", "play music", "pause", "pause music", "resume music", "stop music" ->
            VoiceCommand.PlayPause
        "volume up", "turn it up", "louder", "turn up the volume" -> VoiceCommand.VolumeUp
        "volume down", "turn it down", "quieter", "turn down the volume" ->
            VoiceCommand.VolumeDown
        else -> null
    }

    private fun parseDuration(rest: String): Int? {
        val text = rest.trim()
        if (text.contains("half an hour") || text.contains("half hour")) return 30 * 60
        if (text.contains("half a minute") || text.contains("half minute")) return 30

        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val unit = tokens.last()
        val numberTokens = if (isUnit(unit)) tokens.dropLast(1) else tokens
        val count = parseNumber(numberTokens) ?: return null
        val seconds = when {
            !isUnit(unit) -> count * 60
            unit.startsWith("sec") -> count
            unit.startsWith("min") -> count * 60
            unit.startsWith("hour") || unit.startsWith("hr") -> count * 3600
            else -> return null
        }
        return seconds.takeIf { it in 1..86_400 }
    }

    private fun isUnit(word: String): Boolean =
        word.startsWith("sec") ||
            word.startsWith("min") ||
            word.startsWith("hour") ||
            word.startsWith("hr")

    private fun parseNumber(tokens: List<String>): Int? {
        if (tokens.isEmpty()) return null
        var total = 0
        for (token in tokens) {
            total += token.toIntOrNull() ?: NUMBER_WORDS[token] ?: return null
        }
        return total.takeIf { it > 0 }
    }

    private fun parseClock(rest: String): Pair<Int, Int>? {
        var text = rest.lowercase(Locale.ROOT)
            .replace("a.m.", "am")
            .replace("p.m.", "pm")
            .replace("a.m", "am")
            .replace("p.m", "pm")
            .replace("o'clock", " ")
            .replace("oclock", " ")
            .replace(Regex("\\bat\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val pm = text.contains("pm")
        val am = text.contains("am")
        text = text.replace("am", " ").replace("pm", " ").replace(Regex("\\s+"), " ").trim()

        val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        var hour: Int
        var minute = 0
        val first = parts[0]
        if (first.contains(":")) {
            val split = first.split(":")
            hour = split.getOrNull(0)?.toIntOrNull() ?: return null
            minute = split.getOrNull(1)?.toIntOrNull() ?: return null
        } else {
            hour = first.toIntOrNull() ?: return null
            if (parts.size > 1) {
                minute = parts[1].toIntOrNull() ?: return null
            }
        }

        if (pm && hour in 1..11) hour += 12
        if (am && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    private fun normalizeNumber(spoken: String): String? {
        val cleaned = spoken.replace(Regex("[^0-9+]"), "")
        return cleaned.takeIf { number -> number.count(Char::isDigit) >= 3 }
    }

    private fun normalize(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("[!?;,]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
}
