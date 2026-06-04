package com.duntale.command;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Reusable block-picker overlay backing the {@link ThemeConfigPage} editor.
 *
 * <p>Renders a searchable, scrolling grid of candidate block icons into the
 * {@code #BlockPickerOverlay} markup embedded in {@code ThemeConfigPage.ui}. Each cell shows only
 * the block icon (no inline text); the block ID is surfaced on hover via the cell's tooltip. The
 * component owns only the overlay's
 * rendering and transient state (which field is being edited, the current search query); the
 * owning page is responsible for applying the chosen block ID to its draft model when a
 * selection event arrives.
 *
 * <p>Candidate blocks are supplied by the caller via {@link #setCandidates(List)} — the picker
 * does not enumerate the block registry itself. A free-text "use typed value" path lets the user
 * accept any block ID that is not in the candidate list.
 *
 * @since 1.9.0
 */
public class BlockPickerComponent {

    static final String ACTION_OPEN = "OpenBlockPicker";
    static final String ACTION_SEARCH = "BlockPickerSearch";
    static final String ACTION_SELECT = "BlockPickerSelect";
    static final String ACTION_USE_TYPED = "BlockPickerUseTyped";
    static final String ACTION_CLEAR = "BlockPickerClear";
    static final String ACTION_CANCEL = "BlockPickerCancel";

    private List<String> candidates = List.of();
    private boolean open;
    @Nullable
    private String pendingFieldKey;
    @Nullable
    private String currentValue;
    @Nonnull
    private String searchQuery = "";

    /**
     * Replaces the candidate block list shown by the picker.
     *
     * @param candidates the block IDs available for selection (de-duplicated, order preserved)
     */
    public void setCandidates(@Nonnull List<String> candidates) {
        this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }

    /** @return {@code true} while the picker overlay is visible. */
    public boolean isOpen() {
        return open;
    }

    /** @return the field key passed to the most recent {@link #open}, or {@code null}. */
    @Nullable
    public String getPendingFieldKey() {
        return pendingFieldKey;
    }

    /**
     * Wires the static overlay control bindings (search box, typed/clear/cancel buttons).
     * Call once during the page's initial {@code build}.
     *
     * @param events the event builder to register bindings on
     */
    public void buildStaticBindings(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#BlockSearchInput",
                new EventData().append("Action", ACTION_SEARCH).append("@BlockSearch", "#BlockSearchInput.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BlockUseTypedButton",
                new EventData().append("Action", ACTION_USE_TYPED).append("@BlockSearch", "#BlockSearchInput.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BlockClearButton",
                new EventData().append("Action", ACTION_CLEAR), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BlockCancelButton",
                new EventData().append("Action", ACTION_CANCEL), false);
    }

    /**
     * Opens the overlay for a specific field with a clean, empty search box (the full candidate
     * grid is shown until the user types a query).
     *
     * @param cmd          the command builder to render into
     * @param events       the event builder for the freshly rendered cells
     * @param fieldKey     an opaque key identifying which field is being edited
     * @param currentValue the field's current block ID, or {@code null}
     */
    public void open(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                     @Nonnull String fieldKey, @Nullable String currentValue) {
        this.open = true;
        this.pendingFieldKey = Objects.requireNonNull(fieldKey, "fieldKey");
        this.currentValue = currentValue;
        this.searchQuery = "";
        cmd.set("#BlockPickerOverlay.Visible", true);
        cmd.set("#BlockSearchInput.Value", "");
        cmd.set("#BlockClearButton.Visible", currentValue != null);
        rebuildList(cmd, events);
    }

    /**
     * Applies a new search query and re-renders the filtered list.
     *
     * @param query  the search substring (may be {@code null}/blank for "all")
     * @param cmd    the command builder to render into
     * @param events the event builder for the freshly rendered rows
     */
    public void handleSearch(@Nullable String query, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        this.searchQuery = query == null ? "" : query;
        rebuildList(cmd, events);
    }

    /**
     * Hides the overlay and clears transient state.
     *
     * @param cmd the command builder to render into
     */
    public void close(@Nonnull UICommandBuilder cmd) {
        this.open = false;
        this.pendingFieldKey = null;
        this.currentValue = null;
        this.searchQuery = "";
        cmd.set("#BlockPickerOverlay.Visible", false);
    }

    private void rebuildList(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.clear("#BlockList");
        List<String> filtered = filter();
        for (int i = 0; i < filtered.size(); i++) {
            String blockId = filtered.get(i);
            String cellSelector = "#BlockList[" + i + "]";
            cmd.append("#BlockList", "Pages/ThemeConfig/BlockPickerRow.ui");
            setCellIcon(cmd, cellSelector + " #Icon", blockId);
            // The grid shows no inline text, so the block ID is surfaced on hover instead.
            cmd.set(cellSelector + " #Button.TooltipText", blockId);
            // Selection is driven by the button's Activating event (a proven binding), not by an
            // ItemGrid slot click — the icon is purely visual.
            events.addEventBinding(CustomUIEventBindingType.Activating, cellSelector + " #Button",
                    new EventData().append("Action", ACTION_SELECT).append("BlockId", blockId), false);
        }
    }

    private static void setCellIcon(@Nonnull UICommandBuilder cmd, @Nonnull String slotSelector, @Nonnull String blockId) {
        ItemGridSlot slot;
        try {
            slot = Item.getAssetMap().getAsset(blockId) != null
                    ? new ItemGridSlot(new ItemStack(blockId, 1))
                    : new ItemGridSlot();
        } catch (RuntimeException e) {
            slot = new ItemGridSlot();
        }
        cmd.set(slotSelector + ".Slots", new ItemGridSlot[]{slot});
    }

    @Nonnull
    private List<String> filter() {
        String needle = searchQuery.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return candidates;
        }
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
