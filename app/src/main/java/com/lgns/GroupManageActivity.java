package com.lgns;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import java.util.Collections;
import java.util.List;

public class GroupManageActivity extends AppCompatActivity {
    private RecyclerView rvGroups;
    private TextView tvEmpty;
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
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                boolean empty = groupList.isEmpty();
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
        final EditText et = new EditText(this);
        et.setHint("分组名称");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setPadding(48, 32, 48, 16);
        if (prefill != null) {
            et.setText(prefill);
            et.setSelection(prefill.length());
        }

        String title = (prefill != null) ? "重命名分组" : "添加分组";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确认", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new Thread(() -> {
                        AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                        boolean exists = db.groupExists(name);
                        if (prefill != null) {
                            if (exists && !name.equals(prefill)) {
                                runOnUiThread(() -> Toast.makeText(this, "分组名已存在", Toast.LENGTH_SHORT).show());
                                return;
                            }
                            boolean ok = db.renameGroup(prefill, name) > 0;
                            if (ok) syncKeyStoreRename(prefill, name);
                            runOnUiThread(() -> {
                                Toast.makeText(this, ok ? "✅ 重命名成功" : "❌ 操作失败", Toast.LENGTH_SHORT).show();
                                loadGroups();
                            });
                        } else {
                            if (exists) {
                                runOnUiThread(() -> Toast.makeText(this, "分组已存在", Toast.LENGTH_SHORT).show());
                                return;
                            }
                            boolean ok = db.insertGroup(name) > 0;
                            runOnUiThread(() -> {
                                Toast.makeText(this, ok ? "✅ 添加成功" : "❌ 操作失败", Toast.LENGTH_SHORT).show();
                                loadGroups();
                            });
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteConfirm(String name) {
        new Thread(() -> {
            int usedCount = countKeysInGroup(name);
            String hint = usedCount > 0
                    ? "⚠ 该分组下有 " + usedCount + " 个密钥，删除后将变为未分组状态"
                    : "该分组下没有密钥";
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("删除分组")
                    .setMessage("确定删除分组「" + name + "」？\n\n" + hint)
                    .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                        boolean ok = AppLogDbHelper.getInstance(this).deleteGroup(name) > 0;
                        runOnUiThread(() -> {
                            Toast.makeText(this, ok ? "✅ 已删除" : "❌ 删除失败", Toast.LENGTH_SHORT).show();
                            loadGroups();
                        });
                    }).start())
                    .setNegativeButton("取消", null)
                    .show());
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
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            List<KeyStoreManager.KeyEntry> entries = mgr.loadKeyEntryList();
            boolean changed = false;
            for (KeyStoreManager.KeyEntry e : entries) {
                if (oldName.equals(e.groupNo)) {
                    e.groupNo = newName;
                    changed = true;
                }
            }
            if (changed) mgr.saveKeyEntryList(entries);
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
            holder.tvBadge.setBackgroundColor(getBadgeColor(name));

            int count = countKeysInGroup(name);
            holder.tvCount.setText(count > 0 ? count + " 个密钥" : "空分组");

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
