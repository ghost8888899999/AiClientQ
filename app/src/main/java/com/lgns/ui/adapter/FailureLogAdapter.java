package com.lgns.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.model.FailureLogItem;
import com.lgns.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FailureLogAdapter extends RecyclerView.Adapter<FailureLogAdapter.VH> {
    private List<FailureLogItem> itemList;

    public FailureLogAdapter() {
        this.itemList = new java.util.ArrayList<>();
    }

    public void setItemList(List<FailureLogItem> list) {
        this.itemList = list != null ? list : new java.util.ArrayList<>();
    }

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
        holder.tvReason.setText(item.reason);
        holder.tvTime.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(item.timeMs)));
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

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
}
