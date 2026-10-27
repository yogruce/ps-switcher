package com.psswitcher.tv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Main Activity for PlayStation TV Switcher.
 *
 * Normal Mode:
 * - Immediately wakes any PS4 / PS5 / PS6 on the LAN (triggering HDMI-CEC auto-switch on boxes & TVs)
 * - Auto-detects the PlayStation HDMI input on the TV (or uses the saved HDMI port) and switches immediately
 * - Auto-closes itself after switching
 *
 * Setup / Override Mode:
 * - Activated automatically on first run ONLY if multiple unnamed HDMI ports exist and none matched "PlayStation"
 * - Or activated manually anytime by pressing DPAD_CENTER (OK) / MENU during the 1.2s splash screen.
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "ps_switcher_prefs";
    private static final String KEY_SAVED_INPUT_ID = "saved_input_id";
    private static final String KEY_SAVED_HDMI_INDEX = "saved_hdmi_index";
    private static final String KEY_SAVED_PS_IP = "saved_ps_ip";
    private static final String KEY_SAVED_PS_MAC = "saved_ps_mac";
    private static final String KEY_SAVED_PS_NAME = "saved_ps_name";
    private static final String KEY_CONFIGURED = "is_configured";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean userInterrupted = false;
    private boolean inSetupMode = false;

    private TextView statusTitle;
    private TextView statusSubtitle;
    private TextView networkStatusText;
    private TextView hintText;
    private LinearLayout setupContainer;
    private ProgressBar progressBar;

    private List<TvHdmiSwitcher.HdmiPort> detectedPorts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        startAutoSwitchWorkflow();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(48), dp(36), dp(48), dp(36));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#001B4D"), Color.parseColor("#003791"), Color.parseColor("#001133")}
        );
        root.setBackground(bg);

        // PlayStation Symbols Header (△ ○ × □)
        TextView symbolsView = new TextView(this);
        symbolsView.setText("△   ○   ×   □");
        symbolsView.setTextColor(Color.parseColor("#6898F8"));
        symbolsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        symbolsView.setTypeface(Typeface.DEFAULT_BOLD);
        symbolsView.setGravity(Gravity.CENTER);
        root.addView(symbolsView);

        // Main Title
        statusTitle = new TextView(this);
        statusTitle.setText("PlayStation'a Geçiliyor...");
        statusTitle.setTextColor(Color.WHITE);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(12);
        root.addView(statusTitle, titleParams);

        // Subtitle
        statusSubtitle = new TextView(this);
        statusSubtitle.setText("HDMI kaynağı ve ağdaki konsol kontrol ediliyor...");
        statusSubtitle.setTextColor(Color.parseColor("#B0C8FF"));
        statusSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        statusSubtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(8);
        root.addView(statusSubtitle, subParams);

        // Progress indicator
        progressBar = new ProgressBar(this);
        LinearLayout.LayoutParams progParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        progParams.topMargin = dp(20);
        root.addView(progressBar, progParams);

        // Network Console Discovery Status Badge
        networkStatusText = new TextView(this);
        networkStatusText.setText("Ağ Tespiti: PS4 / PS5 / PS6 aranıyor...");
        networkStatusText.setTextColor(Color.parseColor("#00E5B8"));
        networkStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        networkStatusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams netParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        netParams.topMargin = dp(16);
        root.addView(networkStatusText, netParams);

        // Scrollable Setup Container (hidden during instant auto-switch unless needed)
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                dp(620), ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(18);

        setupContainer = new LinearLayout(this);
        setupContainer.setOrientation(LinearLayout.VERTICAL);
        setupContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        setupContainer.setVisibility(View.GONE);
        scrollView.addView(setupContainer);
        root.addView(scrollView, scrollParams);

        // Bottom Hint for Remote Control
        hintText = new TextView(this);
        hintText.setText("İpucu: Kaynak ayarlarını değiştirmek için kumandadan [OK] tuşuna basın");
        hintText.setTextColor(Color.parseColor("#88A8E8"));
        hintText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hintText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(22);
        root.addView(hintText, hintParams);

        setContentView(root);
    }

    private void startAutoSwitchWorkflow() {
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 1. Start background network discovery & wake-up for PS4 / PS5 / PS6 immediately
        new Thread(() -> {
            // If we already have a saved console, send wake packet immediately for zero-latency
            String savedIp = prefs.getString(KEY_SAVED_PS_IP, null);
            String savedMac = prefs.getString(KEY_SAVED_PS_MAC, null);
            String savedName = prefs.getString(KEY_SAVED_PS_NAME, null);
            if (savedIp != null) {
                PlayStationDiscovery.ConsoleInfo cached = new PlayStationDiscovery.ConsoleInfo();
                cached.ip = savedIp;
                cached.hostId = savedMac;
                cached.name = savedName;
                PlayStationDiscovery.wakeConsole(MainActivity.this, cached, null);
            } else {
                // Broadcast wake packet to wake any standby PlayStation on LAN right away
                PlayStationDiscovery.wakeConsole(MainActivity.this, null, null);
            }

            // Now scan LAN to detect/update PS4, PS5, or PS6 IP & status
            List<PlayStationDiscovery.ConsoleInfo> consoles = PlayStationDiscovery.discoverConsoles(MainActivity.this, 900);
            if (!consoles.isEmpty()) {
                PlayStationDiscovery.ConsoleInfo found = consoles.get(0);
                prefs.edit()
                        .putString(KEY_SAVED_PS_IP, found.ip)
                        .putString(KEY_SAVED_PS_NAME, found.name)
                        .putString(KEY_SAVED_PS_MAC, found.hostId != null ? found.hostId : savedMac)
                        .apply();

                // Send targeted wake packet to the discovered console
                PlayStationDiscovery.wakeConsole(MainActivity.this, found, null);

                handler.post(() -> {
                    String stateStr = found.isStandby ? "Uyandırılıyor (Standby -> Açık)" : "Açık / Aktif";
                    networkStatusText.setText("✓ Otomatik Tespit: " + found.name + " [" + found.ip + "] - " + stateStr);
                });
            } else {
                handler.post(() -> {
                    if (savedIp != null) {
                        networkStatusText.setText("✓ Kayıtlı Konsol Uyandırıldı: " + (savedName != null ? savedName : "PlayStation") + " (" + savedIp + ")");
                    } else {
                        networkStatusText.setText("Ağ üzerinden PS4/PS5/PS6 uyandırma sinyali gönderildi (HDMI-CEC)");
                    }
                });
            }
        }).start();

        // 2. Scan TV HDMI inputs via TvInputManager
        detectedPorts = TvHdmiSwitcher.getHdmiPorts(this);
        TvHdmiSwitcher.HdmiPort autoPort = TvHdmiSwitcher.autoDetectPlayStationPort(detectedPorts);

        String savedInputId = prefs.getString(KEY_SAVED_INPUT_ID, null);
        int savedHdmiIndex = prefs.getInt(KEY_SAVED_HDMI_INDEX, -1);
        boolean isConfigured = prefs.getBoolean(KEY_CONFIGURED, false);

        // Priority 1: If HDMI-CEC automatically matched "PlayStation" / "PS5" / "PS4" / "PS6" on a port!
        if (autoPort != null && autoPort.isPlayStationMatched) {
            savePortSelection(autoPort.inputId, autoPort.hdmiIndex);
            statusSubtitle.setText("Otomatik tespit edildi: " + autoPort.getDisplayName());
            triggerSwitchAndFinish(autoPort.inputId, autoPort.hdmiIndex, 1100);
            return;
        }

        // Priority 2: User previously selected a port (or box mode)
        if (isConfigured) {
            if ("CEC_ONLY".equals(savedInputId)) {
                statusSubtitle.setText("Mi Box / HDMI-CEC üzerinden PlayStation uyandırılıyor ve geçiş yapılıyor...");
                triggerSwitchAndFinish(null, savedHdmiIndex, 1400);
            } else {
                statusSubtitle.setText("HDMI " + (savedHdmiIndex > 0 ? savedHdmiIndex : "") + " kaynağına geçiliyor...");
                triggerSwitchAndFinish(savedInputId, savedHdmiIndex, 1000);
            }
            return;
        }

        // Priority 3: Only 1 active HDMI port found on the TV -> Auto select and switch!
        if (autoPort != null) {
            savePortSelection(autoPort.inputId, autoPort.hdmiIndex);
            statusSubtitle.setText("Aktif HDMI otomatik tespit edildi: " + autoPort.getDisplayName());
            triggerSwitchAndFinish(autoPort.inputId, autoPort.hdmiIndex, 1200);
            return;
        }

        // Priority 4: Device is an external Android TV Box (0 TvInput HDMI ports exposed)
        // or a TV with multiple unnamed HDMI ports on first launch -> Show quick 1-time selector
        if (detectedPorts.isEmpty()) {
            // External TV Box (e.g., Xiaomi Mi Box / Stick): Save CEC/Network Wake mode by default so next launches are instant,
            // but also show the quick selector in case the user is on a custom TV ROM that hides TvInputManager.
            savePortSelection("CEC_ONLY", 2);
            statusSubtitle.setText("PlayStation ağdan uyandırıldı (HDMI-CEC otomatik geçiş bekleniyor)...");
            triggerSwitchAndFinish(null, 2, 1800);
        } else {
            // Multiple HDMI ports exist on the TV, ask once so every future click is 100% instant
            showSetupSelector();
        }
    }

    private void triggerSwitchAndFinish(final String inputId, final int hdmiIndex, int delayMs) {
        handler.postDelayed(() -> {
            if (userInterrupted || inSetupMode) return;
            if (inputId != null && !"CEC_ONLY".equals(inputId)) {
                TvHdmiSwitcher.switchToHdmi(MainActivity.this, inputId, hdmiIndex);
            } else if (hdmiIndex > 0) {
                TvHdmiSwitcher.switchToHdmi(MainActivity.this, null, hdmiIndex);
            }
            handler.postDelayed(() -> {
                if (!userInterrupted && !inSetupMode) {
                    finish();
                }
            }, 450);
        }, delayMs);
    }

    private void showSetupSelector() {
        inSetupMode = true;
        userInterrupted = true;
        progressBar.setVisibility(View.GONE);
        setupContainer.setVisibility(View.VISIBLE);
        setupContainer.removeAllViews();

        statusTitle.setText("PlayStation Giriş Seçimi");
        statusSubtitle.setText("PlayStation'ın bağlı olduğu HDMI portunu bir kez seçin.\nSonraki tıklamalarda doğrudan otomatik geçecektir:");
        hintText.setText("Kumandanın yön tuşları ve [OK] tuşu ile seçim yapabilirsiniz");

        // 1. Add detected TvInputManager HDMI ports (if running directly on a Xiaomi Mi TV / Android TV)
        if (detectedPorts != null && !detectedPorts.isEmpty()) {
            for (final TvHdmiSwitcher.HdmiPort port : detectedPorts) {
                Button btn = createStyledButton("📺  " + port.getDisplayName());
                btn.setOnClickListener(v -> {
                    savePortSelection(port.inputId, port.hdmiIndex);
                    Toast.makeText(MainActivity.this, port.getDisplayName() + " kaydedildi!", Toast.LENGTH_SHORT).show();
                    TvHdmiSwitcher.switchToHdmi(MainActivity.this, port.inputId, port.hdmiIndex);
                    finish();
                });
                setupContainer.addView(btn);
            }
        } else {
            // Virtual HDMI 1 - 4 buttons for TVs that don't list TvInputInfo or use keyevents
            for (int i = 1; i <= 4; i++) {
                final int hdmiNum = i;
                Button btn = createStyledButton("📺  HDMI " + hdmiNum + " (Doğrudan Geçiş Komutu)");
                btn.setOnClickListener(v -> {
                    savePortSelection("VIRTUAL_HDMI_" + hdmiNum, hdmiNum);
                    Toast.makeText(MainActivity.this, "HDMI " + hdmiNum + " kaydedildi!", Toast.LENGTH_SHORT).show();
                    TvHdmiSwitcher.switchToHdmi(MainActivity.this, null, hdmiNum);
                    finish();
                });
                setupContainer.addView(btn);
            }
        }

        // 2. Option for External Xiaomi Mi Box / Stick (Auto Wake PS4/PS5/PS6 + HDMI-CEC Switch)
        Button cecBtn = createStyledButton("🎮  Harici TV Kutusu (Mi Box / Stick) - Otomatik Uyandır & CEC Geçişi");
        cecBtn.setOnClickListener(v -> {
            savePortSelection("CEC_ONLY", 0);
            new Thread(() -> PlayStationDiscovery.wakeConsole(MainActivity.this, null, null)).start();
            Toast.makeText(MainActivity.this, "Otomatik Ağ + HDMI-CEC modu kaydedildi!", Toast.LENGTH_SHORT).show();
            finish();
        });
        setupContainer.addView(cecBtn);

        // Request focus on the first button for TV D-Pad navigation
        if (setupContainer.getChildCount() > 0) {
            setupContainer.getChildAt(0).requestFocus();
        }
    }

    private Button createStyledButton(String text) {
        final Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        btn.setPadding(dp(24), dp(14), dp(24), dp(14));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);

        final GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(Color.parseColor("#1A3B7A"));
        normalBg.setCornerRadius(dp(10));
        normalBg.setStroke(dp(2), Color.parseColor("#3B69C6"));

        final GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setColor(Color.parseColor("#0055D4"));
        focusedBg.setCornerRadius(dp(10));
        focusedBg.setStroke(dp(3), Color.parseColor("#00E5B8"));

        btn.setBackground(normalBg);
        btn.setOnFocusChangeListener((v, hasFocus) -> btn.setBackground(hasFocus ? focusedBg : normalBg));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void savePortSelection(String inputId, int hdmiIndex) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_SAVED_INPUT_ID, inputId)
                .putInt(KEY_SAVED_HDMI_INDEX, hdmiIndex)
                .putBoolean(KEY_CONFIGURED, true)
                .apply();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!inSetupMode && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_SETTINGS)) {
            showSetupSelector();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
