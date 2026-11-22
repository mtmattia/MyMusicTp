package com.cappielloantonio.tempo.ui.fragment;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.InnerFragmentPlayerLyricsBinding;
import com.cappielloantonio.tempo.service.MediaService;
import com.cappielloantonio.tempo.subsonic.models.Child;
import com.cappielloantonio.tempo.subsonic.models.Line;
import com.cappielloantonio.tempo.subsonic.models.LyricsList;
import com.cappielloantonio.tempo.subsonic.models.StructuredLyrics;
import com.cappielloantonio.tempo.util.MusicUtil;
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil;
import com.cappielloantonio.tempo.viewmodel.PlayerBottomSheetViewModel;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;


@OptIn(markerClass = UnstableApi.class)
public class PlayerLyricsFragment extends Fragment {
    private static final String TAG = "PlayerLyricsFragment";

    private InnerFragmentPlayerLyricsBinding bind;
    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;
    private MediaBrowser mediaBrowser;
    private Handler syncLyricsHandler;
    private Runnable syncLyricsRunnable;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        bind = InnerFragmentPlayerLyricsBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);

        initOverlay();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initPanelContent();
        initSyncLyricsObserver();
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();

    }

    @Override
    public void onResume() {
        super.onResume();
        bindMediaController();

        // Reload if lyrics were fetched before the server capabilities were known
        // (no-op when they are already up to date).
        Child media = playerBottomSheetViewModel.getLiveMedia().getValue();
        if (media != null) playerBottomSheetViewModel.refreshMediaInfo(requireActivity(), media);
        updatePanelContent();
    }

    @Override
    public void onPause() {
        super.onPause();
        releaseHandler();
    }

    @Override
    public void onStop() {
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initOverlay() {
        bind.syncLyricsTapButton.setOnClickListener(view -> {
            playerBottomSheetViewModel.changeSyncLyricsState();
        });
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseHandler() {
        if (syncLyricsHandler != null) {
            syncLyricsHandler.removeCallbacks(syncLyricsRunnable);
            syncLyricsHandler = null;
        }
    }

    private void releaseBrowser() {
        mediaBrowser = null;
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                mediaBrowser = mediaBrowserListenableFuture.get();
                updateSyncHandler();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    // Observers registered once per view: previously a new description observer was added
    // on every lyrics change, each holding the lyrics of an older track, and the last one
    // to fire could paint another song's lyrics.
    private void initPanelContent() {
        playerBottomSheetViewModel.getLiveLyrics().observe(getViewLifecycleOwner(), lyrics -> updatePanelContent());
        playerBottomSheetViewModel.getLiveLyricsList().observe(getViewLifecycleOwner(), lyricsList -> updatePanelContent());
        playerBottomSheetViewModel.getLiveDescription().observe(getViewLifecycleOwner(), description -> updatePanelContent());
    }

    private void updatePanelContent() {
        String lyrics = OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable() ? null : playerBottomSheetViewModel.getLiveLyrics().getValue();
        LyricsList lyricsList = OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable() ? playerBottomSheetViewModel.getLiveLyricsList().getValue() : null;
        String description = playerBottomSheetViewModel.getLiveDescription().getValue();

        setPanelContent(lyrics, lyricsList, description);
    }

    private void setPanelContent(String lyrics, LyricsList lyricsList, String description) {
        if (bind == null) return;

        bind.nowPlayingSongLyricsSrollView.smoothScrollTo(0, 0);

        StructuredLyrics structuredLyrics = getBestStructuredLyrics(lyricsList);

        if (lyrics != null && !lyrics.trim().equals("")) {
            bind.nowPlayingSongLyricsTextView.setText(MusicUtil.getReadableLyrics(lyrics));
            bind.nowPlayingSongLyricsTextView.setVisibility(View.VISIBLE);
            bind.emptyDescriptionImageView.setVisibility(View.GONE);
            bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
            bind.syncLyricsTapButton.setVisibility(View.GONE);
        } else if (structuredLyrics != null) {
            setSyncLirics(structuredLyrics);
            bind.nowPlayingSongLyricsTextView.setVisibility(View.VISIBLE);
            bind.emptyDescriptionImageView.setVisibility(View.GONE);
            bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
            bind.syncLyricsTapButton.setVisibility(structuredLyrics.getSynced() ? View.VISIBLE : View.GONE);
        } else if (description != null && !description.trim().equals("")) {
            bind.nowPlayingSongLyricsTextView.setText(MusicUtil.getReadableLyrics(description));
            bind.nowPlayingSongLyricsTextView.setVisibility(View.VISIBLE);
            bind.emptyDescriptionImageView.setVisibility(View.GONE);
            bind.titleEmptyDescriptionLabel.setVisibility(View.GONE);
            bind.syncLyricsTapButton.setVisibility(View.GONE);
        } else {
            bind.nowPlayingSongLyricsTextView.setVisibility(View.GONE);
            bind.emptyDescriptionImageView.setVisibility(View.VISIBLE);
            bind.titleEmptyDescriptionLabel.setVisibility(View.VISIBLE);
            bind.syncLyricsTapButton.setVisibility(View.GONE);
        }
    }

    /** Servers can return several entries (e.g. plain + synced): prefer the synced one. */
    @Nullable
    private StructuredLyrics getBestStructuredLyrics(@Nullable LyricsList lyricsList) {
        if (lyricsList == null || lyricsList.getStructuredLyrics() == null) return null;

        StructuredLyrics fallback = null;

        for (StructuredLyrics structuredLyrics : lyricsList.getStructuredLyrics()) {
            if (structuredLyrics == null || structuredLyrics.getLine() == null || structuredLyrics.getLine().isEmpty()) continue;
            if (structuredLyrics.getSynced()) return structuredLyrics;
            if (fallback == null) fallback = structuredLyrics;
        }

        return fallback;
    }

    @SuppressLint("DefaultLocale")
    private void setSyncLirics(StructuredLyrics structuredLyrics) {
        bind.nowPlayingSongLyricsTextView.setText(buildLyricsText(structuredLyrics.getLine()));
    }

    private String buildLyricsText(List<Line> lines) {
        StringBuilder lyricsBuilder = new StringBuilder();

        for (Line line : lines) {
            lyricsBuilder.append(line.getValue().trim()).append("\n");
        }

        return lyricsBuilder.toString();
    }

    private void initSyncLyricsObserver() {
        playerBottomSheetViewModel.getLiveLyricsList().observe(getViewLifecycleOwner(), lyricsList -> updateSyncHandler());
    }

    // Single 250ms loop, only while the fragment is resumed, the browser is connected and the
    // current lyrics are synced. Before, every onResume added an observer and every emission
    // started a new Handler without stopping the old one: several loops raced each other.
    private void updateSyncHandler() {
        releaseHandler();

        if (bind == null || mediaBrowser == null || !isResumed()) return;

        StructuredLyrics structuredLyrics = getBestStructuredLyrics(playerBottomSheetViewModel.getLiveLyricsList().getValue());
        if (structuredLyrics == null || !structuredLyrics.getSynced()) return;

        syncLyricsHandler = new Handler(Looper.getMainLooper());
        syncLyricsRunnable = () -> {
            if (syncLyricsHandler != null) {
                if (bind != null) {
                    displaySyncedLyrics();
                }

                syncLyricsHandler.postDelayed(syncLyricsRunnable, 250);
            }
        };

        syncLyricsHandler.postDelayed(syncLyricsRunnable, 250);
    }

    private void displaySyncedLyrics() {
        if (mediaBrowser == null) return;

        // Lyrics must belong to the track actually playing; otherwise wait for the new ones.
        MediaItem currentItem = mediaBrowser.getCurrentMediaItem();
        String lyricsMediaId = playerBottomSheetViewModel.getLyricsMediaId();
        if (currentItem == null || lyricsMediaId == null || !lyricsMediaId.equals(currentItem.mediaId)) return;

        StructuredLyrics structuredLyrics = getBestStructuredLyrics(playerBottomSheetViewModel.getLiveLyricsList().getValue());
        if (structuredLyrics == null || !structuredLyrics.getSynced()) return;

        List<Line> lines = structuredLyrics.getLine();
        if (lines == null || lines.isEmpty()) return;

        // OpenSubsonic: positive offset (ms) means lyrics appear sooner.
        long timestamp = mediaBrowser.getCurrentPosition() + structuredLyrics.getOffset();

        Line toHighlight = lines.stream().filter(line -> line != null && line.getStart() != null && line.getStart() <= timestamp).reduce((first, second) -> second).orElse(null);

        String lyrics = buildLyricsText(lines);
        Spannable spannableString = new SpannableString(lyrics);
        spannableString.setSpan(new ForegroundColorSpan(requireContext().getResources().getColor(R.color.shadowsLyricsTextColor, null)), 0, lyrics.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (toHighlight != null) {
            int startingPosition = getStartPosition(lines, toHighlight);
            int endingPosition = Math.min(lyrics.length(), startingPosition + toHighlight.getValue().trim().length());

            spannableString.setSpan(new ForegroundColorSpan(requireContext().getResources().getColor(R.color.lyricsTextColor, null)), startingPosition, endingPosition, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        bind.nowPlayingSongLyricsTextView.setText(spannableString);

        if (toHighlight != null && playerBottomSheetViewModel.getSyncLyricsState()) {
            bind.nowPlayingSongLyricsSrollView.smoothScrollTo(0, getScroll(lines, toHighlight));
        }
    }

    private int getStartPosition(List<Line> lines, Line toHighlight) {
        int start = 0;

        for (Line line : lines) {
            if (line != toHighlight) {
                start = start + line.getValue().trim().length() + 1;
            } else {
                break;
            }
        }

        return start;
    }

    private int getLineCount(List<Line> lines, Line toHighlight) {
        int start = 0;

        for (Line line : lines) {
            if (line != toHighlight) {
                bind.tempLyricsLineTextView.setText(line.getValue().trim());
                start = start + bind.tempLyricsLineTextView.getLineCount();
            } else {
                break;
            }
        }

        return start;
    }

    private int getScroll(List<Line> lines, Line toHighlight) {
        int lineHeight = bind.nowPlayingSongLyricsTextView.getLineHeight();
        int lineCount = getLineCount(lines, toHighlight);
        int scrollViewHeight = bind.nowPlayingSongLyricsSrollView.getHeight();

        return lineHeight * lineCount < scrollViewHeight / 2 ? 0 : lineHeight * lineCount - scrollViewHeight / 2 + lineHeight;
    }
}