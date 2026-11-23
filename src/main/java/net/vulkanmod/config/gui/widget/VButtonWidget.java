package net.vulkanmod.config.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.vulkanmod.config.gui.util.VGuiConstants;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class VButtonWidget extends VAbstractWidget {
    boolean selected = false;
    boolean tabStyle = false;
    Consumer<VButtonWidget> onPress;

    float alpha = 1.0f;

    public VButtonWidget(int x, int y, int width, int height, Component message, Consumer<VButtonWidget> onPress) {
        this.setPosition(x, y, width, height);

        this.message = message;
        this.onPress = onPress;
    }

    public void setTabStyle(boolean tabStyle) {
        this.tabStyle = tabStyle;
    }

    public void renderWidget(double mouseX, double mouseY) {
        if (!this.isVisible()) return;

        int backgroundColor = this.isActive()
                ? VGuiConstants.COLOR_PANEL_BG
                : VGuiConstants.COLOR_PANEL_BG_DISABLED;
        int textColor = this.isActive()
                ? VGuiConstants.COLOR_WHITE
                : VGuiConstants.COLOR_GRAY;
        int selectionOutlineColor = VGuiConstants.COLOR_ACCENT;
        int selectionFillColor = ColorUtil.ARGB.multiplyAlpha(VGuiConstants.COLOR_ACCENT, 0.18f);

        GuiRenderer.fill(this.x, this.y, this.x + this.width, this.y + this.height, backgroundColor);

        if (this.selected) {
            GuiRenderer.fill(this.x, this.y, this.x + this.width, this.y + this.height, selectionFillColor);
            if (this.tabStyle) {
                GuiRenderer.fill(this.x, this.y + this.height - 2, this.x + this.width, this.y + this.height, selectionOutlineColor);
            } else {
                GuiRenderer.fill(this.x, this.y, this.x + 2, this.y + this.height, selectionOutlineColor);
            }
        } else {
            GuiRenderer.renderBorder(this.x, this.y, this.x + this.width, this.y + this.height, 1, VGuiConstants.COLOR_BORDER_SUBTLE);
        }

        this.renderHovering(0, 0);

        // text is down here because of layering
        if (this.centeredText) {
            GuiRenderer.drawCenteredString(
                    Minecraft.getInstance().font,
                    this.message,
                    this.x + this.width / 2, (this.y + this.height / 2) - 4,
                    textColor | (Mth.ceil(this.alpha * 255.0f) << 24));
        }
        else {
            GuiRenderer.drawString(Minecraft.getInstance().font,
                                   this.message,
                                   this.x + this.margin, (this.y + this.height / 2) - 4,
                                   textColor | (Mth.ceil(this.alpha * 255.0f) << 24));
        }

    }

    public void onClick(double mX, double mY) {
        this.onPress.accept(this);
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
        if (!this.active || !this.visible)
            return null;
        return super.nextFocusPath(event);
    }

}
