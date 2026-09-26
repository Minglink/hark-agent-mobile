package com.openminis.app.ui.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ui-craft Grounded About Screen Community & Architecture Cards.
 * Implements high-contrast, professional mobile UI layout conforming to ui-craft specs.
 */
public class HarkAboutCardsView extends LinearLayout {

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    public HarkAboutCardsView(Context context) {
        super(context);
        init(context);
    }

    private void init(final Context context) {
        setOrientation(VERTICAL);
        int padH = dp(16);
        setPadding(padH, dp(8), padH, dp(16));

        int themeMode = AppearanceScreenKt.getThemeMode(context);
        boolean sysDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean isDark = (themeMode == 2) || (themeMode == 0 && sysDark);

        int cardBgColor = isDark ? Color.parseColor("#1E293B") : Color.parseColor("#FFFFFF");
        int cardBorderColor = isDark ? Color.parseColor("#334155") : Color.parseColor("#DBEAFE");
        int textPrimary = isDark ? Color.parseColor("#F8FAFC") : Color.parseColor("#0F172A");
        int textSecondary = isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#475569");
        int labelColor = isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#1E40AF");
        int dividerColor = isDark ? Color.parseColor("#334155") : Color.parseColor("#EFF6FF");
        int accentBlue = isDark ? Color.parseColor("#38BDF8") : Color.parseColor("#2563EB");
        int accentPillBg = isDark ? Color.parseColor("#0F2942") : Color.parseColor("#EFF6FF");
        int accentPillText = isDark ? Color.parseColor("#7DD3FC") : Color.parseColor("#1D4ED8");

        // ---- GROUP 1: 官方开源与社区 ----
        TextView group1Label = createGroupLabel(context, "官方开源与社区", labelColor);
        addView(group1Label);

        LinearLayout card1 = createCardContainer(cardBgColor, cardBorderColor);

        // Row 1: GitHub
        View githubRow = createActionRow(
            context,
            "GitHub 开源仓库",
            "Minglink/hark-agent-mobile",
            "↗ 前往",
            accentBlue,
            accentPillBg,
            accentPillText,
            textPrimary,
            textSecondary,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    HarkCommunityGuard.openGithub(context);
                }
            }
        );
        card1.addView(githubRow);

        card1.addView(createDivider(dividerColor, dp(16)));

        // Row 2: QQ Group
        View qqRow = createActionRow(
            context,
            "官方 QQ 交流群",
            "338431075 (点击直达 / 复制)",
            "加入群",
            accentBlue,
            accentPillBg,
            accentPillText,
            textPrimary,
            textSecondary,
            new OnClickListener() {
                @Override
                public void onClick(View v) {
                    HarkCommunityGuard.joinQqGroup(context);
                }
            }
        );
        card1.addView(qqRow);
        addView(card1);

        // ---- GROUP 2: 核心技术架构 ----
        TextView group2Label = createGroupLabel(context, "核心架构特性", labelColor);
        group2Label.setPadding(0, dp(24), 0, dp(8));
        addView(group2Label);

        LinearLayout card2 = createCardContainer(cardBgColor, cardBorderColor);

        card2.addView(createInfoRow(context, "独立 Linux 终端运行环境", "内置 aarch64 Alpine PRoot 沙箱，免 Root 运行 Linux CLI 工具与环境", textPrimary, textSecondary));
        card2.addView(createDivider(dividerColor, dp(16)));
        card2.addView(createInfoRow(context, "多子代理团队协作 (MoA)", "基于 DelegateTaskTool 动态派发子任务，实现多智能体高效协同", textPrimary, textSecondary));
        card2.addView(createDivider(dividerColor, dp(16)));
        card2.addView(createInfoRow(context, "工作区规范深度感知", "自动识别 .hark/PROJECT_PROMPT.md、SYSTEM.md，定制项目编码准则", textPrimary, textSecondary));
        card2.addView(createDivider(dividerColor, dp(16)));
        card2.addView(createInfoRow(context, "微上下文压缩引擎", "Micro-Compaction 智能裁剪冷输出，节约 Token 消耗并提升推理速度", textPrimary, textSecondary));
        card2.addView(createDivider(dividerColor, dp(16)));
        card2.addView(createInfoRow(context, "动态安全与防篡改防护", "双重动态混淆与运行时签名校验，防止二进制劫持与恶意篡改", textPrimary, textSecondary));

        addView(card2);

        // ---- FOOTER ----
        TextView footer = new TextView(context);
        footer.setText("Non-Commercial License · 严禁任何商业用途 · Hark Team");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setTextColor(textSecondary);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(24), 0, dp(16));
        addView(footer);
    }

    private TextView createGroupLabel(Context context, String title, int color) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(color);
        tv.setPadding(0, dp(4), 0, dp(8));
        return tv;
    }

    private LinearLayout createCardContainer(int bgColor, int borderColor) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), borderColor);
        card.setBackground(bg);
        return card;
    }

    private View createDivider(int color, int insetLeft) {
        View line = new View(getContext());
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(0.75f));
        lp.leftMargin = insetLeft;
        line.setLayoutParams(lp);
        line.setBackgroundColor(color);
        return line;
    }

    private View createActionRow(
        Context context,
        String title,
        String subtitle,
        String badgeText,
        int iconColor,
        int badgeBgColor,
        int badgeTextColor,
        int titleColor,
        int subColor,
        OnClickListener listener
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#20888888")), normal, null);
        row.setBackground(ripple);
        row.setClickable(true);
        row.setOnClickListener(listener);

        View iconDot = new View(context);
        int dotSize = dp(10);
        LayoutParams dotLp = new LayoutParams(dotSize, dotSize);
        dotLp.rightMargin = dp(14);
        iconDot.setLayoutParams(dotLp);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(iconColor);
        iconDot.setBackground(dotBg);
        row.addView(iconDot);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(VERTICAL);
        LayoutParams textLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
        textCol.setLayoutParams(textLp);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(titleColor);
        textCol.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText(subtitle);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvSub.setTextColor(subColor);
        LayoutParams subLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        tvSub.setLayoutParams(subLp);
        textCol.addView(tvSub);

        row.addView(textCol);

        TextView badge = new TextView(context);
        badge.setText(badgeText);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(badgeTextColor);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(badgeBgColor);
        badgeBg.setCornerRadius(dp(12));
        badge.setBackground(badgeBg);

        LayoutParams badgeLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(8);
        badge.setLayoutParams(badgeLp);
        row.addView(badge);

        return row;
    }

    private View createInfoRow(Context context, String title, String desc, int titleColor, int descColor) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(titleColor);
        row.addView(tvTitle);

        TextView tvDesc = new TextView(context);
        tvDesc.setText(desc);
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvDesc.setTextColor(descColor);
        tvDesc.setLineSpacing(dp(2), 1.0f);
        LayoutParams descLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(4);
        tvDesc.setLayoutParams(descLp);
        row.addView(tvDesc);

        return row;
    }
}
