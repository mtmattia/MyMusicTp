package com.cappielloantonio.tempo.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.databinding.FragmentMainPagerBinding;
import com.cappielloantonio.tempo.ui.activity.MainActivity;

/**
 * Home, Libreria e Download in un'unica pagina a scorrimento: si cambia sezione col dito
 * (le pagine seguono il movimento) o dalla barra in basso, con l'animazione di scorrimento.
 */
@OptIn(markerClass = UnstableApi.class)
public class MainPagerFragment extends Fragment {
    public static final int PAGE_HOME = 0;
    public static final int PAGE_LIBRARY = 1;
    public static final int PAGE_DOWNLOAD = 2;

    private static final int[] MENU_IDS = {R.id.homeFragment, R.id.libraryFragment, R.id.downloadFragment};

    /** Ultima pagina vista: si torna lì quando si rientra (es. da un album). */
    private static int lastPage = PAGE_HOME;

    private FragmentMainPagerBinding bind;
    private MainActivity activity;

    private final OnBackPressedCallback backToHome = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (bind != null) bind.mainPager.setCurrentItem(PAGE_HOME, true);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) requireActivity();
        bind = FragmentMainPagerBinding.inflate(inflater, container, false);
        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bind.mainPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case PAGE_LIBRARY:
                        return new LibraryFragment();
                    case PAGE_DOWNLOAD:
                        return new DownloadFragment();
                    default:
                        return new HomeFragment();
                }
            }

            @Override
            public int getItemCount() {
                return 3;
            }
        });
        bind.mainPager.setOffscreenPageLimit(2);
        // Dopo il ripristino dello stato ViewPager2 torna alla pagina salvata: la pagina scelta
        // dalla barra in basso (o l'ultima vista) va impostata dopo
        final int target = pendingPage >= 0 ? pendingPage : lastPage;
        pendingPage = -1;
        bind.mainPager.setCurrentItem(target, false);
        bind.mainPager.post(() -> {
            if (bind == null) return;
            bind.mainPager.setCurrentItem(target, false);
            placeSearchBar(bind.mainPager.getCurrentItem(), 0f);
            activity.setBottomNavigationSelected(MENU_IDS[bind.mainPager.getCurrentItem()]);
            backToHome.setEnabled(bind.mainPager.getCurrentItem() != PAGE_HOME);
        });

        bind.mainSearchBar.getRoot().setOnClickListener(v -> activity.navController.navigate(R.id.searchFragment));

        bind.mainPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrolled(int position, float offset, int offsetPx) {
                placeSearchBar(position, offset);
            }

            @Override
            public void onPageSelected(int position) {
                lastPage = position;
                placeSearchBar(position, 0f);
                backToHome.setEnabled(position != PAGE_HOME);
                activity.setBottomNavigationSelected(MENU_IDS[position]);
            }
        });

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backToHome);
        activity.setMainPager(this);
    }

    /** Ricerca ferma tra Home e Libreria, scorre via verso Download. */
    private void placeSearchBar(int position, float offset) {
        if (bind == null) return;
        View bar = bind.mainSearchBar.getRoot();
        float width = bind.mainPager.getWidth();
        if (width == 0) {
            bind.mainPager.post(() -> placeSearchBar(position, offset));
            return;
        }
        float shift = position >= PAGE_DOWNLOAD ? width : position == PAGE_LIBRARY ? offset * width : 0f;
        bar.setTranslationX(-shift);
    }

    /** Dalla barra in basso: scorre alla sezione con l'animazione. */
    public void showPage(int menuId) {
        if (bind == null) return;
        for (int i = 0; i < MENU_IDS.length; i++) {
            if (MENU_IDS[i] == menuId) bind.mainPager.setCurrentItem(i, true);
        }
    }

    /** Pagina da mostrare quando si rientra dalla barra in basso (es. da un album). */
    private static int pendingPage = -1;

    public static void setLastPageFor(int menuId) {
        for (int i = 0; i < MENU_IDS.length; i++) {
            if (MENU_IDS[i] == menuId) pendingPage = i;
        }
    }

    @Override
    public void onDestroyView() {
        activity.setMainPager(null);
        super.onDestroyView();
        bind = null;
    }
}
