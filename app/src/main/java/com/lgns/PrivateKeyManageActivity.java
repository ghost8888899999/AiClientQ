package com.lgns;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PrivateKeyManageActivity extends AppCompatActivity {
    private RecyclerView rvKeys;
    private TextView tvEmpty;
    private KeyAdapter adapter;
    private List<DisplayItem> displayList = new ArrayList<>();

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_KEY = 1;

    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };

    static class DisplayItem {
        int type;
        String groupName;
        KeyStoreManager.KeyEntry entry;

        static DisplayItem header(String name) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_HEADER;
            d.groupName = name;
            return d;
        }

        static DisplayItem keyItem(KeyStoreManager.KeyEntry e) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_KEY;
            d.entry = e;
            d.groupName = e.groupNo;
            return d;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_private_key_manage);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        rvKeys = findViewById(R.id.rv_keys);
        tvEmpty = findViewById(R.id.tv_empty);

        rvKeys.setLayoutManager(new LinearLayoutManager(this));
        adapter = new KeyAdapter();
        rvKeys.setAdapter(adapter);

        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                if (target.getItemViewType() != TYPE_KEY) return false;
                int from = vh.getAdapterPosition();
                int to = target.getAdapterPosition();
                String fromGroup = displayList.get(from).groupName;
                String toGroup = displayList.get(to).groupName;
                if (!fromGroup.equals(toGroup)) return false;

                Collections.swap(displayList, from, to);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {}

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                int pos = vh.getAdapterPosition();
                if (pos >= 0 && pos < displayList.size()) {
                    saveGroupSortOrder(displayList.get(pos).groupName);
                }
            }
        };
        ItemTouchHelper helper = new ItemTouchHelper(callback);
        helper.attachToRecyclerView(rvKeys);

        FloatingActionButton fabAdd = findViewById(R.id.fab_add);
        fabAdd.setOnClickListener(v -> showAddKeyDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadKeys();
    }

    private void loadKeys() {
        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                List<KeyStoreManager.KeyEntry> entries = mgr.loadKeyEntryList();

                List<KeyStoreManager.KeyEntry> unique = new ArrayList<>();
                for (KeyStoreManager.KeyEntry e : entries) {
                    boolean dup = false;
                    for (KeyStoreManager.KeyEntry u : unique) {
                        if (u.id == e.id) { dup = true; break; }
                    }
                    if (!dup) unique.add(e);
                }
                entries = unique;

                List<Long> sortIds = mgr.getCustomSortIdList();

                List<KeyStoreManager.KeyEntry> sorted = new ArrayList<>();
                for (Long sid : sortIds) {
                    for (KeyStoreManager.KeyEntry e : entries) {
                        if (e.id == sid) { sorted.add(e); break; }
                    }
                }
                for (KeyStoreManager.KeyEntry e : entries) {
                    boolean found = false;
                    for (KeyStoreManager.KeyEntry s : sorted) {
                        if (s.id == e.id) { found = true; break; }
                    }
                    if (!found) sorted.add(e);
                }

                List<String> groups = AppLogDbHelper.getInstance(this).queryAllGroups();
                Map<String, List<KeyStoreManager.KeyEntry>> groupMap = new LinkedHashMap<>();
                for (String g : groups) groupMap.put(g, new ArrayList<>());
                List<KeyStoreManager.KeyEntry> ungrouped = new ArrayList<>();

                for (KeyStoreManager.KeyEntry e : sorted) {
                    String g = (e.groupNo != null && !e.groupNo.isEmpty()) ? e.groupNo : null;
                    if (g != null && groupMap.containsKey(g)) {
                        groupMap.get(g).add(e);
                    } else {
                        ungrouped.add(e);
                    }
                }

                displayList = new ArrayList<>();
                for (Map.Entry<String, List<KeyStoreManager.KeyEntry>> me : groupMap.entrySet()) {
                    if (!me.getValue().isEmpty()) {
                        displayList.add(DisplayItem.header(me.getKey()));
                        for (KeyStoreManager.KeyEntry e : me.getValue()) {
                            displayList.add(DisplayItem.keyItem(e));
                        }
                    }
                }
                if (!ungrouped.isEmpty()) {
                    displayList.add(DisplayItem.header("未分组"));
                    for (KeyStoreManager.KeyEntry e : ungrouped) {
                        displayList.add(DisplayItem.keyItem(e));
                    }
                }

                boolean empty = sorted.isEmpty();
                runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                    rvKeys.setVisibility(empty ? View.GONE : View.VISIBLE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void saveGroupSortOrder(String groupName) {
        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                List<Long> ids = new ArrayList<>();
                for (DisplayItem d : displayList) {
                    if (d.type == TYPE_KEY && d.groupName.equals(groupName)) {
                        ids.add(d.entry.id);
                    }
                }
                List<Long> allIds = mgr.getCustomSortIdList();
                List<Long> otherIds = new ArrayList<>();
                for (Long id : allIds) {
                    if (!ids.contains(id)) otherIds.add(id);
                }
                List<Long> finalIds = new ArrayList<>();
                for (DisplayItem d : displayList) {
                    if (d.type == TYPE_KEY && !d.groupName.equals(groupName)) {
                        finalIds.add(d.entry.id);
                    }
                }
                int insertPos = 0;
                for (int i = 0; i < otherIds.size(); i++) {
                    finalIds.add(otherIds.get(i));
                }
                for (Long id : ids) {
                    if (!finalIds.contains(id)) finalIds.add(insertPos, id);
                    insertPos++;
                }
                mgr.saveCustomSortIdList(finalIds);
            } catch (Exception ignored) {}
        }).start();
    }

    private void showAddKeyDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_key, null);
        EditText etCustomCode = view.findViewById(R.id.et_custom_code);
        Spinner spGroup = view.findViewById(R.id.sp_group_no);
        EditText etPk1 = view.findViewById(R.id.et_pk_part1);
        EditText etPk2 = view.findViewById(R.id.et_pk_part2);
        EditText etPwd = view.findViewById(R.id.et_user_crypto_key);
        ImageButton ivEye = view.findViewById(R.id.iv_eye_toggle);

        List<String> groups = AppLogDbHelper.getInstance(this).queryAllGroups();
        if (groups.isEmpty()) groups.add("default");
        spGroup.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, groups));

        final boolean[] visible = {false};
        ivEye.setOnClickListener(v -> {
            visible[0] = !visible[0];
            etPwd.setInputType(visible[0]
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL)
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            ivEye.setImageResource(visible[0] ? R.drawable.ic_eye_on_svg : R.drawable.ic_eye_off_svg);
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加私钥")
                .setView(view)
                .setPositiveButton("确认添加", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String customCode = etCustomCode != null ? etCustomCode.getText().toString().trim() : "";
            String group = ((String) spGroup.getSelectedItem()).trim();
            String part1 = etPk1.getText().toString().trim();
            String part2 = etPk2.getText().toString().trim();
            String fullKey = (part1 + part2).trim();
            String pwdStr = etPwd.getText().toString().trim();

            if (fullKey.isEmpty()) {
                Toast.makeText(this, "密钥内容不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (pwdStr.isEmpty()) {
                Toast.makeText(this, "请输入加解密密码", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!customCode.isEmpty() && isCustomCodeUsed(customCode, -1)) {
                Toast.makeText(this, "自定义编号已存在", Toast.LENGTH_SHORT).show();
                return;
            }

            char[] pwd = pwdStr.toCharArray();
            new Thread(() -> {
                try {
                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                    mgr.addPrivateKey(fullKey, pwd, customCode, group);
                    java.util.Arrays.fill(pwd, (char) 0);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "✅ 添加成功", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        loadKeys();
                    });
                } catch (Exception e) {
                    java.util.Arrays.fill(pwd, (char) 0);
                    runOnUiThread(() -> Toast.makeText(this, "❌ " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        });
    }

    private void showEditDialog(int position) {
        KeyStoreManager.KeyEntry entry = displayList.get(position).entry;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_key, null);
        EditText etCode = view.findViewById(R.id.et_custom_code);
        Spinner spGroup = view.findViewById(R.id.sp_group);
        TextView tvAddr = view.findViewById(R.id.tv_addr);

        etCode.setText(entry.customCode);
        etCode.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        tvAddr.setText(entry.address != null ? entry.address : "地址未知");

        List<String> groups = AppLogDbHelper.getInstance(this).queryAllGroups();
        if (groups.isEmpty()) groups.add("default");
        ArrayAdapter<String> ga = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, groups);
        spGroup.setAdapter(ga);
        if (entry.groupNo != null && !entry.groupNo.isEmpty()) {
            int idx = groups.indexOf(entry.groupNo);
            if (idx >= 0) spGroup.setSelection(idx);
        }

        new AlertDialog.Builder(this)
                .setTitle("编辑私钥信息")
                .setView(view)
                .setPositiveButton("保存", (d, w) -> {
                    String newCode = etCode.getText().toString().trim();
                    String newGroup = ((String) spGroup.getSelectedItem()).trim();
                    if (!newCode.isEmpty() && isCustomCodeUsed(newCode, entry.id)) {
                        Toast.makeText(this, "自定义编号已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new Thread(() -> {
                        try {
                            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                            List<KeyStoreManager.KeyEntry> all = mgr.loadKeyEntryList();
                            for (KeyStoreManager.KeyEntry e : all) {
                                if (e.id == entry.id) {
                                    e.customCode = newCode;
                                    e.groupNo = newGroup;
                                    break;
                                }
                            }
                            mgr.saveKeyEntryList(all);
                            runOnUiThread(() -> {
                                Toast.makeText(this, "✅ 已保存", Toast.LENGTH_SHORT).show();
                                loadKeys();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "❌ 保存失败", Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean isCustomCodeUsed(String code, long excludeId) {
        try {
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            for (KeyStoreManager.KeyEntry e : mgr.loadKeyEntryList()) {
                if (e.id != excludeId && code.equals(e.customCode)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void showDeleteConfirm(int position) {
        KeyStoreManager.KeyEntry entry = displayList.get(position).entry;
        String label = entry.customCode.isEmpty()
                ? String.format("%03d", entry.id) : entry.customCode;
        new AlertDialog.Builder(this)
                .setTitle("删除私钥")
                .setMessage("确定删除「" + label + "」？\n\n" + entry.address)
                .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                    try {
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                        List<KeyStoreManager.KeyEntry> all = mgr.loadKeyEntryList();
                        for (int i = 0; i < all.size(); i++) {
                            if (all.get(i).id == entry.id) {
                                mgr.removeByIndex(i);
                                break;
                            }
                        }
                        runOnUiThread(() -> {
                            Toast.makeText(this, "✅ 已删除", Toast.LENGTH_SHORT).show();
                            loadKeys();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "❌ 删除失败", Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    private int getBadgeColor(String text) {
        return BADGE_COLORS[Math.abs(text.hashCode()) % BADGE_COLORS.length];
    }

    private class KeyAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int HEADER_VIEW = 0;
        private static final int KEY_VIEW = 1;

        @Override
        public int getItemViewType(int position) {
            return displayList.get(position).type == TYPE_HEADER ? HEADER_VIEW : KEY_VIEW;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == HEADER_VIEW) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group_header, parent, false);
                return new HeaderVH(v);
            } else {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_private_key, parent, false);
                return new KeyVH(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            DisplayItem item = displayList.get(position);
            if (holder instanceof HeaderVH) {
                HeaderVH h = (HeaderVH) holder;
                h.tvGroupName.setText(item.groupName);
                h.viewColor.setBackgroundColor(getBadgeColor(item.groupName));
                int count = 0;
                for (DisplayItem d : displayList) {
                    if (d.type == TYPE_KEY && d.groupName.equals(item.groupName)) count++;
                }
                h.tvGroupCount.setText(count + " 个");
            } else {
                KeyVH h = (KeyVH) holder;
                KeyStoreManager.KeyEntry entry = item.entry;
                String label = entry.customCode.isEmpty()
                        ? String.format("%03d", entry.id) : entry.customCode;
                h.tvCode.setText(label);
                h.tvBadge.setText(String.valueOf(label.charAt(0)).toUpperCase());
                h.tvBadge.setBackgroundColor(getBadgeColor(label));
                h.tvAddr.setText(entry.address != null && !entry.address.isEmpty()
                        ? entry.address : "地址未知");
                h.swEnabled.setChecked(entry.enabled);
                h.swEnabled.setOnCheckedChangeListener((btn, checked) -> {
                    entry.enabled = checked;
                    new Thread(() -> {
                        try {
                            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                            List<KeyStoreManager.KeyEntry> all = mgr.loadKeyEntryList();
                            for (KeyStoreManager.KeyEntry e : all) {
                                if (e.id == entry.id) {
                                    e.enabled = checked;
                                    break;
                                }
                            }
                            mgr.saveKeyEntryList(all);
                        } catch (Exception ignored) {}
                    }).start();
                });
                h.btnEdit.setOnClickListener(v -> showEditDialog(position));
                h.btnDelete.setOnClickListener(v -> showDeleteConfirm(position));
            }
        }

        @Override
        public int getItemCount() {
            return displayList.size();
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvGroupName, tvGroupCount;
        View viewColor;

        HeaderVH(View v) {
            super(v);
            tvGroupName = v.findViewById(R.id.tv_group_name);
            tvGroupCount = v.findViewById(R.id.tv_group_count);
            viewColor = v.findViewById(R.id.view_color);
        }
    }

    static class KeyVH extends RecyclerView.ViewHolder {
        TextView tvBadge, tvCode, tvAddr;
        Switch swEnabled;
        ImageButton btnEdit, btnDelete;

        KeyVH(View v) {
            super(v);
            tvBadge = v.findViewById(R.id.tv_badge);
            tvCode = v.findViewById(R.id.tv_key_code);
            tvAddr = v.findViewById(R.id.tv_key_addr);
            swEnabled = v.findViewById(R.id.sw_enabled);
            btnEdit = v.findViewById(R.id.btn_edit);
            btnDelete = v.findViewById(R.id.btn_delete);
        }
    }
}