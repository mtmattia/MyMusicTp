package com.cappielloantonio.tempo.viewmodel;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.interfaces.StarCallback;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.model.Queue;
import com.cappielloantonio.tempo.repository.AlbumRepository;
import com.cappielloantonio.tempo.repository.ArtistRepository;
import com.cappielloantonio.tempo.repository.FavoriteRepository;
import com.cappielloantonio.tempo.repository.OpenRepository;
import com.cappielloantonio.tempo.repository.QueueRepository;
import com.cappielloantonio.tempo.repository.SongRepository;
import com.cappielloantonio.tempo.subsonic.models.AlbumID3;
import com.cappielloantonio.tempo.subsonic.models.ArtistID3;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.LyricsList;
import com.cappielloantonio.tempo.subsonic.models.PlayQueue;
import com.cappielloantonio.tempo.util.Constants;
import com.cappielloantonio.tempo.util.DownloadUtil;
import com.cappielloantonio.tempo.util.MappingUtil;
import com.cappielloantonio.tempo.util.NetworkUtil;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;
import com.cappielloantonio.tempo.util.Preferences;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@OptIn(markerClass = UnstableApi.class)
public class PlayerBottomSheetViewModel extends AndroidViewModel {
    private static final String TAG = "PlayerBottomSheetViewModel";

    private final SongRepository songRepository;
    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;
    private final QueueRepository queueRepository;
    private final FavoriteRepository favoriteRepository;
    private final OpenRepository openRepository;
    private final MutableLiveData<String> lyricsLiveData = new MutableLiveData<>(null);
    private final MutableLiveData<LyricsList> lyricsListLiveData = new MutableLiveData<>(null);
    private final MutableLiveData<String> descriptionLiveData = new MutableLiveData<>(null);
    private final MutableLiveData<Child> liveMedia = new MutableLiveData<>(null);
    private final MutableLiveData<AlbumID3> liveAlbum = new MutableLiveData<>(null);
    private final MutableLiveData<ArtistID3> liveArtist = new MutableLiveData<>(null);
    private final MutableLiveData<List<Child>> instantMix = new MutableLiveData<>(null);
    private boolean lyricsSyncState = true;

    // Id of the track each async request belongs to. Responses arriving after the
    // track changed are dropped, otherwise a slow reply for the previous song
    // overwrites the current one (lyrics of another track, wrong info).
    private String requestedMediaId;
    private String requestedAlbumId;
    private String requestedArtistId;
    private String lyricsMediaId;
    private Boolean lyricsFetchedWithExtension;


    public PlayerBottomSheetViewModel(@NonNull Application application) {
        super(application);

        songRepository = new SongRepository();
        albumRepository = new AlbumRepository();
        artistRepository = new ArtistRepository();
        queueRepository = new QueueRepository();
        favoriteRepository = new FavoriteRepository();
        openRepository = new OpenRepository();
    }

    public LiveData<List<Queue>> getQueueSong() {
        return queueRepository.getLiveQueue();
    }

    public void setFavorite(Context context, Child media) {
        if (media != null) {
            if (media.getStarred() != null) {
                if (NetworkUtil.isOffline()) {
                    removeFavoriteOffline(media);
                } else {
                    removeFavoriteOnline(media);
                }
            } else {
                if (NetworkUtil.isOffline()) {
                    setFavoriteOffline(media);
                } else {
                    setFavoriteOnline(context, media);
                }
            }
        }
    }

    private void removeFavoriteOffline(Child media) {
        favoriteRepository.starLater(media.getId(), null, null, false);
        media.setStarred(null);
    }

    private void removeFavoriteOnline(Child media) {
        favoriteRepository.unstar(media.getId(), null, null, new StarCallback() {
            @Override
            public void onError() {
                // media.setStarred(new Date());
                favoriteRepository.starLater(media.getId(), null, null, false);
            }
        });

        media.setStarred(null);
    }

    private void setFavoriteOffline(Child media) {
        favoriteRepository.starLater(media.getId(), null, null, true);
        media.setStarred(new Date());
    }

    private void setFavoriteOnline(Context context, Child media) {
        favoriteRepository.star(media.getId(), null, null, new StarCallback() {
            @Override
            public void onError() {
                // media.setStarred(null);
                favoriteRepository.starLater(media.getId(), null, null, true);
            }
        });

        media.setStarred(new Date());

        if (Preferences.isStarredSyncEnabled()) {
            DownloadUtil.getDownloadTracker(context).download(
                    MappingUtil.mapDownload(media),
                    new Download(media)
            );
        }
    }

    public LiveData<String> getLiveLyrics() {
        return lyricsLiveData;
    }

    public LiveData<LyricsList> getLiveLyricsList() {
        return lyricsListLiveData;
    }

    /** Id of the track the current lyrics belong to. */
    public String getLyricsMediaId() {
        return lyricsMediaId;
    }

    public void refreshMediaInfo(LifecycleOwner owner, Child media) {
        if (media == null) return;

        final String mediaId = media.getId();
        final boolean useExtension = OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable();

        // Same track re-emitted: keep what we have, no new request. Refetch if the
        // synced-lyrics extension became available meanwhile (plain lyrics were loaded
        // before the server answered the ping).
        if (Objects.equals(mediaId, lyricsMediaId)
                && Objects.equals(useExtension, lyricsFetchedWithExtension)
                && (lyricsLiveData.getValue() != null || lyricsListLiveData.getValue() != null)) {
            return;
        }

        lyricsMediaId = mediaId;
        lyricsFetchedWithExtension = useExtension;

        // Clear immediately (main thread) so the previous track's lyrics never stay on screen.
        lyricsLiveData.setValue(null);
        lyricsListLiveData.setValue(null);

        if (useExtension) {
            openRepository.getLyricsBySongId(mediaId).observe(owner, lyricsList -> {
                if (Objects.equals(mediaId, lyricsMediaId)) lyricsListLiveData.setValue(lyricsList);
            });
        } else {
            songRepository.getSongLyrics(media).observe(owner, lyrics -> {
                if (Objects.equals(mediaId, lyricsMediaId)) lyricsLiveData.setValue(lyrics);
            });
        }
    }

    public LiveData<Child> getLiveMedia() {
        return liveMedia;
    }

    public void setLiveMedia(LifecycleOwner owner, String mediaType, String mediaId) {
        if (mediaType != null) {
            switch (mediaType) {
                case Constants.MEDIA_TYPE_MUSIC:
                    requestedMediaId = mediaId;
                    songRepository.getSong(mediaId).observe(owner, song -> {
                        if (Objects.equals(mediaId, requestedMediaId)) liveMedia.setValue(song);
                    });
                    descriptionLiveData.postValue(null);
                    break;
                case Constants.MEDIA_TYPE_PODCAST:
                    requestedMediaId = null;
                    liveMedia.postValue(null);
                    break;
            }
        }
    }

    public LiveData<AlbumID3> getLiveAlbum() {
        return liveAlbum;
    }

    public void setLiveAlbum(LifecycleOwner owner, String mediaType, String AlbumId) {
        if (mediaType != null) {
            switch (mediaType) {
                case Constants.MEDIA_TYPE_MUSIC:
                    requestedAlbumId = AlbumId;
                    albumRepository.getAlbum(AlbumId).observe(owner, album -> {
                        if (Objects.equals(AlbumId, requestedAlbumId)) liveAlbum.setValue(album);
                    });
                    break;
                case Constants.MEDIA_TYPE_PODCAST:
                    liveAlbum.postValue(null);
                    break;
            }
        }
    }

    public LiveData<ArtistID3> getLiveArtist() {
        return liveArtist;
    }

    public void setLiveArtist(LifecycleOwner owner, String mediaType, String ArtistId) {
        if (mediaType != null) {
            switch (mediaType) {
                case Constants.MEDIA_TYPE_MUSIC:
                    requestedArtistId = ArtistId;
                    artistRepository.getArtist(ArtistId).observe(owner, artist -> {
                        if (Objects.equals(ArtistId, requestedArtistId)) liveArtist.setValue(artist);
                    });
                    break;
                case Constants.MEDIA_TYPE_PODCAST:
                    liveArtist.postValue(null);
                    break;
            }
        }
    }

    public void setLiveDescription(String description) {
        descriptionLiveData.postValue(description);
    }

    public LiveData<String> getLiveDescription() {
        return descriptionLiveData;
    }

    public LiveData<List<Child>> getMediaInstantMix(LifecycleOwner owner, Child media) {
        instantMix.setValue(Collections.emptyList());

        songRepository.getInstantMix(media.getId(), 20).observe(owner, instantMix::postValue);

        return instantMix;
    }

    public LiveData<PlayQueue> getPlayQueue() {
        return queueRepository.getPlayQueue();
    }

    public boolean savePlayQueue() {
        Child media = getLiveMedia().getValue();
        List<Child> queue = queueRepository.getMedia();
        List<String> ids = queue.stream().map(Child::getId).collect(Collectors.toList());

        if (media != null) {
            queueRepository.savePlayQueue(ids, media.getId(), 0);
            return true;
        }

        return false;
    }

    public void changeSyncLyricsState() {
        lyricsSyncState = !lyricsSyncState;
    }

    public boolean getSyncLyricsState() {
        return lyricsSyncState;
    }
}
