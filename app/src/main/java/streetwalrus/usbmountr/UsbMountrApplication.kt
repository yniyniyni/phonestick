package streetwalrus.usbmountr

import android.app.Application
import com.google.android.material.color.DynamicColors

class UsbMountrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Monet: derive the whole scheme from the wallpaper seed on Android 12+;
        // older devices keep the launcher-green palette.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
