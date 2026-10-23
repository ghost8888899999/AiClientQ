package com.lgns.ui.activity;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class BatchFundConfirmActivity extends AppCompatActivity {

    public static final String EXTRA_MASTER_ADDR = "master_addr";
    public static final String EXTRA_MASTER_KEYNO = "master_key_no";
    public static final String EXTRA_MASTER_BALANCE = "master_balance";
    public static final String EXTRA_FUND_LIST = "fund_list";
    public static final String EXTRA_TOTAL_AMOUNT = "total_amount";

    private TextView tvHeaderCount;
    private RecyclerView rvFundList;
    private View btnCancel, btnConfirm;

    private String masterAddr;
    private String masterKeyNo;
    private String masterBalance;
    private String totalAmount;
    private List<FundItem> fundList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_fund_confirm);

        masterAddr = getIntent().getStringExtra(EXTRA_MASTER_ADDR);
        masterKeyNo = getIntent().getStringExtra(EXTRA_MASTER_KEYNO);
        masterBalance = getIntent().getStringExtra(EXTRA_MASTER_BALANCE);
        totalAmount = getIntent().getStringExtra(EXTRA_TOTAL_AMOUNT);
        ArrayList<FundItem> list = getIntent().getParcelableArrayListExtra(EXTRA_FUND_LIST);
        fundList = list == null ? new ArrayList<>() : list;

        tvHeaderCount = findViewById(R.id.tv_header_count);
        rvFundList = findViewById(R.id.rv_fund_list);
        btnCancel = findViewById(R.id.btn_cancel);
        btnConfirm = findViewById(R.id.btn_confirm);

        rvFundList.setLayoutManager(new LinearLayoutManager(this));
        rvFundList.setAdapter(new FundAdapter());

        // 返回按钮统一模式
        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        btnCancel.setOnClickListener(v -> onBackPressed());
        btnConfirm.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });
    }

    public static class FundItem implements Parcelable {
        public String keyNo;
        public String currentBalance;
        public String needAmount;
        public String address;
        public boolean sufficient;

        public FundItem(String keyNo, String currentBalance, String needAmount, String address, boolean sufficient) {
            this.keyNo = keyNo;
            this.currentBalance = currentBalance;
            this.needAmount = needAmount;
            this.address = address;
            this.sufficient = sufficient;
        }

        protected FundItem(Parcel in) {
            keyNo = in.readString();
            currentBalance = in.readString();
            needAmount = in.readString();
            address = in.readString();
            sufficient = in.readByte() != 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(keyNo);
            dest.writeString(currentBalance);
            dest.writeString(needAmount);
            dest.writeString(address);
            dest.writeByte((byte) (sufficient ? 1 : 0));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<FundItem> CREATOR = new Creator<FundItem>() {
            @Override
            public FundItem createFromParcel(Parcel in) {
                return new FundItem(in);
            }

            @Override
            public FundItem[] newArray(int size) {
                return new FundItem[size];
            }
        };
    }

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;

    /** 头部（汇总卡/主号卡/明细标题）与明细行共用一个列表，滚动时一起上移 */
    class FundAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            return fundList.size() + 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_HEADER) {
                return new HeaderVH(inflater.inflate(R.layout.item_fund_header, parent, false));
            }
            return new ItemVH(inflater.inflate(R.layout.item_fund_confirm, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH) {
                bindHeader((HeaderVH) holder);
            } else {
                bindItem((ItemVH) holder, position - 1);
            }
        }

        private void bindHeader(HeaderVH h) {
            // 总额并入主号区，与主号余额同一行
            h.tvFundTotal.setText(totalAmount == null
                    ? getString(R.string.common_placeholder)
                    : getString(R.string.fund_balance_fmt, totalAmount));
            h.tvDetailCount.setText(getString(R.string.fund_detail_count_fmt, fundList.size()));
            tvHeaderCount.setText(getString(R.string.fund_hero_count_fmt, fundList.size()));

            h.tvMasterNo.setText(masterKeyNo == null || masterKeyNo.isEmpty()
                    ? getString(R.string.common_placeholder) : masterKeyNo);
            h.tvMasterAddr.setText(shortAddr(masterAddr));
            h.tvMasterBalance.setText(getString(R.string.fund_balance_fmt,
                    masterBalance == null ? getString(R.string.common_placeholder) : masterBalance));

            // 主号余额不够本次合计时提前提醒，避免逐笔转账全部失败
            BigDecimal total = parseDecimal(totalAmount);
            BigDecimal balance = parseDecimal(masterBalance);
            if (total != null && balance != null && total.signum() > 0 && balance.compareTo(total) < 0) {
                h.layoutMasterTip.setVisibility(View.VISIBLE);
                h.tvMasterTip.setText(getString(R.string.fund_master_insufficient_fmt,
                        total.subtract(balance).toPlainString()));
            } else {
                h.layoutMasterTip.setVisibility(View.GONE);
            }
        }

        private BigDecimal parseDecimal(String value) {
            if (value == null || value.isEmpty()) return null;
            try {
                return new BigDecimal(value);
            } catch (Exception e) {
                return null;
            }
        }

        // 与主界面账号行一致：0x1234...abcd
        private String shortAddr(String addr) {
            if (addr == null || addr.isEmpty()) return "";
            if (addr.length() > 10) {
                return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
            }
            return addr;
        }

        private void bindItem(ItemVH h, int index) {
            FundItem item = fundList.get(index);
            h.tvSeq.setText(String.valueOf(index + 1));
            h.tvKeyNo.setText(item.keyNo);
            h.tvAddr.setText(shortAddr(item.address));
            h.tvCurrentBalance.setText(getString(R.string.fund_item_balance_fmt, item.currentBalance));

            boolean zero = item.needAmount == null
                    || new java.math.BigDecimal(item.needAmount).signum() == 0;
            if (zero || item.sufficient) {
                h.tvNeedAmount.setText(R.string.fund_already_sufficient);
                h.tvNeedAmount.setTextColor(0xFF2E7D32);
            } else {
                h.tvNeedAmount.setText(getString(R.string.fund_amount_plus_fmt, item.needAmount));
                h.tvNeedAmount.setTextColor(0xFFE65100);
            }
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvFundTotal, tvDetailCount;
        TextView tvMasterNo, tvMasterAddr, tvMasterBalance, tvMasterTip;
        View layoutMasterTip;

        HeaderVH(View v) {
            super(v);
            tvFundTotal = v.findViewById(R.id.tv_fund_total);
            tvDetailCount = v.findViewById(R.id.tv_detail_count);
            tvMasterNo = v.findViewById(R.id.tv_master_no);
            tvMasterAddr = v.findViewById(R.id.tv_master_addr);
            tvMasterBalance = v.findViewById(R.id.tv_master_balance);
            tvMasterTip = v.findViewById(R.id.tv_master_tip);
            layoutMasterTip = v.findViewById(R.id.layout_master_tip);
        }
    }

    static class ItemVH extends RecyclerView.ViewHolder {
        TextView tvSeq, tvKeyNo, tvAddr, tvCurrentBalance, tvNeedAmount;

        ItemVH(View v) {
            super(v);
            tvSeq = v.findViewById(R.id.tv_seq);
            tvKeyNo = v.findViewById(R.id.tv_key_no);
            tvAddr = v.findViewById(R.id.tv_addr);
            tvCurrentBalance = v.findViewById(R.id.tv_current_balance);
            tvNeedAmount = v.findViewById(R.id.tv_need_amount);
        }
    }
}
