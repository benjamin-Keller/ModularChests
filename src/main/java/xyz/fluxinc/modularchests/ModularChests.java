package xyz.fluxinc.modularchests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import com.sun.tools.javac.comp.Todo;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import org.bukkit.plugin.java.JavaPlugin;
import xyz.fluxinc.fluxcore.FluxCore;
import xyz.fluxinc.fluxcore.security.BlockAccessController;

public class ModularChests extends JavaPlugin implements Listener
{
    private class CoordinatePair
    {
        Location start;
        Location end;

        CoordinatePair(Location start, Location end)
        {
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof CoordinatePair && ((CoordinatePair) obj).end.equals(end) && ((CoordinatePair) obj).start.equals(start);
        }
    }

    //Gets the Storage Data
    private class StorageData
    {
        CoordinatePair bounds;
        Inventory[] inventories;
        int index;
        boolean mustOpen;
    }

    //Sorts the storage by Item Name
    private class SortByName implements Comparator<ItemStack>
    {
        @Override
        public int compare(ItemStack item1, ItemStack item2)
        {
            String s1 = item1.getType().name();
            String s2 = item2.getType().name();
            return s1.compareTo(s2);
        }
    }

    /*TODO: Make it compatible with colored glass*/
    private static Material surface = Material.GLASS;
    private static Material surfaceSide = Material.GLASS_PANE;
    private static Material filler = Material.CHEST;
    private static Material edge = Material.SMOOTH_QUARTZ;

    private static int minSize = 4;
    private static int maxSize = 20;

    private static HashMap<Player, StorageData> storageData = new HashMap<Player, StorageData>();

    private static ItemStack arrowBack;
    private static ItemStack arrowNext;
    private static ItemStack whiteFiller;

    private static String arrowBackName = "§r§2Previous";
    private static String arrowNextName = "§r§aNext";
    private static String inUsage = "Storage in use";
    private static String titleFormat = "Page %d of %d";

    private static int inventoryRows = 4;
    private static int itemCount;

    private BlockAccessController blockAccessController;

    @Override
    public void onEnable()
    {
        FluxCore fluxCore = (FluxCore) getServer().getPluginManager().getPlugin("FluxCore");
        if (fluxCore == null) { getServer().getPluginManager().disablePlugin(this); }
        blockAccessController = fluxCore.getBlockAccessController();
        getServer().getPluginManager().registerEvents(this, this);
        loadConfig();
        initFillers();
        itemCount = (inventoryRows - 1) * 9;
    }

    //Sets the "page" navigation
    private void initFillers()
    {
        arrowBack = new ItemStack(Material.ARROW);
        ItemMeta meta = arrowBack.getItemMeta();
        meta.setDisplayName(arrowBackName);
        arrowBack.setItemMeta(meta);

        arrowNext = new ItemStack(Material.ARROW);
        meta = arrowNext.getItemMeta();
        meta.setDisplayName(arrowNextName);
        arrowNext.setItemMeta(meta);

        whiteFiller = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
        meta = whiteFiller.getItemMeta();
        meta.setDisplayName(" ");
        whiteFiller.setItemMeta(meta);
    }

    //Loading the saved Config
    private void loadConfig()
    {
        getConfig().options().copyDefaults(true);

        if (!getConfig().contains("materials.surface"))
            getConfig().set("materials.surface", surface.name());
        surface = Material.getMaterial(getConfig().getString("materials.surface", surface.name()));

        if (!getConfig().contains("materials.surfaceSide"))
            getConfig().set("materials.surfaceSide", surfaceSide.name());
        surfaceSide = Material.getMaterial(getConfig().getString("materials.surfaceSide", surfaceSide.name()));

        if (!getConfig().contains("materials.filler"))
            getConfig().set("materials.filler", filler.name());
        filler = Material.getMaterial(getConfig().getString("materials.filler", filler.name()));

        if (!getConfig().contains("materials.edge"))
            getConfig().set("materials.edge", edge.name());
        edge = Material.getMaterial(getConfig().getString("materials.edge", edge.name()));

        if (!getConfig().contains("size.minimum"))
            getConfig().set("size.minimum", minSize);
        minSize = getConfig().getInt("size.minimum", minSize);

        if (!getConfig().contains("size.maximum"))
            getConfig().set("size.maximum", maxSize);
        maxSize = getConfig().getInt("size.maximum", maxSize);

        if (!getConfig().contains("title.back"))
            getConfig().set("title.back", arrowBackName);
        arrowBackName = getConfig().getString("title.back", arrowBackName);

        if (!getConfig().contains("title.next"))
            getConfig().set("title.next", arrowNextName);
        arrowNextName = getConfig().getString("title.next", arrowNextName);

        if (!getConfig().contains("title.page-format"))
            getConfig().set("title.page-format", titleFormat);
        titleFormat = getConfig().getString("title.page-format", titleFormat);

        if (!getConfig().contains("message.in-usage"))
            getConfig().set("message.in-usage", inUsage);
        inUsage = getConfig().getString("message.in-usage", inUsage);

        if (!getConfig().contains("size.rows"))
            getConfig().set("size.rows", inventoryRows);
        inventoryRows = getConfig().getInt("size.rows", inventoryRows);
        if (inventoryRows > 6)
            inventoryRows = 6;
        else if (inventoryRows < 2)
            inventoryRows = 2;

        saveConfig();
    }

    //When the Player clicks on the multi-block
    @EventHandler
    public void onBlockClick(PlayerInteractEvent e)
    {
        if (e.getPlayer().isSneaking()) { return; }
        if (blockAccessController == null || !blockAccessController.checkBlockAccess(e.getPlayer(), e.getClickedBlock().getLocation())) { return; }
        //Right click on a block
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getHand() == EquipmentSlot.HAND)
        {
            CoordinatePair bounds = findBounds(e.getClickedBlock().getLocation());
            if (bounds != null && isValidBounds(bounds) && checkStructure(bounds.start, bounds.end))
            {
                if (!blockAccessController.checkBlockAccess(e.getPlayer(), bounds.start) || !blockAccessController.checkBlockAccess(e.getPlayer(), bounds.end)) { return; }
                e.setCancelled(true);
                if (isStorageInUse(bounds))
                {
                    e.getPlayer().sendMessage(inUsage);
                    return;
                }
                else
                {
                    //Loading the "Chest" and sorting each item
                    List<ItemStack> items = getContainedItems(bounds);
                    Collections.sort(items, new SortByName());
                    int chestCount = countStorageSpace(bounds);
                    int totalItemCount = chestCount * 27;
                    Inventory[] inv = new Inventory[(int) Math.ceil((float) totalItemCount / itemCount)];
                    int lastSize = totalItemCount - (inv.length - 1) * itemCount;
                    int index = 0;
                    boolean add = items.size() > 0;
                    for (int i = 0; i < inv.length; i++)
                    {
                        if (i < inv.length - 1)
                            inv[i] = Bukkit.createInventory(null, itemCount + 9, String.format(titleFormat, i + 1, inv.length));
                        else
                            inv[i] = Bukkit.createInventory(null, lastSize + 9, String.format(titleFormat, i + 1, inv.length));
                        fillControls(inv[i]);
                        if (add)
                            for (int j = 0; j < inv[i].getSize() - 9; j++)
                            {
                                inv[i].setItem(j, items.get(index++));
                                if (index >= items.size())
                                {
                                    add = false;
                                    break;
                                }
                            }
                    }

                    StorageData st = new StorageData();
                    st.bounds = bounds;
                    st.inventories = inv;
                    st.index = 0;
                    st.mustOpen = false;
                    storageData.put(e.getPlayer(), st);

                    e.getPlayer().openInventory(inv[0]);
                }
            }
        }
    }

    //Saving the inventory when Player closes "Chest"
    @EventHandler
    public void onCloseInventory(InventoryCloseEvent e)
    {
        if (e.getPlayer() instanceof Player)
        {
            Player p = (Player) e.getPlayer();
            if (storageData.containsKey(p))
            {
                StorageData data = storageData.get(p);
                if (!data.mustOpen)
                {
                    List<ItemStack> items = getAllContents(data.inventories);
                    items = putItemsInStorage(data.bounds, items);
                    storageData.remove(p);
                    if (!items.isEmpty())
                        for (ItemStack i: items)
                            p.getWorld().dropItem(p.getLocation(), i);
                }
                else
                    data.mustOpen = false;
            }
        }
    }

    //Inventory shiz (basic getting the chest shape and storage data)
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e)
    {
        if (e.getWhoClicked() instanceof Player)
        {
            Player p = (Player) e.getWhoClicked();
            if (storageData.containsKey(p))
            {
                StorageData data = storageData.get(p);
                int startSlotID = data.inventories[data.index].getSize() - 9;
                int endSlotID = startSlotID + 8;
                if (e.getRawSlot() >= startSlotID && e.getRawSlot() <= endSlotID)
                    e.setCancelled(true);
                if (e.getRawSlot() == startSlotID)
                {
                    int index = data.index - 1;
                    if (index < 0)
                        index += data.inventories.length;
                    data.mustOpen = true;
                    data.index = index;
                    p.openInventory(data.inventories[index]);
                }
                else if (e.getRawSlot() == endSlotID)
                {
                    int index = data.index + 1;
                    if (index >= data.inventories.length)
                        index -= data.inventories.length;
                    data.mustOpen = true;
                    data.index = index;
                    p.openInventory(data.inventories[index]);
                }
                else
                {
                    data.mustOpen = false;
                }
            }
        }
    }

    // Storage data (AGAIN)
    @EventHandler
    public void onBlockBreak(final BlockBreakEvent e)
    {
        for (StorageData st: storageData.values())
            if (isInside(st.bounds, e.getBlock().getLocation()))
            {
                e.getPlayer().sendMessage(inUsage);
                e.setCancelled(true);
            }
    }

    //Nothing on piston extend
    @EventHandler
    public void onPistonMove(BlockPistonExtendEvent e)
    {
        for (StorageData st: storageData.values())
            if (isInside(st.bounds, e.getBlock().getLocation().add(e.getDirection().getDirection())))
            {
                e.setCancelled(true);
            }
    }

    //Breaking on Piston retract
    @EventHandler
    public void onPistonMove(BlockPistonRetractEvent e)
    {
        for (StorageData st: storageData.values())
            if (isInside(st.bounds, e.getBlock().getLocation().add(e.getDirection().getDirection())))
            {
                e.setCancelled(true);
            }
    }

    //Saving items in chests on explosion.
    @EventHandler
    public void onBlockExplode(final BlockExplodeEvent e)
    {
        for (StorageData st: storageData.values())
            if (isInside(st.bounds, e.getBlock().getLocation()))
            {
                e.setCancelled(true);
            }
    }

    //Saving items in ItemStack
    private List<ItemStack> getAllContents(Inventory[] inv)
    {
        List<ItemStack> items = new ArrayList<ItemStack>();
        for (Inventory i: inv)
            for (int j = 0; j < i.getSize() - 9; j++)
            {
                ItemStack item = i.getItem(j);
                if (item != null)
                    items.add(item);
            }
        return items;
    }

    //Chest is inside
    private boolean isInside(CoordinatePair p, Location l)
    {
        return l.getBlockX() >= p.start.getBlockX() &&
                l.getBlockX() <= p.end.getBlockX() &&
                l.getBlockY() >= p.start.getBlockY() &&
                l.getBlockY() <= p.end.getBlockY() &&
                l.getBlockZ() >= p.start.getBlockZ() &&
                l.getBlockZ() <= p.end.getBlockZ();
    }

    //Adding the Navigation
    private void fillControls(Inventory inv)
    {
        int backPos = inv.getSize() - 9;
        int nextPos = backPos + 8;
        inv.setItem(backPos, arrowBack.clone());
        inv.setItem(nextPos, arrowNext.clone());
        for (int i = backPos + 1; i < nextPos; i++)
            inv.setItem(i, whiteFiller.clone());
    }

    //Counting how much storage is available by means of chests
    private int countStorageSpace(CoordinatePair pair)
    {
        int res = (pair.end.getBlockX() - pair.start.getBlockX() - 1);
        res *= (pair.end.getBlockY() - pair.start.getBlockY() - 1);
        res *= (pair.end.getBlockZ() - pair.start.getBlockZ() - 1);
        return res;
    }

    //Checking if the modular storage is made of the set blocks
    private boolean isBoxMaterial(Material mat)
    {
        return mat == surface || mat == surfaceSide || mat == filler || mat == edge;
    }

    //Finding the edges of the modular storage (Glass Block)
    private CoordinatePair findBounds(Location loc)
    {
        Material sel = loc.getBlock().getType();
        World w = loc.getWorld();
        if (isBoxMaterial(surface))
        {
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();

            int x1 = x;
            int x2 = x;
            int y1 = y;
            int y2 = y;
            int z1 = z;
            int z2 = z;

            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x1--, y, z).getType();
            sel = surface;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x2++, y, z).getType();
            sel = surface;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y1--, z).getType();
            sel = surface;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y2++, z).getType();
            sel = surface;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y, z1--).getType();
            sel = surface;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y, z2++).getType();
            return new CoordinatePair(new Location(w, x1 + 2, y1 + 2, z1 + 2), new Location(w, x2 - 2, y2 - 2, z2 - 2));
        }
        //Finding the edges of the modular storage (Glass Pane)
        if (isBoxMaterial(surfaceSide))
        {
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();

            int x1 = x;
            int x2 = x;
            int y1 = y;
            int y2 = y;
            int z1 = z;
            int z2 = z;

            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x1--, y, z).getType();
            sel = surfaceSide;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x2++, y, z).getType();
            sel = surfaceSide;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y1--, z).getType();
            sel = surfaceSide;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y2++, z).getType();
            sel = surfaceSide;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y, z1--).getType();
            sel = surfaceSide;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y, z2++).getType();
            return new CoordinatePair(new Location(w, x1 + 2, y1 + 2, z1 + 2), new Location(w, x2 - 2, y2 - 2, z2 - 2));
        }
        //Finding the edges of the modular storage (Smooth Quartz)
        if (isBoxMaterial(edge))
        {
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();

            int x1 = x;
            int x2 = x;
            int y1 = y;
            int y2 = y;
            int z1 = z;
            int z2 = z;

            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x1--, y, z).getType();
            sel = edge;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x2++, y, z).getType();
            sel = edge;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y1--, z).getType();
            sel = edge;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y2++, z).getType();
            sel = edge;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y, z1--).getType();
            sel = edge;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y, z2++).getType();
            return new CoordinatePair(new Location(w, x1 + 2, y1 + 2, z1 + 2), new Location(w, x2 - 2, y2 - 2, z2 - 2));
        }
        //Finding the edges of the modular storage (Chests)
        if (isBoxMaterial(filler))
        {
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();

            int x1 = x;
            int x2 = x;
            int y1 = y;
            int y2 = y;
            int z1 = z;
            int z2 = z;

            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x1--, y, z).getType();
            sel = filler;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x2++, y, z).getType();
            sel = filler;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y1--, z).getType();
            sel = filler;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y2++, z).getType();
            sel = filler;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y, z1--).getType();
            sel = filler;
            while(isBoxMaterial(sel))
                sel = w.getBlockAt(x, y, z2++).getType();
            return new CoordinatePair(new Location(w, x1 + 2, y1 + 2, z1 + 2), new Location(w, x2 - 2, y2 - 2, z2 - 2));
        }

        return null;
    }

    //Checking the structure if it is valid. (NOT going into depth, please don't break this!)
    private boolean checkStructure(Location start, Location end)
    {
        World w = start.getWorld();
        Material sel;
        for (int x = start.getBlockX(); x <= end.getBlockX(); x++)
            for (int y = start.getBlockY(); y <= end.getBlockY(); y++)
                for (int z = start.getBlockZ(); z <= end.getBlockZ(); z++)
                {
                    sel = w.getBlockAt(x, y, z).getType();
                    if (x == start.getBlockX() || x == end.getBlockX())
                    {
                        if (y == start.getBlockY() || z == start.getBlockZ() || y == end.getBlockY() || z == end.getBlockZ())
                        {
                            if (sel != edge)
                                return false;
                        }
                        else if (sel != surface && sel != surfaceSide)
                            return false;
                    }
                    else if (y == start.getBlockY() || y == end.getBlockY())
                    {
                        if (x == start.getBlockX() || z == start.getBlockZ() || x == end.getBlockX() || z == end.getBlockZ())
                        {
                            if (sel != edge)
                                return false;
                        }
                        else if (sel != surface && sel != surfaceSide)
                            return false;
                    }
                    else if (z == start.getBlockZ() || z == end.getBlockZ())
                    {
                        if (x == start.getBlockX() || y == start.getBlockY() || x == end.getBlockX() || y == end.getBlockY())
                        {
                            if (sel != edge)
                                return false;
                        }
                        else if (sel != surface && sel != surfaceSide)
                            return false;
                    }
                    else if (sel != filler)
                        return false;
                }
        return true;
    }

    //Checking for valid bounds by config
    private boolean isValidBounds(CoordinatePair pair)
    {
        int dx = pair.end.getBlockX() - pair.start.getBlockX() + 1;
        int dy = pair.end.getBlockY() - pair.start.getBlockY() + 1;
        int dz = pair.end.getBlockZ() - pair.start.getBlockZ() + 1;
        return dx >= minSize && dx <= maxSize && dy >= minSize && dy <= maxSize && dz >= minSize && dz <= maxSize;
    }

    //Getting the saved items
    private List<ItemStack> getContainedItems(CoordinatePair pair)
    {
        List<ItemStack> items = new ArrayList<ItemStack>();
        World w = pair.start.getWorld();
        Chest chest;
        Inventory inventory;
        for (int x = pair.start.getBlockX() + 1; x < pair.end.getBlockX(); x++)
            for (int y = pair.start.getBlockY() + 1; y < pair.end.getBlockY(); y++)
                for (int z = pair.start.getBlockZ() + 1; z < pair.end.getBlockZ(); z++)
                {
                    chest = (Chest) w.getBlockAt(x, y, z).getState();
                    inventory = chest.getBlockInventory();
                    for (ItemStack s: inventory.getContents())
                        if (s != null)
                            items.add(s);
                }
        return items;
    }

    //Saving the Items
    private List<ItemStack> putItemsInStorage(CoordinatePair pair, List<ItemStack> items)
    {
        Chest chest;
        Material mat;
        Inventory inventory;
        World w = pair.start.getWorld();
        int index = 0;
        boolean mustPut = items.size() > 0;
        List<ItemStack> itemsRemain = new ArrayList<ItemStack>();
        itemsRemain.addAll(items);
        for (int x = pair.start.getBlockX() + 1; x < pair.end.getBlockX(); x++)
            for (int y = pair.start.getBlockY() + 1; y < pair.end.getBlockY(); y++)
                for (int z = pair.start.getBlockZ() + 1; z < pair.end.getBlockZ(); z++)
                {
                    mat = w.getBlockAt(x, y, z).getType();
                    if (isChest(mat))
                    {
                        chest = (Chest) w.getBlockAt(x, y, z).getState();
                        inventory = chest.getBlockInventory();
                        inventory.clear();
                        if (mustPut)
                            for (int i = 0; i < 27; i++)
                            {
                                ItemStack item = items.get(index);
                                if (item != null)
                                {
                                    //Saving happens here
                                    inventory.addItem(item);
                                    itemsRemain.remove(item);
                                }
                                index++;
                                if (index >= items.size())
                                {
                                    mustPut = false;
                                    break;
                                }
                            }
                    }
                }
        return itemsRemain;
    }

    //Checking if only one person is in (Possible duping otherwise)
    private boolean isStorageInUse(CoordinatePair bounds)
    {
        for (StorageData sd: storageData.values())
            if (sd.bounds.equals(bounds))
                return true;
        return false;
    }

    //Checks if material is Chest
    private boolean isChest(Material mat)
    {
        return mat == Material.CHEST || mat == Material.TRAPPED_CHEST || mat == Material.SHULKER_BOX;
    }
}