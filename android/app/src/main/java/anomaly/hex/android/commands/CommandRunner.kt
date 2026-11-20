package anomaly.hex.android.commands

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.view.KeyEvent
import anomaly.hex.android.DebugLog
import anomaly.hex.android.service.HexAccessibilityService
import java.util.Locale

/**
 * Executes a VoiceCommand with ordinary Android intents and, for global
 * actions, the accessibility service. Calls open the dialer; nothing dials,
 * sends, or deletes by itself.
 */
object CommandRunner {
    data class Result(val ok: Boolean, val message: String)

    fun run(context: Context, command: VoiceCommand): Result {
        val result = try {
            when (command) {
                is VoiceCommand.SetTimer -> setTimer(context, command.seconds)
                is VoiceCommand.SetAlarm -> setAlarm(context, command.hour, command.minute)
                is VoiceCommand.OpenApp -> openApp(context, command.name)
                is VoiceCommand.Dial -> dial(context, command.number)
                is VoiceCommand.Navigate -> navigate(context, command.place)
                is VoiceCommand.Search -> search(context, command.query)
                VoiceCommand.Home -> home(context)
                VoiceCommand.Back -> back()
                VoiceCommand.Screenshot -> screenshot()
                VoiceCommand.PlayPause -> playPause(context)
                VoiceCommand.VolumeUp -> volume(context, AudioManager.ADJUST_RAISE, "Volume up")
                VoiceCommand.VolumeDown -> volume(context, AudioManager.ADJUST_LOWER, "Volume down")
                is VoiceCommand.Unknown -> Result(false, "No command matched")
            }
        } catch (error: Exception) {
            Result(false, error.message ?: "Command failed")
        }
        DebugLog.log(
            "Command",
            "${command::class.simpleName}: ${if (result.ok) "ok" else "failed"}",
        )
        return result
    }

    private fun setTimer(context: Context, seconds: Int): Result {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Hex")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return Result(false, "No clock app")
        }
        context.startActivity(intent)
        return Result(true, "Timer set for ${formatDuration(seconds)}")
    }

    private fun setAlarm(context: Context, hour: Int, minute: Int): Result {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Hex")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return Result(false, "No clock app")
        }
        context.startActivity(intent)
        return Result(true, "Alarm set for %02d:%02d".format(Locale.US, hour, minute))
    }

    private fun openApp(context: Context, name: String): Result {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val query = normalize(name)
        val best = pm.queryIntentActivities(launcher, 0)
            .map { info ->
                val label = pm.getApplicationLabel(info.activityInfo.applicationInfo).toString()
                info to score(label, query)
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?: return Result(false, "No app named \"$name\"")

        val info = best.first.activityInfo
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(info.packageName, info.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val label = pm.getApplicationLabel(info.applicationInfo).toString()
        return Result(true, "Opening $label")
    }

    private fun dial(context: Context, number: String): Result {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return Result(false, "No dialer")
        }
        context.startActivity(intent)
        return Result(true, "Dialer open for $number")
    }

    private fun navigate(context: Context, place: String): Result {
        val pm = context.packageManager
        val encoded = Uri.encode(place)
        val navigation = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=$encoded"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = if (navigation.resolveActivity(pm) != null) {
            navigation
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(pm) == null) {
            return Result(false, "No maps app")
        }
        context.startActivity(intent)
        return Result(true, "Directions to $place")
    }

    private fun search(context: Context, query: String): Result {
        val pm = context.packageManager
        val web = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = if (web.resolveActivity(pm) != null) {
            web
        } else {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return Result(true, "Searching for $query")
    }

    private fun home(context: Context): Result {
        val service = HexAccessibilityService.instance
        if (service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true) {
            return Result(true, "Home")
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return Result(true, "Home")
    }

    private fun back(): Result {
        val service = HexAccessibilityService.instance
            ?: return Result(false, "Enable accessibility for Back")
        return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            Result(true, "Back")
        } else {
            Result(false, "Back failed")
        }
    }

    private fun screenshot(): Result {
        val service = HexAccessibilityService.instance
            ?: return Result(false, "Enable accessibility for screenshots")
        return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) {
            Result(true, "Screenshot saved")
        } else {
            Result(false, "Screenshot failed")
        }
    }

    private fun playPause(context: Context): Result {
        val audio = context.getSystemService(AudioManager::class.java)
        val wasPlaying = audio.isMusicActive
        audio.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        )
        audio.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        )
        return Result(true, if (wasPlaying) "Paused" else "Playing")
    }

    private fun volume(context: Context, direction: Int, label: String): Result {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
        return Result(true, label)
    }

    private fun score(label: String, query: String): Int {
        val text = normalize(label)
        if (text.isEmpty() || query.isEmpty()) return 0
        return when {
            text == query -> 100
            text.startsWith(query) -> 80
            text.contains(query) -> 60
            query.contains(text) -> 40
            else -> 0
        }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("the ")

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, rest)
        } else {
            "%d:%02d".format(Locale.US, minutes, rest)
        }
    }
}
