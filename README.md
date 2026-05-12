# VContainer

VContainer is a Paper Minecraft plugin that gives every player a persistent virtual container. It supports GUI-based deposits and withdrawals, admin-managed storage blocks, player-owned placeable storage blocks, hopper input/output, multiple storage backends, and a public Bukkit API for other plugins.

## Requirements

- Java 21
- Paper compatible with one of the supported Minecraft versions listed below
- Maven 3.x to build from source
- Optional soft dependencies:
  - Oraxen
  - MythicMobs
  - ItemsAdder

## Features

- Per-player virtual containers.
- Paginated Triumph GUI menu.
- Player-controlled sorting modes in the menu.
- Optional compact item display with total amount lore.
- Configurable deposit and withdrawal behavior.
- Shift-click bulk deposit/withdraw.
- Middle-click withdraw one stack.
- Admin commands for opening, clearing, giving items, and setting storage blocks.
- Personal storage block item with owner/member access control.
- Hopper input/output for personal storage blocks.
- Per-player per-chunk personal storage block limit.
- Holograms for global and personal storage blocks.
- Local JSON, MySQL, MariaDB, and H2 storage backends.
- Public API exposed through Bukkit `ServicesManager`.
- Configurable item builder for GUI buttons and the personal storage block item.
- Legacy colors, hex colors, Bukkit section hex colors, and MiniMessage gradients.

## Building

From the project root:

```bash
mvn clean package
```

The plugin jar is created in:

```text
vcontainer-core/target/VContainer-1.0.0.jar
```

## Installation

1. Build the project or download the compiled jar.
2. Place the core jar into your server `plugins/` folder.
3. Start the server.
4. Edit the generated files in `plugins/VContainer/`.
5. Restart the server or use `/vcontainer reload` for reloadable settings.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/container` | `vcontainer.use` | Opens your own virtual container. |
| `/vcontainer` | `vcontainer.admin` | Shows the admin command help. |
| `/vcontainer reload` | `vcontainer.admin` | Reloads config, messages, menu configs, holograms, and hopper timing. |
| `/vcontainer open <player>` | `vcontainer.admin` | Opens another online player's container. Player sender only. |
| `/vcontainer clear <player>` | `vcontainer.admin` | Clears another online player's container. |
| `/vcontainer give minecraft <item> [player] [amount]` | `vcontainer.admin` | Adds a vanilla Minecraft item to a player's virtual container. |
| `/vcontainer give oraxen <item_id> [player] [amount]` | `vcontainer.admin` | Adds an Oraxen item if Oraxen is installed. |
| `/vcontainer give mythicmobs <item_id> [player] [amount]` | `vcontainer.admin` | Adds a MythicMobs item if MythicMobs is installed. |
| `/vcontainer give itemsadder <namespace:id> [player] [amount]` | `vcontainer.admin` | Adds an ItemsAdder item if ItemsAdder is installed. |
| `/vcontainer set` | `vcontainer.admin` and `vcontainer.admin.set` | Marks the block you are looking at as a global storage block. Player sender only. |
| `/vcontainer give-block [player] [amount] -s` | `vcontainer.admin` | Gives a personal storage block item. `player` defaults to the sender, `amount` defaults to `1`, and `-s` hides the received message from the target. |

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `vcontainer.use` | `op` | Allows `/container`. |
| `vcontainer.admin` | `op` | Allows admin commands. |
| `vcontainer.admin.set` | `op` | Allows `/vcontainer set`. |
| `vcontainer.block.use` | `op` | Allows opening containers through storage blocks. |
| `vcontainer.block.remove` | `op` | Allows removing global storage blocks by sneaking and breaking them. |
| `vcontainer.block.limit.bypass` | `op` | Bypasses the per-chunk personal storage block limit. |
| `vcontainer.notify` | `op` | Allows receiving container item notification messages. |

OP players bypass plugin permission checks through the internal permission helper.

## Container Menu

Players can deposit and withdraw items through the GUI.

Default interactions:

- Left-click a displayed item: withdraw all shown amount.
- Right-click a displayed item: withdraw 1 item.
- Middle-click a displayed item: withdraw 1 stack.
- Shift-click a displayed item: withdraw as many matching items as can fit, if enabled.
- Click an item in the player's inventory: deposit that item.
- Shift-click from the player's inventory: deposit all configured inventory contents, if enabled.

The menu has a sorting button. Sorting is per player, not global config. Available modes:

- None
- ABC A-Z
- ABC Z-A
- Most items
- Fewest items

## Storage Blocks

### Global Storage Blocks

Global storage blocks are created with:

```text
/vcontainer set
```

The command marks the block the admin is looking at. Any player with `vcontainer.block.use` can right-click it to open their own virtual container.

Global storage blocks:

- cannot be broken normally,
- can be removed by a player with `vcontainer.block.remove` using sneak + break,
- are protected from block and entity explosions,
- have a configurable hologram.

### Personal Storage Blocks

Admins can give the item with:

```text
/vcontainer give-block [player] [amount] -s
```

The item is identified with a `NamespacedKey` in the item's persistent data. By default it is a `SCULK_SHRIEKER`, and placed sculk shriekers are configured not to summon wardens.

When placed, the block always opens the owner's container. Access is limited to:

- the owner,
- OP players,
- added members.

The owner gets extra menu buttons for:

- picking up the personal storage block,
- managing added players.

Personal storage blocks:

- cannot be removed by sneak-breaking,
- are picked up from the owner-only menu button,
- have separate hologram lines,
- support hopper input and output,
- are protected from explosions.

### Hopper Behavior

Personal storage blocks support hopper transfer.

- Hopper input: a hopper facing into the personal storage block inserts items into the owner's virtual container.
- Hopper output: a hopper below the personal storage block pulls items from the owner's virtual container.
- Open container GUIs update live when hopper transfers change the contents.
- Sneak + right-clicking a personal storage block with a hopper attempts to attach the hopper to the clicked side and face it toward the storage block.

Hopper behavior is controlled in `config.yml`:

```yaml
storage-block:
  hoppers:
    enabled: true
    input: true
    output: true
    interval-ticks: 8
```

### Personal Block Chunk Limit

Each player can place a limited number of personal storage blocks in the same chunk:

```yaml
storage-block:
  personal:
    chunk-limit: 4
```

Use `vcontainer.block.limit.bypass` to bypass this limit.

## Configuration Files

VContainer generates these main files:

```text
plugins/VContainer/
  config.yml
  database.yml
  messages.yml
  menus/
    container.yml
    members.yml
```

### `config.yml`

Important sections:

- `container-options.allow-deposit`: allows players to deposit items from their inventory.
- `container-options.allow-withdraw`: allows players to withdraw items.
- `container-options.messages.deposit`: toggles deposit messages.
- `container-options.messages.withdraw`: toggles withdraw messages.
- `container-options.shift-transfer.deposit-all`: enables shift-click deposit all.
- `container-options.shift-transfer.withdraw-fit`: enables shift-click withdraw as much as fits.
- `container-options.shift-transfer.include-armor`: includes armor in bulk deposit.
- `container-options.shift-transfer.include-offhand`: includes offhand in bulk deposit.
- `container-options.compact-display.enabled`: shows one icon per equal item with total amount in lore.
- `stack`: merges equal items when added.
- `max-stack`: maximum stack size used by VContainer when stacking.

Compact display lore is configurable:

```yaml
container-options:
  compact-display:
    enabled: false
    size:
      enable: true
      line: "&7Item stack size: &f{amount}"
    withdraw-all:
      enable: true
      line: "&eLeft click to withdraw all"
    withdraw:
      enable: true
      line: "&eRight click to withdraw 1"
    withdraw-stack:
      enable: true
      line: "&eMiddle click to withdraw 1 stack"
    format:
      - ""
      - "&8----------"
      - "%amount-line%"
      - "%withdraw-all-line%"
      - "%withdraw-one-line%"
      - "%withdraw-stack-line%"
      - "&8----------"
```

Storage block item and hologram settings:

```yaml
storage-block:
  set-target-distance: 6
  item:
    Material: SCULK_SHRIEKER
    Name: "&bPersonal Storage Block"
    Texture: ""
    Lore:
      - "&7Place this block to create"
      - "&7your own linked storage."
    Unbreakable: false
    CustomModelData: -1
    TooltipStyle: ""
    MaxStackSize: -1
    Glow: false
    ItemFlags:
      - HIDE_ATTRIBUTES
      - HIDE_ENCHANTS
      - HIDE_UNBREAKABLE
    Enchantments: []
    Attributes: []
  hologram:
    enabled: true
    height: 1.35
    see-through: false
    shadow: true
    lines:
      - "&bVContainer"
      - "&7Right click to open"
    personal-lines:
      - "&bPersonal VContainer"
      - "&7Owner: &f{owner}"
      - "&7Right click to open"
      - "&8Hoppers can insert items"
```

### Config Item Format

All configured plugin items use the same item format. This includes:

- `storage-block.item` in `config.yml`
- GUI items in `menus/container.yml`
- GUI items in `menus/members.yml`

Supported color formats in item names and lore:

```text
&bLegacy colors
&#54DAF4Hex colors
§x§5§4§D§A§F§4Bukkit section hex
&x&5&4&D&A&F&4Ampersand hex
<gradient:#54daf4:#545eb6>MiniMessage gradients</gradient>
```

Full item example:

```yaml
example_item:
  Material: PLAYER_HEAD
  Texture: "base64-texture-value"
  Name: "<gradient:#54daf4:#545eb6>Example Item</gradient>"
  Lore:
    - "&7Normal lore line"
    - "&#54DAF4Hex lore line"
  Unbreakable: true
  CustomModelData: 1001
  TooltipStyle: "global:mythic"
  MaxStackSize: 16
  Glow: true
  ItemFlags:
    - HIDE_ATTRIBUTES
    - HIDE_ENCHANTS
    - HIDE_UNBREAKABLE
  Enchantments:
    - minecraft:sharpness:5
    - unbreaking:3
  Attributes:
    - Attribute: GENERIC_ATTACK_DAMAGE
      Amount: 4.0
      Operation: ADD_NUMBER
      Slot: MAINHAND
      Key: vcontainer:example_attack_damage
```

Item keys:

- `Material`: Bukkit material name, for example `PAPER`, `FEATHER`, `DIAMOND`, `PLAYER_HEAD`, or `SCULK_SHRIEKER`.
- `Material: HDB-12345`: uses HeadDatabase if it is installed. If HeadDatabase is missing or the id cannot be loaded, the plugin falls back to `PLAYER_HEAD`.
- `Name`: display name of the item.
- `Texture`: optional base64 skull texture. Only works when the final item is `PLAYER_HEAD`.
- `Lore`: list of lore lines.
- `Unbreakable`: `true` or `false`.
- `CustomModelData`: integer custom model data. Use `-1` to disable.
- `TooltipStyle`: optional Minecraft 1.21.2+ tooltip style. Format: `namespace:path`. Invalid values are skipped with a console warning. Older server versions ignore this option.
- `MaxStackSize`: optional item-specific max stack size. Use `-1` or omit it to disable.
- `Glow`: `true` forces the enchantment glint, `false` disables the forced glint override.
- `ItemFlags`: Bukkit `ItemFlag` names, for example `HIDE_ATTRIBUTES`, `HIDE_ENCHANTS`, `HIDE_UNBREAKABLE`, `HIDE_ADDITIONAL_TOOLTIP`.
- `Enchantments`: list entries use `enchantment:level` or `namespace:enchantment:level`.
- `Attributes`: list of attribute modifier objects.

Attribute keys:

- `Attribute`: Bukkit attribute enum, for example `GENERIC_ATTACK_DAMAGE`, `GENERIC_MAX_HEALTH`, or `GENERIC_MOVEMENT_SPEED`.
- `Amount`: modifier amount.
- `Operation`: Bukkit operation, usually `ADD_NUMBER`, `ADD_SCALAR`, or `MULTIPLY_SCALAR_1`.
- `Slot`: optional equipment slot group. Common values: `ANY`, `MAINHAND`, `OFFHAND`, `HAND`, `FEET`, `LEGS`, `CHEST`, `HEAD`, `ARMOR`.
- `Key`: optional unique namespaced modifier key. If omitted, VContainer generates one.

Tooltip style resource pack paths:

```text
assets/<namespace>/textures/gui/sprites/tooltip/<path>_background.png
assets/<namespace>/textures/gui/sprites/tooltip/<path>_frame.png
```

## Database And Storage

Storage backend is configured in `database.yml`.

Supported types:

- `LOCAL`: JSON files
- `MYSQL`: MySQL database
- `MARIADB`: MariaDB database
- `H2`: embedded SQL database

Example:

```yaml
storage:
  Type: LOCAL
  Hostname: 172.168.0.1
  Port: 3306
  Username: minecraft
  Password: ""
  Database: minecraft
  Pool Size: 5
  Use SSL: false
  Jdbc Url: ""
  Driver Class: ""
  Prefix: vcontainer_
```

### Local Storage Layout

When `storage.Type` is `LOCAL`, files are stored as JSON:

```text
plugins/VContainer/storage/
  player_data/
    <player_uuid>.json
  global_storage_blocks/
    <custom_uuid>.json
  personal_storage_blocks/
    <custom_uuid>.json
```

When `storage.Type` is not `LOCAL`, this `storage/` folder is not created automatically.

### SQL Tables

When using `MYSQL`, `MARIADB`, or `H2`, the plugin creates separate tables:

```text
<prefix>player_data
<prefix>global_storage_blocks
<prefix>personal_storage_blocks
```

With the default prefix:

```text
vcontainer_player_data
vcontainer_global_storage_blocks
vcontainer_personal_storage_blocks
```

Player items are stored as BLOB data using Paper/Bukkit item byte serialization. This preserves internal item data such as display names, lore, enchantments, and persistent data.

### Save Timing

VContainer does not write every change immediately. It keeps data in memory and flushes changed containers:

- every 2 minutes,
- on plugin disable,
- when the API `flush()` method is called.

## API

The API module is `vcontainer-api`. Other plugins can access it through Bukkit's `ServicesManager`:

```java
RegisteredServiceProvider<VContainerAPI> provider =
        Bukkit.getServicesManager().getRegistration(VContainerAPI.class);

if (provider != null) {
    VContainerAPI api = provider.getProvider();
    api.addItem(player, itemStack);
}
```

Main API methods:

```java
void addItem(Player player, ItemStack item);
void addItem(UUID ownerId, ItemStack item);
void removeItem(Player player, ItemStack item);
void removeItem(UUID ownerId, ItemStack item);
int takeItem(UUID ownerId, ItemStack item, int amount);
List<ItemStack> getItems(Player player);
List<ItemStack> getItems(UUID ownerId);
boolean containsItem(Player player, ItemStack item);
void clear(Player player);
void clear(UUID ownerId);

void openContainer(Player player);
void openContainer(Player viewer, UUID ownerId, String ownerName);
void openAdminContainer(Player admin, Player owner);
void flush();

ItemStack createPersonalStorageBlockItem(int amount);
boolean isPersonalStorageBlockItem(ItemStack item);

boolean createGlobalStorageBlock(Block block);
boolean createPersonalStorageBlock(Block block, Player owner);
boolean removeGlobalStorageBlock(Block block);
boolean removePersonalStorageBlock(String storageKey, boolean keepBlock);
boolean isStorageBlock(Block block);

Optional<StorageBlockInfo> getStorageBlock(Block block);
Optional<StorageBlockInfo> getStorageBlock(String storageKey);
Collection<StorageBlockInfo> getStorageBlocks();
Collection<StorageBlockInfo> getGlobalStorageBlocks();
Collection<StorageBlockInfo> getPersonalStorageBlocks();

boolean canAccessStorageBlock(Player player, String storageKey);
boolean isStorageBlockOwner(Player player, String storageKey);
boolean canPlacePersonalStorageBlock(Block block, Player owner);
int getPersonalStorageBlockChunkLimit();

boolean addStorageBlockMember(String storageKey, UUID memberId);
boolean removeStorageBlockMember(String storageKey, UUID memberId);
boolean setStorageBlockMember(String storageKey, UUID memberId, boolean member);

String getStorageBlockKey(Block block);
String getStorageBackendType();
boolean isLocalStorageBackend();
```

Storage block API responses use:

```java
record StorageBlockInfo(
    UUID id,
    String key,
    StorageBlockType type,
    UUID ownerId,
    String ownerName,
    Set<UUID> members
)
```

`StorageBlockType` values:

```java
GLOBAL
PERSONAL
```

The API also includes `ContainerAddItemEvent`, fired before an item is added through the player-aware add path. Cancelling the event prevents the add.

## Developer Notes

- Main plugin class: `hu.vzone.vcontainer.VContainer`
- Container logic: `ContainerManager`
- Storage block logic: `StorageBlockManager`
- GUI logic: `ContainerGUI`
- Public API interface: `VContainerAPI`
- Public API implementation: `VContainerAPIImpl`
- Item serialization helpers: `ItemUtils`

## Compatibility Notes

- `plugin.yml` uses `api-version: '1.21'`, which is the Bukkit/Paper API declaration for the 1.21 API family.
- At runtime, VContainer logs whether the detected Minecraft version is in the supported version list.
- Supported Minecraft versions:
  - `1.21`
  - `1.21.1`
  - `1.21.2`
  - `1.21.3`
  - `1.21.4`
  - `1.21.5`
  - `1.21.6`
  - `1.21.7`
  - `1.21.8`
  - `1.21.9`
  - `1.21.10`
  - `1.21.11`
  - `26.1`
  - `26.1.1`
  - `26.1.2`
- Oraxen, MythicMobs, and ItemsAdder are soft dependencies.
- The plugin shades Triumph GUI and database libraries into the final jar.
- SQL connection pooling uses HikariCP.
- MySQL, MariaDB, and H2 drivers are bundled by the build.
