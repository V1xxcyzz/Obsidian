package net.vulkanmod.config.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.option.SwitchOption;
import net.vulkanmod.vulkan.util.ColorUtil;

public class SwitchOptionWidget extends OptionWidget<SwitchOption> {

    public SwitchOptionWidget(SwitchOption option, Component name) {
        super(option, name);
        updateDisplayedValue();
    }

    @Override
    protected void renderControls(double mouseX, double mouseY) {
        int center = controlX + controlWidth / 2;
        int halfWidth = 12;
        int x0 = center - halfWidth;
        int y0 = y + 4;
        int height = this.height - 8;
        int color;

        int w1 = halfWidth - 4;
        int h1 = height - 4;
        if (this.option.getNewValue()) {
            int x1 = x0 + halfWidth + 2;

            color = ColorUtil.ARGB.multiplyAlpha(net.vulkanmod.config.gui.util.VGuiConstants.COLOR_ACCENT, this.active ? 0.65f : 0.3f);
            GuiRenderer.fillBox(x0 + 2, y0 + 2, x1 - (x0 + 2) - 1, h1, color);

            color = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
            GuiRenderer.fillBox(x1, y0 + 2, w1, h1, color);
        } else {
            color = ColorUtil.ARGB.pack(1.0f, 1.0f, 1.0f, 0.35f);
            GuiRenderer.fillBox(x0 + 2, y0 + 2, w1, h1, color);
        }

        color = this.option.getNewValue() && this.active
                ? net.vulkanmod.config.gui.util.VGuiConstants.COLOR_ACCENT
                : ColorUtil.ARGB.pack(0.55f, 0.55f, 0.60f, 0.9f);
        GuiRenderer.renderBoxBorder(x0, y0, halfWidth * 2, height, 1, color);

        color = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
        Font textRenderer = Minecraft.getInstance().font;
        int margin = Math.max(
                textRenderer.width(Component.translatable("options.on").getString()) / 3,
                textRenderer.width(Component.translatable("options.off").getString()) / 3
        );

        int x = this.controlX + this.controlWidth / 2 - (int) (halfWidth * 1.5f) - 4 - margin;
        int y = this.y + (this.height - 8) / 2;
        GuiRenderer.drawCenteredString(textRenderer, this.getDisplayedValue(), x, y, color);
    }

    public void onClick(double mouseX, double mouseY) {
        this.option.setNewValue(!this.option.getNewValue());
        updateDisplayedValue();
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {

    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {

    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return this.isMouseOver(mouseX, mouseY);
    }

    protected void updateDisplayedValue() {
        this.displayedValue = option.getNewValue()
                ? Component.translatable("options.on")
                : Component.translatable("options.off");
    }

}
