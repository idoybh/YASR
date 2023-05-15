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
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class FirstFragment extends Fragment {
    private static final int CREATE_FILE_CODE = 0x01;
    private static final int SORT_BY_NAME = 0;
    private static final int SORT_BY_DATE = 1;
    private static final int SORT_BY_DURATION = 2;
    private static final int SORT_BY_SIZE = 3;
    private static final int SORT_BY_TYPE = 4;
    private static final long GB = 1000000000;
    private static final long MB = 1000000;
    private static final long KB = 1000;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private FragmentFirstBinding binding;
    private RecyclerAdapter mAdapter;
    private List<FloatingActionButton> mMultiSelectFabs;
    private int mSortSelection = ListView.INVALID_POSITION;
    private boolean mReverseSort = false;
    private boolean mDidCreate = false;
    private volatile boolean mWaitingForResults = false;

    // to save animation calculations and do only once
    private float ACTION_FAB_HEIGHT;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mMultiSelectFabs = new ArrayList<>(List.of(
                binding.fabSelection,
                binding.fabSave,
                binding.fabShare,
                binding.fabDelete
        ));

        final FloatingActionButton sampleFab = mMultiSelectFabs.get(0);
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)
                sampleFab.getLayoutParams();
        int[] position = new int[2];
        sampleFab.getLocationInWindow(position);
        ACTION_FAB_HEIGHT = sampleFab.getMeasuredHeight() + params.bottomMargin;
        ACTION_FAB_HEIGHT *= 10; // more bounce

        mDidCreate = true;
        new Thread(() -> {
            mUiHandler.post(() -> setSortProgressRunning(true));
            List<File> recordings = new ArrayList<>();
            File fileDir = requireContext().getFilesDir();
            File[] files = fileDir.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file.isDirectory()) continue;
                final String fileName = file.getName();
                final String ext = fileName.substring(fileName.lastIndexOf(".") + 1);
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null && (mime.contains("audio") || mime.contains("video"))) {
                    recordings.add(file);
                }
            }
            LinearLayoutManager manager = new LinearLayoutManager(requireContext());
            manager.setOrientation(LinearLayoutManager.VERTICAL);
            mUiHandler.removeCallbacksAndMessages(null);
            mUiHandler.post(() -> {
                if (binding == null) return;
                binding.recycler.setLayoutManager(manager);
                mAdapter = new RecyclerAdapter(recordings);
                mAdapter.setOnCheckedListener((selectedFiles) -> {
                    final boolean selectionEmpty = selectedFiles.isEmpty();
                    animateMultiFab(!selectionEmpty);
                    binding.fabSelection.setImageResource(mAdapter.isFullySelected()
                            ? R.drawable.baseline_deselect_24 : R.drawable.baseline_select_all_24);
                });
                binding.recycler.setAdapter(mAdapter);
                setSortProgressRunning(false);
            });
        }).start();

        // sort and filter
        binding.filterText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            final Editable editable = ((TextInputEditText) v).getText();
            if (editable == null) return;
            final String filter = editable.toString();

        });
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
        });
        binding.sortButton.setOnClickListener(v -> {
            mReverseSort = !mReverseSort;
            v.setRotation(mReverseSort ? 0 : 180);
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
        binding.fabSave.setOnClickListener(v -> new Thread(() -> {
            for (File file : mAdapter.getSelectedRecordings()) {
                final String name = file.getName();
                final String ext = name.substring(name.lastIndexOf(".") + 1);
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                mAdapter.setSavingRecording(file);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mime);
                intent.putExtra(Intent.EXTRA_TITLE, name);
                mWaitingForResults = true;
                //noinspection deprecation
                startActivityForResult(intent, CREATE_FILE_CODE);
                while (mWaitingForResults) Thread.onSpinWait();
            }
        }).start());
        binding.fabShare.setOnClickListener(v -> {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File file : mAdapter.getSelectedRecordings()) {
                Uri fileUri = null;
                try {
                    fileUri = FileProvider.getUriForFile(requireActivity(),
                            "com.idoybh.yasr.fileprovider", file);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
                if (fileUri == null) continue;
                uris.add(fileUri);
            }
            final String name = mAdapter.getSelectedRecordings().get(0).getName();
            final String ext = name.substring(name.lastIndexOf(".") + 1);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            Intent shareIntent = new Intent();
            shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            shareIntent.setType(mime);
            startActivity(Intent.createChooser(shareIntent, null));
        });
        binding.fabDelete.setOnClickListener(v -> displayAreYouSureDialog((dialog, which) -> {
            for (File file : mAdapter.getSelectedRecordings())
                    mAdapter.removeRecording(file);
            animateMultiFab(false);
        }, mAdapter.getSelectedRecordings().size()));
        binding.fab.setOnClickListener(v -> {
            if (mAdapter.mPlayingHolder != null)
                mAdapter.mPlayingHolder.stopPlaying();
            NavHostFragment.findNavController(FirstFragment.this)
                    .navigate(R.id.action_FirstFragment_to_RecordFragment);
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

        if (mDidCreate) {
            mDidCreate = false;
            return;
        }
        new Thread(() -> {
            File fileDir = requireContext().getFilesDir();
            File[] files = fileDir.listFiles();
            if (files == null) return;
            if (files.length == mAdapter.mRecordings.size()) return;
            mUiHandler.post(() -> setSortProgressRunning(true));
            int added = 0;
            for (File file : files) {
                if (file.isDirectory()) continue;
                if (mAdapter.mRecordings.contains(file)) continue;
                final String fileName = file.getName();
                final String ext = fileName.substring(fileName.lastIndexOf(".") + 1);
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null && (mime.contains("audio") || mime.contains("video"))) {
                    mAdapter.mRecordings.add(file);
                    added++;
                }
            }
            final int addedFinal = added;
            mUiHandler.removeCallbacksAndMessages(null);
            mUiHandler.post(() -> {
                if (addedFinal == 0) {
                    setSortProgressRunning(false);
                    return;
                }
                mAdapter.notifyItemRangeChanged(
                        mAdapter.mRecordings.size() - addedFinal - 1, addedFinal);
                setSortProgressRunning(false);
            });
        }).start();
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

    @SuppressWarnings("deprecation")
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mWaitingForResults = false;
        if (requestCode != CREATE_FILE_CODE || resultCode != Activity.RESULT_OK)
            return;
        Uri uri = null;
        if (data != null) uri = data.getData();
        if (uri == null) return;
        // it is very unclear why android made this so complicated instead of just returning
        // a sane path in the uri... but it is what it is I guess....
        try (ParcelFileDescriptor descriptor = requireContext().getContentResolver()
                .openFileDescriptor(uri, "rw")) {
            final File sourceFile = mAdapter.getSavingFile();
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
        private final List<File> mOrigRecordings;
        private final List<File> mRecordings;
        private final List<File> mSelectedRecordings = new ArrayList<>();
        private File mSavingRecording;
        private File mPlayingRecording;
        private RecyclerAdapter.ViewHolder mPlayingHolder;
        private MediaPlayer mMediaPlayer;

        private OnCheckedListener mOnCheckedListener;
        public interface OnCheckedListener {
            void onChecked(List<File> selectedFiles);
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
            public final CircularProgressIndicator loadingIndicator;
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
                loadingIndicator = view.findViewById(R.id.loadingIndicator);
                detailCard = view.findViewById(R.id.detailCard);
            }

            public void stopPlaying() {
                mAnimateSliderTask.stop();
                mAnimateSliderTask = null;
                mPlayingRecording = null;
                mPlayingHolder = null;
                mMediaPlayer.stop();
                mMediaPlayer.release();
                mMediaPlayer = null;
                playButton.setImageResource(R.drawable.baseline_play_arrow_24);
            }
        }

        public RecyclerAdapter(List<File> recordings) {
            mRecordings = recordings;
            mOrigRecordings = new ArrayList<>(mRecordings);
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
            final File file = mRecordings.get(position);
            final String name = file.getName();
            final int dotPos = name.lastIndexOf(".");
            final String ext = name.substring(dotPos + 1);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            holder.detailCard.setChecked(mSelectedRecordings.contains(file));
            holder.fileNameTxt.setText(name.substring(0, dotPos));
            holder.typeTxt.setText(name.substring(dotPos + 1).toUpperCase());

            // post heavy operations on a thread and indicate we're still loading this view
            // otherwise, because of the nature of a recycler view, it'll hand during fast scrolling
            // what follows is essentially MediaMetadataRetriever for duration and all its dependencies
            // and some other things that could be added simply
            // in tests the user barely gets to see the progress bar, but scrolling latency is reduced by much
            holder.loadingIndicator.setIndeterminate(true);
            new Thread(() -> {
                final Calendar lastModified = Calendar.getInstance();
                lastModified.setTimeInMillis(file.lastModified());
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd kk:mm", Locale.getDefault());
                final long size = file.length();
                String sizeText = String.valueOf(size); // bytes
                if (size > GB) {
                    sizeText = size / GB + " " + getString(R.string.unit_gb);
                } else if (size > MB) {
                    sizeText = size / MB + " " + getString(R.string.unit_mb);
                } else if (size > KB) {
                    sizeText = size / 1000 + " " + getString(R.string.unit_kb);
                }
                String timeStr = "%02d:%02d/%02d:%02d";
                long duration;
                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                    retriever.setDataSource(file.getPath());
                    duration = Long.parseLong(retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION));
                    final int durationSub = Math.round((float) duration / 1000);
                    timeStr = String.format(Locale.ENGLISH, timeStr, 0, 0,
                            durationSub / 60, durationSub % 60);
                } catch (Exception e) {
                    duration = -1;
                    timeStr = "ERROR";
                }
                final String finalTimeStr = timeStr;
                final String finalSizeText = sizeText;
                final long finalDuration = duration;
                final int finalDurationInt = Math.round((float) duration / 1000);

                requireActivity().runOnUiThread(() -> {
                    holder.createTimeTxt.setText(sdf.format(lastModified.getTime()));
                    holder.timeTxt.setText(finalTimeStr);
                    holder.sizeTxt.setText(finalSizeText);
                    if (finalDuration > 0)
                        holder.playProgress.setValueTo(finalDuration);

                    // player actions
                    holder.playButton.setOnClickListener(v -> {
                        if (mPlayingRecording != null && mPlayingRecording != file) {
                            // a different file is playing, stop it before
                            mPlayingHolder.stopPlaying();
                            String timeText = String.format(Locale.ENGLISH, "%02d:%02d/%02d:%02d",
                                    0, 0, finalDurationInt / 60, finalDurationInt % 60);
                            holder.timeTxt.setText(timeText);
                        } else if (mPlayingRecording != null && mMediaPlayer != null) {
                            // this is currently playing / paused
                            mMediaPlayer.seekTo((int) holder.playProgress.getValue());
                            if (mMediaPlayer.isPlaying()) {
                                mMediaPlayer.pause();
                                holder.playButton.setImageResource(R.drawable.baseline_play_arrow_24);
                                return;
                            }
                            mMediaPlayer.start();
                            holder.playButton.setImageResource(R.drawable.baseline_pause_24);
                            return;
                        }
                        // play this file
                        mPlayingRecording = file;
                        mPlayingHolder = holder;
                        mMediaPlayer = MediaPlayer.create(requireActivity(), Uri.fromFile(file));
                        mMediaPlayer.setLooping(false);
                        mMediaPlayer.seekTo((int) holder.playProgress.getValue());
                        mMediaPlayer.setOnCompletionListener(mp -> {
                            holder.stopPlaying();
                            String timeText = "%02d:%02d/%02d:%02d";
                            timeText = String.format(Locale.ENGLISH, timeText, 0, 0,
                                    finalDurationInt / 60, finalDurationInt % 60);
                            holder.timeTxt.setText(timeText);
                            animateSliderValue(holder.playProgress, 0);
                        });
                        holder.playButton.setImageResource(R.drawable.baseline_pause_24);
                        mMediaPlayer.start();
                        if (mAnimateSliderTask != null) mAnimateSliderTask.stop();
                        mAnimateSliderTask = new AnimateSliderTask(mPlayingRecording, holder.playProgress,
                                holder.timeTxt, finalDurationInt);
                        mAnimateSliderTask.start();
                    });
                    holder.playProgress.addOnChangeListener((slider, value, fromUser) -> {
                        if (!fromUser) return;
                        final int pos = (int) value;
                        String timeText = String.format(Locale.ENGLISH, "%02d:%02d/%02d:%02d",
                                (pos / 1000) / 60, (pos / 1000) % 60,
                                (finalDuration / 1000) / 60, (finalDuration / 1000) % 60);
                        holder.timeTxt.setText(timeText);
                        if (mMediaPlayer == null || file != mPlayingRecording) return;
                        mMediaPlayer.seekTo(pos);
                    });
                    holder.playProgress.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                        @Override
                        public void onStartTrackingTouch(@NonNull Slider slider) {
                            if (mMediaPlayer == null || mPlayingRecording != file || !mMediaPlayer.isPlaying())
                                return;
                            mMediaPlayer.pause();
                            mAnimateSliderTask.stop();
                            mAnimateSliderTask = null;
                        }

                        @Override
                        public void onStopTrackingTouch(@NonNull Slider slider) {
                            if (mMediaPlayer == null || mPlayingRecording != file || mMediaPlayer.isPlaying())
                                return;
                            mMediaPlayer.start();
                            if (mAnimateSliderTask != null) return;
                            mAnimateSliderTask = new AnimateSliderTask(mPlayingRecording, holder.playProgress,
                                    holder.timeTxt, finalDurationInt);
                            mAnimateSliderTask.start();
                        }
                    });
                    holder.loadingIndicator.setIndeterminate(false);
                }); // </runOnUiThread>
            }).start(); // </new Thread()>

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
                final String oldName = name.substring(0, name.lastIndexOf("."));
                final Editable editable = ((TextInputEditText) v).getText();
                if (editable == null) return;
                final String newName = editable.toString();
                if (oldName.equals(newName)) return;
                final File newFile = new File(file.getPath().replace(oldName, newName));
                if (newName.isEmpty() || !file.renameTo(newFile)) return;
                ((TextInputEditText) v).setText(newName);
                mRecordings.set(holder.getAdapterPosition(), newFile);
                notifyItemRangeChanged(holder.getAdapterPosition(), 1);
            });
            holder.selectButton.setOnClickListener(v -> checkItem(holder.detailCard, holder.getAdapterPosition()));
            holder.saveButton.setOnClickListener(v -> {
                if (!mSelectedRecordings.isEmpty()) return;
                mSavingRecording = file;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mime);
                intent.putExtra(Intent.EXTRA_TITLE, name);
                //noinspection deprecation
                startActivityForResult(intent, CREATE_FILE_CODE);
            });
            holder.shareButton.setOnClickListener(v -> {
                if (!mSelectedRecordings.isEmpty()) return;
                Uri fileUri = null;
                try {
                    fileUri = FileProvider.getUriForFile(requireActivity(),
                            "com.idoybh.yasr.fileprovider", file);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
                if (fileUri == null) return;
                if (mime == null) return;
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                shareIntent.setType(mime);
                startActivity(Intent.createChooser(shareIntent, null));
            });
            holder.deleteButton.setOnClickListener(v -> {
                if (!mSelectedRecordings.isEmpty()) return;
                displayAreYouSureDialog((dialog, which) -> removeRecording(file));
            });
        }

        private AnimateSliderTask mAnimateSliderTask;
        private class AnimateSliderTask extends TimerTask {
            private Timer timer;
            private static final int DELAY_MS = 50;
            private final File file;
            private final Slider slider;
            private final TextView text;
            private final Handler handler;
            private final int duration;

            public AnimateSliderTask(File file, Slider slider, TextView text, int duration) {
                this.file = file;
                this.slider = slider;
                this.text = text;
                this.duration = duration;
                handler = new Handler(requireActivity().getMainLooper());
            }

            public void start() {
                if (timer != null) return;
                timer = new Timer();
                timer.scheduleAtFixedRate(this, 0, DELAY_MS);
            }

            public void stop() {
                if (timer == null) return;
                timer.cancel();
                timer.purge();
                timer = null;
                handler.removeCallbacksAndMessages(null);
                this.cancel();
            }

            @Override
            public void run() {
                handler.postAtFrontOfQueue(() -> {
                    final boolean plays = mPlayingRecording != null &&
                            mPlayingRecording == file && mMediaPlayer != null;
                    try {
                        if (plays && mMediaPlayer.isPlaying()) {
                            int pos = mMediaPlayer.getCurrentPosition();
                            animateSliderValue(slider, pos);
                            pos /= 1000;
                            String timeText = String.format(Locale.ENGLISH, "%02d:%02d/%02d:%02d",
                                    pos / 60, pos % 60, duration / 60, duration % 60);
                            text.setText(timeText);
                        }
                        if (plays) {
                            // while this file still plays
                            handler.removeCallbacksAndMessages(null);
                            return;
                        }
                        stop();
                    } catch (Exception ignored) {
                    }
                });
            }
        }

        private static void animateSliderValue(Slider slider, float value) {
            final ValueAnimator animator = ValueAnimator.ofFloat(slider.getValue(), value);
            final float width = Math.abs(slider.getValue() - value);
            animator.setDuration(width > slider.getValueTo() / 4f ? 500 : 100);
            animator.setFloatValues(slider.getValue(), value);
            animator.addUpdateListener(animation -> {
                final float val = (float) animation.getAnimatedValue();
                slider.setValue(val);
            });
            animator.start();
        }

        @Override
        public int getItemCount() {
            return mRecordings.size();
        }

        @Override
        public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
            if (mSelectedRecordings.contains(mRecordings.get(holder.getLayoutPosition()))) {
                holder.detailCard.setChecked(true);
                return;
            }
            holder.detailCard.setChecked(false);
        }

        private void checkItem(MaterialCardView detailCard, int position) {
            final boolean isChecked = !detailCard.isChecked();
            final File file = mRecordings.get(position);
            if (isChecked) mSelectedRecordings.add(file);
            else mSelectedRecordings.remove(file);
            detailCard.setChecked(isChecked);
            if (mOnCheckedListener == null) return;
            mOnCheckedListener.onChecked(mSelectedRecordings);
        }

        public void setOnCheckedListener(OnCheckedListener listener) {
            mOnCheckedListener = listener;
        }

        public List<File> getSelectedRecordings() {
            return mSelectedRecordings;
        }

        public void setSavingRecording(File file) {
            mSavingRecording = file;
        }

        public File getSavingFile() {
            return mSavingRecording;
        }

        public void removeRecording(File file) {
            if (!file.delete()) return;
            final int position = mRecordings.indexOf(file);
            mRecordings.remove(file);
            if (mPlayingRecording == file && mMediaPlayer != null)
                mPlayingHolder.stopPlaying();
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, mRecordings.size());
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

        @SuppressLint("NotifyDataSetChanged")
        public void sortBy(final int sort) {
            setSortProgressRunning(true);
            new Thread(() -> {
                switch (sort) {
                    case SORT_BY_NAME -> mRecordings.sort(mReverseSort
                            ? Comparator.comparing(File::getName).reversed()
                            : Comparator.comparing(File::getName));
                    case SORT_BY_DATE -> mRecordings.sort(mReverseSort
                            ? Comparator.comparing(File::lastModified).reversed()
                            : Comparator.comparing(File::lastModified));
                    case SORT_BY_DURATION -> mRecordings.sort((o1, o2) -> {
                        long duration1, duration2;
                        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                            retriever.setDataSource(o1.getPath());
                            duration1 = Long.parseLong(retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION));
                        } catch (Exception e) {
                            return mReverseSort ? -1 : 1;
                        }
                        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                            retriever.setDataSource(o2.getPath());
                            duration2 = Long.parseLong(retriever.extractMetadata(
                                    MediaMetadataRetriever.METADATA_KEY_DURATION));
                        } catch (Exception e) {
                            return mReverseSort ? 1 : -1;
                        }
                        return mReverseSort
                                ? Long.compare(duration2, duration1)
                                : Long.compare(duration1, duration2);
                    });
                    case SORT_BY_SIZE -> mRecordings.sort(mReverseSort
                            ? Comparator.comparing(File::length).reversed()
                            : Comparator.comparing(File::length));
                    case SORT_BY_TYPE -> mRecordings.sort((o1, o2) -> {
                        String name1 = o1.getName();
                        String ext1 = name1.substring(name1.lastIndexOf(".") + 1);
                        String name2 = o2.getName();
                        String ext2 = name2.substring(name2.lastIndexOf(".") + 1);
                        return mReverseSort ? ext2.compareTo(ext1) : ext1.compareTo(ext2);
                    });
                }
                requireActivity().runOnUiThread(() -> {
                    clearSelection();
                    notifyDataSetChanged();
                    setSortProgressRunning(false);
                });
            }).start();
        }

        private HandlerThread mFilterHT;
        private Handler mFilterHandler;
        private Handler mFilterUIHandler;
        @SuppressLint("NotifyDataSetChanged")
        public void filter(final String filter) {
            if (mFilterHT == null) {
                mFilterHT = new HandlerThread("Filter HandlerThread");
                mFilterHT.start();
                mFilterHandler = new Handler(mFilterHT.getLooper());
                mFilterUIHandler = new Handler(Looper.getMainLooper());
            }
            setSortProgressRunning(true);
            mFilterHandler.post(() -> {
                mRecordings.clear();
                if (filter == null || filter.isEmpty()) {
                    mRecordings.addAll(mOrigRecordings);
                } else {
                    for (File record : mOrigRecordings) {
                        final String fn = record.getName();
                        final String fnl = fn.substring(0, fn.lastIndexOf("."))
                                .toLowerCase(Locale.ENGLISH);
                        if (fnl.contains(filter.toLowerCase(Locale.ENGLISH)))
                            mRecordings.add(record);
                    }
                }
                mFilterUIHandler.removeCallbacksAndMessages(null);
                mFilterUIHandler.post(() -> {
                    if (mSortSelection != ListView.INVALID_POSITION) {
                        mSortSelection = ListView.INVALID_POSITION;
                        binding.sortMenu.setText("");
                    }
                    clearSelection();
                    notifyDataSetChanged();
                    setSortProgressRunning(false);
                });
            });
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
        binding.sortMenu.setEnabled(!running);
        binding.sortButton.setEnabled(!running);
        binding.sortIndicator.setIndeterminate(running);
        if (!running) return;
        binding.sortIndicator.bringToFront();
    }
}