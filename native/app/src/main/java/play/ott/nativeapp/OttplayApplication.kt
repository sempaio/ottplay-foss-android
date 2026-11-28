package play.ott.nativeapp
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import play.ott.nativeapp.data.NativeRepository
import play.ott.nativeapp.data.EpgRefreshWorker
open class OttplayApplication : Application(), ImageLoaderFactory {
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
        scheduleEpgRefresh()
    }

    protected open fun scheduleEpgRefresh() {
        EpgRefreshWorker.schedule(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(buildArtworkHttpClient())
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "image_cache"))
                .maxSizeBytes(150L * 1024L * 1024L)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()

    internal companion object {
        const val ARTWORK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun buildArtworkHttpClient(): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 12
            }
            val base = OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val next = req.newBuilder().apply {
                        if (req.header("User-Agent").isNullOrBlank()) {
                            header("User-Agent", ARTWORK_USER_AGENT)
                        }
                        if (req.header("Accept").isNullOrBlank()) {
                            header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        }
                    }.build()
                    chain.proceed(next)
                }
                .build()
            return AppTransportPolicy.current.secure(base)
        }
    }
}
