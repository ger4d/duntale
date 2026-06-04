package com.duntale.command;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
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
 * Reusable NPC-role picker overlay backing the {@link ThemeConfigPage} spawn-pool editor.
 *
 * <p>Renders a searchable, scrolling list of NPC role-template names into the
 * {@code #RolePickerOverlay} markup, mirroring {@link BlockPickerComponent} but for the spawn-pool
 * {@code npcRole} field. The component owns only the overlay's rendering and transient state (which
 * spawn row is being edited, the current search query); the owning page applies the chosen role.
 *
 * <p>Role names are text-only (no item icons), and a free-text "use typed value" path lets the user
 * accept a role that is not in the list.
 *
 * @since 1.9.0
 */
public class RolePickerComponent {

    /** Maximum number of rows rendered for a single (possibly filtered) candidate list. */
    private static final int MAX_ROWS = 200;

    static final String ACTION_OPEN = "OpenRolePicker";
    static final String ACTION_SEARCH = "RolePickerSearch";
    static final String ACTION_SELECT = "RolePickerSelect";
    static final String ACTION_USE_TYPED = "RolePickerUseTyped";
    static final String ACTION_CANCEL = "RolePickerCancel";

    private List<String> candidates = List.of();
    private boolean open;
    @Nullable
    private String pendingFieldKey;
    @Nonnull
    private String searchQuery = "";

    /**
     * Replaces the candidate role list shown by the picker.
     *
     * @param candidates the role names available for selection (order preserved, de-duplicated)
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
     * Wires the static overlay control bindings (search box, typed/cancel buttons).
     * Call once during the page's initial {@code build}.
     *
     * @param events the event builder to register bindings on
     */
    public void buildStaticBindings(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#RoleSearchInput",
                new EventData().append("Action", ACTION_SEARCH).append("@RoleSearch", "#RoleSearchInput.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RoleUseTypedButton",
                new EventData().append("Action", ACTION_USE_TYPED).append("@RoleSearch", "#RoleSearchInput.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RoleCancelButton",
                new EventData().append("Action", ACTION_CANCEL), false);
    }

    /**
     * Opens the overlay for a specific spawn row, pre-seeding the search box with the current value.
     *
     * @param cmd          the command builder to render into
     * @param events       the event builder for the freshly rendered rows
     * @param fieldKey     an opaque key identifying which spawn row is being edited
     * @param currentValue the row's current role, or {@code null}
     */
    public void open(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                     @Nonnull String fieldKey, @Nullable String currentValue) {
        this.open = true;
        this.pendingFieldKey = Objects.requireNonNull(fieldKey, "fieldKey");
        this.searchQuery = "";
        cmd.set("#RolePickerOverlay.Visible", true);
        cmd.set("#RoleSearchInput.Value", currentValue == null ? "" : currentValue);
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
        this.searchQuery = "";
        cmd.set("#RolePickerOverlay.Visible", false);
    }

    private void rebuildList(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.clear("#RoleList");
        List<String> filtered = filter();
        for (int i = 0; i < filtered.size(); i++) {
            String roleId = filtered.get(i);
            String rowSelector = "#RoleList[" + i + "]";
            cmd.append("#RoleList", "Common/TextButton.ui");
            cmd.set(rowSelector + " #Button.Text", roleId);
            events.addEventBinding(CustomUIEventBindingType.Activating, rowSelector + " #Button",
                    new EventData().append("Action", ACTION_SELECT).append("RoleId", roleId), false);
        }
    }

    @Nonnull
    private List<String> filter() {
        String needle = searchQuery.trim().toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (needle.isEmpty() || candidate.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(candidate);
                if (result.size() >= MAX_ROWS) {
                    break;
                }
            }
        }
        return result;
    }
}
