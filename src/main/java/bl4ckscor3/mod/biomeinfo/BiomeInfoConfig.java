package bl4ckscor3.mod.biomeinfo;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = "biomeinfo")
public class BiomeInfoConfig implements ConfigData {
    public boolean enabled = true;
    public int posX = 10;
    public int posY = 10;
    public double scale = 1.0;
    public boolean textShadow = true;
    public int color = 0xFFFFFF;
    public boolean fadeIn = true;
    public boolean fadeOut = true;
    public int displayTime = 40;
    public boolean hideWithUI = true;
    public boolean hideOnDebugScreen = true;
    public boolean fallbackOnUntranslatableName = true;
    public boolean appendModName = true;
    
    @ConfigEntry.Gui.CollapsibleObject
    public PositionPreset positionPreset = PositionPreset.TOP_LEFT;

    // --- 新增功能：基础配置 ---
    
    @ConfigEntry.Gui.Tooltip(count = 2) // 鼠标悬停提示
    public int delayTicks = 10; // 防抖延迟：在群系停留多久才显示 (Ticks)

    @ConfigEntry.Gui.Tooltip(count = 2)
    public int historySize = 3; // 记忆功能：记住最近多少个群系不再重复显示

    // --- 新增功能：维度显示配置 ---

    @ConfigEntry.Gui.Tooltip
    public boolean showDimension = true; // 是否显示维度名称

    @ConfigEntry.Gui.Tooltip(count = 3)
    public boolean dimensionShowOnWorldChangeOnly = false; // true=只在刚切换维度时显示一次，false=每次换群系都显示

    public int dimensionDelayTicks = 20; // 维度文字延迟显示时间 (相对于主标题弹出后)

    public boolean dimensionBelow = true; // true=显示在下方，false=显示在上方
    public double dimensionScale = 0.8;   // 维度文字缩放倍率
    public int dimensionYOffset = 2;      // 与主标题的垂直间距
    public int dimensionColor = 0xFFFFFF; // 维度文字颜色
    
    // 兼容旧代码，如果没有 TextAlignment 枚举的配置，可以忽略，因为由 PositionPreset 控制
    public TextAlignment textAlignment = TextAlignment.LEFT; 
}
