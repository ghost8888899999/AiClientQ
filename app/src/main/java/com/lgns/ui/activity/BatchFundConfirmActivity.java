package com.lgns.ui.activity;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
    public static final String EXTRA_MASTER_BALANCE = "master_balance";
    public static final String EXTRA_FUND_LIST = "fund_list";
    public static final String EXTRA_TOTAL_AMOUNT = "total_amount";

    private TextView tvMasterAddr, tvMasterBalance, tvSummary;
    private RecyclerView rvFundList;
    private Button btnCancel, btnConfirm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_fund_confirm);

        String masterAddr = getIntent().getStringExtra(EXTRA_MASTER_ADDR);
        String masterBalance = getIntent().getStringExtra(EXTRA_MASTER_BALANCE);
        ArrayList<FundItem> fundList = getIntent().getParcelableArrayListExtra(EXTRA_FUND_LIST);
        String totalAmount = getIntent().getStringExtra(EXTRA_TOTAL_AMOUNT);

        tvMasterAddr = findViewById(R.id.tv_master_addr);
        tvMasterBalance = findViewById(R.id.tv_master_balance);
        tvSummary = findViewById(R.id.tv_summary);
        rvFundList = findViewById(R.id.rv_fund_list);
        btnCancel = findViewById(R.id.btn_cancel);
        btnConfirm = findViewById(R.id.btn_confirm);

        tvMasterAddr.setText("主号：" + masterAddr);
        tvMasterBalance.setText("主号余额：" + masterBalance + " fLGNS");
        tvSummary.setText("共 " + fundList.size() + " 个账号，需转入合计 " + totalAmount + " fLGNS");

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

        public FundItem(String keyNo, String currentBalance, String needAmount) {
            this.keyNo = keyNo;
            this.currentBalance = currentBalance;
            this.needAmount = needAmount;
        }

        protected FundItem(Parcel in) {
            keyNo = in.readString();
            currentBalance = in.readString();
            needAmount = in.readString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(keyNo);
            dest.writeString(currentBalance);
            dest.writeString(needAmount);
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
            holder.tvSeq.setText(String.valueOf(position + 1));
            holder.tvKeyNo.setText(item.keyNo);
            holder.tvCurrentBalance.setText("余额：" + item.currentBalance);
            holder.tvNeedAmount.setText("+" + item.needAmount);
        }

        @Override
        public int getItemCount() {
            return itemList.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvSeq, tvKeyNo, tvCurrentBalance, tvNeedAmount;
            VH(View v) {
                super(v);
                tvSeq = v.findViewById(R.id.tv_seq);
                tvKeyNo = v.findViewById(R.id.tv_key_no);
                tvCurrentBalance = v.findViewById(R.id.tv_current_balance);
                tvNeedAmount = v.findViewById(R.id.tv_need_amount);
            }
        }
    }
}
