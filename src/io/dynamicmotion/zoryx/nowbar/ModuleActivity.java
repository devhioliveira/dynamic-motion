package io.dynamicmotion.zoryx.nowbar;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ModuleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setBackgroundColor(0xFF101014);
        int horizontal = dp(28);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(horizontal + system.left, dp(24) + system.top,
                    horizontal + system.right, dp(24) + system.bottom);
            return insets;
        });
        TextView heading = text("Dynamic Motion 3.0", 30, Color.WHITE);
        content.addView(heading);
        TextView steps = text("Desative o OneUisland no LSPosed. Ative o Dynamic Motion com o escopo"
                + " System UI e reinicie. Abra uma atividade da Now Bar, como música ou cronômetro."
                + "\n\nToque na ilha para expandir. Mantenha pressionado para abrir o app de origem"
                + " quando a atividade oferecer essa ação.", 16, 0xFFDDDDE4);
        LinearLayout.LayoutParams instructions = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instructions.topMargin = dp(18);
        content.addView(steps, instructions);
        setContentView(content);
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
