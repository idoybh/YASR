package com.idoybh.yasr;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
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
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.idoybh.yasr.databinding.FragmentFirstBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FirstFragment extends Fragment {
    private static final String MEDIA_PLAYER_THREAD_NAME = "MediaPlayer tracker";
    private static final int CREATE_FILE_CODE = 0x01;
    private static final long GB = 1000000000;
    private static final long MB = 1000000;
    private static final long KB = 1000;

    private FragmentFirstBinding binding;
    private RecyclerAdapter mAdapter;
    private List<FloatingActionButton> mMultiSelectFabs;
    private volatile boolean mWaitingForResults = false;
    private HandlerThread mProgressHT;

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
        binding.recycler.setLayoutManager(manager);
        mAdapter = new RecyclerAdapter(recordings);
        mAdapter.setOnCheckedListener((selectedFiles) -> {
            final boolean selectionEmpty = selectedFiles.isEmpty();
            for (FloatingActionButton fab : mMultiSelectFabs)
                fab.setVisibility(selectionEmpty ? View.INVISIBLE : View.VISIBLE);
            binding.fabSelection.setImageResource(mAdapter.isFullySelected()
                    ? R.drawable.baseline_deselect_24 : R.drawable.baseline_select_all_24);
        });
        binding.recycler.setAdapter(mAdapter);

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
            for (FloatingActionButton fab : mMultiSelectFabs)
                fab.setVisibility(View.INVISIBLE);
        }, mAdapter.getSelectedRecordings().size()));
        binding.fab.setOnClickListener(v ->
                NavHostFragment.findNavController(FirstFragment.this)
                .navigate(R.id.action_FirstFragment_to_RecordFragment));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

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

    private class RecyclerAdapter extends RecyclerView.Adapter<RecyclerAdapter.ViewHolder> {
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

            public void stopPlaying() {
                if (mProgressHT != null && mProgressHT.isAlive())
                    mProgressHT.quit();
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
            holder.fileNameTxt.setText(name.substring(0, dotPos));
            holder.typeTxt.setText(name.substring(dotPos + 1).toUpperCase());
            final long size = file.length();
            String sizeText = String.valueOf(size); // bytes
            if (size > GB) {
                sizeText = size / GB + " " + getString(R.string.unit_gb);
            } else if (size > MB) {
                sizeText = size / MB + " " + getString(R.string.unit_mb);
            } else if (size > KB) {
                sizeText = size / 1000 + " " + getString(R.string.unit_kb);
            }
            holder.sizeTxt.setText(sizeText);
            String timeStr = "%02d:%02d/%02d:%02d";
            long duration;
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(file.getPath());
                duration = Long.parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000 /* ms to s */;
                timeStr = String.format(Locale.ENGLISH, timeStr, 0, 0, duration / 60, duration % 60);
            } catch (Exception e) {
                duration = 1;
                timeStr = "ERROR";
            }
            holder.timeTxt.setText(timeStr);
            holder.playProgress.setValueTo(duration * 1000);
            final int finalDuration = (int) duration;

            // actions
            holder.detailCard.setOnLongClickListener(v -> {
                checkItem(holder.detailCard, position);
                return true;
            });
            holder.detailCard.setOnClickListener(v -> {
                if (holder.detailCard.isChecked() || mSelectedRecordings.size() > 0)
                    checkItem(holder.detailCard, position);
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
                mRecordings.set(position, newFile);
                notifyItemRangeChanged(position, 1);
            });
            holder.selectButton.setOnClickListener(v -> checkItem(holder.detailCard, position));
            holder.saveButton.setOnClickListener(v -> {
                if (!mSelectedRecordings.isEmpty()) return;
                mSavingRecording = file;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mime);
                intent.putExtra(Intent.EXTRA_TITLE, name);
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

            // player actions
            holder.playButton.setOnClickListener(v -> {
                if (mPlayingRecording != null && mPlayingRecording != file) {
                    // a different file is playing, stop it before
                    mPlayingHolder.stopPlaying();
                    String timeText = String.format(Locale.ENGLISH, "%02d:%02d/%02d:%02d",
                            0, 0, finalDuration / 60, finalDuration % 60);
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
                    long dur = 0;
                    try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                        retriever.setDataSource(file.getPath());
                        dur = Long.parseLong(retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000 /* ms to s */;
                        timeText = String.format(Locale.ENGLISH, timeText, 0, 0, dur / 60, dur % 60);
                    } catch (IOException e) {
                        timeText = "ERROR";
                    }
                    holder.timeTxt.setText(timeText);
                    animateSliderValue(holder.playProgress, 0);
                });
                holder.playProgress.setValueTo(mMediaPlayer.getDuration());
                holder.playButton.setImageResource(R.drawable.baseline_pause_24);
                mMediaPlayer.start();
                if (mProgressHT != null && mProgressHT.isAlive())
                    mProgressHT.quit();
                mProgressHT = new HandlerThread(MEDIA_PLAYER_THREAD_NAME);
                mProgressHT.start();
                Handler handler = new Handler(mProgressHT.getLooper());
                requireActivity().runOnUiThread(() -> handler.post(new Runnable() {
                    @Override
                    public void run() {
                        final boolean plays = mPlayingRecording != null &&
                                mPlayingRecording == file && mMediaPlayer != null;
                        try {
                            if (plays && mMediaPlayer.isPlaying()) {
                                final int pos = mMediaPlayer.getCurrentPosition();
                                animateSliderValue(holder.playProgress, pos);
                                String timeText = String.format(Locale.ENGLISH, "%02d:%02d/%02d:%02d",
                                        (pos / 1000) / 60, (pos / 1000) % 60, finalDuration / 60, finalDuration % 60);
                                holder.timeTxt.setText(timeText);
                            }
                            if (plays) {
                                // while this file still plays
                                handler.postDelayed(this, 100);
                                return;
                            }
                            mProgressHT.quit();
                        } catch (Exception e) {
                            if (plays) handler.postDelayed(this, 100);
                            else mProgressHT.quit();
                        }
                    }
                }));
            });
            holder.playProgress.addOnChangeListener((slider, value, fromUser) -> {
                if (!fromUser) return;
                final int pos = (int) value;
                String timeText = String.format(Locale.ENGLISH, "%02d:%02d/%02d:%02d",
                        (pos / 1000) / 60, (pos / 1000) % 60, finalDuration / 60, finalDuration % 60);
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
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    if (mMediaPlayer == null || mPlayingRecording != file || mMediaPlayer.isPlaying())
                        return;
                    mMediaPlayer.start();
                }
            });
        }

        private static void animateSliderValue(Slider slider, float value) {
            final ValueAnimator animator = ValueAnimator.ofFloat(slider.getValue(), value);
            final float width = Math.abs(slider.getValue() - value);
            animator.setDuration(width > slider.getValueTo() / 5f ? 500 : 200);
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
    }

    private void displayAreYouSureDialog(DialogInterface.OnClickListener listener) {
        displayAreYouSureDialog(listener, 0);
    }

    private void displayAreYouSureDialog(DialogInterface.OnClickListener listener, int num) {
        String msg = getString(R.string.are_you_sure_msg);
        if (num > 0) {
            msg = String.format(getString(R.string.are_you_sure_msg_mul), num) + msg;
        }
        (new MaterialAlertDialogBuilder(requireContext())
                .setMessage(msg)
                .setPositiveButton(R.string.button_yes, listener)
                .setNegativeButton(R.string.button_no, (dialog, which) -> {})
        ).show();
    }
}