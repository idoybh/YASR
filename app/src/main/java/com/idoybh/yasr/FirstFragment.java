/*
 * Copyright (C) 2023 Ido Ben-Hur
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.idoybh.yasr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.idoybh.yasr.databinding.FragmentFirstBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FirstFragment extends Fragment {
    private static final String PREF_SORT = "last_sort";
    private static final String PREF_REVERSE_SORT = "last_reverse_sort";
    private static final int SORT_BY_NAME = 0;
    private static final int SORT_BY_DATE = 1;
    private static final int SORT_BY_DURATION = 2;
    private static final int SORT_BY_SIZE = 3;
    private static final int SORT_BY_TYPE = 4;
    public static final Handler mUiHandler = new Handler(Looper.getMainLooper());
    public static final ExecutorService mExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors());

    private SharedPreferences mSharedPrefs;
    private FragmentFirstBinding binding;
    private LinearProgressIndicator mProgressIndicator;
    private RecyclerAdapter mAdapter;
    private List<FloatingActionButton> mMultiSelectFabs;
    private int mSortSelection = ListView.INVALID_POSITION;
    private boolean mRememberSort = true;
    private boolean mReverseSort = false;

    // to save animation calculations and do only once
    private float ACTION_FAB_HEIGHT;

    private final OnBackPressedCallback onBackCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            mAdapter.clearSelection();
            onBackCallback.setEnabled(false);
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().getOnBackPressedDispatcher().addCallback(onBackCallback);

        mProgressIndicator = requireActivity().requireViewById(R.id.progressIndicator);

        mMultiSelectFabs = new ArrayList<>(List.of(
                binding.fabSelection,
                binding.fabSave,
                binding.fabShare,
                binding.fabDelete
        ));

        final FloatingActionButton sampleFab = mMultiSelectFabs.get(0);
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)
                sampleFab.getLayoutParams();
        ACTION_FAB_HEIGHT = sampleFab.getMeasuredHeight() + params.bottomMargin;
        ACTION_FAB_HEIGHT *= 10; // more bounce

        // sort and filter
        binding.filterText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // do nothing
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // do nothing
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (mAdapter == null) return;
                if (s == null || s.toString().isEmpty()) {
                    mAdapter.filter(null);
                    return;
                }
                mAdapter.filter(s.toString());
            }
        });
        binding.filterText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            binding.filterText.clearFocus();
            return false;
        });
        final String[] sorts = requireContext().getResources().getStringArray(R.array.sort_by_items);
        ArrayAdapter<String> menuAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, sorts);
        binding.sortMenu.setAdapter(menuAdapter);
        binding.sortMenu.setOnItemClickListener((parent, view1, position1, id) -> {
            mSortSelection = position1;
            mAdapter.sortBy(position1);
            if (mRememberSort) getPrefs().edit().putInt(PREF_SORT, position1).apply();
        });
        binding.sortButton.setOnClickListener(v -> {
            mReverseSort = !mReverseSort;
            animateRotation(v, mReverseSort ? 0 : 180);
            if (mRememberSort) getPrefs().edit().putBoolean(PREF_REVERSE_SORT, mReverseSort).apply();
            if (mSortSelection == ListView.INVALID_POSITION) return;
            mAdapter.sortBy(mSortSelection);
        });
        binding.sortButton.setRotation(180); // normal sort

        binding.fabSelection.setOnClickListener(v -> {
            if (mAdapter.isFullySelected()) {
                mAdapter.clearSelection();
                return;
            }
            mAdapter.selectAll();
        });
        binding.fabSave.setOnClickListener(v -> {
            mSavingQueue = new LinkedList<>();
            mSavingQueue.addAll(mAdapter.getSelectedRecordings());
            final RecordingData first = mSavingQueue.peek();
            if (first == null) return;
            mResultLauncher.launch(first.name + "." + first.ext);
        });
        binding.fabShare.setOnClickListener(v -> {
            ArrayList<Uri> uris = new ArrayList<>();
            for (RecordingData data : mAdapter.getSelectedRecordings()) {
                Uri fileUri = null;
                try {
                    fileUri = FileProvider.getUriForFile(requireActivity(),
                            "com.idoybh.yasr.fileprovider", data.recording);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
                if (fileUri == null) continue;
                uris.add(fileUri);
            }
            RecordingData data = mAdapter.getSelectedRecordings().get(0);
            Intent shareIntent = new Intent();
            shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            shareIntent.setType(data.mime);
            startActivity(Intent.createChooser(shareIntent, null));
        });
        binding.fabDelete.setOnClickListener(v -> displayAreYouSureDialog((dialog, which) -> {
            for (RecordingData data : mAdapter.getSelectedRecordings())
                mAdapter.removeRecording(data);
            animateMultiFab(false);
        }, mAdapter.getSelectedRecordings().size()));
        binding.fab.setOnClickListener(v -> {
            mAdapter.stopPlaying();
            NavHostFragment.findNavController(FirstFragment.this)
                    .navigate(R.id.action_FirstFragment_to_RecordFragment);
        });
    }

    @MainThread
    private void onDataFetched(List<RecordingData> dataList) {
        LinearLayoutManager manager = new LinearLayoutManager(requireContext());
        manager.setOrientation(LinearLayoutManager.VERTICAL);
        if (binding == null) return;
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemViewCacheSize(5);
        binding.recycler.setLayoutManager(manager);
        mAdapter = new RecyclerAdapter(dataList);
        mAdapter.setOnCheckedListener((selectedFiles) -> {
            final boolean selectionEmpty = selectedFiles.isEmpty();
            animateMultiFab(!selectionEmpty);
            binding.fabSelection.setImageResource(mAdapter.isFullySelected()
                    ? R.drawable.baseline_deselect_24 : R.drawable.baseline_select_all_24);
            final String tooltip = getString(mAdapter.isFullySelected()
                    ? R.string.tooltip_toggle_selection_none
                    : R.string.tooltip_toggle_selection_all);
            binding.fabSelection.setTooltipText(tooltip);
            binding.fabSelection.setContentDescription(tooltip);
            onBackCallback.setEnabled(!selectionEmpty);
        });
        binding.recycler.setAdapter(mAdapter);
        setSortProgressRunning(false);

        final String[] sorts = requireContext().getResources().getStringArray(R.array.sort_by_items);
        ArrayAdapter<String> menuAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, sorts);
        binding.sortMenu.setAdapter(menuAdapter);
        binding.filterText.setText("");

        // loading user prefs
        setSortProgressRunning(true);
        mExecutor.execute(() -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity());
            final int defaultSorting = Integer.parseInt(
                    prefs.getString(SettingsFragment.SORT_KEY, "1"));
            mRememberSort = defaultSorting == 1;
            int sortMode;
            switch (defaultSorting) {
                case 0 -> { // disabled
                    mSortSelection = ListView.INVALID_POSITION;
                    mUiHandler.post(() -> setSortProgressRunning(false));
                    return;
                }
                case 1 -> // remember last
                        sortMode = getPrefs().getInt(PREF_SORT, ListView.INVALID_POSITION);
                default -> // name, date, duration, size and type - ordered
                        sortMode = defaultSorting - 2;
            }
            mSortSelection = sortMode;
            if (sortMode == ListView.INVALID_POSITION) {
                mUiHandler.post(() -> setSortProgressRunning(false));
                return;
            }
            mReverseSort = getPrefs().getBoolean(PREF_REVERSE_SORT, false);
            // so it's synced with the adapter init onCreate
            // consider just making a synchronized block around a lock object
            mUiHandler.post(() -> {
                if (binding == null || mAdapter == null) return;
                binding.sortButton.setRotation(mReverseSort ? 0 : 180);
                binding.sortMenu.setText(sorts[sortMode], false);
                mAdapter.sortBy(sortMode);
            });
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        // must be done again to prevent item filtering
        final String[] sorts = requireContext().getResources().getStringArray(R.array.sort_by_items);
        ArrayAdapter<String> menuAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, sorts);
        binding.sortMenu.setAdapter(menuAdapter);
        binding.filterText.setText("");

        List<RecordingData> dataList = Collections.synchronizedList(new ArrayList<>());
        setSortProgressRunning(true);
        mExecutor.execute(() -> {
            File fileDir = requireContext().getFilesDir();
            File[] files = fileDir.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file.isDirectory()) continue;
                final String fileName = file.getName();
                final String ext = fileName.substring(fileName.lastIndexOf(".") + 1);
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null && (mime.contains("audio") || mime.contains("video"))) {
                    dataList.add(new RecordingData(file));
                }
            }
            mUiHandler.post(() -> onDataFetched(dataList));
        });
    }

    @Override
    public void onDestroyView() {
        if (mAdapter != null && mAdapter.mFilterHT != null && mAdapter.mFilterHT.isAlive()) {
            mAdapter.mFilterHT.quitSafely();
            mAdapter.mFilterHT = null;
        }
        super.onDestroyView();
        binding = null;
    }

    private static void animateRotation(final View v, final int targetRotation) {
        final ValueAnimator animator = new ValueAnimator();
        animator.setDuration(250);
        animator.setFloatValues(180 - targetRotation, targetRotation);
        animator.setInterpolator(new OvershootInterpolator());
        animator.addUpdateListener(animation ->
                v.setRotation((float) animation.getAnimatedValue()));
        animator.start();
    }

    private void animateMultiFab(final boolean in) {
        if (mMultiSelectFabs.get(0).getVisibility() == View.VISIBLE && in) return;
        if (mMultiSelectFabs.get(0).getVisibility() == View.INVISIBLE && !in) return;
        final int duration = 200;
        ArrayList<ObjectAnimator> animatorsList = new ArrayList<>();
        for (FloatingActionButton fab : mMultiSelectFabs) {
            ObjectAnimator animator = ObjectAnimator.ofFloat(fab, "translationY",
                    in ? ACTION_FAB_HEIGHT : 0, in ? 0 : ACTION_FAB_HEIGHT);
            if (in) animator.setInterpolator(new OvershootInterpolator());
            else animator.setInterpolator(new AnticipateInterpolator());
            animator.setDuration(duration);
            animatorsList.add(animator);
        }
        AnimatorSet animSet = new AnimatorSet();
        animSet.playTogether(animatorsList.toArray(new ObjectAnimator[0]));
        animSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                if (in) return;
                for (FloatingActionButton fab : mMultiSelectFabs) {
                    // always put the views back in place after the animation ends
                    fab.setVisibility(View.INVISIBLE); // make the fab invisible before:
                    fab.setTranslationY(ACTION_FAB_HEIGHT); // putting it back to its place
                }
            }

            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                if (!in) return;
                for (FloatingActionButton fab : mMultiSelectFabs) {
                    fab.setTranslationY(-ACTION_FAB_HEIGHT); // get the fab out of the screen before:
                    fab.setVisibility(View.VISIBLE); // making it visible
                }
            }
        });
        animSet.setDuration(duration);
        animSet.start();
    }

    private class RecyclerAdapter extends RecyclerView.Adapter<RecyclerAdapter.ViewHolder> {
        private final List<RecordingData> mRecordings;
        private final List<RecordingData> mOrigRecordings;
        private final List<RecordingData> mSelectedRecordings = new ArrayList<>();
        private final List<Long> mProgresses = new ArrayList<>();
        private RecordingData mPlayingRecording;
        private int mPlayingAdapterPos = RecyclerView.NO_POSITION;
        private boolean mIsPaused = false;
        private MediaPlayer mMediaPlayer;

        private OnCheckedListener mOnCheckedListener;
        public interface OnCheckedListener {
            void onChecked(List<RecordingData> selectedFiles);
        }

        private class ViewHolder extends RecyclerView.ViewHolder {
            public final TextInputEditText fileNameTxt;
            public final TextView timeTxt;
            public final TextView createTimeTxt;
            public final TextView typeTxt;
            public final TextView sizeTxt;
            public final ImageButton playButton;
            public final ImageButton selectButton;
            public final ImageButton saveButton;
            public final ImageButton shareButton;
            public final ImageButton deleteButton;
            public final Slider playProgress;
            public final MaterialCardView detailCard;

            public ViewHolder(View view) {
                super(view);
                fileNameTxt = view.findViewById(R.id.fileName);
                timeTxt = view.findViewById(R.id.timeTxt);
                createTimeTxt = view.findViewById(R.id.createTimeTxt);
                typeTxt = view.findViewById(R.id.typeTxt);
                sizeTxt = view.findViewById(R.id.sizeTxt);
                playButton = view.findViewById(R.id.playButton);
                selectButton = view.findViewById(R.id.selectButton);
                saveButton = view.findViewById(R.id.saveButton);
                shareButton = view.findViewById(R.id.shareButton);
                deleteButton = view.findViewById(R.id.deleteButton);
                playProgress = view.findViewById(R.id.playProgress);
                detailCard = view.findViewById(R.id.detailCard);
            }
        }

        public RecyclerAdapter(List<RecordingData> recordings) {
            mRecordings = recordings;
            mOrigRecordings = new ArrayList<>(mRecordings);
            for (RecordingData ignored : mRecordings)
                mProgresses.add(0L);
        }

        @NonNull
        @Override
        public RecyclerAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.card_layout, parent, false);
            return new RecyclerAdapter.ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerAdapter.ViewHolder holder, int position) {
            // file info
            final RecordingData record = mRecordings.get(position);
            holder.detailCard.setChecked(mSelectedRecordings.contains(record));
            holder.fileNameTxt.setText(record.name);
            holder.typeTxt.setText(record.ext.toUpperCase());
            holder.createTimeTxt.setText(record.getLastModStr());
            holder.timeTxt.setText(record.getTimeStr());
            holder.sizeTxt.setText(record.getSizeStr(requireContext()));
            holder.playProgress.setValue(0);
            holder.playProgress.setValueTo(record.duration > 0 ? record.duration : 1);

            final long progress = mProgresses.get(position); // restore set progress if any
            if (mPlayingRecording == record && mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                // this is currently playing / paused - reflect progress
                holder.playProgress.setValue(mMediaPlayer.getCurrentPosition());
                holder.playButton.setImageResource(R.drawable.baseline_pause_24);
                startPlayingAnimation(holder);
            } else {
                if (mPlayingRecording == record) stopPlayingAnimation();
                holder.playButton.setImageResource(R.drawable.baseline_play_arrow_24);
                if (progress > 0) {
                    final int sec = Math.round((float) progress / 1000f);
                    holder.playProgress.setValue(progress);
                    holder.timeTxt.setText(record.getTimeStr(sec / 60, sec % 60));
                }
            }

            // player actions

            holder.playButton.setOnClickListener(v -> {
                if (mPlayingRecording != null && mPlayingRecording != record && mMediaPlayer != null) {
                    // a different file is playing, stop it before
                    final ViewHolder pHolder = (ViewHolder) binding.recycler
                            .findViewHolderForAdapterPosition(mPlayingAdapterPos);
                    stopPlaying();
                    holder.timeTxt.setText(record.getTimeStr());
                    mIsPaused = false;
                    if (pHolder != null) {
                        pHolder.playButton.setImageResource(R.drawable.baseline_play_arrow_24);
                    }
                    // continue to - play this file
                } else if (mPlayingRecording != null && mMediaPlayer != null) {
                    // this is currently playing / paused
                    final int pos = Math.round(holder.playProgress.getValue());
                    mMediaPlayer.seekTo(pos);
                    mProgresses.set(holder.getAdapterPosition(), (long) pos);
                    if (mMediaPlayer.isPlaying()) {
                        stopPlayingAnimation();
                        mIsPaused = true;
                        mMediaPlayer.pause();
                        holder.playButton.setImageResource(R.drawable.baseline_play_arrow_24);
                        return;
                    }
                    mIsPaused = false;
                    mMediaPlayer.start();
                    holder.playButton.setImageResource(R.drawable.baseline_pause_24);
                    startPlayingAnimation(holder);
                    return;
                }
                // play this file
                mIsPaused = false;
                mPlayingRecording = record;
                mPlayingAdapterPos = holder.getAdapterPosition();
                mMediaPlayer = MediaPlayer.create(requireActivity(), Uri.fromFile(record.recording));
                mMediaPlayer.setLooping(false);
                mMediaPlayer.seekTo(Math.round(holder.playProgress.getValue()));
                mMediaPlayer.setOnCompletionListener(mp -> {
                    final int index = mRecordings.indexOf(mPlayingRecording);
                    stopPlaying();
                    if (index != RecyclerView.NO_POSITION) mProgresses.set(index, 0L);
                    holder.timeTxt.setText(record.getTimeStr());
                    holder.playButton.setImageResource(R.drawable.baseline_play_arrow_24);
                    animateSliderValue(holder.playProgress, 0);
                });
                holder.playButton.setImageResource(R.drawable.baseline_pause_24);
                mMediaPlayer.start();
                startPlayingAnimation(holder);
            });
            holder.playProgress.addOnChangeListener((slider, value, fromUser) -> {
                if (!fromUser) return;
                final int pos = Math.round(value / 1000);
                mProgresses.set(holder.getAdapterPosition(), (long) value);
                holder.timeTxt.setText(record.getTimeStr(pos / 60, pos % 60));
                if (mMediaPlayer == null || record != mPlayingRecording || mIsPaused) return;
                stopPlayingAnimation();
                slider.setValue(value);
                mMediaPlayer.seekTo(pos);
                startPlayingAnimation(holder);
            });
            holder.playProgress.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) {
                    if (mMediaPlayer == null || mPlayingRecording != record || !mMediaPlayer.isPlaying())
                        return;
                    if (mIsPaused) return;
                    stopPlayingAnimation();
                    mMediaPlayer.pause();
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    mProgresses.set(holder.getAdapterPosition(), (long) slider.getValue());
                    if (mMediaPlayer == null || mPlayingRecording != record || mMediaPlayer.isPlaying())
                        return;
                    if (mIsPaused) return;
                    mMediaPlayer.start();
                    mMediaPlayer.seekTo(Math.round(slider.getValue()));
                    startPlayingAnimation(holder);
                }
            });

            // actions
            holder.detailCard.setOnLongClickListener(v -> {
                checkItem(holder.detailCard, holder.getAdapterPosition());
                return true;
            });
            holder.detailCard.setOnClickListener(v -> {
                if (holder.detailCard.isChecked() || mSelectedRecordings.size() > 0)
                    checkItem(holder.detailCard, holder.getAdapterPosition());
            });
            holder.fileNameTxt.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                holder.fileNameTxt.clearFocus();
                return false;
            });
            holder.fileNameTxt.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                final String oldName = record.name;
                final Editable editable = ((TextInputEditText) v).getText();
                if (editable == null) return;
                final String newName = editable.toString();
                if (oldName.equals(newName)) return;
                final File newFile = new File(record.recording.getPath().replace(oldName, newName));
                if (newName.isEmpty() || !record.recording.renameTo(newFile)) return;
                ((TextInputEditText) v).setText(newName);
                mRecordings.set(holder.getAdapterPosition(), new RecordingData(newFile));
                notifyItemRangeChanged(holder.getAdapterPosition(), 1);
            });
            holder.selectButton.setOnClickListener(v -> checkItem(holder.detailCard, holder.getAdapterPosition()));
            holder.saveButton.setOnClickListener(v -> {
                if (!mSelectedRecordings.isEmpty()) return;
                mSavingQueue = new LinkedList<>();
                mSavingQueue.add(record);
                mResultLauncher.launch(record.name + "." + record.ext);
            });
            holder.shareButton.setOnClickListener(v -> {
                if (!mSelectedRecordings.isEmpty()) return;
                Uri fileUri = null;
                try {
                    fileUri = FileProvider.getUriForFile(requireActivity(),
                            "com.idoybh.yasr.fileprovider", record.recording);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
                if (fileUri == null) return;
                if (record.mime == null) return;
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                shareIntent.setType(record.mime);
                startActivity(Intent.createChooser(shareIntent, null));
            });
            holder.deleteButton.setOnClickListener(v -> {
                if (!mSelectedRecordings.isEmpty()) return;
                displayAreYouSureDialog((dialog, which) -> removeRecording(record));
            });
        }

        private AnimateSliderTask mAnimateSliderTask;
        private class AnimateSliderTask extends TimerTask {
            private Timer timer;
            private static final int DELAY_MS = 20;
            private final ViewHolder holder;
            private final Handler handler;

            public AnimateSliderTask(ViewHolder holder) {
                this.holder = holder;
                handler = new Handler(requireActivity().getMainLooper());
            }

            public synchronized void start() {
                if (timer != null) {
                    timer.cancel();
                    timer.purge();
                    handler.removeCallbacksAndMessages(null);
                }
                holder.setIsRecyclable(false);
                timer = new Timer();
                timer.scheduleAtFixedRate(this, 0, DELAY_MS);
            }

            public synchronized void stop() {
                if (timer == null) return;
                timer.cancel();
                timer.purge();
                timer = null;
                holder.setIsRecyclable(true);
                handler.removeCallbacksAndMessages(null);
                final int pos = mMediaPlayer.getCurrentPosition();
                mProgresses.set(holder.getAdapterPosition(), (long) pos);
                animateSliderValue(holder.playProgress, mMediaPlayer.getCurrentPosition());
                this.cancel();
            }

            @Override
            public synchronized void run() {
                handler.removeCallbacksAndMessages(null);
                handler.post(() -> {
                    if (mIsPaused || mMediaPlayer == null) return;
                    int pos = mMediaPlayer.getCurrentPosition();
                    final int index = holder.getAdapterPosition();
                    if (index == RecyclerView.NO_POSITION) return;
                    mProgresses.set(index, (long) pos);
                    animateSliderValue(holder.playProgress, pos);
                    pos /= 1000;
                    final int duration = Math.round(holder.playProgress.getValueTo() / 1000);
                    String timeText = String.format(Locale.ENGLISH, "%02d:%02d/%02d:%02d",
                            pos / 60, pos % 60, duration / 60, duration % 60);
                    holder.timeTxt.setText(timeText);
                });
            }
        }

        private synchronized void stopPlaying() {
            stopPlayingAnimation();
            mPlayingRecording = null;
            mIsPaused = false;
            if (mMediaPlayer == null) return;
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        private synchronized void stopPlayingAnimation() {
            if (mAnimateSliderTask == null) return;
            mAnimateSliderTask.stop();
            mAnimateSliderTask = null;
        }

        private synchronized void startPlayingAnimation(ViewHolder holder) {
            stopPlayingAnimation();
            mAnimateSliderTask = new AnimateSliderTask(holder);
            mAnimateSliderTask.start();
        }

        private static synchronized void animateSliderValue(Slider slider, float value) {
            final ValueAnimator animator = ValueAnimator.ofFloat(slider.getValue(), value);
            final float distance = Math.abs(slider.getValue() - value);
            final boolean large = distance > slider.getValueTo() / 2f && slider.getValueTo() > 2f;
            animator.setDuration(large ? 500 : 50);
            animator.setFloatValues(slider.getValue(), value);
            animator.addUpdateListener(animation -> {
                final float val = (float) animation.getAnimatedValue();
                try {
                    slider.setValue(val);
                } catch (Exception e) { animation.cancel(); }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    try {
                        slider.setValue(value);
                    } catch (Exception e) { animation.cancel(); }
                }
            });
            animator.start();
        }

        @Override
        public int getItemCount() {
            return mRecordings.size();
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            final int position = holder.getAdapterPosition();
            if (position > 0 && position < mRecordings.size() - 1
                    && mPlayingRecording == mRecordings.get(position)) {
                stopPlayingAnimation();
            }
            super.onViewRecycled(holder);
        }

        @Override
        public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
            holder.playProgress.setValue(0);
            super.onViewAttachedToWindow(holder);
            final int position = holder.getAdapterPosition();
            final RecordingData data = mRecordings.get(position);
            holder.playProgress.setValueTo(data.duration);
            holder.playProgress.setValue(mProgresses.get(position));
            if (mPlayingRecording == data && mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                startPlayingAnimation(holder);
            }
            if (mSelectedRecordings.contains(data)) {
                holder.detailCard.setChecked(true);
                return;
            }
            holder.detailCard.setChecked(false);
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
            holder.playProgress.setValue(0);
            final int position = holder.getAdapterPosition();
            if (position > 0 && position < mRecordings.size() - 1
                    && mPlayingRecording == mRecordings.get(position)) {
                stopPlayingAnimation();
            }
            super.onViewDetachedFromWindow(holder);
        }

        private void checkItem(MaterialCardView detailCard, int position) {
            final boolean isChecked = !detailCard.isChecked();
            final RecordingData file = mRecordings.get(position);
            if (isChecked) mSelectedRecordings.add(file);
            else mSelectedRecordings.remove(file);
            detailCard.setChecked(isChecked);
            if (mOnCheckedListener == null) return;
            mOnCheckedListener.onChecked(mSelectedRecordings);
        }

        public void setOnCheckedListener(OnCheckedListener listener) {
            mOnCheckedListener = listener;
        }

        public List<RecordingData> getSelectedRecordings() {
            return mSelectedRecordings;
        }

        public void removeRecording(RecordingData data) {
            if (!data.recording.delete()) return;
            final int position = mRecordings.indexOf(data);
            mRecordings.remove(data);
            if (mPlayingRecording == data && mMediaPlayer != null)
                stopPlaying();
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, mRecordings.size() - position);
        }

        public boolean isFullySelected() {
            return mSelectedRecordings.size() == mRecordings.size();
        }

        public void clearSelection() {
            recursiveSelection(binding.recycler, false);
            mSelectedRecordings.clear();
            mOnCheckedListener.onChecked(mSelectedRecordings);
        }

        public void selectAll() {
            recursiveSelection(binding.recycler, true);
            mSelectedRecordings.clear();
            mSelectedRecordings.addAll(mRecordings);
            mOnCheckedListener.onChecked(mSelectedRecordings);
        }

        private void recursiveSelection(ViewGroup start, boolean select) {
            for (int i = 0; i < start.getChildCount(); i++) {
                final View view = start.getChildAt(i);
                if (view instanceof ViewGroup)
                    recursiveSelection((ViewGroup) view, select);
                if (view instanceof MaterialCardView)
                    ((MaterialCardView) view).setChecked(select);
            }
        }

        public void sortBy(final int sort) {
            setSortProgressRunning(true);
            mExecutor.execute(() -> {
                switch (sort) {
                    case SORT_BY_NAME -> mRecordings.sort((o1, o2) -> mReverseSort
                            ? o2.name.compareTo(o1.name)
                            : o1.name.compareTo(o2.name));
                    case SORT_BY_DATE -> mRecordings.sort((o1, o2) -> mReverseSort
                            ? o2.lastModified.compareTo(o1.lastModified)
                            : o1.lastModified.compareTo(o2.lastModified));
                    case SORT_BY_DURATION -> mRecordings.sort((o1, o2) -> mReverseSort
                            ? Long.compare(o2.duration, o1.duration)
                            : Long.compare(o1.duration, o2.duration));
                    case SORT_BY_SIZE -> mRecordings.sort((o1, o2) -> mReverseSort
                            ? Long.compare(o2.size, o1.size)
                            : Long.compare(o1.size, o2.size));
                    case SORT_BY_TYPE -> mRecordings.sort((o1, o2) -> mReverseSort
                            ? o2.ext.compareTo(o1.ext)
                            : o1.ext.compareTo(o2.ext));
                }
                mUiHandler.post(() -> {
                    restart();
                    setSortProgressRunning(false);
                });
            });
        }

        private HandlerThread mFilterHT;
        private Handler mFilterHandler;
        private Handler mFilterUIHandler;
        public void filter(final String filter) {
            if (mFilterHT == null) {
                mFilterHT = new HandlerThread("Filter HandlerThread");
                mFilterHT.start();
                mFilterHandler = new Handler(mFilterHT.getLooper());
                mFilterUIHandler = new Handler(Looper.getMainLooper());
            }
            setSortProgressRunning(true);
            mFilterHandler.removeCallbacksAndMessages(null);
            mFilterHandler.postAtFrontOfQueue(() -> {
                mRecordings.clear();
                if (filter == null || filter.isEmpty()) {
                    mRecordings.addAll(mOrigRecordings);
                } else {
                    for (RecordingData record : mOrigRecordings) {
                        final String fnl = record.name.toLowerCase(Locale.ENGLISH);
                        if (fnl.contains(filter.toLowerCase(Locale.ENGLISH)))
                            mRecordings.add(record);
                    }
                }
                mFilterUIHandler.removeCallbacksAndMessages(null);
                mFilterUIHandler.postAtFrontOfQueue(() -> {
                    if (mSortSelection != ListView.INVALID_POSITION) {
                        sortBy(mSortSelection);
                        return; // already calls following
                    }
                    restart();
                    setSortProgressRunning(false);
                });
            });
        }

        @SuppressLint("NotifyDataSetChanged")
        private void restart() {
            clearSelection();
            mProgresses.clear();
            for (RecordingData ignored : mRecordings) mProgresses.add(0L);
            binding.recycler.setAdapter(null);
            binding.recycler.setLayoutManager(null);
            binding.recycler.setAdapter(this);
            LinearLayoutManager manager = new LinearLayoutManager(requireContext());
            manager.setOrientation(LinearLayoutManager.VERTICAL);
            binding.recycler.setLayoutManager(manager);
            notifyDataSetChanged();
        }
    }

    private static class RecordingData {
        private static final long GB = 1000000000;
        private static final long MB = 1000000;
        private static final long KB = 1000;

        final File recording;
        String name;
        String ext;
        String mime;
        long size;
        long duration;
        Calendar lastModified = Calendar.getInstance();

        public RecordingData(File recording) {
            this.recording = recording;
            this.size = recording.length();
            final String fullName = recording.getName();
            final int dotPos = fullName.lastIndexOf(".");
            name = fullName.substring(0, dotPos);
            ext = fullName.substring(dotPos + 1);
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            fetchLastModified();
            fetchDuration();
        }

        private void fetchLastModified() {
            lastModified.setTimeInMillis(recording.lastModified());
        }

        private void fetchDuration() {
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(recording.getPath());
                duration = Long.parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION));
            } catch (Exception e) {
                duration = 1000;
            }
        }

        public String getSizeStr(Context ctx) {
            String sizeText = String.valueOf(size); // bytes
            if (size > GB) {
                sizeText = size / GB + " " + ctx.getString(R.string.unit_gb);
            } else if (size > MB) {
                sizeText = size / MB + " " + ctx.getString(R.string.unit_mb);
            } else if (size > KB) {
                sizeText = size / 1000 + " " + ctx.getString(R.string.unit_kb);
            }
            return sizeText;
        }

        public String getLastModStr() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd kk:mm", Locale.getDefault());
            return sdf.format(lastModified.getTime());
        }

        public String getTimeStr() {
            return getTimeStr(0, 0);
        }

        public String getTimeStr(int m, int s) {
            final int dur = Math.round(duration / 1000f);
            if (dur == 0) return "ERROR";
            String timeStr = "%02d:%02d/%02d:%02d";
            return String.format(Locale.ENGLISH, timeStr, m, s, dur / 60, dur % 60);
        }
    }

    private void displayAreYouSureDialog(DialogInterface.OnClickListener listener) {
        displayAreYouSureDialog(listener, 0);
    }

    private void displayAreYouSureDialog(DialogInterface.OnClickListener listener, int num) {
        String msg = getString(R.string.are_you_sure_msg);
        if (num > 0) {
            msg = String.format(getString(R.string.are_you_sure_msg_mul), num) + msg;
        }
        displayAreYouSureDialog(requireContext(), msg, listener);
    }

    public static void displayAreYouSureDialog(Context context, String msg,
                                               DialogInterface.OnClickListener listener) {
        (new MaterialAlertDialogBuilder(context)
                .setMessage(msg)
                .setPositiveButton(R.string.button_yes, listener)
                .setNegativeButton(R.string.button_no, (dialog, which) -> {})
        ).show();
    }

    private void setSortProgressRunning(final boolean running) {
        if (binding == null) return;
        binding.fab.setEnabled(!running);
        binding.fab.bringToFront();
        binding.sortMenu.setEnabled(!running);
        binding.sortButton.setEnabled(!running);
        mProgressIndicator.setIndeterminate(true);
        mProgressIndicator.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    private SharedPreferences getPrefs() {
        if (mSharedPrefs != null) return mSharedPrefs;
        mSharedPrefs = requireContext().getSharedPreferences(
                RecordFragment.SHARED_PREF_FILE, Context.MODE_PRIVATE);
        return mSharedPrefs;
    }

    private Queue<RecordingData> mSavingQueue;

    private void saveFile(Uri uri) {
        if (uri == null) return;
        if (mSavingQueue == null || mSavingQueue.peek() == null) return;
        // it is very unclear why android made this so complicated instead of just returning
        // a sane path in the uri... but it is what it is I guess....
        try (ParcelFileDescriptor descriptor = requireContext().getContentResolver()
                .openFileDescriptor(uri, "rw")) {
            final File sourceFile = Objects.requireNonNull(mSavingQueue.poll()).recording;
            if (mSavingQueue.size() == 0) mSavingQueue = null;
            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = new FileOutputStream(descriptor.getFileDescriptor())) {
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0)
                    out.write(buf, 0, len);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (mSavingQueue == null) return;
        final RecordingData next = mSavingQueue.peek();
        if (next == null) return;
        mResultLauncher.launch(next.name + "." + next.ext);
    }

    private final ActivityResultLauncher<String> mResultLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("audio/*"), this::saveFile);
}
