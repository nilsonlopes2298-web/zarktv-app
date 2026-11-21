package io.github.axon07.channelbox

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import io.github.axon07.channelbox.data.AppDatabase
import io.github.axon07.channelbox.data.NetworkSettings
import io.github.axon07.channelbox.data.Recorder
import io.github.axon07.channelbox.data.RecordingStorage
import io.github.axon07.channelbox.data.SettingsStore
import io.github.axon07.channelbox.data.TvRepository
import io.github.axon07.channelbox.data.Updater
import io.github.axon07.channelbox.data.UserAgent
import io.github.axon07.channelbox.data.createHttpClient
import io.github.axon07.channelbox.system.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class App : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        io.github.axon07.channelbox.ui.ZarkLeve.detectar(this)
        val settings = SettingsStore(this)
        // Network options (proxy, DNS, user agent) are read once here; changing them restarts the app.
        val network = runBlocking { settings.current().network }
        UserAgent.apply(network.userAgent)
        container = AppContainer(this, settings, network)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The chosen language, before the first screen draws (English needs nothing).
        runCatching {
            val code = kotlinx.coroutines.runBlocking { container.settings.current().language }
            if (code != "en") io.github.axon07.channelbox.ui.I18n.load(this, code)
        }
        scope.launch {
            RefreshWorker.schedule(this@App, container.settings.current().refreshHours)
            container.recorder.tidyUp()
        }
        // "Start when the box wakes up": the screen-on broadcast only reaches running apps, so it's
        // listened for here (the app is kept around in the background on most boxes).
        val wake = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                scope.launch {
                    if (container.settings.current().startOnWake) {
                        io.github.axon07.channelbox.system.SetTopBox.bringToFront(this@App, listOf(1_500L, 2_500L, 4_000L))
                    }
                }
            }
        }
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(wake, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(wake, filter)
    }

    /**
     * Logos and posters go through the app's HTTP client (proxy / DNS / user agent) and are kept on
     * the device, so each picture is downloaded once. Many IPTV image servers send "don't cache"
     * headers, which would otherwise make every scroll download them again; those are ignored.
     * The cache is capped (small devices) and Android may clear it when space runs low.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // Posters come from slow servers: more downloads at once, and give up sooner on stuck ones.
            .okHttpClient {
                container.http.newBuilder()
                    .dispatcher(okhttp3.Dispatcher().apply {
                        // TV Box fraca: menos imagens baixando ao mesmo tempo (não trava a navegação).
                        val leve = io.github.axon07.channelbox.ui.ZarkLeve.ativo
                        maxRequests = if (leve) 16 else 64
                        maxRequestsPerHost = if (leve) 6 else 16
                    })
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowRgb565(io.github.axon07.channelbox.ui.ZarkLeve.ativo)
            .crossfade(!io.github.axon07.channelbox.ui.ZarkLeve.ativo)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()
}

/** Hand-rolled dependency container — small enough that a DI framework isn't worth it. */
class AppContainer(context: Context, val settings: SettingsStore, val startupNetwork: NetworkSettings) {
    val database = AppDatabase.create(context)
    val http = createHttpClient(startupNetwork)
    val repository = TvRepository(database, settings, http).also {
        // Stalker channels, movies and episodes get a fresh portal link when they're played.
        io.github.axon07.channelbox.data.StreamResolver.stalker = it.stalker
    }
    val storage = RecordingStorage(context, http, settings)
    val recorder = Recorder(context, database, http, storage)
    val updater = Updater(context, http)
    val backup = io.github.axon07.channelbox.data.Backup(context, database, settings)
}

val Context.container: AppContainer get() = (applicationContext as App).container
