package com.lgns.ui.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.task.TaskLogItem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 任务日志适配器：把运行日志渲染成「日期分组 + 日志卡片」。
 *
 * <p>三份状态刻意分开：{@link #all} 是库里读回来的原始集合（时间倒序），
 * {@link #shown} 是当前关键词与类型筛选后的展示序列（日期头与行混排），
 * 页面只负责 {@link #submit(List)} 交数据，条数与空态从 {@link #getShownCount()} 读。</p>
 *
 * <p>日期头只在日期变化时插入，且是在筛选之后插入，所以筛选结果里不会出现空的日期分组。
 * 日期头里显示「今天 / 昨天 / MM-dd」，行内只显示时分秒，避免每行重复一遍日期。</p>
 *
 * <p>筛选完全在内存里做：日志页一次读库即可，输入框敲字、切类型都不会再查库。</p>
 */
public class LogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** 日期分组头 */
    private static final int TYPE_DATE = 0;
    /** 日志卡片 */
    private static final int TYPE_ROW = 1;

    /** 取类型中文名用；只持有 Application 级 Context，不牵连页面生命周期 */
    private final Context ctx;
    /** 全量日志：submit 整体替换，筛选不回头再查库 */
    private final List<TaskLogItem> all = new ArrayList<>();
    /** 当前展示序列：日期头与日志行混排 */
    private final List<DisplayItem> shown = new ArrayList<>();

    /** 行内时间：日期已经在分组头里 */
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    /** 分组键：只用于判断「是否换了一天」，不直接上屏 */
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    /** 搜索关键词（已转小写；空串表示不过滤） */
    private String query = "";
    /** 类型筛选；null 表示全部 */
    private String typeFilter;
    /** 展示序列中日志行的条数（不含日期头） */
    private int shownRows;

    /** 展示序列中的一项：要么是日期头，要么是一条日志 */
    private static class DisplayItem {
        /** 非空表示日期头，值为 yyyy-MM-dd */
        final String day;
        /** 非空表示日志行 */
        final TaskLogItem item;

        private DisplayItem(String day, TaskLogItem item) {
            this.day = day;
            this.item = item;
        }

        static DisplayItem header(String day) {
            return new DisplayItem(day, null);
        }

        static DisplayItem row(TaskLogItem item) {
            return new DisplayItem(null, item);
        }

        boolean isHeader() {
            return item == null;
        }
    }

    public LogAdapter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // region 数据入口

    /** 整表替换：交给页面的是「库里全量」，当前搜索与类型筛选保持不变 */
    public void submit(List<TaskLogItem> list) {
        all.clear();
        if (list != null) {
            all.addAll(list);
        }
        rebuild();
    }

    /** 关键词筛选：编号、类型（英文与中文名）、正文任一命中即保留 */
    public void setQuery(String q) {
        String next = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (next.equals(query)) {
            return;
        }
        query = next;
        rebuild();
    }

    /** 类型筛选：传 null 表示全部 */
    public void setTypeFilter(String type) {
        typeFilter = type;
        rebuild();
    }

    /** 当前数据里出现过的类型（按最近出现顺序），页面据此生成筛选标签 */
    public List<String> getPresentTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (TaskLogItem item : all) {
            String type = item.logType;
            if (type != null && !type.isEmpty()) {
                types.add(type);
            }
        }
        return new ArrayList<>(types);
    }

    /** 库里读回来的总条数，用于区分「本来就没日志」与「筛选后没命中」 */
    public int getTotalCount() {
        return all.size();
    }

    /** 当前屏上的日志行数（不含日期头） */
    public int getShownCount() {
        return shownRows;
    }

    /** 类型英文名转中文标签，筛选标签与徽章共用同一套文案 */
    public String labelOf(String type) {
        return typeLabel(type);
    }

    // endregion

    // region 筛选与分组

    /**
     * 重建展示序列：先按条件筛，再按日期插分组头。
     *
     * <p>重排发生在筛选之后，所以被筛掉的日期不会留下空分组头。</p>
     */
    private void rebuild() {
        shown.clear();
        shownRows = 0;
        String lastDay = null;
        for (TaskLogItem item : all) {
            if (!matches(item)) {
                continue;
            }
            String day = dayFmt.format(new Date(item.timeMs));
            if (!day.equals(lastDay)) {
                shown.add(DisplayItem.header(day));
                lastDay = day;
            }
            shown.add(DisplayItem.row(item));
            shownRows++;
        }
        notifyDataSetChanged();
    }

    /** 一条日志是否命中当前筛选：类型必须完全相等，关键词是包含匹配 */
    private boolean matches(TaskLogItem item) {
        if (typeFilter != null && !typeFilter.equals(item.logType)) {
            return false;
        }
        if (query.isEmpty()) {
            return true;
        }
        return contains(item.keyNo)
                || contains(item.logType)
                || contains(typeLabel(item.logType))
                || contains(item.content);
    }

    /** 关键词已转小写，被搜字段一并转小写后再比，避免中英混排时大小写漏命中 */
    private boolean contains(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

    // endregion

    // region 列表回调

    @Override
    public int getItemCount() {
        return shown.size();
    }

    @Override
    public int getItemViewType(int position) {
        return shown.get(position).isHeader() ? TYPE_DATE : TYPE_ROW;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DATE) {
            return new DateVH(inflater.inflate(R.layout.item_log_header, parent, false));
        }
        return new RowVH(inflater.inflate(R.layout.item_log_row, parent, false));
    }

    /**
     * 绑定一项：日期头只填文案，行要按类型上色。
     *
     * <p>徽章是「浅底 + 同色系深字」，比纯色块更耐看，也不会在长列表里过于抢眼；
     * 未识别的类型统一走中性灰，不会因为后端新增类型而出现无样式黑块。</p>
     */
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DisplayItem displayItem = shown.get(position);
        if (displayItem.isHeader()) {
            ((DateVH) holder).tvDate.setText(dayLabel(displayItem.day));
            return;
        }

        RowVH vh = (RowVH) holder;
        TaskLogItem item = displayItem.item;
        String type = item.logType;

        vh.tvType.setText(typeLabel(type));
        vh.tvType.setTextColor(typeColor(type));
        tintPill(vh.tvType, typeBgColor(type));
        vh.tvKey.setText(item.keyNo != null && !item.keyNo.isEmpty() ? item.keyNo : "-");
        vh.tvTime.setText(timeFmt.format(new Date(item.timeMs)));
        vh.tvContent.setText(item.content != null ? item.content : "");
    }

    // endregion

    // region 文案与配色

    /** 日期键转展示文案：今天/昨天直接给中文，更早的只留「月-日」，避免一行太长 */
    private String dayLabel(String day) {
        String today = dayFmt.format(new Date());
        if (day.equals(today)) {
            return ctx.getString(R.string.log_day_today);
        }
        String yesterday = dayFmt.format(new Date(System.currentTimeMillis() - 24L * 60L * 60L * 1000L));
        if (day.equals(yesterday)) {
            return ctx.getString(R.string.log_day_yesterday);
        }
        return day.length() >= 10 ? day.substring(5) : day;
    }

    /** 类型英文名转中文：任务层写库用的是 ENTER/CLAIM 这类常量，界面不该直接暴露 */
    private String typeLabel(String type) {
        if (type == null) {
            return ctx.getString(R.string.log_type_default);
        }
        switch (type) {
            case "ENTER":
                return ctx.getString(R.string.log_type_enter);
            case "CLAIM":
                return ctx.getString(R.string.log_type_claim);
            case "FUND":
                return ctx.getString(R.string.log_type_fund);
            case "GAS":
                return ctx.getString(R.string.log_type_gas);
            case "TRANSFER":
                return ctx.getString(R.string.log_type_transfer);
            case "SQUAD":
                return ctx.getString(R.string.log_type_squad);
            case "DELEGATE":
                return ctx.getString(R.string.log_type_delegate);
            case "CONFIG":
                return ctx.getString(R.string.log_type_config);
            default:
                // 未知类型原样展示：宁可露英文，也不要显示成「日志」后无法区分
                return type;
        }
    }

    /** 徽章字色：每种任务阶段一个色系，扫日志时先看颜色再读文案 */
    private int typeColor(String type) {
        if (type == null) {
            return 0xFF607D8B;
        }
        switch (type) {
            case "ENTER":
                return 0xFF1976D2;
            case "CLAIM":
                return 0xFF2E7D32;
            case "FUND":
                return 0xFFEF6C00;
            case "GAS":
                return 0xFFF9A825;
            case "TRANSFER":
                return 0xFF6A1B9A;
            case "SQUAD":
                return 0xFF00838F;
            case "DELEGATE":
                return 0xFF455A64;
            case "CONFIG":
                return 0xFF5D4037;
            default:
                return 0xFF607D8B;
        }
    }

    /** 徽章底色：与字色同色系的浅色，保证对比度又不刺眼 */
    private int typeBgColor(String type) {
        if (type == null) {
            return 0xFFECEFF1;
        }
        switch (type) {
            case "ENTER":
                return 0xFFE3F2FD;
            case "CLAIM":
                return 0xFFE8F5E9;
            case "FUND":
                return 0xFFFFF3E0;
            case "GAS":
                return 0xFFFFF8E1;
            case "TRANSFER":
                return 0xFFF3E5F5;
            case "SQUAD":
                return 0xFFE0F7FA;
            case "DELEGATE":
                return 0xFFECEFF1;
            case "CONFIG":
                return 0xFFEFEBE9;
            default:
                return 0xFFECEFF1;
        }
    }

    /** 给胶囊底上色：逐行 mutate，避免多行共用同一份 drawable 状态互相覆盖 */
    private void tintPill(TextView view, int color) {
        if (view.getBackground() != null) {
            view.getBackground().mutate().setTintList(ColorStateList.valueOf(color));
        }
    }

    // endregion

    // region ViewHolder

    /** 日期分组头 ViewHolder：一个 TextView，文案在绑定时算 */
    static class DateVH extends RecyclerView.ViewHolder {
        final TextView tvDate;

        DateVH(View v) {
            super(v);
            tvDate = v.findViewById(R.id.tv_log_date);
        }
    }

    /** 日志行 ViewHolder：类型徽章、账号编号、时间、正文 */
    static class RowVH extends RecyclerView.ViewHolder {
        final TextView tvType, tvKey, tvTime, tvContent;

        RowVH(View v) {
            super(v);
            tvType = v.findViewById(R.id.tv_log_type);
            tvKey = v.findViewById(R.id.tv_log_key);
            tvTime = v.findViewById(R.id.tv_log_time);
            tvContent = v.findViewById(R.id.tv_log_content);
        }
    }

    // endregion

}
