package com.magicbili.blockholo;

import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.data.property.Visibility;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * FancyHolograms 集成辅助类
 * 负责创建和管理临时全息图（不持久化到FancyHolograms配置）
 */
public class FancyHologramHelper {
    
    private final HologramManager hologramManager;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();
    
    public FancyHologramHelper(HologramManager hologramManager) {
        this.hologramManager = hologramManager;
    }
    
    /**
     * 创建全息图
     * @param blockLoc 方块位置
     * @param material 方块类型
     * @param rawLines 原始文本行（&格式）
     * @param offsetX X偏移
     * @param offsetY Y偏移
     * @param offsetZ Z偏移
     * @param backgroundEnabled 是否启用背景
     * @param backgroundArgb 背景颜色ARGB值
     * @return 创建的全息图对象
     */
    public Hologram createHologram(Location blockLoc, Material material, List<String> rawLines,
                                   double offsetX, double offsetY, double offsetZ,
                                   boolean backgroundEnabled, int backgroundArgb) {
        // 计算全息图位置
        Location hologramLoc = blockLoc.clone().add(offsetX, offsetY, offsetZ);
        
        // 生成唯一名称
        String hologramName = generateHologramName(blockLoc);
        
        // 创建全息图数据
        TextHologramData data = new TextHologramData(hologramName, hologramLoc);
        data.setPersistent(false); // 重要：不持久化到FancyHolograms配置
        data.setVisibility(Visibility.ALL); // 所有玩家可见
        
        // FancyHolograms 2.8.0 使用字符串列表
        // 转换颜色代码 & -> §
        List<String> coloredLines = new ArrayList<>();
        for (String line : rawLines) {
            String colored = ChatColor.translateAlternateColorCodes('&', line);
            coloredLines.add(colored);
        }
        data.setText(coloredLines);
        
        // 设置样式
        data.setTextShadow(true);
        data.setSeeThrough(true);
        
        // 设置背景颜色
        if (backgroundEnabled) {
            // ARGB格式：0xAARRGGBB
            int alpha = (backgroundArgb >> 24) & 0xFF;
            int red = (backgroundArgb >> 16) & 0xFF;
            int green = (backgroundArgb >> 8) & 0xFF;
            int blue = backgroundArgb & 0xFF;
            data.setBackground(org.bukkit.Color.fromARGB(alpha, red, green, blue));
        } else {
            // 透明背景
            data.setBackground(org.bukkit.Color.fromARGB(0, 0, 0, 0));
        }
        
        // 创建全息图
        Hologram hologram = hologramManager.create(data);
        hologramManager.addHologram(hologram);
        
        return hologram;
    }
    
    /**
     * 删除全息图
     */
    public void removeHologram(Hologram hologram) {
        if (hologram == null) return;
        hologramManager.removeHologram(hologram);
    }
    
    /**
     * 生成全息图唯一名称
     */
    private String generateHologramName(Location loc) {
        return "blockholo_" + loc.getWorld().getName() + "_" + 
               loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }
}
