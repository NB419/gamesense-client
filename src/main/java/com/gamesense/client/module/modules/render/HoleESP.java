package com.gamesense.client.module.modules.render;

import com.gamesense.api.event.events.RenderEvent;
import com.gamesense.api.setting.Setting;
import com.gamesense.api.util.misc.Pair;
import com.gamesense.api.util.player.PlayerUtil;
import com.gamesense.api.util.render.GSColor;
import com.gamesense.api.util.render.RenderUtil;
import com.gamesense.api.util.world.EntityUtil;
import com.gamesense.api.util.world.GeometryMasks;
import com.gamesense.api.util.world.HoleUtil;
import com.gamesense.client.module.Module;
import com.google.common.collect.Sets;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author GameSense client for original code (actual author unknown)
 * @source https://github.com/IUDevman/gamesense-client/blob/2.2.5/src/main/java/com/gamesense/client/module/modules/render/HoleESP.java
 * @reworked by 0b00101010 on 14/01/2021
 */
public class HoleESP extends Module {

    public HoleESP() {
        super("HoleESP", Category.Render);
    }

    public static Setting.Integer rangeS;
    Setting.Boolean hideOwn;
    Setting.Boolean flatOwn;
    Setting.Mode customHoles;
    Setting.Mode mode;
    Setting.Mode type;
    Setting.Double slabHeight;
    Setting.Integer width;
    Setting.ColorSetting bedrockColor;
    Setting.ColorSetting obsidianColor;
    Setting.ColorSetting customColor;
    Setting.Integer ufoAlpha;

    public void setup() {
        ArrayList<String> holes = new ArrayList<>();
        holes.add("Single");
        // https://github.com/IUDevman/gamesense-client/issues/57
        holes.add("Double");
        /*
         * This refers to two wide holes with one down block being blast resistant
         * and the other being air or a breakable block
         *
         * This is technically a safe hole as putting in that block or switching it
         * to a blast resistant one makes it a two wide hole
         *
         * CAUTION: standing over the air gap can cause you to be crystallized which
         * is why I gave it a separate mode
         */
        holes.add("Custom");

        ArrayList<String> render = new ArrayList<>();
        render.add("Outline");
        render.add("Fill");
        render.add("Both");

        ArrayList<String> modes = new ArrayList<>();
        modes.add("Air");
        modes.add("Ground");
        modes.add("Flat");
        modes.add("Slab");
        modes.add("Double");

        rangeS = registerInteger("Range", 5, 1, 20);
        customHoles = registerMode("Show", holes, "Single");
        type = registerMode("Render", render, "Both");
        mode = registerMode("Mode", modes, "Air");
        hideOwn = registerBoolean("Hide Own", false);
        flatOwn = registerBoolean("Flat Own", false);
        slabHeight = registerDouble("Slab Height", 0.5, 0.1, 1.5);
        width = registerInteger("Width",1,1,10);
        bedrockColor = registerColor("Bedrock Color", new GSColor(0,255,0));
        obsidianColor = registerColor("Obsidian Color", new GSColor(255,0,0));
        customColor = registerColor("Custom Color", new GSColor(0,0,255));
        ufoAlpha = registerInteger("UFOAlpha",255,0,255);
    }

    private ConcurrentHashMap<AxisAlignedBB, GSColor> holes;

    public void onUpdate() {
        if (mc.player == null || mc.world == null) {
            return;
        }

        if (holes == null) {
            holes = new ConcurrentHashMap<>();
        }
        else {
            holes.clear();
        }

        int range = (int) Math.ceil(rangeS.getValue());

        // hashSets are easier to navigate
        HashSet<BlockPos> possibleFullHoles = Sets.newHashSet();
        HashMap<BlockPos, Pair<HoleUtil.BlockOffset, GSColor>> possibleWideHoles = new HashMap<>();
        List<BlockPos> blockPosList = EntityUtil.getSphere(PlayerUtil.getPlayerPos(), range, range, false, true, 0);

        // find all holes
        for (BlockPos pos : blockPosList) {

            if (!mc.world.getBlockState(pos).getBlock().equals(Blocks.AIR)) {
                continue;
            }
            // if air below, we are wasting our time and hashset space
            // we do not remove check from surround offset as potentially a weak block
            if (mc.world.getBlockState(pos.add(0, -1, 0)).getBlock().equals(Blocks.AIR)) {
                continue;
            }
            if (!mc.world.getBlockState(pos.add(0, 1, 0)).getBlock().equals(Blocks.AIR)) {
                continue;
            }

            if (mc.world.getBlockState(pos.add(0, 2, 0)).getBlock().equals(Blocks.AIR)) {
                possibleFullHoles.add(pos);
            }
        }

        possibleFullHoles.forEach(pos -> {
            GSColor color = new GSColor(bedrockColor.getValue(), 255);

            HashMap<HoleUtil.BlockOffset, HoleUtil.BlockSafety> unsafeSides = HoleUtil.getUnsafeSides(pos);

            if (unsafeSides.containsKey(HoleUtil.BlockOffset.DOWN)) {
                if (unsafeSides.remove(HoleUtil.BlockOffset.DOWN, HoleUtil.BlockSafety.BREAKABLE)) {
                    return;
                }
            }

            int size = unsafeSides.size();

            unsafeSides.entrySet().removeIf(entry -> entry.getValue() == HoleUtil.BlockSafety.RESISTANT);

            // size has changed so must have weak side
            if (unsafeSides.size() != size)
                color = new GSColor(obsidianColor.getValue(), 255);

            size = unsafeSides.size();

            // is it a perfect hole
            if (size == 0) {
                holes.put(new AxisAlignedBB(pos), color);

            }
            // have one open side
            if (size == 1) {
                possibleWideHoles.put(pos, new Pair<>(unsafeSides.keySet().stream().findFirst().get(), color));
            }
        });

        // two wide and/or custom holes is enabled
        // we can guarantee all holes in possibleWideHoles
        // have only one open side
        String customHoleMode = customHoles.getValue();
        if (!customHoleMode.equalsIgnoreCase("Single")) {
            possibleWideHoles.forEach((pos, pair) -> {
                GSColor color = pair.getValue();
                BlockPos unsafePos = pair.getKey().offset(pos);

                // Custom allows hole in floor for second side
                boolean allowCustom = customHoleMode.equalsIgnoreCase("Custom");
                HashMap<HoleUtil.BlockOffset, HoleUtil.BlockSafety> unsafeSides = HoleUtil.getUnsafeSides(unsafePos);

                int size = unsafeSides.size();

                unsafeSides.entrySet().removeIf(entry -> entry.getValue() == HoleUtil.BlockSafety.RESISTANT);

                // size has changed so must have weak side
                if (unsafeSides.size() != size)
                    color = new GSColor(obsidianColor.getValue(), 255);

                if (allowCustom) {
                    if (unsafeSides.containsKey(HoleUtil.BlockOffset.DOWN))
                        color = new GSColor(customColor.getValue(), 255);
                    unsafeSides.remove(HoleUtil.BlockOffset.DOWN);
                }

                // is it a safe hole
                if (unsafeSides.size() >  1)
                    return;

                // it is
                double minX = Math.min(pos.getX(), unsafePos.getX());
                double maxX = Math.max(pos.getX(), unsafePos.getX()) + 1;
                double minZ = Math.min(pos.getZ(), unsafePos.getZ());
                double maxZ = Math.max(pos.getZ(), unsafePos.getZ()) + 1;

                holes.put(new AxisAlignedBB(minX, pos.getY(), minZ, maxX, pos.getY() + 1, maxZ), color);
            });

        }
    }

    public void onWorldRender(RenderEvent event) {
        if (mc.player == null || mc.world == null || holes == null || holes.isEmpty()) {
            return;
        }

        holes.forEach(this::renderHoles);
    }

    private void renderHoles(AxisAlignedBB hole, GSColor color) {
        switch (type.getValue()) {
            case "Outline": {
                renderOutline(hole, color);
                break;
            }
            case "Fill": {
                renderFill(hole, color);
                break;
            }
            case "Both": {
                renderOutline(hole, color);
                renderFill(hole, color);
                break;
            }
        }
    }

    private void renderFill(AxisAlignedBB hole, GSColor color) {
        GSColor fillColor = new GSColor(color, 50);
        int ufoAlpha=(this.ufoAlpha.getValue()*50)/255;

        if (hideOwn.getValue() && hole.intersects(mc.player.getEntityBoundingBox())) return;

        switch (mode.getValue()) {
            case "Air": {
                if (flatOwn.getValue() && hole.intersects(mc.player.getEntityBoundingBox())) {
                    RenderUtil.drawBox(hole, true, 1, fillColor, ufoAlpha, GeometryMasks.Quad.DOWN);
                }
                else {
                    RenderUtil.drawBox(hole, true, 1, fillColor, ufoAlpha, GeometryMasks.Quad.ALL);
                }
                break;
            }
            case "Ground": {
                RenderUtil.drawBox(hole.offset(0, -1, 0), true, 1, new GSColor(fillColor,ufoAlpha), fillColor.getAlpha(), GeometryMasks.Quad.ALL);
                break;
            }
            case "Flat": {
                RenderUtil.drawBox(hole, true, 1, fillColor, ufoAlpha, GeometryMasks.Quad.DOWN);
                break;
            }
            case "Slab": {
                if (flatOwn.getValue() && hole.intersects(mc.player.getEntityBoundingBox())) {
                    RenderUtil.drawBox(hole, true, 1, fillColor, ufoAlpha, GeometryMasks.Quad.DOWN);
                }
                else {
                    RenderUtil.drawBox(hole, false, slabHeight.getValue(), fillColor, ufoAlpha, GeometryMasks.Quad.ALL);
                }
                break;
            }
            case "Double": {
                if (flatOwn.getValue() && hole.intersects(mc.player.getEntityBoundingBox())) {
                    RenderUtil.drawBox(hole, true, 1, fillColor, ufoAlpha, GeometryMasks.Quad.DOWN);
                }
                else {
                    RenderUtil.drawBox(hole.setMaxY(hole.maxY + 1), true, 2, fillColor, ufoAlpha, GeometryMasks.Quad.ALL);
                }
                break;
            }
        }
    }

    private void renderOutline(AxisAlignedBB hole, GSColor color) {
        GSColor outlineColor = new GSColor(color, 255);

        if (hideOwn.getValue() && hole.intersects(mc.player.getEntityBoundingBox())) return;

        switch (mode.getValue()) {
            case "Air": {
                if (flatOwn.getValue() && hole.intersects(mc.player.getEntityBoundingBox())) {
                    RenderUtil.drawBoundingBoxWithSides(hole, width.getValue(), outlineColor, ufoAlpha.getValue(), GeometryMasks.Quad.DOWN);
                }
                else {
                    RenderUtil.drawBoundingBox(hole, width.getValue(), outlineColor, ufoAlpha.getValue());
                }
                break;
            }
            case "Ground": {
                RenderUtil.drawBoundingBox(hole.offset(0, -1, 0), width.getValue(), new GSColor(outlineColor,ufoAlpha.getValue()), outlineColor.getAlpha());
                break;
            }
            case "Flat": {
                RenderUtil.drawBoundingBoxWithSides(hole, width.getValue(), outlineColor, ufoAlpha.getValue(), GeometryMasks.Quad.DOWN);
                break;
            }
            case "Slab": {
                if (this.flatOwn.getValue() && hole.intersects(mc.player.getEntityBoundingBox())) {
                    RenderUtil.drawBoundingBoxWithSides(hole, width.getValue(), outlineColor, ufoAlpha.getValue(), GeometryMasks.Quad.DOWN);
                }
                else {
                    RenderUtil.drawBoundingBox(hole.setMaxY(hole.minY + slabHeight.getValue()), width.getValue(), outlineColor, ufoAlpha.getValue());
                }
                break;
            }
            case "Double": {
                if (this.flatOwn.getValue() && hole.intersects(mc.player.getEntityBoundingBox())) {
                    RenderUtil.drawBoundingBoxWithSides(hole, width.getValue(), outlineColor, ufoAlpha.getValue(), GeometryMasks.Quad.DOWN);
                }
                else {
                    RenderUtil.drawBoundingBox(hole.setMaxY(hole.maxY + 1), width.getValue(), outlineColor, ufoAlpha.getValue());
                }
                break;
            }
        }
    }
}