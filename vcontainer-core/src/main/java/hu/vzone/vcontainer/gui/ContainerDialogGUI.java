package hu.vzone.vcontainer.gui;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import hu.vzone.vcontainer.sell.SellService;
import hu.vzone.vcontainer.gui.item.AggregatedItem;
import hu.vzone.vcontainer.gui.item.ItemAggregationService;
import hu.vzone.vcontainer.utils.ItemDisplayNames;
import hu.vzone.vcontainer.utils.ItemUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Runtime-reflection bridge so the plugin can still load on Paper versions before Dialog API support. */
final class ContainerDialogGUI {
    private static final int PAGE_SIZE = 12;
    private static final Map<UUID, DialogState> STATES = new ConcurrentHashMap<>();
    private static volatile boolean dialogFailureLogged;

    private ContainerDialogGUI() {
    }

    static void clear(UUID playerId) {
        STATES.remove(playerId);
    }

    static void shutdown() {
        STATES.clear();
    }

    static boolean open(Player viewer, UUID ownerId, String ownerName, ContainerManager manager, StorageBlockManager storageBlockManager, String storageKey) {
        VContainer plugin = VContainer.getInstance();
        if (plugin == null || !plugin.getConfig().getBoolean("Dialog", false) || !isSupported(viewer)) return false;

        DialogState state = STATES.compute(viewer.getUniqueId(), (ignored, existing) -> existing != null
                && existing.ownerId.equals(ownerId)
                ? existing
                : new DialogState(viewer.getUniqueId(), ownerId, ownerName, manager, storageBlockManager, storageKey));
        return show(viewer, state);
    }

    private static boolean show(Player viewer, DialogState state) {
        try {
            List<IndexedItem> filtered = filteredItems(state);
            int maxPage = Math.max(0, (filtered.size() - 1) / PAGE_SIZE);
            state.page = Math.max(0, Math.min(state.page, maxPage));
            List<IndexedItem> pageItems = filtered.subList(Math.min(state.page * PAGE_SIZE, filtered.size()), Math.min((state.page + 1) * PAGE_SIZE, filtered.size()));
            if (state.selectedIndex < 0 || pageItems.stream().noneMatch(item -> item.index == state.selectedIndex)) {
                state.selectedIndex = pageItems.isEmpty() ? -1 : pageItems.get(0).index;
            }

            Object base = buildBase(state, filtered.size(), pageItems);
            Object type = buildType(state);
            Class<?> dialogClass = Class.forName("io.papermc.paper.dialog.Dialog");
            Method create = dialogClass.getMethod("create", Consumer.class);
            Object dialog = create.invoke(null, (Consumer<Object>) factory -> {
                try {
                    Object builder = invoke(factory, "empty");
                    invoke(builder, "base", base);
                    invoke(builder, "type", type);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Could not build VContainer dialog.", ex);
                }
            });
            showDialog(viewer, dialog);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logDialogFailure(ex);
            return false;
        }
    }

    /**
     * Player implementations do not have to declare the default Audience method themselves.
     * Invoking it on Audience keeps this compatible with CraftPlayer implementations on Paper.
     */
    private static void showDialog(Player viewer, Object dialog) throws ReflectiveOperationException {
        Class<?> dialogLike = Class.forName("net.kyori.adventure.dialog.DialogLike");
        Class<?> audience = Class.forName("net.kyori.adventure.audience.Audience");
        audience.getMethod("showDialog", dialogLike).invoke(viewer, dialog);
    }

    private static void logDialogFailure(Exception exception) {
        VContainer plugin = VContainer.getInstance();
        if (plugin != null && !dialogFailureLogged) {
            dialogFailureLogged = true;
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not open the VContainer Dialog UI. Please report this stack trace with your Paper version.", exception);
        }
    }

    private static Object buildBase(DialogState state, int total, List<IndexedItem> pageItems) throws ReflectiveOperationException {
        Class<?> baseClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
        Object builder = baseClass.getMethod("builder", Component.class).invoke(null, Component.text("VContainer - " + state.ownerName));
        List<Object> body = new ArrayList<>();
        Class<?> bodyClass = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody");
        body.add(bodyClass.getMethod("plainMessage", Component.class).invoke(null,
                Component.text("Items: " + total + " | Page " + (state.page + 1))));
        for (IndexedItem entry : pageItems) body.add(itemBody(bodyClass, entry));
        invoke(builder, "body", body);
        invoke(builder, "inputs", List.of(searchInput(state.query), selectionInput(pageItems, state.selectedIndex)));
        return invoke(builder, "build");
    }

    /** Keeps all client-side render data, including CustomModelData, on the displayed item. */
    private static Object itemBody(Class<?> bodyClass, IndexedItem entry) throws ReflectiveOperationException {
        ItemStack displayItem = entry.item.clone();
        displayItem.setAmount(1);
        Object builder = bodyClass.getMethod("item", ItemStack.class).invoke(null, displayItem);
        Object description = bodyClass.getMethod("plainMessage", Component.class).invoke(null,
                Component.text(itemLabel(displayItem) + " x" + String.format("%,d", entry.amount)));
        invoke(builder, "description", description);
        invoke(builder, "showDecorations", true);
        invoke(builder, "showTooltip", true);
        invoke(builder, "width", 72);
        invoke(builder, "height", 72);
        return invoke(builder, "build");
    }

    private static Object searchInput(String query) throws ReflectiveOperationException {
        Class<?> inputClass = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
        Object builder = inputClass.getMethod("text", String.class, Component.class).invoke(null, "search", Component.text("Search"));
        invoke(builder, "width", 300);
        invoke(builder, "labelVisible", true);
        invoke(builder, "initial", query == null ? "" : query);
        invoke(builder, "maxLength", 64);
        return invoke(builder, "build");
    }

    private static Object selectionInput(List<IndexedItem> items, int selectedIndex) throws ReflectiveOperationException {
        Class<?> optionClass = Class.forName("io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput$OptionEntry");
        List<Object> options = new ArrayList<>();
        if (items.isEmpty()) {
            options.add(optionClass.getMethod("create", String.class, Component.class, boolean.class)
                    .invoke(null, "-1", Component.text("No matching items"), true));
        }
        for (IndexedItem item : items) {
            options.add(optionClass.getMethod("create", String.class, Component.class, boolean.class)
                    .invoke(null, String.valueOf(item.index), Component.text(itemLabel(item.item) + " x" + String.format("%,d", item.amount)), item.index == selectedIndex));
        }
        Class<?> inputClass = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
        Object builder = inputClass.getMethod("singleOption", String.class, Component.class, List.class)
                .invoke(null, "item", Component.text("Selected item"), options);
        invoke(builder, "width", 300);
        invoke(builder, "labelVisible", true);
        return invoke(builder, "build");
    }

    private static Object buildType(DialogState state) throws ReflectiveOperationException {
        List<Object> buttons = new ArrayList<>();
        buttons.add(button("Search", DialogAction.SEARCH, state));
        buttons.add(button("Previous page", DialogAction.PREVIOUS, state));
        buttons.add(button("Next page", DialogAction.NEXT, state));
        buttons.add(button("Sort", DialogAction.SORT, state));
        buttons.add(button("Withdraw 1", DialogAction.WITHDRAW_ONE, state));
        buttons.add(button("Withdraw stack", DialogAction.WITHDRAW_STACK, state));
        buttons.add(button("Withdraw all", DialogAction.WITHDRAW_ALL, state));
        buttons.add(button("Sell 1", DialogAction.SELL_ONE, state));
        buttons.add(button("Sell stack", DialogAction.SELL_STACK, state));
        buttons.add(button("Sell all", DialogAction.SELL_ALL, state));
        buttons.add(button("Open deposit menu", DialogAction.DEPOSIT, state));

        Class<?> typeClass = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
        Class<?> actionButtonClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
        Object exit = actionButtonClass.getMethod("create", Component.class, Component.class, int.class,
                        Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction"))
                .invoke(null, Component.text("Close"), Component.text("Close this dialog"), 100, null);
        return typeClass.getMethod("multiAction", List.class, actionButtonClass, int.class).invoke(null, buttons, exit, 2);
    }

    private static Object button(String label, DialogAction action, DialogState state) throws ReflectiveOperationException {
        Class<?> callbackClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogActionCallback");
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("accept") && args != null && args.length >= 1) {
                Bukkit.getScheduler().runTask(VContainer.getInstance(), () -> handle(state, action, args[0]));
            }
            return null;
        };
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass}, handler);
        Object options = callbackOptions();
        Class<?> actionClass = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
        Object dialogAction = actionClass.getMethod("customClick", callbackClass,
                Class.forName("net.kyori.adventure.text.event.ClickCallback$Options")).invoke(null, callback, options);
        Class<?> actionButtonClass = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
        return actionButtonClass.getMethod("create", Component.class, Component.class, int.class, actionClass)
                .invoke(null, Component.text(label), Component.text(label), 100, dialogAction);
    }

    private static Object callbackOptions() throws ReflectiveOperationException {
        Class<?> optionsClass = Class.forName("net.kyori.adventure.text.event.ClickCallback$Options");
        Object builder = optionsClass.getMethod("builder").invoke(null);
        invoke(builder, "uses", 1);
        return invoke(builder, "build");
    }

    private static void handle(DialogState state, DialogAction action, Object response) {
        Player player = Bukkit.getPlayer(state.viewerId);
        if (player == null || !player.isOnline()) {
            STATES.remove(state.viewerId);
            return;
        }
        String search = responseText(response, "search");
        if (search != null) state.query = search;
        String selected = responseText(response, "item");
        if (selected != null) {
            try {
                state.selectedIndex = Integer.parseInt(selected);
            } catch (NumberFormatException ignored) {
            }
        }

        if (action == DialogAction.PREVIOUS) state.page--;
        if (action == DialogAction.NEXT) state.page++;
        if (action == DialogAction.SORT) state.amountSort = !state.amountSort;
        if (action == DialogAction.DEPOSIT) {
            ContainerGUI.openClassicContainer(player, state.ownerId, state.ownerName, state.manager, 1, state.storageBlockManager, state.storageKey);
            return;
        }
        if (action == DialogAction.WITHDRAW_ONE || action == DialogAction.WITHDRAW_STACK || action == DialogAction.WITHDRAW_ALL) {
            withdraw(player, state, action);
        }
        if (action == DialogAction.SELL_ONE || action == DialogAction.SELL_STACK || action == DialogAction.SELL_ALL) {
            sell(player, state, action);
        }
        show(player, state);
    }

    private static void withdraw(Player player, DialogState state, DialogAction action) {
        ItemStack target = selectedItem(state);
        if (target == null) return;
        int amount = switch (action) {
            case WITHDRAW_ONE -> 1;
            case WITHDRAW_STACK -> target.getMaxStackSize();
            case WITHDRAW_ALL -> Integer.MAX_VALUE;
            default -> 0;
        };
        int removed = state.manager.takeItemFromContainer(state.ownerId, target, amount);
        if (removed <= 0) return;
        ItemStack give = target.clone();
        give.setAmount(removed);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(give);
        for (ItemStack leftover : leftovers.values()) {
            state.manager.restoreItemToContainer(state.ownerId, leftover);
        }
    }

    private static void sell(Player player, DialogState state, DialogAction action) {
        SellService sellService = VContainer.getInstance().getSellService();
        ItemStack target = selectedItem(state);
        if (sellService == null || target == null || !sellService.isSellEnabled()) return;
        int amount = switch (action) {
            case SELL_ONE -> 1;
            case SELL_STACK -> target.getMaxStackSize();
            case SELL_ALL -> matchingAmount(state, target);
            default -> 0;
        };
        if (amount <= 0) return;
        if (amount >= sellService.bulkSaleThreshold()) {
            sellService.startBulkSell(player, state.manager, state.ownerId, target, amount, ignored -> show(player, state));
            return;
        }
        sellService.sell(player, state.manager, state.ownerId, target, amount);
    }

    private static ItemStack selectedItem(DialogState state) {
        for (IndexedItem item : filteredItems(state)) {
            if (item.index == state.selectedIndex) return item.item.clone();
        }
        return null;
    }

    private static int matchingAmount(DialogState state, ItemStack target) {
        long total = 0L;
        for (ItemStack item : state.manager.getItemView(state.ownerId)) {
            if (ItemUtils.isSameItemWithNBT(item, target)) total += item.getAmount();
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    private static List<IndexedItem> filteredItems(DialogState state) {
        List<IndexedItem> result = new ArrayList<>();
        List<AggregatedItem> aggregated = ItemAggregationService.aggregate(state.manager.getItemView(state.ownerId));
        List<AggregatedItem> filtered = ItemAggregationService.filter(aggregated, state.query);
        for (int index = 0; index < filtered.size(); index++) {
            AggregatedItem item = filtered.get(index);
            result.add(new IndexedItem(index, item.template(), item.amount()));
        }
        if (state.amountSort) {
            result.sort(Comparator.comparingInt((IndexedItem entry) -> entry.amount).reversed());
        } else {
            result.sort(Comparator.comparing(entry -> entry.item.getType().name()));
        }
        return result;
    }

    private static String responseText(Object response, String key) {
        try {
            Object value = response.getClass().getMethod("getText", String.class).invoke(response, key);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isSupported(Player player) {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            Class.forName("net.kyori.adventure.dialog.DialogLike");
            Boolean viaSupportsDialogs = Bukkit.getPluginManager().isPluginEnabled("ViaVersion")
                    ? isViaDialogClient(player)
                    : null;
            if (Boolean.FALSE.equals(viaSupportsDialogs)) {
                return false;
            }
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    /** Returns null when ViaVersion cannot identify the client, so a valid Paper dialog is not hidden by mistake. */
    private static Boolean isViaDialogClient(Player player) {
        try {
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = viaClass.getMethod("getAPI").invoke(null);
            int playerProtocol = ((Number) api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId())).intValue();
            Class<?> protocolClass = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
            Object minimumProtocol = protocolClass.getField("v1_21_6").get(null);
            int minimum = ((Number) protocolClass.getMethod("getVersion").invoke(minimumProtocol)).intValue();
            return playerProtocol >= minimum;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private static Object invoke(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            if (Modifier.isPublic(method.getDeclaringClass().getModifiers()) && compatible(method.getParameterTypes(), args)) {
                return method.invoke(target, args);
            }
        }
        Method interfaceMethod = findPublicInterfaceMethod(target.getClass(), name, args);
        if (interfaceMethod != null) return interfaceMethod.invoke(target, args);
        throw new NoSuchMethodException(name);
    }

    /**
     * Adventure builders often use package-private implementation classes. Their public methods
     * must be invoked through the public builder interface rather than the implementation class.
     */
    private static Method findPublicInterfaceMethod(Class<?> type, String name, Object[] args) {
        for (Class<?> interfaceType : type.getInterfaces()) {
            for (Method method : interfaceType.getMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == args.length
                        && compatible(method.getParameterTypes(), args)) {
                    return method;
                }
            }
            Method nested = findPublicInterfaceMethod(interfaceType, name, args);
            if (nested != null) return nested;
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? null : findPublicInterfaceMethod(parent, name, args);
    }

    private static boolean compatible(Class<?>[] parameters, Object[] args) {
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] == null) continue;
            if (parameters[i].isPrimitive()) {
                if (!(args[i] instanceof Number) && parameters[i] != boolean.class) return false;
            } else if (!parameters[i].isInstance(args[i])) return false;
        }
        return true;
    }

    private static String itemLabel(ItemStack item) {
        return ItemDisplayNames.resolve(item);
    }

    private enum DialogAction {
        SEARCH, PREVIOUS, NEXT, SORT, WITHDRAW_ONE, WITHDRAW_STACK, WITHDRAW_ALL, SELL_ONE, SELL_STACK, SELL_ALL, DEPOSIT
    }

    private record IndexedItem(int index, ItemStack item, int amount) {
    }

    private static final class DialogState {
        private final UUID viewerId;
        private final UUID ownerId;
        private final String ownerName;
        private final ContainerManager manager;
        private final StorageBlockManager storageBlockManager;
        private final String storageKey;
        private String query = "";
        private int page;
        private int selectedIndex = -1;
        private boolean amountSort;

        private DialogState(UUID viewerId, UUID ownerId, String ownerName, ContainerManager manager, StorageBlockManager storageBlockManager, String storageKey) {
            this.viewerId = viewerId;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.manager = manager;
            this.storageBlockManager = storageBlockManager;
            this.storageKey = storageKey;
        }
    }
}
