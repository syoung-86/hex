package anomaly.hex.android

import android.app.Application
import android.os.Build
import android.util.Log
import kotlin.system.exitProcess

class HexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DebugLog.log("Crash", "${error.javaClass.simpleName}: ${error.message}")
            Log.e("HexM0", "uncaught on ${thread.name}", error)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                exitProcess(1)
            }
        }
        DebugLog.log("App", "Hex M0 on ${Build.MANUFACTURER} ${Build.MODEL}")
    }
}
