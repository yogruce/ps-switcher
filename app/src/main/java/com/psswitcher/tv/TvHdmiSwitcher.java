package com.psswitcher.tv;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Handles automatic detection of PlayStation HDMI ports via TvInputManager / HDMI-CEC
 * and switches the TV directly to the target HDMI port across Xiaomi Mi TV, Google TV,
 * Sony, TCL, Philips, and generic Android TV boxes.
 */
public class TvHdmiSwitcher {

    public static class HdmiPort {
        public String inputId;
        public String label;
        public String customLabel;
        public int state; // CONNECTED, CONNECTED_STANDBY, DISCONNECTED
        public boolean isPlayStationMatched;
        public int hdmiIndex; // 1, 2, 3, 4

        public String getDisplayName() {
            StringBuilder sb = new StringBuilder();
            if (customLabel != null && !customLabel.isEmpty() && !customLabel.equalsIgnoreCase(label)) {
                sb.append(label).append(" (").append(customLabel).append(")");
            } else if (label != null && !label.isEmpty()) {
                sb.append(label);
            } else {
                sb.append("HDMI ").append(hdmiIndex > 0 ? hdmiIndex : "");
            }

            if (isPlayStationMatched) {
                sb.append(" [PlayStation Tespit Edildi]");
            } else if (state == TvInputManager.INPUT_STATE_CONNECTED) {
                sb.append(" [Aktif / Bağlı]");
            } else if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) {
                sb.append(" [Beklemede / Bağlı]");
            }
            return sb.toString();
        }
    }

    /**
     * Scans all TV inputs on the device and returns HDMI inputs (or fallback virtual HDMI 1-4 ports).
     */
    public static List<HdmiPort> getHdmiPorts(Context context) {
        List<HdmiPort> hdmiPorts = new ArrayList<>();
        TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);

        if (manager != null) {
            try {
                List<TvInputInfo> inputs = manager.getTvInputList();
                int idx = 1;
                for (TvInputInfo info : inputs) {
                    if (info.getType() == TvInputInfo.TYPE_HDMI || isLikelyHdmiInput(info)) {
                        HdmiPort port = new HdmiPort();
                        port.inputId = info.getId();
                        CharSequence lbl = info.loadLabel(context);
                        port.label = lbl != null ? lbl.toString().trim() : ("HDMI " + idx);

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            CharSequence custom = info.loadCustomLabel(context);
                            if (custom != null) {
                                port.customLabel = custom.toString().trim();
                            }
                        }

                        try {
                            port.state = manager.getInputState(info.getId());
                        } catch (Exception e) {
                            port.state = TvInputManager.INPUT_STATE_CONNECTED;
                        }

                        port.hdmiIndex = extractHdmiNumber(port.label, port.inputId, idx);
                        port.isPlayStationMatched = matchesPlayStationName(port.label)
                                || matchesPlayStationName(port.customLabel)
                                || matchesPlayStationName(port.inputId);

                        hdmiPorts.add(port);
                        idx++;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return hdmiPorts;
    }

    /**
     * Attempts to automatically find the PlayStation HDMI port without user intervention:
     * 1. Checks if any HDMI input has CEC/Custom label matching "PlayStation", "PS4", "PS5", "PS6", "Sony"
     * 2. If only ONE HDMI input is currently in CONNECTED or CONNECTED_STANDBY state, selects it automatically.
     */
    public static HdmiPort autoDetectPlayStationPort(List<HdmiPort> ports) {
        if (ports == null || ports.isEmpty()) return null;

        // 1. Direct name match via HDMI-CEC or custom label
        for (HdmiPort port : ports) {
            if (port.isPlayStationMatched) {
                return port;
            }
        }

        // 2. If exactly one HDMI port is connected/active, return it automatically
        List<HdmiPort> connected = new ArrayList<>();
        for (HdmiPort port : ports) {
            if (port.state == TvInputManager.INPUT_STATE_CONNECTED
                    || port.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) {
                connected.add(port);
            }
        }
        if (connected.size() == 1) {
            return connected.get(0);
        }

        // 3. If there is only 1 HDMI port total on the device
        if (ports.size() == 1) {
            return ports.get(0);
        }

        return null;
    }

    private static boolean isLikelyHdmiInput(TvInputInfo info) {
        String id = info.getId() != null ? info.getId().toLowerCase(Locale.ROOT) : "";
        return id.contains("hdmi") || id.contains("hw5") || id.contains("passthrough");
    }

    private static boolean matchesPlayStationName(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("playstation")
                || lower.contains("ps4")
                || lower.contains("ps5")
                || lower.contains("ps6")
                || lower.contains("ps4 pro")
                || lower.contains("ps5 pro")
                || lower.contains("sony interactive");
    }

    private static int extractHdmiNumber(String label, String inputId, int fallback) {
        String combined = ((label != null ? label : "") + " " + (inputId != null ? inputId : "")).toLowerCase(Locale.ROOT);
        for (int i = 1; i <= 4; i++) {
            if (combined.contains("hdmi " + i) || combined.contains("hdmi" + i) || combined.endsWith("/hw" + i)) {
                return i;
            }
        }
        return fallback;
    }

    /**
     * Switches the TV to the specified HDMI input ID or HDMI port index (1..4).
     * Returns true if an Android TV intent was successfully dispatched.
     */
    public static boolean switchToHdmi(Context context, String inputId, int hdmiIndex) {
        boolean switched = false;

        // Method 1: Standard Android TV Passthrough Input URI (Works on Xiaomi Mi TV, Google TV, Sony, TCL, Philips)
        if (inputId != null && !inputId.isEmpty() && !inputId.startsWith("VIRTUAL_HDMI_")) {
            try {
                Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                if (canResolve(context, intent)) {
                    context.startActivity(intent);
                    switched = true;
                } else {
                    // Try known OEM TV Player packages explicitly (Xiaomi PatchWall / MiTV, Google Live Channels, etc.)
                    String[] tvPackages = {
                            "com.xiaomi.mitv.tvplayer",
                            "com.mitv.tvhome",
                            "com.android.tv",
                            "com.google.android.tv",
                            "com.tcl.tv",
                            "com.sony.dtv.tvx",
                            "org.droidlogic.tvinput"
                    };
                    for (String pkg : tvPackages) {
                        Intent pkgIntent = new Intent(Intent.ACTION_VIEW, uri);
                        pkgIntent.setPackage(pkg);
                        pkgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        if (canResolve(context, pkgIntent)) {
                            context.startActivity(pkgIntent);
                            switched = true;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // Method 2: TvInputInfo SETUP / Switch broadcast for Xiaomi / Amlogic / MediaTek TVs
            if (!switched) {
                try {
                    Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
                    Intent intent = new Intent("android.intent.action.VIEW");
                    intent.setDataAndType(uri, "vnd.android.cursor.item/channel");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    switched = true;
                } catch (Exception ignored) {
                }
            }
        }

        // Method 3: Xiaomi Mi TV specific source switch intent
        if (!switched && hdmiIndex >= 1 && hdmiIndex <= 4) {
            try {
                Intent miIntent = new Intent("com.xiaomi.mitv.action.SWITCH_SOURCE");
                miIntent.putExtra("source", "hdmi" + hdmiIndex);
                miIntent.putExtra("input_id", inputId);
                miIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (canResolve(context, miIntent)) {
                    context.startActivity(miIntent);
                    switched = true;
                }
            } catch (Exception ignored) {
            }
        }

        // Method 4: Send HDMI KeyEvent (KEYCODE_TV_INPUT_HDMI_1..4) in background thread
        if (hdmiIndex >= 1 && hdmiIndex <= 4) {
            final int keyCode = getHdmiKeyCode(hdmiIndex);
            new Thread(() -> {
                try {
                    Instrumentation inst = new Instrumentation();
                    inst.sendKeyDownUpSync(keyCode);
                } catch (Exception ignored) {
                    try {
                        Runtime.getRuntime().exec(new String[]{"input", "keyevent", String.valueOf(keyCode)});
                    } catch (Exception ignored2) {
                    }
                }
            }).start();
        }

        return switched;
    }

    private static int getHdmiKeyCode(int hdmiIndex) {
        switch (hdmiIndex) {
            case 1:
                return KeyEvent.KEYCODE_TV_INPUT_HDMI_1;
            case 2:
                return KeyEvent.KEYCODE_TV_INPUT_HDMI_2;
            case 3:
                return KeyEvent.KEYCODE_TV_INPUT_HDMI_3;
            case 4:
                return KeyEvent.KEYCODE_TV_INPUT_HDMI_4;
            default:
                return KeyEvent.KEYCODE_TV_INPUT;
        }
    }

    private static boolean canResolve(Context context, Intent intent) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        return list != null && !list.isEmpty();
    }
}
