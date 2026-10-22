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

import java.util.ArrayList;
import java.util.List;

public class BatchFundConfirmActivity extends AppCompatActivity {

    public static final String EXTRA_MASTER_ADDR = "master_addr";
    public static final String EXTRA_MASTER_KEYNO = "master_key_no";
    public static final String EXTRA_MASTER_BALANCE = "master_balance";
    public static final String EXTRA_FUND_LIST = "fund_list";
    public static final String EXTRA_TOTAL_AMOUNT = "total_amount";

    private TextView tvMasterAddr, tvMasterBalance, tvSummary, tvMasterNo;
    private RecyclerView rvFundList;
    private View btnCancel, btnConfirm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_fund_confirm);

        String masterAddr = getIntent().getStringExtra(EXTRA_MASTER_ADDR);
        String masterKeyNo = getIntent().getStringExtra(EXTRA_MASTER_KEYNO);
        String masterBalance = getIntent().getStringExtra(EXTRA_MASTER_BALANCE);
        ArrayList<FundItem> fundList = getIntent().getParcelableArrayListExtra(EXTRA_FUND_LIST);
        String totalAmount = getIntent().getStringExtra(EXTRA_TOTAL_AMOUNT);

        tvMasterAddr = findViewById(R.id.tv_master_addr);
        tvMasterNo = findViewById(R.id.tv_master_no);
        tvMasterBalance = findViewById(R.id.tv_master_balance);
        tvSummary = findViewById(R.id.tv_summary);
        rvFundList = findViewById(R.id.rv_fund_list);
        btnCancel = findViewById(R.id.btn_cancel);
        btnConfirm = findViewById(R.id.btn_confirm);

        tvMasterNo.setText(masterKeyNo == null ? "" : masterKeyNo);
        tvMasterAddr.setText(masterAddr);
        tvMasterBalance.setText((masterBalance == null ? "--" : masterBalance) + " fLGNS");
        tvSummary.setText("共 " + fundList.size() + " 个账号 · 需转入合计 " + totalAmount + " fLGNS");

        rvFundList.setLayoutManager(new LinearLayoutManager(this));
        rvFundList.setAdapter(new FundAdapter(fundList));

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

    static class FundAdapter extends RecyclerView.Adapter<FundAdapter.VH> {
        private List<FundItem> itemList;

        FundAdapter(List<FundItem> list) {
            this.itemList = list;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_fund_confirm, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            FundItem item = itemList.get(position);
            holder.tvSeq.setText("#" + (position + 1));
            holder.tvKeyNo.setText(item.keyNo);
            String a = item.address;
            holder.tvAddr.setText(a == null ? "" :
                    (a.length() > 16 ? a.substring(0, 10) + "..." + a.substring(a.length() - 6) : a));
            holder.tvCurrentBalance.setText(item.currentBalance);
            boolean zero = item.needAmount == null
                    || new java.math.BigDecimal(item.needAmount).signum() == 0;
            holder.tvNeedAmount.setText(zero ? "已足额" : "+" + item.needAmount);
            holder.tvSuffTag.setVisibility(item.sufficient ? View.VISIBLE : View.GONE);
        }

        @Override
        public int getItemCount() {
            return itemList.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvSeq, tvKeyNo, tvAddr, tvCurrentBalance, tvNeedAmount, tvSuffTag;
            VH(View v) {
                super(v);
                tvSeq = v.findViewById(R.id.tv_seq);
                tvKeyNo = v.findViewById(R.id.tv_key_no);
                tvAddr = v.findViewById(R.id.tv_addr);
                tvCurrentBalance = v.findViewById(R.id.tv_current_balance);
                tvNeedAmount = v.findViewById(R.id.tv_need_amount);
                tvSuffTag = v.findViewById(R.id.tv_suff_tag);
            }
        }
    }
}
