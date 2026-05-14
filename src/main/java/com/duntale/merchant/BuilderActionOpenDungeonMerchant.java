package com.duntale.merchant;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.instructions.Action;

import javax.annotation.Nonnull;

/**
 * Builder for {@link ActionOpenDungeonMerchant}. Registered as a core NPC
 * component type so that the custom merchant role JSON can reference it
 * as {@code "Type": "OpenDungeonMerchant"}.
 *
 * @since 1.3.0
 */
public class BuilderActionOpenDungeonMerchant extends BuilderActionBase {

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Open the dungeon merchant UI for the interacting player";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return getShortDescription();
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public Action build(@Nonnull BuilderSupport builderSupport) {
        return new ActionOpenDungeonMerchant(this);
    }

    @Nonnull
    public BuilderActionOpenDungeonMerchant readConfig(@Nonnull JsonElement data) {
        // No additional config needed — floor level comes from MerchantComponent at runtime
        return this;
    }
}
