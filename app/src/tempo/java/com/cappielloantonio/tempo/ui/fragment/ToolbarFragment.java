package com.cappielloantonio.tempo.ui.fragment;

import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentToolbarBinding;
import com.cappielloantonio.tempo.ui.activity.MainActivity;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;

@UnstableApi
public class ToolbarFragment extends Fragment implements MenuProvider {
    private static final String TAG = "ToolbarFragment";

    private FragmentToolbarBinding bind;
    private MainActivity activity;

    public ToolbarFragment() {
        // Required empty public constructor
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.main_page_menu, menu);
        // CastContext ora si inizializza in modo asincrono: colleghiamo il pulsante quando è pronto.
        Context context = requireContext();
        try {
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
                    .addOnSuccessListener(castContext -> {
                        if (isAdded()) CastButtonFactory.setUpMediaRouteButton(context, menu, R.id.media_route_menu_item);
                    });
        } catch (Exception e) {
            Log.w(TAG, "Cast non disponibile", e);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = FragmentToolbarBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        return view;
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_search) {
            activity.navController.navigate(R.id.searchFragment);
            return true;
        } else if (item.getItemId() == R.id.action_settings) {
            activity.navController.navigate(R.id.settingsFragment);
            return true;
        }

        return false;
    }
}