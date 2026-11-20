package anomaly.hex.android.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {
    @Test
    fun timers() {
        assertEquals(
            VoiceCommand.SetTimer(5 * 60),
            CommandParser.parse("set a timer for five minutes"),
        )
        assertEquals(VoiceCommand.SetTimer(90), CommandParser.parse("timer 90 seconds"))
        assertEquals(VoiceCommand.SetTimer(5 * 60), CommandParser.parse("timer 5"))
        assertEquals(VoiceCommand.SetTimer(30 * 60), CommandParser.parse("set a timer for half an hour"))
        assertEquals(VoiceCommand.SetTimer(3 * 3600), CommandParser.parse("start a timer for 3 hours"))
        assertEquals(
            VoiceCommand.SetTimer(25 * 60),
            CommandParser.parse("set a timer for twenty five minutes"),
        )
    }

    @Test
    fun alarms() {
        assertEquals(
            VoiceCommand.SetAlarm(7, 30),
            CommandParser.parse("set an alarm for 7:30 am"),
        )
        assertEquals(VoiceCommand.SetAlarm(19, 0), CommandParser.parse("wake me up at 7 pm"))
        assertEquals(
            VoiceCommand.SetAlarm(6, 15),
            CommandParser.parse("set an alarm for 6 15"),
        )
        assertEquals(VoiceCommand.SetAlarm(19, 30), CommandParser.parse("alarm at 19:30"))
    }

    @Test
    fun apps_and_calls() {
        assertEquals(VoiceCommand.OpenApp("whatsapp"), CommandParser.parse("open WhatsApp"))
        assertEquals(VoiceCommand.OpenApp("chrome"), CommandParser.parse("launch the Chrome app"))
        assertEquals(
            VoiceCommand.Dial("+15551234567"),
            CommandParser.parse("call +1 555 123 4567"),
        )
    }

    @Test
    fun places_and_queries() {
        assertEquals(
            VoiceCommand.Navigate("the nearest coffee"),
            CommandParser.parse("navigate to the nearest coffee"),
        )
        assertEquals(
            VoiceCommand.Search("weather tomorrow"),
            CommandParser.parse("search for weather tomorrow"),
        )
    }

    @Test
    fun system_actions() {
        assertEquals(VoiceCommand.Home, CommandParser.parse("go home"))
        assertEquals(VoiceCommand.Back, CommandParser.parse("back"))
        assertEquals(VoiceCommand.Screenshot, CommandParser.parse("take a screenshot"))
        assertEquals(VoiceCommand.PlayPause, CommandParser.parse("pause music"))
        assertEquals(VoiceCommand.VolumeUp, CommandParser.parse("turn it up"))
        assertEquals(VoiceCommand.VolumeDown, CommandParser.parse("quieter"))
    }

    @Test
    fun unknown_and_guardrails() {
        assertEquals(
            VoiceCommand.Unknown("call mom"),
            CommandParser.parse("call mom"),
        )
        assertEquals(
            VoiceCommand.Unknown("i love you"),
            CommandParser.parse("I love you."),
        )
        assertEquals(
            VoiceCommand.Unknown("set a timer for a while"),
            CommandParser.parse("set a timer for a while"),
        )
    }
}
