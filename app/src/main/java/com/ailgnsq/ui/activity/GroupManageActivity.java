package com.ailgnsq.ui.activity;

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
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.ailgnsq.helper.StatusTipDialog;
import com.ailgnsq.manager.AppGlobal;
import com.ailgnsq.manager.AppLogDbHelper;
import com.ailgnsq.manager.KeyStoreManager;
import com.ailgnsq.R;

import java.util.Collections;
import java.util.List;

/**
 * 分组管理页：新增、重命名、删除分组并调整顺序，改动同步到私钥库中的分组字段。
 *
 * <p>职责边界：分组只是账号上的一个标签，页面负责标签本身的增删改序；
 * 具体账号移动到哪个分组由私钥管理页完成，这里只校验「组内是否还有账号」以免误删。</p>
 *
 * <p>重命名需要同时改写私钥库，两处必须一致，否则账号会落到一个不存在的分组里。</p>
 */
public class GroupManageActivity extends BaseActivity {
    private RecyclerView rvGroups;
    private View tvEmpty;
    private TextView tvGroupTotal, tvSummaryGroups, tvSummaryKeys;
    private View cardSummary;
    private GroupAdapter adapter;
    /** 分组名列表：顺序即展示顺序，拖拽只改这份内存，松手后落盘到 sort_order */
    private List<String> groupList;

    /**
     * 分组胶囊颜色盘。按分组名 hash 取模选色，重命名会换色、刷新不换色；
     * 与私钥管理页同一套色值，保证同一个组在两处看到颜色一致。
     */
    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };

    // region 生命周期

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

    // endregion

    // region 分组数据

    /** 后台读分组表与账号总数，回 UI 线程一次刷新统计与空态 */
    private void loadGroups() {
        new Thread(() -> {
            groupList = AppLogDbHelper.getInstance(this).queryAllGroups();
            int keyCount = 0;
            try {
                // 数行数只是元数据：走会话门会让会话过期后这里静默变成 0
                keyCount = AppGlobal.getMetaManager().loadKeyEntryList().size();
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

    /** 拖拽结束后把当前顺序逐行写入 sort_order（后台线程，索引从 0 起） */
    private void saveGroupOrder() {
        new Thread(() -> {
            AppLogDbHelper db = AppLogDbHelper.getInstance(this);
            for (int i = 0; i < groupList.size(); i++) {
                db.updateGroupSortOrder(groupList.get(i), i);
            }
        }).start();
    }

    // endregion

    // region 增删改交互

    /**
     * 新增/重命名分组（共用一个弹窗，具体行为看传入参数）。
     *
     * <p>传入 {@code prefill} 即为重命名：同名直接关闭弹窗不写库；
     * 新名字已被其他组用时在弹窗内提示重名。重命名成功后还要同步改写私钥库的 group_no，
     * 否则账号会挂在一个已经不存在的组名上。</p>
     */
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

    /**
     * 删除分组：先在后台数一下组内还有多少账号，把「有没有账号」写进确认文案，
     * 确认后先删分组、再把同组账号的 group_no 清空（归入未分组）。
     */
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
                                AppGlobal.getMetaManager().renameKeyGroup(name, "");
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

    // endregion

    // region 辅助方法

    /** 统计组内账号数：读库失败按 0 处理，不影响页面展示 */
    private int countKeysInGroup(String groupName) {
        int count = 0;
        try {
            KeyStoreManager mgr = AppGlobal.getMetaManager();
            for (KeyStoreManager.KeyEntry e : mgr.loadKeyEntryList()) {
                if (groupName.equals(e.groupNo)) count++;
            }
        } catch (Exception ignored) {}
        return count;
    }

    /** 把私钥库里的旧组名改成新组名（只在分组表改名成功后调用） */
    private void syncKeyStoreRename(String oldName, String newName) {
        try {
            // 一条 UPDATE ... WHERE group_no=? 改完同组记录；
            // 旧实现读整表再 saveKeyEntryList，会把期间改动的开关状态回滚
            AppGlobal.getMetaManager().renameKeyGroup(oldName, newName);
        } catch (Exception ignored) {}
    }

    /** 分组名的稳定取色：同一分组名每次绑定颜色一致，不随滚动或排序变化 */
    private int getBadgeColor(String name) {
        return BADGE_COLORS[Math.abs(name.hashCode()) % BADGE_COLORS.length];
    }

    // endregion

    // region 列表适配器

    /** 分组列表适配器：数据源就是外部的 groupList，不另存副本 */
    private class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false);
            return new VH(v);
        }

        /** 绑定一行：首字母徽标 + 组名 + 组内账号数（空组显示「空」标记）+ 拖拽/编辑/删除入口 */
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

        /** 分组行 ViewHolder：首字色块、分组名、人数 + 编辑/删除/拖拽按钮 */
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

    // endregion

}
