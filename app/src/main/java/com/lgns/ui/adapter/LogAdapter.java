package com.lgns.ui.adapter;

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
import java.util.List;
import java.util.Locale;

public class LogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;

    private final List<TaskLogItem> items = new ArrayList<>();
    private final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    public void setItems(List<TaskLogItem> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_ROW;
    }

    @Override
    public int getItemCount() {
        return items.isEmpty() ? 0 : items.size() + 1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inflater.inflate(R.layout.item_log_header, parent, false));
        }
        return new RowVH(inflater.inflate(R.layout.item_log_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof RowVH) {
            TaskLogItem item = items.get(position - 1);
            RowVH vh = (RowVH) holder;
            vh.tvTime.setText(sdf.format(new Date(item.timeMs)));
            vh.tvKey.setText(item.keyNo != null ? item.keyNo : "-");
            vh.tvType.setText(item.logType != null ? item.logType : "");
            vh.tvContent.setText(item.content);

            int typeColor = "ENTER".equals(item.logType) ? 0xFF1976D2 :
                    "CLAIM".equals(item.logType) ? 0xFF388E3C : 0xFF757575;
            vh.tvType.setTextColor(typeColor);
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        HeaderVH(View v) { super(v); }
    }

    static class RowVH extends RecyclerView.ViewHolder {
        TextView tvTime, tvKey, tvType, tvContent;
        RowVH(View v) {
            super(v);
            tvTime = v.findViewById(R.id.tv_log_time);
            tvKey = v.findViewById(R.id.tv_log_key);
            tvType = v.findViewById(R.id.tv_log_type);
            tvContent = v.findViewById(R.id.tv_log_content);
        }
    }
}
