package com.lgns.ui.activity;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.KeyStoreManager;
import com.lgns.R;

import java.util.Collections;
import java.util.List;

public class GroupManageActivity extends AppCompatActivity {
    private RecyclerView rvGroups;
    private View tvEmpty;
    private TextView tvGroupTotal, tvSummaryGroups, tvSummaryKeys;
    private View cardSummary;
    private GroupAdapter adapter;
    private List<String> groupList;

    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_manage);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        rvGroups = findViewById(R.id.rv_groups);
        tvEmpty = findViewById(R.id.tv_empty);
        tvGroupTotal = findViewById(R.id.tv_group_total);
        tvSummaryGroups = findViewById(R.id.tv_summary_groups);
        tvSummaryKeys = findViewById(R.id.tv_summary_keys);
        cardSummary = findViewById(R.id.card_summary);

        rvGroups.setLayoutManager(new LinearLayoutManager(this));
        adapter = new GroupAdapter();
        rvGroups.setAdapter(adapter);

        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                if (vh instanceof GroupAdapter.VH) {
                    ((GroupAdapter.VH) vh).btnDrag.setAlpha(1f);
                }
                return super.getMovementFlags(rv, vh);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getAdapterPosition();
                int to = target.getAdapterPosition();
                Collections.swap(groupList, from, to);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {}

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                saveGroupOrder();
            }
        };
        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(rvGroups);

        FloatingActionButton fabAdd = findViewById(R.id.fab_add);
        fabAdd.setOnClickListener(v -> showAddDialog(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadGroups();
    }

    private void loadGroups() {
        new Thread(() -> {
            groupList = AppLogDbHelper.getInstance(this).queryAllGroups();
            int keyCount = 0;
            try {
                keyCount = AppGlobal.getKeyStoreManager().loadKeyEntryList().size();
            } catch (Exception ignored) {
            }
            final int totalKeys = keyCount;
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                tvGroupTotal.setText(getString(R.string.manage_total_fmt, groupList.size()));
                tvSummaryGroups.setText(String.valueOf(groupList.size()));
                tvSummaryKeys.setText(String.valueOf(totalKeys));

                boolean empty = groupList.isEmpty();
                cardSummary.setVisibility(empty ? View.GONE : View.VISIBLE);
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                rvGroups.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        }).start();
    }

    private void saveGroupOrder() {
        new Thread(() -> {
            AppLogDbHelper db = AppLogDbHelper.getInstance(this);
            for (int i = 0; i < groupList.size(); i++) {
                db.updateGroupSortOrder(groupList.get(i), i);
            }
        }).start();
    }

    private void showAddDialog(String prefill) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_group_name, null);
        TextView tvTitle = view.findViewById(R.id.tv_title);
        TextView tvCounter = view.findViewById(R.id.tv_counter);
        TextView tvError = view.findViewById(R.id.tv_error);
        EditText et = view.findViewById(R.id.et_group_name);

        final boolean isRename = prefill != null;
        tvTitle.setText(isRename ? R.string.dialog_rename_group_title : R.string.dialog_add_group_title);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        if (isRename) {
            et.setText(prefill);
            et.setSelection(prefill.length());
        }
        updateNameCounter(tvCounter, et.getText().length());

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        et.requestFocus();

        // 输入时实时刷新字数，并隐藏上一次的内联错误
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateNameCounter(tvCounter, s.length());
                if (tvError.getVisibility() == View.VISIBLE) {
                    tvError.setVisibility(View.GONE);
                }
            }
        });

        view.findViewById(R.id.btn_grp_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_grp_save).setOnClickListener(v -> {
            String name = et.getText().toString().trim();
            if (name.isEmpty()) {
                showNameError(tvError, getString(R.string.err_group_name_empty));
                return;
            }
            if (isRename && name.equals(prefill)) {
                dialog.dismiss();
                return;
            }
            new Thread(() -> {
                AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                boolean exists = db.groupExists(name);
                if (isRename) {
                    if (exists && !name.equals(prefill)) {
                        runOnUiThread(() -> showNameError(tvError, getString(R.string.err_group_name_exists)));
                        return;
                    }
                    boolean ok = db.renameGroup(prefill, name) > 0;
                    if (ok) syncKeyStoreRename(prefill, name);
                    runOnUiThread(() -> {
                        Toast.makeText(this, ok ? R.string.toast_group_renamed : R.string.toast_op_failed_plain,
                                Toast.LENGTH_SHORT).show();
                        if (ok) dialog.dismiss();
                        loadGroups();
                    });
                } else {
                    if (exists) {
                        runOnUiThread(() -> showNameError(tvError, getString(R.string.err_group_exists)));
                        return;
                    }
                    boolean ok = db.insertGroup(name) > 0;
                    runOnUiThread(() -> {
                        Toast.makeText(this, ok ? R.string.toast_group_added : R.string.toast_op_failed_plain,
                                Toast.LENGTH_SHORT).show();
                        if (ok) dialog.dismiss();
                        loadGroups();
                    });
                }
            }).start();
        });
    }

    private void updateNameCounter(TextView tvCounter, int length) {
        tvCounter.setText(getString(R.string.dialog_group_name_counter_fmt, length, 10));
    }

    private void showNameError(TextView tvError, String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    private void showDeleteConfirm(String name) {
        new Thread(() -> {
            int usedCount = countKeysInGroup(name);
            String hint = usedCount > 0
                    ? getString(R.string.grp_delete_has_keys_fmt, usedCount)
                    : getString(R.string.grp_delete_no_keys);
            runOnUiThread(() -> StatusTipDialog.showDanger(this, StatusTipDialog.TONE_WARN,
                    R.drawable.ic_delete_warn_svg,
                    getString(R.string.dialog_delete_group_title),
                    getString(R.string.grp_delete_msg_v2_fmt, name, hint),
                    getString(R.string.grp_delete_confirm_btn),
                    () -> new Thread(() -> {
                        boolean ok = AppLogDbHelper.getInstance(this).deleteGroup(name) > 0;
                        // 同组账号的 group_no 一并清空：否则账号挂着已删除的组名，
                        // 列表归到“未分组”但库里仍是旧名字，编辑保存时会被静默改组
                        if (ok) {
                            try {
                                AppGlobal.getKeyStoreManager().renameKeyGroup(name, "");
                            } catch (Exception ignored) {}
                        }
                        runOnUiThread(() -> {
                            Toast.makeText(this, ok ? R.string.toast_key_deleted : R.string.toast_delete_failed,
                                    Toast.LENGTH_SHORT).show();
                            loadGroups();
                        });
                    }).start(),
                    getString(R.string.btn_cancel), null));
        }).start();
    }

    private int countKeysInGroup(String groupName) {
        int count = 0;
        try {
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            for (KeyStoreManager.KeyEntry e : mgr.loadKeyEntryList()) {
                if (groupName.equals(e.groupNo)) count++;
            }
        } catch (Exception ignored) {}
        return count;
    }

    private void syncKeyStoreRename(String oldName, String newName) {
        try {
            // 一条 UPDATE ... WHERE group_no=? 改完同组记录；
            // 旧实现读整表再 saveKeyEntryList，会把期间改动的开关状态回滚
            AppGlobal.getKeyStoreManager().renameKeyGroup(oldName, newName);
        } catch (Exception ignored) {}
    }

    private int getBadgeColor(String name) {
        return BADGE_COLORS[Math.abs(name.hashCode()) % BADGE_COLORS.length];
    }

    private class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String name = groupList.get(position);
            holder.tvName.setText(name);
            holder.tvBadge.setText(String.valueOf(name.isEmpty() ? '?' : name.charAt(0)).toUpperCase());
            holder.tvBadge.setBackgroundTintList(ColorStateList.valueOf(getBadgeColor(name)));

            int count = countKeysInGroup(name);
            holder.tvCount.setText(count > 0
                    ? holder.itemView.getContext().getString(R.string.grp_key_pill_fmt, count)
                    : holder.itemView.getContext().getString(R.string.group_empty_tag));

            holder.btnEdit.setOnClickListener(v -> showAddDialog(name));
            holder.btnDelete.setOnClickListener(v -> showDeleteConfirm(name));
        }

        @Override
        public int getItemCount() {
            return groupList == null ? 0 : groupList.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvBadge, tvName, tvCount;
            ImageButton btnEdit, btnDelete;
            ImageView btnDrag;

            VH(View itemView) {
                super(itemView);
                btnDrag = itemView.findViewById(R.id.btn_drag);
                tvBadge = itemView.findViewById(R.id.tv_badge);
                tvName = itemView.findViewById(R.id.tv_group_name);
                tvCount = itemView.findViewById(R.id.tv_group_count);
                btnEdit = itemView.findViewById(R.id.btn_edit);
                btnDelete = itemView.findViewById(R.id.btn_delete);
            }
        }
    }
}
