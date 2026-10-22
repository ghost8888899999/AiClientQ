package com.lgns.ui.activity;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.Toast;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.KeyStoreManager;
import com.lgns.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PrivateKeyManageActivity extends AppCompatActivity {
    private RecyclerView rvKeys;
    private TextView tvEmpty;
    private KeyAdapter adapter;
    private List<DisplayItem> allDisplayList = new ArrayList<>(); // 完整列表
    private List<DisplayItem> displayList = new ArrayList<>(); // 可见列表
    private Map<String, Boolean> groupExpandedMap = new LinkedHashMap<>(); // 分组展开状态

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

                // 构建完整列表
                allDisplayList = new ArrayList<>();
                Map<String, Boolean> prevExpandedMap = new LinkedHashMap<>(groupExpandedMap);
                groupExpandedMap.clear();
                int groupIndex = 0;
                for (Map.Entry<String, List<KeyStoreManager.KeyEntry>> me : groupMap.entrySet()) {
                    if (!me.getValue().isEmpty()) {
                        allDisplayList.add(DisplayItem.header(me.getKey()));
                        Boolean prev = prevExpandedMap.get(me.getKey());
                        groupExpandedMap.put(me.getKey(), prev != null ? prev : true);
                        groupIndex++;
                        for (KeyStoreManager.KeyEntry e : me.getValue()) {
                            allDisplayList.add(DisplayItem.keyItem(e));
                        }
                    }
                }
                if (!ungrouped.isEmpty()) {
                    allDisplayList.add(DisplayItem.header("未分组"));
                    Boolean prevUngrouped = prevExpandedMap.get("未分组");
                    groupExpandedMap.put("未分组", prevUngrouped != null ? prevUngrouped : true);
                    for (KeyStoreManager.KeyEntry e : ungrouped) {
                        allDisplayList.add(DisplayItem.keyItem(e));
                    }
                }

                // 构建可见列表
                rebuildVisibleList();

                boolean empty = sorted.isEmpty();
                runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                Log.e("PrivateKeyManage", "loadKeys error", e);
            }
        }).start();
    }

    private void rebuildVisibleList() {
        displayList.clear();
        String currentGroup = null;
        boolean currentExpanded = false;
        for (DisplayItem item : allDisplayList) {
            if (item.type == TYPE_HEADER) {
                currentGroup = item.groupName;
                currentExpanded = groupExpandedMap.getOrDefault(currentGroup, false);
                displayList.add(item); // 分组标题始终可见
            } else if (currentExpanded) {
                displayList.add(item); // 只添加展开分组的数据项
            }
        }
    }

    private void toggleGroup(String groupName) {
        boolean expanded = groupExpandedMap.getOrDefault(groupName, false);
        groupExpandedMap.put(groupName, !expanded);
        rebuildVisibleList();
        adapter.notifyDataSetChanged();
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
        ImageButton ivCryptoHelp = view.findViewById(R.id.iv_crypto_help);

        ivCryptoHelp.setOnClickListener(v -> new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("密码安全说明")
                .setMessage("若设备丢失，黑客可拿到加密数据离线暴力破解：\n\n" +
                        "6 位纯数字 → 约 3~14 小时可破解 ❌\n\n" +
                        "7 位纯数字 → 约 1~6 天可破解 ❌\n\n" +
                        "7 位字母+数字 → 约 1100 年 ✅\n\n" +
                        "8 位字母+数字 → 约 10 万年 ✅✅\n\n" +
                        "10 位混合 → 千万年级别 ✅✅✅\n\n" +
                        "建议：至少 7 位字母+数字组合，越长越安全。\n密码仅在内存中使用，关闭 APP 即清除。")
                .setPositiveButton("知道了", null)
                .show());

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

            String validateError = validateAddKeyInput(customCode, fullKey, pwdStr);
            if (validateError != null) {
                Toast.makeText(this, validateError, Toast.LENGTH_LONG).show();
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

    private String validateAddKeyInput(String customCode, String fullKey, String pwdStr) {
        if (fullKey.isEmpty()) {
            return "密钥内容不能为空";
        }
        if (pwdStr.isEmpty()) {
            return "请输入加解密密码";
        }
        if (customCode.isEmpty()) {
            return "请输入自定义编号";
        }
        if (customCode.length() > 10) {
            return "自定义编号不能超过10个字符";
        }
        if (isCustomCodeUsed(customCode, -1)) {
            return "自定义编号已存在";
        }
        String pkError = validatePrivateKey(fullKey);
        if (pkError != null) {
            return pkError;
        }
        String dupError = checkDuplicatePrivateKey(fullKey);
        if (dupError != null) {
            return dupError;
        }
        return null;
    }

    private String validatePrivateKey(String pk) {
        String clean = pk.trim().toLowerCase();
        if (clean.startsWith("0x")) clean = clean.substring(2);
        
        if (clean.isEmpty()) {
            return "私钥内容不能为空";
        }
        if (!clean.matches("[0-9a-f]+")) {
            return "私钥格式错误：只能包含 0-9 和 a-f 字符（可带 0x 前缀）";
        }
        
        return null;
    }

    private String checkDuplicatePrivateKey(String rawPrivateKey) {
        try {
            String clean = rawPrivateKey.trim().toLowerCase();
            if (clean.startsWith("0x")) clean = clean.substring(2);
            
            String newAddr = "0x" + org.web3j.crypto.Keys.getAddress(
                    org.web3j.crypto.ECKeyPair.create(org.web3j.utils.Numeric.toBigInt(clean)));
            
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            List<KeyStoreManager.KeyEntry> allKeys = mgr.loadKeyEntryList();
            
            for (KeyStoreManager.KeyEntry entry : allKeys) {
                if (entry.address != null && entry.address.equalsIgnoreCase(newAddr)) {
                    String label = entry.customCode.isEmpty() 
                            ? String.format("%03d", entry.id) 
                            : entry.customCode;
                    return "私钥已存在！对应编号: " + label + "，地址: " + entry.address;
                }
            }
        } catch (Exception e) {
            return "私钥校验失败: " + e.getMessage();
        }
        return null;
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

        LinearLayout layoutMaster = new LinearLayout(this);
        layoutMaster.setOrientation(LinearLayout.HORIZONTAL);
        layoutMaster.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layoutMaster.setPadding(0, 16, 0, 0);
        CheckBox cbMaster = new CheckBox(this);
        cbMaster.setText("设为主号（用于批量充值）");
        cbMaster.setTextSize(14);
        long currentMasterId = AppGlobal.getKeyStoreManager().getMasterAccountId();
        cbMaster.setChecked(currentMasterId == entry.id);
        layoutMaster.addView(cbMaster);

        if (view instanceof LinearLayout) {
            ((LinearLayout) view).addView(layoutMaster);
        } else if (view instanceof android.widget.ScrollView) {
            android.widget.ScrollView sv = (android.widget.ScrollView) view;
            android.widget.LinearLayout inner = (android.widget.LinearLayout) sv.getChildAt(0);
            inner.addView(layoutMaster);
        }

        final long fCurrentMasterId = currentMasterId;

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this)
                .setTitle("编辑私钥信息")
                .setView(view)
                .setPositiveButton("保存", (d, w) -> {
                    String newCode = etCode.getText().toString().trim();
                    String newGroup = (String) spGroup.getSelectedItem();
                    boolean isMaster = cbMaster.isChecked();
                    entry.customCode = newCode.isEmpty() ? null : newCode;
                    entry.groupNo = newGroup;
                    new Thread(() -> {
                        try {
                            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                            mgr.updateKeyEntry((int) entry.id, entry.customCode, entry.groupNo, entry.enabled);
                            if (isMaster && fCurrentMasterId != entry.id) {
                                mgr.setMasterAccountId(entry.id);
                                mgr.updateKeyEntry((int) entry.id, entry.customCode, entry.groupNo, false);
                                entry.enabled = false;
                                runOnUiThread(() -> Toast.makeText(this, "已设为主号并自动禁用", Toast.LENGTH_SHORT).show());
                            } else if (!isMaster && fCurrentMasterId == entry.id) {
                                mgr.setMasterAccountId(-1);
                            }
                            runOnUiThread(() -> loadKeys());
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this, "❌ 保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                })
                .setNegativeButton("取消", null);
        dialogBuilder.show();
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
                for (DisplayItem d : allDisplayList) {
                    if (d.type == TYPE_KEY && d.groupName.equals(item.groupName)) count++;
                }
                h.tvGroupCount.setText(count + " 个");
                // 展开/折叠图标旋转
                boolean expanded = groupExpandedMap.getOrDefault(item.groupName, false);
                h.ivExpand.setRotation(expanded ? 90 : 0);
                // 点击展开/折叠
                h.itemView.setOnClickListener(v -> toggleGroup(item.groupName));
            } else {
                KeyVH h = (KeyVH) holder;
                KeyStoreManager.KeyEntry entry = item.entry;
                String label = entry.customCode.isEmpty()
                        ? String.format("%03d", entry.id) : entry.customCode;
                long masterId = AppGlobal.getKeyStoreManager().getMasterAccountId();
                if (masterId == entry.id) {
                    label = "[主] " + label;
                }
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
        ImageView ivExpand;

        HeaderVH(View v) {
            super(v);
            tvGroupName = v.findViewById(R.id.tv_group_name);
            tvGroupCount = v.findViewById(R.id.tv_group_count);
            viewColor = v.findViewById(R.id.view_color);
            ivExpand = v.findViewById(R.id.iv_expand);
        }
    }

    static class KeyVH extends RecyclerView.ViewHolder {
        TextView tvBadge, tvCode, tvAddr;
        SwitchCompat swEnabled;
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