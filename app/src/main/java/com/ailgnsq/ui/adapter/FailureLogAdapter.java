package com.ailgnsq.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ailgnsq.model.FailureLogItem;
import com.ailgnsq.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 失败记录适配器：只读展示失败日志条目（账号、环节、原因、时间）。
 *
 * <p>没有交互也没有分组，属于最简的一种列表；页面替换数据时直接整表刷新。</p>
 */
public class FailureLogAdapter extends RecyclerView.Adapter<FailureLogAdapter.VH> {
    /** 失败条目（页面已经按时间倒序排好，适配器不再排序） */
    private List<FailureLogItem> itemList;
    // region 构造与数据入口

    /** 空表构造：先给个空列表占位，避免未调 setItemList 就直接刷新时空指针 */
    public FailureLogAdapter() {
        this.itemList = new java.util.ArrayList<>();
    }

    /** 换数据：传 null 当空表处理，避免调用方忘记判空时 NPE */
    public void setItemList(List<FailureLogItem> list) {
        this.itemList = list != null ? list : new java.util.ArrayList<>();
    }

    // endregion

    // region 列表回调

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_failure_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        FailureLogItem item = itemList.get(position);
        holder.tvKeyNo.setText(item.keyNo);
        holder.tvFailType.setText(item.failType);
        holder.tvReason.setText(item.failReason);
        holder.tvTime.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(item.timeMs)));
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    // endregion

    // region ViewHolder

    /** 失败记录行 ViewHolder：编号、失败类型、原因、时间 */
    static class VH extends RecyclerView.ViewHolder {
        TextView tvKeyNo, tvFailType, tvReason, tvTime;
        VH(View v) {
            super(v);
            tvKeyNo = v.findViewById(R.id.tv_key_no);
            tvFailType = v.findViewById(R.id.tv_fail_type);
            tvReason = v.findViewById(R.id.tv_reason);
            tvTime = v.findViewById(R.id.tv_time);
        }
    }

    // endregion

}
