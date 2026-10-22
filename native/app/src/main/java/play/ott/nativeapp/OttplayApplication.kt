package play.ott.nativeapp
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import play.ott.nativeapp.data.NativeRepository
import play.ott.nativeapp.data.EpgRefreshWorker
class OttplayApplication : Application(), ImageLoaderFactory {
    val repository: NativeRepository by lazy { NativeRepository(this) }
    private val playbackVisible = MutableStateFlow(false)
    internal val playbackActivityVisible = playbackVisible.asStateFlow()
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val visibility = PlaybackActivityVisibility({ playbackVisible.value = it }) { expire ->
        val callback = Runnable(expire)
        // Bound the handoff if Android cannot start the replacement Activity. Ordinary
        // Home/finish of a started Activity still hides playback immediately.
        lifecycleHandler.postDelayed(callback, 750)
        val cancel: () -> Unit = { lifecycleHandler.removeCallbacks(callback) }
        cancel
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity is MainActivity) {
                    visibility.started(activity)
                }
            }
            override fun onActivityStopped(activity: Activity) {
                if (activity is MainActivity) visibility.stopped(activity, activity.isChangingConfigurations)
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is MainActivity) visibility.stopped(activity, false)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        EpgRefreshWorker.schedule(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(AppTransportPolicy.current.secure(OkHttpClient()))
        .build()
}
