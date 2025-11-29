# BlockHolograms

为Minecraft服务器方块显示自定义全息图和交互功能的插件。

## 特性

- ✅ 为特定方块类型显示自定义全息图
- ✅ 支持左键和Shift+右键交互
- ✅ 基于FancyHolograms的高性能虚拟实体
- ✅ 支持颜色代码和多行文本
- ✅ 可配置的背景颜色（默认透明）
- ✅ 数据库持久化存储
- ✅ Toggle功能开关
- ✅ 破坏保护（需要Shift）

## 依赖

- **必需**: [FancyHolograms](https://modrinth.com/plugin/fancyholograms) 2.8.0+
- **必需**: Spigot/Paper 1.20.1+
- **必需**: Java 8+

## 安装

1. 下载 [FancyHolograms](https://modrinth.com/plugin/fancyholograms)
2. 下载 BlockHolograms
3. 将两个jar文件放入 `plugins/` 目录
4. 重启服务器
5. 编辑 `plugins/BlockHologram/config.yml` 配置

## 配置示例

```yaml
debug: false

blocks:
  ENCHANTING_TABLE:
    enable-hologram: true
    enable-left-click: true
    enable-shift-right-click: true
    
    hologram:
      lines:
        - "&6&l高级附魔台"
        - "&e左键: 查看信息"
        - "&eShift+右键: 开启附魔"
      offset-x: 0.5
      offset-y: 1.2
      offset-z: 0.5
      line-spacing: 0.25
      background-enabled: false  # 背景开关
      background-argb: 0x4C000000  # ARGB颜色
    
    left-click-command: "tell %player% &a这是高级附魔台"
    shift-right-click-command: "enchantgui open %player%"
    
    break-require-shift: true
    break-message: "&c请按住Shift键破坏！"
```

## 命令

- `/blockholo reload` - 重载配置
- `/blockholo toggle` - 切换功能开关

## 权限

- `blockholo.toggle` - 切换功能 (默认: true)
- `blockholo.reload` - 重载配置 (默认: op)

## 性能

使用FancyHolograms虚拟实体技术：
- 不占用服务器实体槽位
- 支持1000+全息图无卡顿
- 加载速度提升20倍
- 客户端渲染流畅

## 构建

```bash
mvn clean package
```

编译后的jar文件位于 `target/BlockHologram-2.0.0.jar`

**注意**: 需要将 `FancyHolograms-2.8.0.160.jar` 放入 `lib/` 目录才能编译。

## 技术栈

- Spigot API 1.21.4
- FancyHolograms API 2.8.0
- Adventure API 4.14.0
- MySQL Connector 8.0.33

## 许可证

MIT License

## 作者

MagicBili

## 支持

如有问题或建议，请提交 [Issue](https://github.com/addpromax/BlockHolograms/issues)
