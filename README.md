# VContainer

VContainer egy Paper alapú Minecraft plugin, amely játékosonként külön virtuális tárolót kezel. A tároló GUI-ból nyitható, lapozható, az itemek JSON fájlokba mentődnek, és admin parancsból Minecraft, Oraxen vagy MythicMobs itemek is hozzáadhatók.

## Fő funkciók

- Játékosonként külön virtuális konténer.
- 54 slotos GUI, 45 tartalom slottal és alsó navigációs sorral.
- Lapozás nagyobb itemmennyiségnél.
- Item kivétel kattintással, inventory telítettség ellenőrzéssel.
- Itemek mentése játékos UUID alapján JSON fájlba.
- Opcionális item stackelés NBT/meta egyezés alapján.
- Admin nyitás, ürítés, reload és item hozzáadás.
- Opcionális Oraxen és MythicMobs item támogatás.
- Egyszerű publikus API más pluginek számára.

## Követelmények

- Java 21
- Paper API / Paper szerver 1.21.x
- Maven 3.x a fordításhoz
- Opcionális: Oraxen
- Opcionális: MythicMobs

## Projekt felépítés

```text
VContainer/
├── pom.xml
├── vcontainer-api/
│   └── src/main/java/hu/vzone/vcontainer/api/
└── vcontainer-core/
    ├── src/main/java/hu/vzone/vcontainer/
    └── src/main/resources/
```

- `vcontainer-api`: a publikus API interfész.
- `vcontainer-core`: maga a plugin implementáció, GUI, parancsok, listener és adatkezelés.

## Fordítás

A projekt gyökeréből:

```bash
mvn clean package
```

A kész plugin jar a core modul target mappájába kerül:

```text
vcontainer-core/target/VContainer-1.0.0.jar
```

## Telepítés

1. Fordítsd le a projektet Mavennel.
2. Másold a `vcontainer-core/target/VContainer-1.0.0.jar` fájlt a szerver `plugins` mappájába.
3. Indítsd újra a szervert.
4. A plugin létrehozza a konfigurációs fájlokat a `plugins/VContainer/` mappában.

## Parancsok

| Parancs | Jogosultság | Leírás |
| --- | --- | --- |
| `/container` | `vcontainer.use` | Megnyitja a saját konténert. |
| `/vcontainer` | `vcontainer.admin` | Kiírja az admin súgót. |
| `/vcontainer open <player>` | `vcontainer.admin` | Megnyitja egy online játékos konténerét. |
| `/vcontainer clear <player>` | `vcontainer.admin` | Kiüríti egy online játékos konténerét. |
| `/vcontainer give minecraft <item> [player] [amount]` | `vcontainer.admin` | Vanilla Minecraft itemet ad a konténerbe. |
| `/vcontainer give oraxen <item_id> [player] [amount]` | `vcontainer.admin` | Oraxen itemet ad a konténerbe, ha az Oraxen fut. |
| `/vcontainer give mythicmobs <item_id> [player] [amount]` | `vcontainer.admin` | MythicMobs itemet ad a konténerbe, ha a MythicMobs fut. |
| `/vcontainer reload` | `vcontainer.admin` | Újratölti a configot és a messages fájlt. |

Az admin `give` parancsnál, ha a játékos nincs megadva és a parancsot játékos futtatja, a plugin a parancsot futtató játékos konténerébe adja az itemet.

## Jogosultságok

| Jogosultság | Leírás |
| --- | --- |
| `vcontainer.use` | Saját konténer megnyitása. |
| `vcontainer.admin` | Admin parancsok használata. |
| `vcontainer.notify` | Konténerből kivett itemről visszajelző üzenet fogadása. |

## Konfiguráció

Alapértelmezett `config.yml`:

```yaml
title: "&0Container %current-page%/%max-page%"

content-slots: []

buttons:
  prev:
    material: ARROW
    name: "&a« Prev"
    lore:
      - "&7Previous Page"
    slot: 45+3
  next:
    material: ARROW
    name: "&aNext »"
    lore:
      - "&7Next Page"
    slot: 45+5

stack: true
max-stack: 64
player-data-folder: player_data
```

Fontosabb beállítások:

- `title`: a GUI címe. Használható placeholder: `%current-page%`, `%max-page%`.
- `stack`: ha `true`, az azonos típusú és azonos meta/NBT adatú itemek stackelődnek.
- `max-stack`: a plugin által használt maximális stack méret.
- `buttons.prev` és `buttons.next`: lapozógombok kinézete.
- `player-data-folder`: tervezett adatkönyvtár neve. A jelenlegi implementáció a `plugins/VContainer/player_data/` mappát használja.

Az üzenetek a `messages.yml` fájlban módosíthatók. A plugin támogatja az `&` színkódokat és a hex formátumot is, például: `&#1898FF`.

## Adattárolás

A konténerek játékosonként külön JSON fájlba kerülnek:

```text
plugins/VContainer/player_data/<player-uuid>.json
```

A fájlban az itemlista Base64 formában tárolódik Bukkit szerializációval. Emiatt a mentések Bukkit/Paper item meta adatokat is megőriznek, például display name, lore, enchantok és persistent data.

## GUI működés

- A GUI mérete 6 sor, összesen 54 slot.
- Az első 5 sor tartalomként működik, oldalanként legfeljebb 45 itemmel.
- Az utolsó sor dekorációs/navigációs sor.
- A lapozógombok alapértelmezetten az utolsó sor 4. és 6. slotján vannak.
- Kattintáskor az item a játékos inventoryjába kerül, majd a konténer frissül.

## API használat

Más pluginek a Bukkit ServicesManageren keresztül érhetik el az API-t:

```java
RegisteredServiceProvider<VContainerAPI> provider =
        Bukkit.getServicesManager().getRegistration(VContainerAPI.class);

if (provider != null) {
    VContainerAPI api = provider.getProvider();
    api.addItem(player, itemStack);
}
```

Elérhető API metódusok:

```java
void addItem(Player player, ItemStack item);
void removeItem(Player player, ItemStack item);
List<ItemStack> getItems(Player player);
void clear(Player player);
```

## Opcionális plugin támogatás

A `plugin.yml` szerint az Oraxen és a MythicMobs soft dependency:

```yaml
softdepend:
  - Oraxen
  - MythicMobs
```

Ha ezek a pluginek futnak a szerveren, a `/vcontainer give` parancs képes az általuk definiált itemeket is konténerbe adni.

## Fejlesztői megjegyzések

- A fő plugin osztály: `hu.vzone.vcontainer.VContainer`
- A konténer logika: `ContainerManager`
- A GUI: `ContainerGUI`
- A kattintáskezelés: `ContainerListener`
- A publikus API implementáció: `VContainerAPIImpl`

Jelenleg a `ContainerAddItemEvent` osztály üres, ezért publikus eventként még nem használható.
