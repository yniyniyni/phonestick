package streetwalrus.usbmountr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import eu.chainfire.libsuperuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class MountState {
    object Unknown : MountState()   // probe in flight
    object Unmounted : MountState()
    object Busy : MountState()      // mount/unmount in flight
    data class Mounted(val path: String) : MountState()
}

/**
 * Bound + started foreground service; the single source of truth for the
 * USB gadget. Probes the real configfs state on creation, so the UI is
 * truthful even after process death, and holds an ongoing notification
 * (with an Unmount action) while mounted.
 */
class MountService : Service() {
    companion object {
        const val ACTION_UNMOUNT = "streetwalrus.usbmountr.UNMOUNT"
        private const val CHANNEL_ID = "mount"
        private const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        val service: MountService get() = this@MountService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<MountState>(MountState.Unknown)
    val state: StateFlow<MountState> = _state

    // One-shot user-facing results; extraBufferCapacity so emit never
    // suspends, and no replay so re-binding doesn't repeat old snackbars.
    private val _messages = MutableSharedFlow<CharSequence>(extraBufferCapacity = 4)
    val messages: SharedFlow<CharSequence> = _messages

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_mount),
                NotificationManager.IMPORTANCE_LOW))
        probe()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UNMOUNT) unmount()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun probe() {
        _state.value = MountState.Unknown
        scope.launch {
            _state.value = when (val result = runScript(GadgetShell.probeScript())) {
                is GadgetShell.ShellResult.MountedProbe -> {
                    goForeground(result.path)
                    MountState.Mounted(result.path)
                }
                // Any failure (no root, unsupported, odd output) renders as
                // unmounted; mounting will surface the real error.
                else -> MountState.Unmounted
            }
        }
    }

    // Main-thread only (activity clicks / onStartCommand): the state guard
    // below relies on Dispatchers.Main.immediate serialization.
    fun mount(path: String, ro: Boolean, cdrom: Boolean) {
        if (_state.value !is MountState.Unmounted) return
        _state.value = MountState.Busy
        scope.launch {
            when (val result = runScript(GadgetShell.mountScript(path, ro, cdrom))) {
                is GadgetShell.ShellResult.Success -> {
                    _state.value = MountState.Mounted(path)
                    goForeground(path)
                    _messages.emit(getString(R.string.host_success))
                }
                else -> {
                    _state.value = MountState.Unmounted
                    _messages.emit(errorText(result))
                }
            }
        }
    }

    // Main-thread only — see mount().
    fun unmount() {
        val previous = _state.value
        if (previous !is MountState.Mounted) return
        _state.value = MountState.Busy
        scope.launch {
            when (val result = runScript(GadgetShell.unmountScript())) {
                is GadgetShell.ShellResult.Success -> {
                    _state.value = MountState.Unmounted
                    stopForeground(true)
                    stopSelf()
                    _messages.emit(getString(R.string.host_disable_success))
                }
                else -> {
                    _state.value = previous
                    _messages.emit(errorText(result))
                }
            }
        }
    }

    private suspend fun runScript(script: Array<String>): GadgetShell.ShellResult =
            withContext(Dispatchers.IO) { GadgetShell.parse(Shell.SU.run(script)) }

    private fun errorText(result: GadgetShell.ShellResult): CharSequence = when (result) {
        GadgetShell.ShellResult.NoRoot -> getString(R.string.host_noroot)
        GadgetShell.ShellResult.Unsupported -> getString(R.string.host_unsupported)
        GadgetShell.ShellResult.BindFail -> getString(R.string.host_bind_failed)
        is GadgetShell.ShellResult.Error -> getString(R.string.host_error_generic, result.raw)
        else -> getString(R.string.host_error_generic, result.toString())
    }

    private fun goForeground(path: String) {
        // Mark the service as started so it outlives the activity binding.
        startService(Intent(this, MountService::class.java))
        val openApp = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val unmountAction = PendingIntent.getService(this, 1,
                Intent(this, MountService::class.java).setAction(ACTION_UNMOUNT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_usb)
                .setContentTitle(getString(R.string.status_mounted))
                .setContentText(File(path).name)
                .setContentIntent(openApp)
                .setOngoing(true)
                .addAction(R.drawable.ic_eject, getString(R.string.host_disable), unmountAction)
                .build()
        startForeground(NOTIFICATION_ID, notification)
    }
}
