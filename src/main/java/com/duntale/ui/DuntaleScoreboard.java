package com.duntale.ui;

import com.duntale.rpg.RpgStat;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persistent HUD overlay displayed during dungeon gameplay.
 *
 * <p>Shows the player's gold balance, level, XP progress bar, and all
 * RPG stat values. Updated incrementally via {@link #updateData(DuntaleScoreboardData)}
 * without rebuilding the entire UI.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code show()} on player join — renders the initial UI from template</li>
 *   <li>{@code updateData(...)} on gold/XP/stat changes — incremental updates</li>
 *   <li>{@code HudManager.removeCustomHud(..., "default")} on leave — removes the HUD</li>
 * </ul>
 */
public class DuntaleScoreboard extends CustomUIHud {

    private static final String HUD_KEY = "default";
    private static final String UI_FILE = "Scoreboard/DuntaleScoreboard.ui";

    /** Width of the XP bar in pixels (must match .ui template). */
    private static final int XP_BAR_WIDTH = 160;

    @Nullable
    private DuntaleScoreboardData data;

    /**
     * Creates a new Duntale scoreboard HUD for the given player.
     *
     * @param playerRef the player reference
     */
    public DuntaleScoreboard(@Nonnull PlayerRef playerRef) {
        super(playerRef, HUD_KEY);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(UI_FILE);
    }

    /**
     * Updates the scoreboard with new data (incremental, no rebuild).
     *
     * @param newData the new scoreboard data
     */
    public void updateData(@Nonnull DuntaleScoreboardData newData) {
        this.data = newData;
        UICommandBuilder cmd = new UICommandBuilder();
        applyData(cmd);
        update(false, cmd);
    }

    /**
     * Returns the current data state, or {@code null} if never updated.
     *
     * @return the current data, or {@code null}
     */
    @Nullable
    public DuntaleScoreboardData getData() {
        return data;
    }

    private void applyData(@Nonnull UICommandBuilder cmd) {
        if (data == null) {
            return;
        }

        // Gold
        cmd.set("#GoldValue.Text", formatGold(data.gold()));

        // Level
        cmd.set("#LevelValue.Text", String.valueOf(data.level()));

        // XP bar
        long xp = data.xp();
        long xpMax = data.xpMax();
        if (xpMax > 0 && xp >= 0) {
            float progress = (float) xp / xpMax;
            int fillWidth = Math.min(XP_BAR_WIDTH, (int) (XP_BAR_WIDTH * progress));
            cmd.set("#XPText.Text", formatNumber(xp) + " / " + formatNumber(xpMax) + " XP");
            Anchor barAnchor = new Anchor();
            barAnchor.setWidth(Value.of(fillWidth));
            cmd.setObject("#XPBarFill.Anchor", barAnchor);
        } else {
            cmd.set("#XPText.Text", "0 / 0 XP");
            Anchor barAnchor = new Anchor();
            barAnchor.setWidth(Value.of(0));
            cmd.setObject("#XPBarFill.Anchor", barAnchor);
        }

        // Stats
        cmd.set("#StrValue.Text", String.valueOf(data.getStat(RpgStat.STRENGTH)));
        cmd.set("#SpdValue.Text", String.valueOf(data.getStat(RpgStat.SPEED)));
        cmd.set("#AgiValue.Text", String.valueOf(data.getStat(RpgStat.AGILITY)));
        cmd.set("#ResValue.Text", String.valueOf(data.getStat(RpgStat.RESISTANCE)));
        cmd.set("#LckValue.Text", String.valueOf(data.getStat(RpgStat.LUCK)));
        cmd.set("#VitValue.Text", String.valueOf(data.getStat(RpgStat.VITALITY)));
        cmd.set("#StaValue.Text", String.valueOf(data.getStat(RpgStat.STAMINA)));
    }

    private static String formatGold(long gold) {
        if (gold >= 1_000_000) {
            return String.format("%.1fM", gold / 1_000_000.0);
        } else if (gold >= 10_000) {
            return String.format("%.1fK", gold / 1_000.0);
        }
        return String.valueOf(gold);
    }

    private static String formatNumber(long value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        } else if (value >= 1_000) {
            return String.format("%.1fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }
}
