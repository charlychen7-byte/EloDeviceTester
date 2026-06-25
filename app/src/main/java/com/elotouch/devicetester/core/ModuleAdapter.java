package com.elotouch.devicetester.core;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Renders the level-1 module grid. Unsupported modules are greyed out and
 * non-clickable, with a "硬件不支持" label (PRD §5.3).
 */
public class ModuleAdapter extends RecyclerView.Adapter<ModuleAdapter.Holder> {

    public interface OnModuleClick {
        void onClick(TestModule module);
    }

    private final Context context;
    private final TestModule[] modules = TestModule.values();
    private final OnModuleClick listener;

    public ModuleAdapter(Context context, OnModuleClick listener) {
        this.context = context;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int pad = dp(12);

        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(pad, pad, pad, pad);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        int m = dp(6);
        lp.setMargins(m, m, m, m);
        cell.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#E0E0E0"));
        cell.setBackground(bg);

        TextView icon = new TextView(context);
        icon.setTextSize(34);
        icon.setGravity(Gravity.CENTER);
        cell.addView(icon);

        TextView name = new TextView(context);
        name.setTextSize(13);
        name.setGravity(Gravity.CENTER);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setPadding(0, dp(4), 0, 0);
        cell.addView(name);

        TextView status = new TextView(context);
        status.setTextSize(10);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.parseColor("#9E9E9E"));
        cell.addView(status);

        return new Holder(cell, icon, name, status);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        TestModule module = modules[position];
        boolean available = HardwareDetector.isAvailable(context, module.feature);

        h.icon.setText(module.icon);
        h.name.setText(module.title);

        if (available) {
            h.status.setVisibility(View.GONE);
            h.itemView.setAlpha(1f);
            h.itemView.setEnabled(true);
            h.itemView.setOnClickListener(v -> listener.onClick(module));
        } else {
            h.status.setVisibility(View.VISIBLE);
            h.status.setText("硬件不支持");
            h.itemView.setAlpha(0.4f);
            h.itemView.setEnabled(false);
            h.itemView.setOnClickListener(null);
        }
    }

    @Override
    public int getItemCount() {
        return modules.length;
    }

    private int dp(int v) {
        return Math.round(context.getResources().getDisplayMetrics().density * v);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView icon, name, status;

        Holder(View itemView, TextView icon, TextView name, TextView status) {
            super(itemView);
            this.icon = icon;
            this.name = name;
            this.status = status;
        }
    }
}
