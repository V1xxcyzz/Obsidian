package net.vulkanmod.config.gui;

import com.google.common.collect.Lists;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.util.SearchHelper;
import net.vulkanmod.config.gui.util.VGuiConstants;
import net.vulkanmod.config.gui.widget.*;
import net.vulkanmod.config.option.*;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.util.ColorUtil;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

public class VOptionScreen extends Screen {
    public final static int MARGIN = 10;

    private final Screen parent;

    private final List<ModSettingsEntry> modSettingsEntries;

    private final List<OptionPage> optionPages;
    private OptionPage searchResultsPage;

    private int currentListIdx = 0;
    private boolean isSearchActive = false;

    private int tooltipX;
    private int tooltipY;
    private int tooltipWidth;

    private VButtonWidget applyButton;
    private VButtonWidget undoButton;

    private VTextInputWidget searchField;

    private final List<VAbstractWidget> iconWidgets = Lists.newArrayList();
    private final List<VButtonWidget> pageButtons = Lists.newArrayList();
    private final List<VButtonWidget> buttons = Lists.newArrayList();


    public VOptionScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;

        this.optionPages = new ArrayList<>();
        this.modSettingsEntries = new ArrayList<>(ModSettingsRegistry.INSTANCE.getModEntries());
    }

    @Override
    protected void init() {
        this.initOptionsPages();

        if (this.optionPages.isEmpty()) {
            throw new IllegalStateException("Default Options weren't added!");
        }

        int top = 32;
        int bottom = 36;
        int itemHeight = 20;

        int leftMargin = MARGIN;
        int listWidth = Math.min(Math.max(240, (int) (this.width * 0.56f)), 380);
        if (leftMargin + listWidth + MARGIN > this.width) {
            listWidth = this.width - leftMargin - MARGIN;
        }
        int listHeight = this.height - top - bottom;

        this.buildLists(leftMargin, top, listWidth, listHeight, itemHeight);

        this.searchField = createSearchField();

        int x = leftMargin + listWidth + 10;
        int tooltipWidth = Math.min(this.width - x - MARGIN, 280);
        int y = top + 4;

        if (tooltipWidth < 140) {
            x = leftMargin + 4;
            tooltipWidth = listWidth - 8;
            y = this.height - bottom - 48;
        }

        this.tooltipX = x;
        this.tooltipY = y;
        this.tooltipWidth = tooltipWidth;

        this.buildPage();

        this.applyButton.active = false;
        this.undoButton.visible = false;
    }

    private void initOptionsPages() {
        this.optionPages.clear();

        for (var modPageSet : this.modSettingsEntries) {
            modPageSet.initPages();

            this.optionPages.addAll(modPageSet.getPages());
        }
    }

    private VTextInputWidget createSearchField() {
        int rightMargin = MARGIN;
        int tabsEndX = MARGIN;
        for (OptionPage page : this.optionPages) {
            tabsEndX += Math.max(52, this.font.width(page.name) + 18) + 2;
        }

        int availableWidth = this.width - tabsEndX - rightMargin - 6;
        int searchWidth = Math.min(Math.max(100, availableWidth), 170);
        int searchX = Math.max(tabsEndX + 4, this.width - searchWidth - rightMargin);
        searchWidth = Math.max(60, Math.min(searchWidth, this.width - rightMargin - searchX));

        return new VTextInputWidget(
                searchX, 6,
                searchWidth, VGuiConstants.WIDGET_HEIGHT,
                Component.translatable("obsidian.options.searchFieldPlaceholder"),
                widget -> performSearch(widget.getInput())
        );
    }

    private void buildLists(int left, int top, int listWidth, int listHeight, int itemHeight) {
        for (OptionPage page : this.optionPages) {
            page.createList(left, top, listWidth, listHeight, itemHeight);
            page.updateOptionStates();
        }
    }

    private void performSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            isSearchActive = false;
            this.currentListIdx = 0;
            buildPage();
            return;
        }

        String searchTerm = query.toLowerCase().trim();
        List<OptionBlock> searchResults = new ArrayList<>();

        for (OptionPage page : this.optionPages) {
            List<Option<?>> matchingOptions = new ArrayList<>();

            for (OptionBlock block : page.optionBlocks) {
                for (Option<?> option : block.options()) {
                    boolean matches = false;

                    String optionName = option.getName().getString().toLowerCase();
                    String optionTooltip = option.getTooltip() != null ?
                            option.getTooltip().getString().toLowerCase() : "";
                    String displayedValue = option.getDisplayedValue().getString().toLowerCase();

                    if (optionName.contains(searchTerm) ||
                            optionTooltip.contains(searchTerm) ||
                            displayedValue.contains(searchTerm)) {
                        matches = true;
                    }

                    else if (option instanceof CyclingOption<?> cycling) {
                        if (SearchHelper.matchesAnyValue(cycling, searchTerm)) {
                            matches = true;
                        }
                    }

                    if (matches) {
                        matchingOptions.add(option);
                    }
                }
            }

            if (!matchingOptions.isEmpty()) {
                searchResults.add(new OptionBlock("§l" + page.name,
                        matchingOptions.toArray(new Option<?>[0])));
                searchResults.add(new OptionBlock("", new Option<?>[0]));
            }
        }

        searchResultsPage = new OptionPage(
                "Search Results",
                searchResults.toArray(new OptionBlock[0])
        );

        int top = 32;
        int bottom = 36;
        int itemHeight = 20;
        int leftMargin = MARGIN;
        int listWidth = Math.min(Math.max(240, (int) (this.width * 0.56f)), 380);
        if (leftMargin + listWidth + MARGIN > this.width) {
            listWidth = this.width - leftMargin - MARGIN;
        }
        int listHeight = this.height - top - bottom;

        searchResultsPage.createList(leftMargin, top, listWidth, listHeight, itemHeight);

        isSearchActive = true;
        buildPage();
    }

    private void buildPage() {
        this.buttons.clear();
        this.pageButtons.clear();
        this.iconWidgets.clear();

        String savedInput = this.searchField != null ? this.searchField.getInput() : "";
        boolean savedFocused = this.searchField != null && this.searchField.focused;
        boolean savedSelected = this.searchField != null && this.searchField.selected;

        this.clearWidgets();

        // Sodium-style horizontal top tab bar
        int tabX = MARGIN;
        int tabY = 6;
        int j = 0;
        for (var modEntry : this.modSettingsEntries) {
            var pages = modEntry.getPages();
            for (OptionPage page : pages) {
                final int finalIdx = j;
                int tabWidth = Math.max(52, this.font.width(page.name) + 18);
                VButtonWidget widget = new VButtonWidget(tabX, tabY, tabWidth, VGuiConstants.WIDGET_HEIGHT,
                        Component.nullToEmpty(page.name), button -> this.setOptionList(finalIdx));
                widget.setTabStyle(true);
                widget.setTextLayout(true, 0);
                this.buttons.add(widget);
                this.pageButtons.add(widget);
                this.addWidget(widget);

                tabX += tabWidth + 2;
                j++;
            }
        }

        if (!isSearchActive) {
            this.pageButtons.get(this.currentListIdx).setSelected(true);
            VOptionList currentList = this.optionPages.get(this.currentListIdx).getOptionList();
            this.addWidget(currentList);
        } else {
            if (searchResultsPage != null) {
                VOptionList searchList = searchResultsPage.getOptionList();
                this.addWidget(searchList);
                searchResultsPage.updateOptionStates();
            }
        }

        this.addButtonsWithSearchBar();

        this.searchField.setInput(savedInput);
        if (savedFocused) {
            this.setFocused(this.searchField);
            this.searchField.setFocused(true);
            this.searchField.setSelected(savedSelected);
        }
        this.updateState();
    }

    @SuppressWarnings("DuplicatedCode")
    private void addButtonsWithSearchBar() {
        int rightMargin = MARGIN;
        int padding = 10;
        int buttonWidth = Minecraft.getInstance().font.width(CommonComponents.GUI_DONE) + 2 * padding;
        int x0 = (this.width - buttonWidth - rightMargin);
        int y0 = this.height - VGuiConstants.WIDGET_HEIGHT - 8;

        VButtonWidget doneButton = new VButtonWidget(x0, y0, buttonWidth, VGuiConstants.WIDGET_HEIGHT,
                CommonComponents.GUI_DONE, button -> Minecraft.getInstance().setScreenAndShow(this.parent));

        buttonWidth = Minecraft.getInstance().font.width(Component.translatable("obsidian.options.buttons.apply")) + 2 * padding;
        x0 -= (buttonWidth + VGuiConstants.WIDGET_MARGIN);
        this.applyButton = new VButtonWidget(x0, y0, buttonWidth, VGuiConstants.WIDGET_HEIGHT,
                Component.translatable("obsidian.options.buttons.apply"), button -> this.applyOptions());

        buttonWidth = Minecraft.getInstance().font.width(Component.translatable("obsidian.options.buttons.undo")) + 2 * padding;
        x0 -= (buttonWidth + VGuiConstants.WIDGET_MARGIN);
        this.undoButton = new VButtonWidget(x0, y0, buttonWidth, VGuiConstants.WIDGET_HEIGHT,
                Component.translatable("obsidian.options.buttons.undo"), button -> undo());

        this.buttons.add(this.applyButton);
        this.buttons.add(doneButton);
        this.buttons.add(this.undoButton);

        this.addWidget(this.applyButton);
        this.addWidget(doneButton);
        this.addWidget(this.undoButton);
        this.addWidget(this.searchField);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
        for (GuiEventListener element : List.copyOf(this.children())) {
            if (element.mouseClicked(event, bl)) {
                if (this.searchField != null && element != this.searchField) {
                    this.searchField.setFocused(false);
                }
                this.setFocused(this.children().contains(element) ? element : null);
                if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                    this.setDragging(true);
                }

                this.updateState();
                return true;
            }
        }

        if (this.searchField != null) {
            this.searchField.setFocused(false);
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.setDragging(false);
        GuiEventListener focused = this.getFocused();
        boolean handled;
        if (focused != null) {
            handled = focused.mouseReleased(event);
        } else {
            handled = this.getChildAt(event.x(), event.y())
                          .filter(guiEventListener -> guiEventListener.mouseReleased(event))
                          .isPresent();
        }
        this.updateState();
        return handled;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        GuiRenderer.guiGraphics = guiGraphics;
        VRenderSystem.enableBlend();

        VOptionList currentList;
        if (isSearchActive && searchResultsPage != null) {
            currentList = searchResultsPage.getOptionList();
        } else {
            currentList = this.optionPages.get(this.currentListIdx).getOptionList();
        }

        currentList.updateState(mouseX, mouseY);
        currentList.renderWidget(mouseX, mouseY);

        for (var widget : iconWidgets) {
            widget.render(mouseX, mouseY);
        }

        for (VButtonWidget button : buttons) {
            button.updateState(mouseX, mouseY);
            button.render(mouseX, mouseY);
        }
        searchField.updateState(mouseX, mouseY);
        searchField.render(mouseX, mouseY);

        VAbstractWidget hoveredWidget = null;

        for (var b : buttons) {
            if (b.isMouseOver(mouseX, mouseY)) {
                hoveredWidget = b;
                break;
            }
        }

        if (hoveredWidget == null) {
            hoveredWidget = currentList.getHoveredWidget(mouseX, mouseY);
        }

        if (hoveredWidget != null) {
            this.renderTooltip(hoveredWidget, this.tooltipX, this.tooltipY);
        }
    }

    private void renderTooltip(VAbstractWidget widget, int x, int y) {
        var list = this.getWidgetTooltip(widget);

        if (list.isEmpty()) {
            return;
        }

        int lines = list.size();

        int padding = 6;
        int width = GuiRenderer.getMaxTextWidth(this.font, list);
        int height = lines * 11;

        GuiRenderer.fill(x - padding, y - padding, x + width + padding, y + height + padding, VGuiConstants.COLOR_TOOLTIP_BG);
        GuiRenderer.renderBorder(x - padding, y - padding, x + width + padding, y + height + padding, 1, VGuiConstants.COLOR_BORDER_SUBTLE);
        GuiRenderer.fill(x - padding, y - padding, x - padding + 2, y + height + padding, VGuiConstants.COLOR_ACCENT);

        int yOffset = 1;
        for (var text : list) {
            GuiRenderer.drawString(this.font, text, x + 1, y + yOffset, 0xFFEAEAF0);
            yOffset += 11;
        }
    }

    private List<FormattedCharSequence> getWidgetTooltip(VAbstractWidget widget) {
        var tooltip = widget.getTooltip();
        var impact = widget.getImpact();

        List<FormattedCharSequence> textList = new ArrayList<>();
        if (tooltip != null) {
            textList.addAll(this.font.split(tooltip, this.tooltipWidth));
        }

        if (impact != null) {
            textList.addAll(this.font.split(Component.translatable("Performance Impact: %s", impact.component()), this.tooltipWidth));
        }

        return textList;
    }

    private void updateState() {
        if (this.applyButton == null | this.undoButton == null) return;
        boolean modified = false;
        for (var page : this.optionPages) {
            modified |= page.optionChanged();
        }

        if (modified) {
            for (var page : this.optionPages) {
                page.optionChanged();
            }
        }

        this.applyButton.active = modified;
        this.undoButton.visible = modified;
    }

    private void setOptionList(int i) {
        this.currentListIdx = i;
        this.isSearchActive = false;

        this.searchField.setInput("");
        this.searchField.setFocused(false);

        this.buildPage();

        this.pageButtons.get(i).setSelected(true);
    }

    private void undo() {
        for (OptionPage page : this.optionPages) {
            page.resetToOriginalState();
            page.updateOptionStates();
        }

        buildPage();
    }

    private void applyOptions() {
        List<OptionPage> pages = List.copyOf(this.optionPages);
        for (var page : pages) {
            page.applyOptionChanges();
            page.updateOptionStates();
        }

        for (var modEntry : this.modSettingsEntries) {
            modEntry.runOnApply();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (keyEvent.hasControlDown() && keyEvent.key() == InputConstants.KEY_L) {
            this.setFocused(searchField);
            searchField.setFocused(true);
            searchField.setSelected(true);

            return true;
        }

        if (keyEvent.key() == InputConstants.KEY_ESCAPE && this.isSearchActive) {
            this.isSearchActive = false;
            this.searchField.setInput("");
            this.searchField.setFocused(false);
            this.buildPage();
            this.pageButtons.get(this.currentListIdx).setSelected(true);
            return true;
        }


        if (!this.searchField.focused
                && keyEvent.key() == InputConstants.KEY_P
                && keyEvent.hasShiftDown()) {
            Minecraft.getInstance().setScreenAndShow(new VideoSettingsScreen(this, Minecraft.getInstance(), Minecraft.getInstance().options));

            return false;
        }

        return super.keyPressed(keyEvent);
    }
}