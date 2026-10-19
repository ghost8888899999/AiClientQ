package com.lgns.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.model.DailyStatusItem;
import com.lgns.R;

import java.util.List;

public class DailyStatusAdapter extends RecyclerView.Adapter<DailyStatusAdapter.VH> {
    private List<DailyStatusItem> itemList;

    public DailyStatusAdapter() {
        this.itemList = new java.util.ArrayList<>();
    }

    public void setItemList(List<DailyStatusItem> list) {
        this.itemList = list != null ? list : new java.util.ArrayList<>();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_daily_status, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        DailyStatusItem item = itemList.get(position);
        holder.tvSeq.setText(String.valueOf(position + 1));
        holder.tvKeyNo.setText(item.keyNo);
        holder.tvTotalQd.setText(String.valueOf(item.totalQd));
        holder.tvTodayQd.setText(String.valueOf(item.todayQd));
        holder.tvTodayEnter.setText(String.valueOf(item.todayEnter));
        if (item.isFailed) {
            holder.tvStatusBadge.setVisibility(View.VISIBLE);
            holder.tvStatusBadge.setText("失败");
            holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_badge_fail);
            holder.itemRoot.setBackgroundColor(0xFFFFFBFB);
        } else {
            holder.tvStatusBadge.setVisibility(View.VISIBLE);
            holder.tvStatusBadge.setText("正常");
            holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_badge);
            holder.itemRoot.setBackgroundColor(0xFFFFFFFF);
        }
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvSeq, tvKeyNo, tvTotalQd, tvTodayQd, tvTodayEnter, tvStatusBadge;
        View itemRoot;
        VH(View v) {
            super(v);
            itemRoot = v.findViewById(R.id.item_root);
            tvSeq = v.findViewById(R.id.tv_seq);
            tvKeyNo = v.findViewById(R.id.tv_key_no);
            tvTotalQd = v.findViewById(R.id.tv_total_qd);
            tvTodayQd = v.findViewById(R.id.tv_today_qd);
            tvTodayEnter = v.findViewById(R.id.tv_today_enter);
            tvStatusBadge = v.findViewById(R.id.tv_status_badge);
        }
    }
}
