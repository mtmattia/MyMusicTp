package com.cappielloantonio.tempo.service

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

open class MediaLibrarySessionCallback(
    context: Context,
    automotiveRepository: AutomotiveRepository
) :
    MediaLibraryService.MediaLibrarySession.Callback {

    init {
        MediaBrowserTree.initialize(automotiveRepository)
    }

    private val shuffleOnButton: CommandButton = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_shuffle_on_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON, Bundle.EMPTY))
        .setIconResId(R.drawable.exo_icon_shuffle_off)
        .build()

    private val shuffleOffButton: CommandButton = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_shuffle_off_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF, Bundle.EMPTY))
        .setIconResId(R.drawable.exo_icon_shuffle_on)
        .build()

    private val repeatOffButton: CommandButton = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_repeat_off_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_REPEAT_MODE, Bundle.EMPTY))
        .setIconResId(R.drawable.exo_icon_repeat_off)
        .build()

    private val repeatAllButton: CommandButton = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_repeat_all_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_REPEAT_MODE, Bundle.EMPTY))
        .setIconResId(R.drawable.exo_icon_repeat_all)
        .build()

    private val repeatOneButton: CommandButton = CommandButton.Builder()
        .setDisplayName(context.getString(R.string.exo_controls_repeat_one_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_REPEAT_MODE, Bundle.EMPTY))
        .setIconResId(R.drawable.exo_icon_repeat_one)
        .build()

    @OptIn(UnstableApi::class)
    val mediaNotificationSessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON, Bundle.EMPTY))
            .add(SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF, Bundle.EMPTY))
            .add(SessionCommand(CUSTOM_COMMAND_REPEAT_MODE, Bundle.EMPTY))
            .build()

    private fun buildCustomLayout(player: Player): ImmutableList<CommandButton> {
        val shuffle = if (player.shuffleModeEnabled) shuffleOffButton else shuffleOnButton
        val repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> repeatOneButton
            Player.REPEAT_MODE_ALL -> repeatAllButton
            else -> repeatOffButton
        }
        return ImmutableList.of(shuffle, repeat)
    }

    @OptIn(UnstableApi::class)
    private fun isExternalController(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean {
        return session.isMediaNotificationController(controller) ||
                session.isAutomotiveController(controller) ||
                session.isAutoCompanionController(controller)
    }

    /**
     * Aggiorna i pulsanti custom (shuffle/repeat) su notifica, Android Auto e Automotive.
     * Prima veniva aggiornata solo la notifica: in auto l'icona restava sbagliata.
     */
    @OptIn(UnstableApi::class)
    fun updateCustomLayout(session: MediaSession) {
        val layout = buildCustomLayout(session.player)
        session.connectedControllers
            .filter { isExternalController(session, it) }
            .forEach { session.setCustomLayout(it, layout) }
    }

    @OptIn(UnstableApi::class)
    override fun onConnect(
        session: MediaSession, controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        if (isExternalController(session, controller)) {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(mediaNotificationSessionCommands)
                .setCustomLayout(buildCustomLayout(session.player))
                .build()
        }

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
    }

    @OptIn(UnstableApi::class)
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        val player = session.player

        when (customCommand.customAction) {
            CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON -> player.shuffleModeEnabled = true
            CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF -> player.shuffleModeEnabled = false
            CUSTOM_COMMAND_REPEAT_MODE -> player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        updateCustomLayout(session)
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return Futures.immediateFuture(LibraryResult.ofItem(MediaBrowserTree.getRootItem(), params))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return safe { MediaBrowserTree.getChildren(parentId) }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        return try {
            super.onAddMediaItems(
                mediaSession,
                controller,
                MediaBrowserTree.getItems(mediaItems)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "onAddMediaItems failed", t)
            Futures.immediateFailedFuture(t)
        }
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 60, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return safe { MediaBrowserTree.search(query) }
    }

    /**
     * Android Auto mostra uno spinner infinito (e può marcare l'app come non responsiva)
     * se un future non viene mai completato o fallisce: convertiamo timeout/errori in un errore pulito.
     */
    private fun safe(
        block: () -> ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Browse failed", t)
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
        }
        val withTimeout = Futures.withTimeout(future, BROWSE_TIMEOUT_SECONDS, TimeUnit.SECONDS, timeoutExecutor)
        return Futures.catching(
            withTimeout,
            Throwable::class.java,
            { LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO) },
            MoreExecutors.directExecutor()
        )
    }

    companion object {
        private const val TAG = "MediaLibraryCallback"
        private const val BROWSE_TIMEOUT_SECONDS = 15L
        private val timeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
        private const val CUSTOM_COMMAND_REPEAT_MODE =
            "com.cappielloantonio.tempo.REPEAT_MODE"
        private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON =
            "android.media3.session.demo.SHUFFLE_ON"
        private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF =
            "android.media3.session.demo.SHUFFLE_OFF"
    }
}