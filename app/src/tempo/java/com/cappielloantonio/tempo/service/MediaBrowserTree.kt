package com.cappielloantonio.tempo.service

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.util.Preferences.getServerId
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture

object MediaBrowserTree {

    // Chiavi standard Android Auto per lo stile dei contenuti
    const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val CONTENT_STYLE_LIST = 1
    const val CONTENT_STYLE_GRID = 2
    private fun t(res: Int): String = App.getContext().getString(res)

    private lateinit var automotiveRepository: AutomotiveRepository

    private var treeNodes: MutableMap<String, MediaItemNode> = mutableMapOf()

    private var isInitialized = false

    // Root
    private const val ROOT_ID = "[rootID]"

    // First level
    private const val HOME_ID = "[homeID]"
    private const val LIBRARY_ID = "[libraryID]"
    private const val OTHER_ID = "[otherID]"

    // Second level HOME_ID
    private const val MOST_PLAYED_ID = "[mostPlayedID]"
    private const val LAST_PLAYED_ID = "[lastPlayedID]"
    private const val RECENTLY_ADDED_ID = "[recentlyAddedID]"
    private const val RECENT_SONGS_ID = "[recentSongsID]"
    private const val MADE_FOR_YOU_ID = "[madeForYouID]"
    private const val STARRED_TRACKS_ID = "[starredTracksID]"
    private const val STARRED_ALBUMS_ID = "[starredAlbumsID]"
    private const val STARRED_ARTISTS_ID = "[starredArtistsID]"
    private const val RANDOM_ID = "[randomID]"

    // Second level LIBRARY_ID
    private const val FOLDER_ID = "[folderID]"
    private const val INDEX_ID = "[indexID]"
    private const val DIRECTORY_ID = "[directoryID]"
    private const val PLAYLIST_ID = "[playlistID]"

    // Second level OTHER_ID
    private const val PODCAST_ID = "[podcastID]"
    private const val RADIO_ID = "[radioID]"

    private const val ALBUM_ID = "[albumID]"
    private const val ARTIST_ID = "[artistID]"

    private class MediaItemNode(val item: MediaItem) {
        private val children: MutableList<MediaItem> = ArrayList()

        fun addChild(childID: String) {
            this.children.add(treeNodes[childID]!!.item)
        }

        fun getChildren(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val listenableFuture = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val libraryResult = LibraryResult.ofItemList(children, null)

            listenableFuture.set(libraryResult)

            return listenableFuture
        }
    }

    private fun buildMediaItem(
        title: String,
        mediaId: String,
        isPlayable: Boolean,
        isBrowsable: Boolean,
        mediaType: @MediaMetadata.MediaType Int,
        subtitleConfigurations: List<SubtitleConfiguration> = mutableListOf(),
        album: String? = null,
        artist: String? = null,
        genre: String? = null,
        sourceUri: Uri? = null,
        imageUri: Uri? = null,
        childrenBrowsableStyle: Int? = null
    ): MediaItem {
        // Android Auto: stile dei figli di questo nodo (lista o griglia)
        val extras = childrenBrowsableStyle?.let {
            Bundle().apply { putInt(CONTENT_STYLE_BROWSABLE_HINT, it) }
        }

        val metadata =
            MediaMetadata.Builder()
                .setExtras(extras)
                .setAlbumTitle(album)
                .setTitle(title)
                .setArtist(artist)
                .setGenre(genre)
                .setIsBrowsable(isBrowsable)
                .setIsPlayable(isPlayable)
                .setArtworkUri(imageUri)
                .setMediaType(mediaType)
                .build()

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setSubtitleConfigurations(subtitleConfigurations)
            .setMediaMetadata(metadata)
            .setUri(sourceUri)
            .build()
    }

    fun initialize(automotiveRepository: AutomotiveRepository) {
        this.automotiveRepository = automotiveRepository

        if (isInitialized) return

        isInitialized = true

        // Root level

        treeNodes[ROOT_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = "Root Folder",
                    mediaId = ROOT_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        // First level

        treeNodes[HOME_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_home),
                    mediaId = HOME_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                    childrenBrowsableStyle = CONTENT_STYLE_GRID
                )
            )

        treeNodes[LIBRARY_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_library),
                    mediaId = LIBRARY_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                    childrenBrowsableStyle = CONTENT_STYLE_LIST
                )
            )

        treeNodes[OTHER_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_other),
                    mediaId = OTHER_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                    childrenBrowsableStyle = CONTENT_STYLE_LIST
                )
            )

        treeNodes[ROOT_ID]!!.addChild(HOME_ID)
        treeNodes[ROOT_ID]!!.addChild(LIBRARY_ID)
        treeNodes[ROOT_ID]!!.addChild(OTHER_ID)

        // Second level HOME_ID

        treeNodes[MOST_PLAYED_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.home_title_most_played),
                    mediaId = MOST_PLAYED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                    childrenBrowsableStyle = CONTENT_STYLE_GRID
                )
            )

        treeNodes[LAST_PLAYED_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.home_title_last_played),
                    mediaId = LAST_PLAYED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                    childrenBrowsableStyle = CONTENT_STYLE_GRID
                )
            )

        treeNodes[RECENTLY_ADDED_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.home_title_recently_added),
                    mediaId = RECENTLY_ADDED_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                    childrenBrowsableStyle = CONTENT_STYLE_GRID
                )
            )

        treeNodes[RECENT_SONGS_ID] =
                MediaItemNode(
                        buildMediaItem(
                                title = t(R.string.auto_recent_songs),
                                mediaId = RECENT_SONGS_ID,
                                isPlayable = false,
                                isBrowsable = true,
                                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                        )
                )

        treeNodes[MADE_FOR_YOU_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.home_title_made_for_you),
                    mediaId = MADE_FOR_YOU_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                    childrenBrowsableStyle = CONTENT_STYLE_GRID
                )
            )

        treeNodes[STARRED_TRACKS_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_starred_tracks),
                    mediaId = STARRED_TRACKS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                )
            )

        treeNodes[STARRED_ALBUMS_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_starred_albums),
                    mediaId = STARRED_ALBUMS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                    childrenBrowsableStyle = CONTENT_STYLE_GRID
                )
            )

        treeNodes[STARRED_ARTISTS_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_starred_artists),
                    mediaId = STARRED_ARTISTS_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                    childrenBrowsableStyle = CONTENT_STYLE_GRID
                )
            )

        treeNodes[RANDOM_ID] =
                MediaItemNode(
                        buildMediaItem(
                                title = t(R.string.auto_random),
                                mediaId = RANDOM_ID,
                                isPlayable = false,
                                isBrowsable = true,
                                mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                        )
                )

        treeNodes[HOME_ID]!!.addChild(MOST_PLAYED_ID)
        treeNodes[HOME_ID]!!.addChild(LAST_PLAYED_ID)
        treeNodes[HOME_ID]!!.addChild(RECENTLY_ADDED_ID)
        treeNodes[HOME_ID]!!.addChild(RECENT_SONGS_ID)
        treeNodes[HOME_ID]!!.addChild(MADE_FOR_YOU_ID)
        treeNodes[HOME_ID]!!.addChild(STARRED_TRACKS_ID)
        treeNodes[HOME_ID]!!.addChild(STARRED_ALBUMS_ID)
        treeNodes[HOME_ID]!!.addChild(STARRED_ARTISTS_ID)
        treeNodes[HOME_ID]!!.addChild(RANDOM_ID)

        // Second level LIBRARY_ID

        treeNodes[FOLDER_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_folders),
                    mediaId = FOLDER_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                    childrenBrowsableStyle = CONTENT_STYLE_LIST
                )
            )

        treeNodes[PLAYLIST_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_playlists),
                    mediaId = PLAYLIST_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                )
            )

        treeNodes[LIBRARY_ID]!!.addChild(FOLDER_ID)
        treeNodes[LIBRARY_ID]!!.addChild(PLAYLIST_ID)

        // Second level OTHER_ID

        treeNodes[PODCAST_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_podcasts),
                    mediaId = PODCAST_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS
                )
            )

        treeNodes[RADIO_ID] =
            MediaItemNode(
                buildMediaItem(
                    title = t(R.string.auto_radio),
                    mediaId = RADIO_ID,
                    isPlayable = false,
                    isBrowsable = true,
                    mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS
                )
            )

        treeNodes[OTHER_ID]!!.addChild(PODCAST_ID)
        treeNodes[OTHER_ID]!!.addChild(RADIO_ID)
    }

    fun getRootItem(): MediaItem {
        return treeNodes[ROOT_ID]!!.item
    }

    /**
     * Home di Android Auto: riquadri grandi, uno per sezione, con la copertina del
     * primo elemento (come le app di musica standard). Toccando un riquadro si apre
     * la sezione, anche lei a copertine.
     */
    private fun homeSections(): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val tiles = listOf(
            tile(LAST_PLAYED_ID, automotiveRepository.getAlbums(LAST_PLAYED_ID, "recent", 1)),
            tile(MADE_FOR_YOU_ID, automotiveRepository.getMadeForYouSeeds(MADE_FOR_YOU_ID, 1)),
            tile(RECENTLY_ADDED_ID, automotiveRepository.getAlbums(RECENTLY_ADDED_ID, "newest", 1)),
            tile(MOST_PLAYED_ID, automotiveRepository.getAlbums(MOST_PLAYED_ID, "frequent", 1)),
            tile(STARRED_TRACKS_ID, automotiveRepository.starredSongs),
            tile(STARRED_ALBUMS_ID, automotiveRepository.getStarredAlbums(STARRED_ALBUMS_ID)),
            tile(STARRED_ARTISTS_ID, automotiveRepository.getStarredArtists(STARRED_ARTISTS_ID)),
            tile(RECENT_SONGS_ID, automotiveRepository.getRecentlyPlayedSongs(getServerId(), 1)),
            tile(RANDOM_ID, automotiveRepository.getRandomSongs(1))
        )

        return Futures.transform(Futures.allAsList(tiles), { items ->
            LibraryResult.ofItemList(ImmutableList.copyOf(items.filterNotNull()), null)
        }, MoreExecutors.directExecutor())
    }

    /** Riquadro della sezione con la copertina del primo elemento; null se la sezione è vuota. */
    private fun tile(
        id: String,
        content: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
    ): ListenableFuture<MediaItem?> {
        val node = treeNodes[id]!!.item
        val withArt = Futures.transform(content, { result ->
            if (result?.value.isNullOrEmpty()) return@transform null
            val art = result?.value?.firstOrNull { it.mediaMetadata.artworkUri != null }?.mediaMetadata?.artworkUri
            if (art == null) node
            else node.buildUpon().setMediaMetadata(node.mediaMetadata.buildUpon().setArtworkUri(art).build()).build()
        }, MoreExecutors.directExecutor())
        // Sezione vuota o in errore (es. nessun preferito): niente riquadro col triangolo di avviso
        return Futures.catching(withArt, Throwable::class.java, { null }, MoreExecutors.directExecutor())
    }

    fun getChildren(
        id: String
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return when (id) {
            ROOT_ID -> treeNodes[ROOT_ID]?.getChildren()!!
            HOME_ID -> homeSections()
            LIBRARY_ID -> treeNodes[LIBRARY_ID]?.getChildren()!!
            OTHER_ID -> treeNodes[OTHER_ID]?.getChildren()!!

            MOST_PLAYED_ID -> automotiveRepository.getAlbums(id, "frequent", 100)
            LAST_PLAYED_ID -> automotiveRepository.getAlbums(id, "recent", 100)
            RECENTLY_ADDED_ID -> automotiveRepository.getAlbums(id, "newest", 100)
            RECENT_SONGS_ID -> automotiveRepository.getRecentlyPlayedSongs(getServerId(),100)
            MADE_FOR_YOU_ID -> automotiveRepository.getMadeForYouSeeds(id, 12)
            STARRED_TRACKS_ID -> automotiveRepository.starredSongs
            STARRED_ALBUMS_ID -> automotiveRepository.getStarredAlbums(id)
            STARRED_ARTISTS_ID -> automotiveRepository.getStarredArtists(id)
            RANDOM_ID -> automotiveRepository.getRandomSongs(100)
            FOLDER_ID -> automotiveRepository.getMusicFolders(id)
            PLAYLIST_ID -> automotiveRepository.getPlaylists(id)
            PODCAST_ID -> automotiveRepository.getNewestPodcastEpisodes(100)
            RADIO_ID -> automotiveRepository.internetRadioStations

            else -> {
                if (id.startsWith(MOST_PLAYED_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            MOST_PLAYED_ID
                        )
                    )
                }

                if (id.startsWith(LAST_PLAYED_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            LAST_PLAYED_ID
                        )
                    )
                }

                if (id.startsWith(RECENTLY_ADDED_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            RECENTLY_ADDED_ID
                        )
                    )
                }

                if (id.startsWith(MADE_FOR_YOU_ID)) {
                    return automotiveRepository.getMadeForYou(
                        id.removePrefix(
                            MADE_FOR_YOU_ID
                        ),
                        20
                    )
                }

                if (id.startsWith(STARRED_ALBUMS_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            STARRED_ALBUMS_ID
                        )
                    )
                }

                if (id.startsWith(STARRED_ARTISTS_ID)) {
                    return automotiveRepository.getArtistAlbum(
                        STARRED_ALBUMS_ID,
                        id.removePrefix(
                            STARRED_ARTISTS_ID
                        )
                    )
                }

                if (id.startsWith(FOLDER_ID)) {
                    return automotiveRepository.getIndexes(
                        INDEX_ID,
                        id.removePrefix(
                            FOLDER_ID
                        )
                    )
                }

                if (id.startsWith(INDEX_ID)) {
                    return automotiveRepository.getDirectories(
                        DIRECTORY_ID,
                        id.removePrefix(
                            INDEX_ID
                        )
                    )
                }

                if (id.startsWith(DIRECTORY_ID)) {
                    return automotiveRepository.getDirectories(
                        DIRECTORY_ID,
                        id.removePrefix(
                            DIRECTORY_ID
                        )
                    )
                }

                if (id.startsWith(PLAYLIST_ID)) {
                    return automotiveRepository.getPlaylistSongs(
                        id.removePrefix(
                            PLAYLIST_ID
                        )
                    )
                }

                if (id.startsWith(ALBUM_ID)) {
                    return automotiveRepository.getAlbumTracks(
                        id.removePrefix(
                            ALBUM_ID
                        )
                    )
                }

                if (id.startsWith(ARTIST_ID)) {
                    return automotiveRepository.getArtistAlbum(
                        ALBUM_ID,
                        id.removePrefix(
                            ARTIST_ID
                        )
                    )
                }

                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
        }
    }

    // https://github.com/androidx/media/issues/156
    fun getItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val updatedMediaItems = ArrayList<MediaItem>()

        mediaItems.forEach {
            if (it.localConfiguration?.uri != null) {
                updatedMediaItems.add(it)
            } else {
                val sessionMediaItem = automotiveRepository.getSessionMediaItem(it.mediaId)

                if (sessionMediaItem != null) {
                    val timestamp = sessionMediaItem.timestamp ?: return@forEach
                    var toAdd = automotiveRepository.getMetadatas(timestamp)
                    val index = toAdd.indexOfFirst { mediaItem -> mediaItem.mediaId == it.mediaId }

                    // index == -1 causava IndexOutOfBounds e crash del servizio (app sparisce da Android Auto)
                    if (index < 0) return@forEach
                    toAdd = toAdd.subList(index, toAdd.size)

                    updatedMediaItems.addAll(toAdd)
                }
            }
        }

        return updatedMediaItems
    }

    /**
     * Ricerca vocale ("Ok Google, metti X su Tempo" o microfono in Android Auto).
     * Restituisce solo brani riproducibili; query vuota = brani casuali.
     */
    fun voiceSearch(query: String): ListenableFuture<List<MediaItem>> {
        val future = if (query.isBlank()) {
            automotiveRepository.getRandomSongs(100)
        } else {
            automotiveRepository.search(query, ALBUM_ID, ARTIST_ID)
        }

        return Futures.transform(future, { result ->
            result?.value
                ?.filter { it.mediaMetadata.isPlayable == true && it.localConfiguration != null }
                ?: emptyList()
        }, MoreExecutors.directExecutor())
    }

    fun search(query: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return automotiveRepository.search(
            query,
            ALBUM_ID,
            ARTIST_ID
        )
    }
}
