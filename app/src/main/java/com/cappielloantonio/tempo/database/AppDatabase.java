package com.cappielloantonio.tempo.database;

import androidx.media3.common.util.UnstableApi;
import androidx.room.AutoMigration;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import androidx.annotation.NonNull;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.database.converter.DateConverters;
import com.cappielloantonio.tempo.database.dao.ChronologyDao;
import com.cappielloantonio.tempo.database.dao.DownloadDao;
import com.cappielloantonio.tempo.database.dao.FavoriteDao;
import com.cappielloantonio.tempo.database.dao.PlaylistDao;
import com.cappielloantonio.tempo.database.dao.QueueDao;
import com.cappielloantonio.tempo.database.dao.RecentSearchDao;
import com.cappielloantonio.tempo.database.dao.ServerDao;
import com.cappielloantonio.tempo.database.dao.SessionMediaItemDao;
import com.cappielloantonio.tempo.model.Chronology;
import com.cappielloantonio.tempo.model.Download;
import com.cappielloantonio.tempo.model.Favorite;
import com.cappielloantonio.tempo.model.Queue;
import com.cappielloantonio.tempo.model.RecentSearch;
import com.cappielloantonio.tempo.model.Server;
import com.cappielloantonio.tempo.model.SessionMediaItem;
import com.cappielloantonio.tempo.subsonic.models.Playlist;

@UnstableApi
@Database(
        version = 11,
        entities = {Queue.class, Server.class, RecentSearch.class, Download.class, Chronology.class, Favorite.class, SessionMediaItem.class, Playlist.class},
        autoMigrations = {@AutoMigration(from = 9, to = 10)}
)
@TypeConverters({DateConverters.class})
public abstract class AppDatabase extends RoomDatabase {
    private final static String DB_NAME = "tempo_db";
    private static AppDatabase instance;

    // Chronology: one row per play (autoincrement uid) instead of one row per song.
    static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `chronology_new` (`uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `server` TEXT, `parent_id` TEXT, `is_dir` INTEGER NOT NULL, `title` TEXT, `album` TEXT, `artist` TEXT, `track` INTEGER, `year` INTEGER, `genre` TEXT, `cover_art_id` TEXT, `size` INTEGER, `content_type` TEXT, `suffix` TEXT, `transcoding_content_type` TEXT, `transcoded_suffix` TEXT, `duration` INTEGER, `bitrate` INTEGER, `path` TEXT, `is_video` INTEGER NOT NULL, `user_rating` INTEGER, `average_rating` REAL, `play_count` INTEGER, `disc_number` INTEGER, `created` INTEGER, `starred` INTEGER, `album_id` TEXT, `artist_id` TEXT, `type` TEXT, `bookmark_position` INTEGER, `original_width` INTEGER, `original_height` INTEGER)");
            db.execSQL("INSERT INTO `chronology_new` (`id`, `timestamp`, `server`, `parent_id`, `is_dir`, `title`, `album`, `artist`, `track`, `year`, `genre`, `cover_art_id`, `size`, `content_type`, `suffix`, `transcoding_content_type`, `transcoded_suffix`, `duration`, `bitrate`, `path`, `is_video`, `user_rating`, `average_rating`, `play_count`, `disc_number`, `created`, `starred`, `album_id`, `artist_id`, `type`, `bookmark_position`, `original_width`, `original_height`) SELECT `id`, `timestamp`, `server`, `parent_id`, `is_dir`, `title`, `album`, `artist`, `track`, `year`, `genre`, `cover_art_id`, `size`, `content_type`, `suffix`, `transcoding_content_type`, `transcoded_suffix`, `duration`, `bitrate`, `path`, `is_video`, `user_rating`, `average_rating`, `play_count`, `disc_number`, `created`, `starred`, `album_id`, `artist_id`, `type`, `bookmark_position`, `original_width`, `original_height` FROM `chronology`");
            db.execSQL("DROP TABLE `chronology`");
            db.execSQL("ALTER TABLE `chronology_new` RENAME TO `chronology`");
        }
    };

    public static synchronized AppDatabase getInstance() {
        if (instance == null) {
            instance = Room.databaseBuilder(App.getContext(), AppDatabase.class, DB_NAME)
                    .addMigrations(MIGRATION_10_11)
                    .fallbackToDestructiveMigration()
                    .build();
        }

        return instance;
    }

    public abstract QueueDao queueDao();

    public abstract ServerDao serverDao();

    public abstract RecentSearchDao recentSearchDao();

    public abstract DownloadDao downloadDao();

    public abstract ChronologyDao chronologyDao();

    public abstract FavoriteDao favoriteDao();

    public abstract SessionMediaItemDao sessionMediaItemDao();

    public abstract PlaylistDao playlistDao();
}
