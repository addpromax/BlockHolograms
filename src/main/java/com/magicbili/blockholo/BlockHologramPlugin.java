package com.magicbili.blockholo;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.data.property.Visibility;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class BlockHologramPlugin extends JavaPlugin implements Listener, TabCompleter {

    private FileConfiguration config;
    private FancyHologramsPlugin fancyHolograms;
    private HologramManager hologramManager;
    private FancyHologramHelper hologramHelper;
    private final Map<Location, Hologram> hologramMap = new HashMap<>();
    private final List<PendingHologram> pendingHolograms = new ArrayList<>();
    private Connection connection;
    private boolean debugMode = false;

    private void debug(String message) {
        if (debugMode) getLogger().info("[DEBUG] " + message);
    }
    
    private Location normalizeBlockLocation(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
    
    @Override
    public void onEnable() {
        // 检查 FancyHolograms 依赖
        Plugin plugin = Bukkit.getPluginManager().getPlugin("FancyHolograms");
        if (plugin == null || !(plugin instanceof FancyHologramsPlugin)) {
            getLogger().severe("========================================");
            getLogger().severe("FancyHolograms 插件未找到！");
            getLogger().severe("请安装 FancyHolograms 2.3.2+");
            getLogger().severe("下载: https://modrinth.com/plugin/fancyholograms");
            getLogger().severe("========================================");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        fancyHolograms = (FancyHologramsPlugin) plugin;
        hologramManager = fancyHolograms.getHologramManager();
        hologramHelper = new FancyHologramHelper(hologramManager);
        getLogger().info("成功连接到 FancyHolograms");
        
        reloadConfig();
        config = getConfig();
        saveDefaultConfig();
        debugMode = config.getBoolean("debug", false);
        setupDatabase();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("blockholo")).setTabCompleter(this);
        
        // 异步加载全息图
        loadExistingHolograms();
    }

    @Override
    public void onDisable() {
        clearAllHolograms();
        closeDatabase();
    }
    
    private void setupDatabase() {
        try {
            String host = config.getString("database.host", "localhost");
            int port = config.getInt("database.port", 3306);
            String database = config.getString("database.database", "mc_server");
            String username = config.getString("database.username", "root");
            String password = config.getString("database.password", "");
            
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database + 
                        "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8";
            connection = DriverManager.getConnection(url, username, password);
            
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS blockholo_players (" +
                    "uuid VARCHAR(36) PRIMARY KEY, toggle_status BOOLEAN NOT NULL DEFAULT 1)");
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS blockholo_holograms (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, world VARCHAR(255) NOT NULL, " +
                    "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, material VARCHAR(50) NOT NULL, " +
                    "`lines` TEXT NOT NULL, offset_x DOUBLE NOT NULL, offset_y DOUBLE NOT NULL, " +
                    "offset_z DOUBLE NOT NULL, UNIQUE KEY location (world, x, y, z))");
            }
        } catch (SQLException e) {
            getLogger().severe("数据库连接失败: " + e.getMessage());
            getLogger().warning("插件将无法保存全息图数据!");
        }
    }

    
    private void closeDatabase() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                getLogger().warning("关闭数据库连接时出错: " + e.getMessage());
            }
        }
    }
    
    private boolean getPlayerToggleStatus(UUID uuid) {
        if (connection == null) return true;
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT toggle_status FROM blockholo_players WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getBoolean("toggle_status");
            setPlayerToggleStatus(uuid, true);
            return true;
        } catch (SQLException e) {
            getLogger().warning("获取玩家状态时出错: " + e.getMessage());
            return true;
        }
    }
    
    private void setPlayerToggleStatus(UUID uuid, boolean status) {
        if (connection == null) return;
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO blockholo_players (uuid, toggle_status) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE toggle_status = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setBoolean(2, status);
            stmt.setBoolean(3, status);
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().warning("保存玩家状态时出错: " + e.getMessage());
        }
    }
    
    // 加载现有全息图（服务器启动时）
    private void loadExistingHolograms() {
        if (connection == null) {
            getLogger().warning("无法加载全息图 - 数据库连接不可用");
            return;
        }
        
        // 异步读取数据库
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM blockholo_holograms")) {
                
                List<PendingHologram> allRecords = new ArrayList<>();
                
                // 异步收集所有记录
                while (rs.next()) {
                    try {
                        String worldName = rs.getString("world");
                        int x = rs.getInt("x");
                        int y = rs.getInt("y");
                        int z = rs.getInt("z");
                        Material material = Material.valueOf(rs.getString("material"));
                        String lines = rs.getString("lines");
                        double storedOffsetX = rs.getDouble("offset_x");
                        double storedOffsetY = rs.getDouble("offset_y");
                        double storedOffsetZ = rs.getDouble("offset_z");

                        PendingHologram record = new PendingHologram(
                                worldName, x, y, z,
                                material.name(), lines,
                                storedOffsetX, storedOffsetY, storedOffsetZ
                        );
                        allRecords.add(record);
                    } catch (Exception e) {
                        getLogger().warning("读取全息图记录时出错: " + e.getMessage());
                    }
                }
                
                debug("从数据库读取了 " + allRecords.size() + " 条全息图记录");
                
                // 切换到主线程进行批量加载
                final int BATCH_SIZE = 10;
                final int[] loaded = {0};
                final int[] pending = {0};
                
                for (int i = 0; i < allRecords.size(); i += BATCH_SIZE) {
                    final int startIndex = i;
                    final int endIndex = Math.min(i + BATCH_SIZE, allRecords.size());
                    final List<PendingHologram> batch = allRecords.subList(startIndex, endIndex);
                    
                    // 每批之间延迟1 tick，避免卡顿
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        for (PendingHologram record : batch) {
                            debug("加载全息图: " + record.worldName + "@" + record.x + "," + record.y + "," + record.z);
                            if (trySpawnHologram(record)) {
                                loaded[0]++;
                                debug("成功生成全息图: " + record.worldName + "@" + record.x + "," + record.y + "," + record.z);
                            } else {
                                pendingHolograms.add(record);
                                pending[0]++;
                                debug("世界未加载，延迟恢复全息图: " + record.worldName + "@" + record.x + "," + record.y + "," + record.z);
                            }
                        }
                        
                        // 最后一批完成后输出统计
                        if (endIndex >= allRecords.size()) {
                            getLogger().info("全息图加载完成: 成功=" + loaded[0] + ", 待加载=" + pending[0]);
                        }
                    }, (long) (i / BATCH_SIZE) + 1);
                }
                
            } catch (SQLException e) {
                getLogger().severe("加载全息图时出错: " + e.getMessage());
            }
        });
    }
    
    private void saveHologramToDB(Location blockLoc, Material material, String lines, 
                                 double offsetX, double offsetY, double offsetZ) {
        if (connection == null) return;
        Location normalized = normalizeBlockLocation(blockLoc);
        if (normalized == null || normalized.getWorld() == null) return;
        
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO blockholo_holograms (world, x, y, z, material, `lines`, offset_x, offset_y, offset_z) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE material = ?, `lines` = ?, offset_x = ?, offset_y = ?, offset_z = ?")) {
            stmt.setString(1, normalized.getWorld().getName());
            stmt.setInt(2, normalized.getBlockX());
            stmt.setInt(3, normalized.getBlockY());
            stmt.setInt(4, normalized.getBlockZ());
            stmt.setString(5, material.name());
            stmt.setString(6, lines);
            stmt.setDouble(7, offsetX);
            stmt.setDouble(8, offsetY);
            stmt.setDouble(9, offsetZ);
            stmt.setString(10, material.name());
            stmt.setString(11, lines);
            stmt.setDouble(12, offsetX);
            stmt.setDouble(13, offsetY);
            stmt.setDouble(14, offsetZ);
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().warning("保存全息图到数据库时出错: " + e.getMessage());
        }
    }
    
    private void deleteHologramFromDB(Location blockLoc) {
        if (connection == null) return;
        Location normalized = normalizeBlockLocation(blockLoc);
        if (normalized == null || normalized.getWorld() == null) return;
        
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM blockholo_holograms WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            stmt.setString(1, normalized.getWorld().getName());
            stmt.setInt(2, normalized.getBlockX());
            stmt.setInt(3, normalized.getBlockY());
            stmt.setInt(4, normalized.getBlockZ());
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().warning("从数据库删除全息图时出错: " + e.getMessage());
        }
    }

    private boolean hasRecordedHologram(Location blockLoc, Material currentType) {
        Location normalized = normalizeBlockLocation(blockLoc);
        if (normalized == null) return false;
        if (hologramMap.containsKey(normalized)) {
            debug("记录命中缓存: " + normalized);
            return true;
        }
        cleanupStaleRecord(normalized, currentType);
        return false;
    }

    private void cleanupStaleRecord(Location blockLoc, Material currentType) {
        if (connection == null || blockLoc.getWorld() == null) return;
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT material FROM blockholo_holograms WHERE world = ? AND x = ? AND y = ? AND z = ? LIMIT 1")) {
            stmt.setString(1, blockLoc.getWorld().getName());
            stmt.setInt(2, blockLoc.getBlockX());
            stmt.setInt(3, blockLoc.getBlockY());
            stmt.setInt(4, blockLoc.getBlockZ());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return;
                String storedMaterial = rs.getString("material");
                debug("发现无全息记录但数据库仍存在数据，将自动清理: " + blockLoc +
                        " stored=" + storedMaterial + " current=" + currentType);
                deleteHologramFromDB(blockLoc);
            }
        } catch (SQLException e) {
            if (debugMode) getLogger().warning("清理残留全息记录时出错: " + e.getMessage());
        }
    }
    
    private boolean isBlockConfigured(Material material) {
        return config.getConfigurationSection("blocks." + material.name()) != null;
    }
    
    private ConfigurationSection getBlockConfig(Material material) {
        return config.getConfigurationSection("blocks." + material.name());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "BlockHolo 插件命令:");
            sender.sendMessage(ChatColor.YELLOW + "/blockholo toggle - 切换全息显示状态");
            sender.sendMessage(ChatColor.YELLOW + "/blockholo reload - 重载插件配置");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("blockholo.reload")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此命令!");
                return true;
            }
            reloadConfig();
            config = getConfig();
            debugMode = config.getBoolean("debug", false);
            closeDatabase();
            setupDatabase();
            clearAllHolograms();
            loadExistingHolograms();
            sender.sendMessage(ChatColor.GREEN + "配置已重载!");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家可以使用此命令!");
            return true;
        }

        Player player = (Player) sender;
        if (args[0].equalsIgnoreCase("toggle")) {
            boolean currentStatus = getPlayerToggleStatus(player.getUniqueId());
            boolean newStatus = !currentStatus;
            setPlayerToggleStatus(player.getUniqueId(), newStatus);
            String messageKey = newStatus ? "toggle-messages.enable" : "toggle-messages.disable";
            String rawMessage = config.getString(messageKey, "&a默认消息");
            String formattedMessage = ChatColor.translateAlternateColorCodes('&', rawMessage)
                    .replace("%player%", player.getName())
                    .replace("%state%", newStatus ? "启用" : "禁用");
            player.sendMessage(formattedMessage);
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("toggle");
            if (sender.hasPermission("blockholo.reload")) completions.add("reload");
            return completions.stream()
                    .filter(cmd -> cmd.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Material placedType = event.getBlockPlaced().getType();
        if (!isBlockConfigured(placedType)) return;
        ConfigurationSection blockConfig = getBlockConfig(placedType);
        if (blockConfig == null || !blockConfig.getBoolean("enable-hologram", true)) return;
        
        Player player = event.getPlayer();
        if (!getPlayerToggleStatus(player.getUniqueId())) {
            debug("玩家 " + player.getName() + " toggle 关闭，不会记录全息: " + event.getBlockPlaced().getLocation());
            return;
        }

        Location blockLoc = normalizeBlockLocation(event.getBlockPlaced().getLocation());
        if (blockLoc == null) return;
        createHologram(blockLoc, placedType);
        debug("已为 " + player.getName() + " 放置的方块创建全息: " + blockLoc);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        Material blockType = block.getType();
        if (!isBlockConfigured(blockType)) return;
        ConfigurationSection blockConfig = getBlockConfig(blockType);
        if (blockConfig == null) return;
        
        Location blockLoc = normalizeBlockLocation(block.getLocation());
        if (blockLoc == null) return;
        Player player = event.getPlayer();

        boolean recorded = hasRecordedHologram(blockLoc, blockType);
        debug("玩家交互 " + player.getName() + " 方块 " + blockType + " @ " + blockLoc + " recorded=" + recorded + " action=" + event.getAction() + " hologramMapSize=" + hologramMap.size());

        if (!recorded) {
            debug("方块未绑定全息，跳过交互，保持原版行为: " + blockLoc);
            return;
        }
        debug("方块已绑定全息，将处理自定义交互: " + blockLoc);
        
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (!blockConfig.getBoolean("enable-left-click", true)) return;
            if (!player.isSneaking()) {
                event.setCancelled(true);
                String command = blockConfig.getString("left-click-command", "say 默认左键命令 %player%")
                        .replace("%player%", player.getName())
                        .replace("%block%", blockType.name());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                debug("左键触发指令: " + command);
            }
            return;
        }
        
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (!blockConfig.getBoolean("enable-shift-right-click", true)) return;
            if (!player.isSneaking()) return;
            event.setCancelled(true);
            String command = blockConfig.getString("shift-right-click-command", "say 默认Shift+右键指令 %player%")
                    .replace("%player%", player.getName())
                    .replace("%block%", blockType.name());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            debug("Shift+右键触发指令: " + command);
        }
    }
    
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = normalizeBlockLocation(event.getBlock().getLocation());
        if (loc == null) return;

        Material blockType = event.getBlock().getType();
        boolean recorded = hasRecordedHologram(loc, blockType);
        debug("玩家破坏 " + event.getPlayer().getName() + " 方块 " + blockType + " @ " + loc + " recorded=" + recorded);

        if (!recorded) {
            debug("破坏方块未绑定全息，保持原版行为: " + loc);
            return;
        }
        
        if (!isBlockConfigured(blockType)) {
            removeHologram(loc);
            return;
        }

        ConfigurationSection blockConfig = getBlockConfig(blockType);
        if (blockConfig == null) {
            removeHologram(loc);
            return;
        }
        
        Player player = event.getPlayer();
        boolean requireShift = blockConfig.getBoolean("break-require-shift", true);
        
        if (requireShift && !player.isSneaking() && 
            (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
            event.setCancelled(true);
            String message = blockConfig.getString("break-message", "&c请按住Shift键并左键破坏方块！");
            debug("阻止破坏，提示消息: " + message);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            return;
        }
        removeHologram(loc);
        debug("已移除方块及对应全息记录: " + loc);
    }

    private void createHologram(Location blockLoc, Material material) {
        ConfigurationSection blockConfig = getBlockConfig(material);
        if (blockConfig == null) return;
        
        ConfigurationSection hologramConfig = blockConfig.getConfigurationSection("hologram");
        if (hologramConfig == null) return;
        
        List<String> rawLines = hologramConfig.getStringList("lines");
        double offsetX = hologramConfig.getDouble("offset-x", 0.5);
        double offsetY = hologramConfig.getDouble("offset-y", 1.2);
        double offsetZ = hologramConfig.getDouble("offset-z", 0.5);
        boolean backgroundEnabled = hologramConfig.getBoolean("background-enabled", false);
        int backgroundArgb = hologramConfig.getInt("background-argb", 0x4C000000);
        
        Location normalized = normalizeBlockLocation(blockLoc);
        if (normalized == null) return;
        
        // 移除旧的全息图
        Hologram oldHologram = hologramMap.remove(normalized);
        if (oldHologram != null) {
            hologramHelper.removeHologram(oldHologram);
        }
        
        // 使用 FancyHolograms 创建
        Hologram hologram = hologramHelper.createHologram(normalized, material, rawLines, 
                offsetX, offsetY, offsetZ, backgroundEnabled, backgroundArgb);
        hologramMap.put(normalized, hologram);
        
        // 保存到数据库
        String serializedLines = String.join("\n", rawLines);
        saveHologramToDB(normalized, material, serializedLines, offsetX, offsetY, offsetZ);
        
        debug("创建全息图: " + normalized + " 类型: " + material);
    }
    
    private void createHologramFromDatabase(Location blockLoc, Material material, String lines,
                                            double offsetX, double offsetY, double offsetZ,
                                            ConfigurationSection hologramConfig) {
        debug("从数据库创建全息图 - 原始数据: " + (lines != null ? lines.replace("\n", "|") : "null"));
        
        List<String> rawLines = (lines == null || lines.isEmpty())
                ? Collections.singletonList(" ")
                : Arrays.asList(lines.split("\n", -1));
        
        Location normalized = normalizeBlockLocation(blockLoc);
        if (normalized == null) return;
        
        // 读取背景颜色配置
        boolean backgroundEnabled = hologramConfig != null ? hologramConfig.getBoolean("background-enabled", false) : false;
        int backgroundArgb = hologramConfig != null ? hologramConfig.getInt("background-argb", 0x4C000000) : 0x4C000000;
        
        // 使用 FancyHolograms 创建
        Hologram hologram = hologramHelper.createHologram(normalized, material, rawLines, 
                offsetX, offsetY, offsetZ, backgroundEnabled, backgroundArgb);
        hologramMap.put(normalized, hologram);
        
        debug("从数据库创建全息图完成: " + normalized);
    }


    private void removeHologram(Location blockLoc) {
        Location normalized = normalizeBlockLocation(blockLoc);
        if (normalized == null) return;
        
        Hologram hologram = hologramMap.remove(normalized);
        if (hologram != null) {
            hologramHelper.removeHologram(hologram);
        }
        
        deleteHologramFromDB(normalized);
        debug("移除全息图: " + normalized);
    }

    private void clearAllHolograms() {
        List<Location> locations = new ArrayList<>(hologramMap.keySet());
        for (Location location : locations) {
            Hologram hologram = hologramMap.remove(location);
            if (hologram != null) {
                hologramHelper.removeHologram(hologram);
            }
        }
        hologramMap.clear();
        pendingHolograms.clear();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (pendingHolograms.isEmpty()) return;

        Iterator<PendingHologram> iterator = pendingHolograms.iterator();
        while (iterator.hasNext()) {
            PendingHologram pending = iterator.next();
            if (!pending.worldName.equals(event.getWorld().getName())) {
                continue;
            }

            if (trySpawnHologram(pending)) {
                iterator.remove();
            }
        }
    }

    private boolean trySpawnHologram(PendingHologram record) {
        World world = Bukkit.getWorld(record.worldName);
        if (world == null) {
            debug("世界未加载: " + record.worldName);
            return false;
        }

        Location blockLoc = new Location(world, record.x, record.y, record.z);
        
        // 检查区块是否已加载
        if (!world.isChunkLoaded(record.x >> 4, record.z >> 4)) {
            debug("区块未加载: " + record.worldName + " chunk(" + (record.x >> 4) + "," + (record.z >> 4) + ")");
            // 尝试加载区块
            world.loadChunk(record.x >> 4, record.z >> 4);
            debug("已加载区块: chunk(" + (record.x >> 4) + "," + (record.z >> 4) + ")");
        }

        Material material;
        try {
            material = Material.valueOf(record.materialName);
        } catch (IllegalArgumentException ex) {
            debug("无效的材质类型，删除记录: " + record.materialName);
            deleteHologramFromDB(blockLoc);
            return true;
        }

        Material currentType = blockLoc.getBlock().getType();
        if (currentType != material) {
            debug("方块类型不匹配，删除记录: 期望=" + material + ", 实际=" + currentType);
            deleteHologramFromDB(blockLoc);
            return true;
        }

        if (!isBlockConfigured(material)) {
            debug("方块未在配置中，删除记录: " + material);
            deleteHologramFromDB(blockLoc);
            return true;
        }

        ConfigurationSection blockConfig = getBlockConfig(material);
        ConfigurationSection hologramConfig = blockConfig != null ? blockConfig.getConfigurationSection("hologram") : null;

        double offsetX = hologramConfig != null ? hologramConfig.getDouble("offset-x", record.offsetX) : record.offsetX;
        double offsetY = hologramConfig != null ? hologramConfig.getDouble("offset-y", record.offsetY) : record.offsetY;
        double offsetZ = hologramConfig != null ? hologramConfig.getDouble("offset-z", record.offsetZ) : record.offsetZ;

        debug("尝试创建全息图: " + blockLoc + " 偏移=(" + offsetX + "," + offsetY + "," + offsetZ + ")");
        createHologramFromDatabase(blockLoc, material, record.lines, offsetX, offsetY, offsetZ, hologramConfig);

        if (offsetX != record.offsetX || offsetY != record.offsetY || offsetZ != record.offsetZ) {
            saveHologramToDB(blockLoc, material, record.lines, offsetX, offsetY, offsetZ);
        }

        return true;
    }

    private static final class PendingHologram {
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;
        private final String materialName;
        private final String lines;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;

        private PendingHologram(String worldName, int x, int y, int z,
                                String materialName, String lines,
                                double offsetX, double offsetY, double offsetZ) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.materialName = materialName;
            this.lines = lines;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }
    }
}
