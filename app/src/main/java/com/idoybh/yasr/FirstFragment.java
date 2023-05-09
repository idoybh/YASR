package com.idoybh.yasr;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
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
    private static final int CREATE_FILE_CODE = 0x01;
    private static final long GB = 1000000000;
    private static final long MB = 1000000;
    private static final long KB = 1000;

    private FragmentFirstBinding binding;
    private RecyclerAdapter mAdapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

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
            if (selectedFiles.isEmpty()) return;
            // TODO: enable multiple delete action
        });
        binding.recycler.setAdapter(mAdapter);

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
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        mAdapter.clearSavingFile();
    }

    private class RecyclerAdapter extends RecyclerView.Adapter<RecyclerAdapter.ViewHolder> {
        private final List<File> mRecordings;
        private final List<File> mSelectedRecordings = new ArrayList<>();
        private File mSavingRecording;

        private OnCheckedListener mOnCheckedListener;
        public interface OnCheckedListener {
            void onChecked(List<File> selectedFiles);
        }

        private static class ViewHolder extends RecyclerView.ViewHolder {
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
            long duration = 0;
            try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                retriever.setDataSource(file.getPath());
                duration = Long.parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000 /* ms to s */;
                timeStr = String.format(Locale.ENGLISH, timeStr, 0, 0, duration / 60, duration % 60);
            } catch (IOException e) {
                timeStr = "ERROR";
            }
            holder.timeTxt.setText(timeStr);
            holder.playProgress.setValueTo(duration);

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
                final String newName = ((TextInputEditText) v).getText().toString();
                if (oldName.equals(newName)) return;
                final File newFile = new File(file.getPath().replace(oldName, newName));
                if (newName.isEmpty() || !file.renameTo(newFile)) return;
                ((TextInputEditText) v).setText(newName);
                mRecordings.set(position, newFile);
                notifyItemRangeChanged(position, 1);
            });
            holder.selectButton.setOnClickListener(v -> checkItem(holder.detailCard, position));
            holder.saveButton.setOnClickListener(v -> {
                mSavingRecording = file;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mime);
                intent.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(intent, CREATE_FILE_CODE);
            });
            holder.shareButton.setOnClickListener(v -> {
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
                displayAreYouSureDialog((dialog, which) -> {
                    if (!file.delete()) return;
                    mRecordings.remove(file);
                    notifyItemRemoved(position);
                    notifyItemRangeChanged(position, mRecordings.size());
                });
            });

            // player actions
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

        public File getSavingFile() {
            return mSavingRecording;
        }

        public void clearSavingFile() {
            mSavingRecording = null;
        }
    }

    private void displayAreYouSureDialog(DialogInterface.OnClickListener listener) {
        (new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.are_you_sure_msg)
                .setPositiveButton(R.string.button_yes, listener)
                .setNegativeButton(R.string.button_no, (dialog, which) -> {})
        ).show();
    }
}