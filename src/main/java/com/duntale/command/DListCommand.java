package com.duntale.command;

import com.duntale.progression.AssetCatalog;
import com.duntale.progression.AssetCatalog.ArmorBaseRow;
import com.duntale.progression.AssetCatalog.MonsterBaseRow;
import com.duntale.progression.AssetCatalog.WeaponBaseRow;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Lists scaling database assets in chat.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /dlist npc [--sort=hp] [--order=desc] [--count=20] [--tier=X] [--category=X]}</li>
 *   <li>{@code /dlist weapon [--sort=dmg] [--order=desc] [--count=20] [--family=X]}</li>
 *   <li>{@code /dlist armor [--sort=phys] [--order=desc] [--count=20] [--slot=X]}</li>
 * </ul>
 */
public class DListCommand extends CommandBase {

    // ── Color Constants ──────────────────────────────────────────────
    private static final String GOLD = "#FFD700";
    private static final String WHITE = "#FFFFFF";
    private static final String GRAY = "#AAAAAA";
    private static final String YELLOW = "#FFEE55";
    private static final String RED = "#FF5555";

    private final AssetCatalog assetCatalog;

    /**
     * Creates a new /dlist command.
     *
     * @param assetCatalog the asset catalog for DB queries
     */
    public DListCommand(@Nonnull AssetCatalog assetCatalog) {
        super("dlist", "List scaling database assets");
        this.assetCatalog = assetCatalog;

        this.addSubCommand(new NpcSubCommand());
        this.addSubCommand(new WeaponSubCommand());
        this.addSubCommand(new ArmorSubCommand());
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage: /dlist npc|weapon|armor [--sort=X] [--order=asc|desc] [--count=N]").color(YELLOW));
        context.sendMessage(Message.raw("  NPC sorts: " + String.join(", ", AssetCatalog.npcSortKeys())).color(GRAY));
        context.sendMessage(Message.raw("  Weapon sorts: " + String.join(", ", AssetCatalog.weaponSortKeys())).color(GRAY));
        context.sendMessage(Message.raw("  Armor sorts: " + String.join(", ", AssetCatalog.armorSortKeys())).color(GRAY));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String safe(@Nullable String s, int maxLen) {
        if (s == null) return "?";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private static String pad(@Nonnull String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private static String rpad(@Nonnull String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return " ".repeat(width - s.length()) + s;
    }

    /** Builds a row: name in white + stats in gray, all monospace. */
    private static Message row(@Nonnull String name, @Nonnull String rest) {
        return Message.raw(name).color(WHITE).monospace(true)
                .insert(Message.raw(rest).color(GRAY).monospace(true));
    }

    /** Builds a header: bold gold title + gray subtitle. */
    private static Message header(@Nonnull String title, @Nonnull String subtitle) {
        return Message.raw(title).color(GOLD).bold(true)
                .insert(Message.raw(" " + subtitle).color(GRAY).bold(false));
    }

    /** Builds a column header line in yellow monospace. */
    private static Message columnHeader(@Nonnull String text) {
        return Message.raw(text).color(YELLOW).monospace(true);
    }

    /** Builds a footer line in gray. */
    private static Message footer(int count) {
        return Message.raw("  Showing " + count + " results.").color(GRAY);
    }

    // ── NPC Subcommand ───────────────────────────────────────────────

    private class NpcSubCommand extends CommandBase {
        private final OptionalArg<String> sortArg =
                this.withOptionalArg("sort", "Sort column (hp, dmg, name, tier, speed, category)", ArgTypes.STRING);
        private final OptionalArg<String> orderArg =
                this.withOptionalArg("order", "Sort order (asc or desc)", ArgTypes.STRING);
        private final OptionalArg<Integer> countArg =
                this.withOptionalArg("count", "Number of results (1-100)", ArgTypes.INTEGER);
        private final OptionalArg<String> tierArg =
                this.withOptionalArg("tier", "Filter by tier (Fodder, Standard, Tough, Elite, Boss)", ArgTypes.STRING);
        private final OptionalArg<String> categoryArg =
                this.withOptionalArg("category", "Filter by category (Undead, Creature, etc.)", ArgTypes.STRING);

        NpcSubCommand() {
            super("npc", "List NPCs from the scaling database");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String sort = sortArg.provided(context) ? sortArg.get(context) : "hp";
            String order = orderArg.provided(context) ? orderArg.get(context) : "desc";
            int count = countArg.provided(context) ? countArg.get(context) : 20;
            String tier = tierArg.provided(context) ? tierArg.get(context) : null;
            String category = categoryArg.provided(context) ? categoryArg.get(context) : null;

            boolean ascending = "asc".equalsIgnoreCase(order);

            if (tier != null && !tier.isEmpty()) {
                tier = Character.toUpperCase(tier.charAt(0)) + tier.substring(1).toLowerCase();
            }

            List<MonsterBaseRow> rows = assetCatalog.listMonsters(sort, ascending, count, tier, category);

            if (rows.isEmpty()) {
                context.sendMessage(Message.raw("No NPCs found.").color(RED));
                return;
            }

            String subtitle = String.format("(sort=%s %s, %d results%s%s)",
                    sort, ascending ? "ASC" : "DESC", rows.size(),
                    tier != null ? ", tier=" + tier : "",
                    category != null ? ", cat=" + category : "");
            context.sendMessage(header("== NPCs ==", subtitle));

            context.sendMessage(columnHeader(String.format("  %s %s %s %s %s %s",
                    pad("Name", 30), rpad("Tier", 8), rpad("HP", 6),
                    rpad("Dmg", 6), rpad("Spd", 5), rpad("AtkD", 5))));

            for (MonsterBaseRow r : rows) {
                String name = "  " + pad(safe(r.name(), 30), 30) + " ";
                String stats = String.format("%s %s %s %s %s",
                        rpad(safe(r.tier(), 8), 8),
                        rpad(String.valueOf(r.baseHp()), 6),
                        rpad(String.format("%.1f", r.baseDamage()), 6),
                        rpad(String.format("%.0f", r.baseSpeed()), 5),
                        rpad(String.format("%.1f", r.attackDistance()), 5));
                context.sendMessage(row(name, stats));
            }

            context.sendMessage(footer(rows.size()));
        }
    }

    // ── Weapon Subcommand ────────────────────────────────────────────

    private class WeaponSubCommand extends CommandBase {
        private final OptionalArg<String> sortArg =
                this.withOptionalArg("sort", "Sort column (dmg, name, family, level, quality)", ArgTypes.STRING);
        private final OptionalArg<String> orderArg =
                this.withOptionalArg("order", "Sort order (asc or desc)", ArgTypes.STRING);
        private final OptionalArg<Integer> countArg =
                this.withOptionalArg("count", "Number of results (1-100)", ArgTypes.INTEGER);
        private final OptionalArg<String> familyArg =
                this.withOptionalArg("family", "Filter by weapon family (Sword, Axe, Spear, etc.)", ArgTypes.STRING);

        WeaponSubCommand() {
            super("weapon", "List weapons from the scaling database");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String sort = sortArg.provided(context) ? sortArg.get(context) : "dmg";
            String order = orderArg.provided(context) ? orderArg.get(context) : "desc";
            int count = countArg.provided(context) ? countArg.get(context) : 20;
            String family = familyArg.provided(context) ? familyArg.get(context) : null;

            boolean ascending = "asc".equalsIgnoreCase(order);

            if (family != null && !family.isEmpty()) {
                family = Character.toUpperCase(family.charAt(0)) + family.substring(1).toLowerCase();
            }

            List<WeaponBaseRow> rows = assetCatalog.listWeapons(sort, ascending, count, family);

            if (rows.isEmpty()) {
                context.sendMessage(Message.raw("No weapons found.").color(RED));
                return;
            }

            String subtitle = String.format("(sort=%s %s, %d results%s)",
                    sort, ascending ? "ASC" : "DESC", rows.size(),
                    family != null ? ", family=" + family : "");
            context.sendMessage(header("== Weapons ==", subtitle));

            context.sendMessage(columnHeader(String.format("  %s %s %s %s %s",
                    pad("Name", 35), pad("Family", 12), pad("Quality", 10),
                    rpad("Lvl", 4), rpad("Dmg", 7))));

            for (WeaponBaseRow r : rows) {
                String name = "  " + pad(safe(r.name(), 35), 35) + " ";
                String stats = String.format("%s %s %s %s",
                        pad(safe(r.family(), 12), 12),
                        pad(safe(r.quality(), 10), 10),
                        rpad(String.valueOf(r.itemLevel()), 4),
                        rpad(String.format("%.1f", r.baseDamage()), 7));
                context.sendMessage(row(name, stats));
            }

            context.sendMessage(footer(rows.size()));
        }
    }

    // ── Armor Subcommand ─────────────────────────────────────────────

    private class ArmorSubCommand extends CommandBase {
        private final OptionalArg<String> sortArg =
                this.withOptionalArg("sort", "Sort column (phys, proj, hp, name, slot, level, quality)", ArgTypes.STRING);
        private final OptionalArg<String> orderArg =
                this.withOptionalArg("order", "Sort order (asc or desc)", ArgTypes.STRING);
        private final OptionalArg<Integer> countArg =
                this.withOptionalArg("count", "Number of results (1-100)", ArgTypes.INTEGER);
        private final OptionalArg<String> slotArg =
                this.withOptionalArg("slot", "Filter by armor slot (Head, Chest, Hands, Legs)", ArgTypes.STRING);

        ArmorSubCommand() {
            super("armor", "List armor from the scaling database");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String sort = sortArg.provided(context) ? sortArg.get(context) : "phys";
            String order = orderArg.provided(context) ? orderArg.get(context) : "desc";
            int count = countArg.provided(context) ? countArg.get(context) : 20;
            String slot = slotArg.provided(context) ? slotArg.get(context) : null;

            boolean ascending = "asc".equalsIgnoreCase(order);

            if (slot != null && !slot.isEmpty()) {
                slot = Character.toUpperCase(slot.charAt(0)) + slot.substring(1).toLowerCase();
            }

            List<ArmorBaseRow> rows = assetCatalog.listArmor(sort, ascending, count, slot);

            if (rows.isEmpty()) {
                context.sendMessage(Message.raw("No armor found.").color(RED));
                return;
            }

            String subtitle = String.format("(sort=%s %s, %d results%s)",
                    sort, ascending ? "ASC" : "DESC", rows.size(),
                    slot != null ? ", slot=" + slot : "");
            context.sendMessage(header("== Armor ==", subtitle));

            context.sendMessage(columnHeader(String.format("  %s %s %s %s %s %s %s",
                    pad("Name", 30), pad("Slot", 6), pad("Qual", 10),
                    rpad("Lvl", 4), rpad("Phys%", 6),
                    rpad("Proj%", 6), rpad("HP+", 4))));

            for (ArmorBaseRow r : rows) {
                String name = "  " + pad(safe(r.name(), 30), 30) + " ";
                String stats = String.format("%s %s %s %s %s %s",
                        pad(safe(r.slot(), 6), 6),
                        pad(safe(r.quality(), 10), 10),
                        rpad(String.valueOf(r.itemLevel()), 4),
                        rpad(String.format("%.1f", r.physResist() * 100), 6),
                        rpad(String.format("%.1f", r.projResist() * 100), 6),
                        rpad(String.valueOf(r.healthBonus()), 4));
                context.sendMessage(row(name, stats));
            }

            context.sendMessage(footer(rows.size()));
        }
    }
}
