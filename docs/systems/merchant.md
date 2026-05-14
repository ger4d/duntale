# Merchant

Status: Current
Last verified: 2026-05-14
Source docs: PLAN-economy-rpg.md, RPG_DUNGEON.md
Verified against: src/main/java/com/duntale/merchant/, src/main/java/com/duntale/progression/AssetCatalog.java, src/main/java/com/duntale/progression/CombatScaling.java, src/main/java/com/duntale/progression/GearLevelService.java, src/main/java/com/duntale/DuntalePlugin.java, src/main/resources/Server/NPC/Roles/Intelligent/Passive/Dungeon_Merchant.json, src/test/java/com/duntale/merchant/MerchantServiceTest.java, src/test/java/com/duntale/merchant/MerchantPriceRegistryTest.java

## Purpose

Document the current dungeon merchant NPC hookup, catalog generation rules, pricing model, buy or sell container flow, tooltip state, and merchant-specific session cleanup.

## Current State

### NPC Hookup

- Dungeon merchants are spawned from blueprint `MerchantDefinition` entries by `MerchantNpcSpawner`, which offsets authored coordinates by the dungeon world origin and attaches `MerchantComponent(floorLevel)` to each NPC.
- `DuntalePlugin` registers the custom NPC action type `OpenDungeonMerchant`, and the `Dungeon_Merchant` role asset uses that action when a player interacts.
- `Dungeon_Merchant.json` currently makes merchants invulnerable, neutral to players, locked to an idle or interaction loop, and interactable with the `server.interactionHints.trade` hint.
- `ActionOpenDungeonMerchant` reads the merchant's `floorLevel`, lazily generates a catalog on first interaction, stores that catalog in `MerchantComponent`, and reuses it for later interactions with the same runtime entity.
- `MerchantComponent.CODEC` only persists `floorLevel`. The cached catalog is runtime-only and is regenerated after a save or reload.

### Session And Window Model

- `MerchantService` allows at most one open merchant session per player. Opening a new merchant closes the previous session record first.
- The merchant UI uses `Page.Bench` plus a custom `MerchantWindow` around a per-session `MerchantContainer`.
- The buy zone is the front portion of the container and is populated with the current catalog. The sell zone is always `2` slots.
- `MerchantWindow.onClose0(...)` calls `MerchantService.cleanupInventoryMetadata(...)`, which strips temporary buy-display metadata from the player's hotbar, storage, and armor containers, then closes the active session.
- `DuntalePlugin.onPlayerDisconnect(...)` also closes any active merchant session.

### Buy Flow

- Catalog display items are created as ordinary `ItemStack`s with merchant-only metadata:
  - `merchant_buy_price`
  - `merchant_gold`
- Leveled gear display items also receive either `duntale_weapon_level` or `duntale_armor_level` so the item preview reflects the stamped dungeon level. Merchant display items do not get variance metadata.
- `MerchantContainer.cantRemoveFromSlot(...)` blocks taking an item from a buy slot when the player UUID is missing, the slot metadata is invalid, or the player does not have enough gold.
- When a buy slot becomes empty, `MerchantService` treats that as a purchase, removes gold through `GoldService.removeGold(...)`, sends a chat confirmation, refills the display slot, and rewrites `merchant_gold` on every remaining buy item so tooltip balance stays current.
- After a successful purchase, `MerchantService` scans the player's hotbar, storage, and armor containers for the first matching bought item, removes the temporary display metadata, and stamps `merchant_sell_price` onto that item.

### Sell Flow

- Players can never place items into buy slots.
- Sell slots reject two categories of items:
  - Merchant display items that still carry `merchant_buy_price`
  - Items not present in `MerchantPriceRegistry`
- Placing a sellable item into a sell slot immediately sells it. `MerchantService` reads the stamped dungeon level from `duntale_weapon_level` or `duntale_armor_level`, credits gold, removes the item from the sell slot, sends a chat confirmation, and refreshes buy-slot gold metadata.
- Removing an item from an empty sell slot has no side effect. There is no delayed confirmation or barter state.

### Catalog Generation

- `CatalogGenerator` produces deterministic merchant catalogs from `(floorLevel, seed)`.
- Runtime merchant NPCs use `seed = merchantRef.hashCode()`. The `/merchant` debug command uses a fixed catalog generated at floor level `15` with seed `42`.
- Each generated catalog contains up to `25` buy entries:
  - `21` gear entries
  - `4` consumable entries
- Gear entries are selected from five base item-level ranges: `0-10`, `10-20`, `20-30`, `30-40`, and `40-50`.
- Tier slot counts are weighted by proximity to the merchant's floor level, but every tier gets at least one slot and the dominant tier is adjusted so the total is exactly `21` gear items.
- Each generated gear item is stamped with a random gear level in `[floorLevel - 4, floorLevel + 10]`, clamped to `1-60`, and priced directly from that stamped level.
- Gear entries are sorted by stamped level first and buy price second.
- Consumables are fixed-price entries:
  - One guaranteed health potion: `Potion_Health` below floor `20`, `Potion_Health_Greater` at floor `20+`
  - Three additional random entries without duplicates from the fixed pool of arrows, food, stamina or health potions, and antidotes

### Pricing Model

- `MerchantPriceRegistry` initializes at plugin start from `AssetCatalog`, which scans runtime item assets after all plugins finish setup.
- The price registry excludes `Developer` and `Technical` quality items and filters out obvious NPC-only assets.
- Buy prices are level-aware and recomputed from the item's runtime profile when a dungeon level is provided.
- Sell prices are `floor(buyPrice * 0.80)`.
- The final buy-price rule is:

| Item type | Score model | Price rule |
| --- | --- | --- |
| Weapon with runtime damage | `baseDamage * CombatScaling.weaponMult(level)` | `max(25, round(score^1.4 * 10))` |
| Armor with runtime resist or health stats | `avgEffectiveDRPercent * 3.0 + healthBonus * 0.9` | `max(25, round(score^1.4 * 10))` |
| Fallback utility item | `itemLevel * qualityCoefficient^0.5`, then family or slot multiplier | `max(25, round(score^1.4 * 10))` |

- Quality coefficients for fallback pricing are `Common: 1.0`, `Uncommon: 1.5`, `Rare: 2.5`, `Epic: 5.0`, and `Legendary: 15.0`.
- Fallback family multipliers currently raise ranged and caster utility weapons above neutral items and slightly discount torch or fire items.
- Fallback armor slot multipliers are `Chest: 1.0`, `Legs: 0.85`, `Head: 0.75`, and `Hands: 0.65`.

### Tooltip And Merchant-Specific UI State

- `DuntalePlugin` registers `MerchantTooltipProvider` only when DynamicTooltipsLib is present.
- Buy-slot tooltips read `merchant_buy_price` and `merchant_gold` and show both the buy cost and the player's current gold. The gold line turns red when the player cannot afford the item.
- Sell tooltips are shown for any item the price registry recognizes. The provider computes the sell price from the item ID and stamped dungeon level metadata instead of reading `merchant_sell_price`.
- `merchant_sell_price` is still stamped on purchased items and intentionally survives window close cleanup. `merchant_buy_price` and `merchant_gold` are temporary display state and are stripped when the merchant window closes.

## Implementation Map

- `src/main/java/com/duntale/merchant/MerchantNpcSpawner.java` owns runtime merchant NPC creation from blueprint definitions.
- `src/main/java/com/duntale/merchant/MerchantComponent.java` owns the persisted floor level and runtime catalog cache for each merchant entity.
- `src/main/java/com/duntale/merchant/BuilderActionOpenDungeonMerchant.java` and `src/main/java/com/duntale/merchant/ActionOpenDungeonMerchant.java` own NPC interaction hookup.
- `src/main/java/com/duntale/merchant/MerchantService.java` owns session state, buy or sell transaction handling, inventory metadata cleanup, and player chat feedback.
- `src/main/java/com/duntale/merchant/MerchantContainer.java` owns buy-zone affordability checks, sell-zone validation, and container-level slot restrictions.
- `src/main/java/com/duntale/merchant/MerchantWindow.java` owns close-time cleanup routing.
- `src/main/java/com/duntale/merchant/MerchantPriceRegistry.java` owns price profiles, cached base prices, and level-aware buy or sell lookups.
- `src/main/java/com/duntale/merchant/CatalogGenerator.java` owns floor-based catalog composition and consumable insertion.
- `src/main/java/com/duntale/merchant/MerchantTooltipProvider.java` owns tooltip text for buy and sell states.
- `src/main/java/com/duntale/merchant/MerchantCommand.java` exposes the debug `/merchant` command.
- `src/main/java/com/duntale/DuntalePlugin.java` wires the merchant component type, NPC action registration, service construction, price-registry initialization, and optional tooltip provider registration.

## Data, Assets, And Config

- `src/main/resources/Server/NPC/Roles/Intelligent/Passive/Dungeon_Merchant.json` is the canonical merchant role asset.
- Merchant session state uses these runtime metadata keys on `ItemStack`s:
  - `merchant_buy_price`
  - `merchant_gold`
  - `merchant_sell_price`
- Merchant pricing depends on `AssetCatalog`, which scans the runtime `Item` asset registry after startup rather than using a hand-maintained price table.
- Merchant level-aware gear pricing depends on item metadata stamped with `duntale_weapon_level` or `duntale_armor_level`.
- Consumable prices are hard-coded in `CatalogGenerator` rather than sourced from the price registry.

## Validation

- Automated coverage exists for merchant inventory metadata retagging and cleanup in `src/test/java/com/duntale/merchant/MerchantServiceTest.java`.
- Automated coverage exists for level-aware pricing and catalog price stamping in `src/test/java/com/duntale/merchant/MerchantPriceRegistryTest.java`.
- The verified NPC hookup, session lifecycle, and tooltip registration paths are in the live source and asset files listed in the metadata block above.

## Known Gaps

- The cached merchant catalog is runtime-only. `MerchantComponent` persists the floor level, but not the generated catalog, so catalogs regenerate after entity reload.
- There is no dedicated automated coverage for full in-engine NPC interaction, `Page.Bench` open or close behavior, or sell-slot drag interactions through the live UI.
- The current sell flow is immediate. There is no preview, confirmation, stack splitting UI, or container-side negotiation state.

## Related Docs

- [economy-rpg.md](../systems/economy-rpg.md)
- [dungeon-instances.md](../systems/dungeon-instances.md)