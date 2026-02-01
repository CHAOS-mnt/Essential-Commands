package com.fibermc.essentialcommands.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fibermc.essentialcommands.ManagerLocator;
import com.fibermc.essentialcommands.teleportation.PlayerTeleporter;
import com.fibermc.essentialcommands.text.ECText;
import com.fibermc.essentialcommands.text.TextFormatType;
import com.fibermc.essentialcommands.types.WarpLocation;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

public class WarpTpCommand implements Command<CommandSourceStack> {

    public WarpTpCommand() {}

    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer senderPlayer = context.getSource().getPlayerOrException();
        String warpName = StringArgumentType.getString(context, "warp_name");
        exec(context, senderPlayer, warpName);

        return SINGLE_SUCCESS;
    }

    public int runDefault(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer senderPlayer = context.getSource().getPlayerOrException();
        UUID playerUUID = senderPlayer.getUUID();
        
        // Check if the player is a Bedrock player using Floodgate API
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            if (floodgateApi.isFloodgatePlayer(playerUUID)) {
                // Player is from Bedrock Edition, show main menu form
                showWarpMenuForm(senderPlayer);
                return SINGLE_SUCCESS;
            }
        } catch (Exception e) {
            // Floodgate API not available or error occurred, treat as Java player
        }
        
        // For Java players, throw error asking for warp name
        var ecText = ECText.access(senderPlayer);
        throw CommandUtil.createSimpleException(ecText.getText(
            "cmd.warp.tp.error.not_found",
            TextFormatType.Error,
            ecText.accent("?")));
    }

    private void exec(
        CommandContext<CommandSourceStack> context,
        ServerPlayer targetPlayer) throws CommandSyntaxException
    {
        String warpName = StringArgumentType.getString(context, "warp_name");
        exec(context, targetPlayer, warpName);
    }

    private void exec(
        CommandContext<CommandSourceStack> context,
        ServerPlayer targetPlayer,
        String warpName) throws CommandSyntaxException
    {
        var worldDataManager = ManagerLocator.getInstance().getWorldDataManager();
        var senderPlayer = context.getSource().getPlayerOrException();
        var ecText = ECText.access(senderPlayer);

        var warpNameText = ecText.accent(warpName);
        WarpLocation loc = worldDataManager.getWarp(warpName);

        if (loc == null) {
            throw CommandUtil.createSimpleException(ecText.getText(
                "cmd.warp.tp.error.not_found",
                TextFormatType.Error,
                warpNameText));
        }

        if (!loc.hasPermission(senderPlayer)) {
            throw CommandUtil.createSimpleException(ecText.getText(
                "cmd.warp.tp.error.permission",
                TextFormatType.Error,
                warpNameText));
        }

        // Teleport & chat message
        PlayerTeleporter.requestTeleport(
            targetPlayer,
            loc,
            ecText.getText("cmd.warp.location_name", warpNameText));
    }

    public int runOther(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        exec(context, EntityArgument.getPlayer(context, "target_player"));
        return 0;
    }

    /**
     * Shows the main warp menu form to Bedrock players.
     */
    private void showWarpMenuForm(ServerPlayer player) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var ecText = ECText.access(player);
            var worldDataManager = ManagerLocator.getInstance().getWorldDataManager();
            List<String> warpNames = worldDataManager.getWarpNames();
            
            // Check permissions
            var commandSource = player.createCommandSourceStack();
            boolean canSet = com.fibermc.essentialcommands.ECPerms.check(
                commandSource, 
                com.fibermc.essentialcommands.ECPerms.Registry.warp_set, 
                4);
            boolean canDelete = com.fibermc.essentialcommands.ECPerms.check(
                commandSource, 
                com.fibermc.essentialcommands.ECPerms.Registry.warp_delete, 
                4);
            
            // Filter warps that the player has permission for
            List<String> accessibleWarps = new ArrayList<>();
            for (String warpName : warpNames) {
                WarpLocation warp = worldDataManager.getWarp(warpName);
                if (warp != null && warp.hasPermission(player)) {
                    accessibleWarps.add(warpName);
                }
            }
            
            boolean hasWarps = !accessibleWarps.isEmpty();
            
            SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(ecText.getString("cmd.warp.form.menu.title"))
                .content(ecText.getString("cmd.warp.form.menu.content"));
            
            // Track button indices
            List<String> buttonActions = new ArrayList<>();
            
            // Add teleport button if there are accessible warps
            if (hasWarps) {
                formBuilder.button(ecText.getString("cmd.warp.form.menu.teleport"));
                buttonActions.add("teleport");
            }
            
            // Add set button if player has permission
            if (canSet) {
                formBuilder.button(ecText.getString("cmd.warp.form.menu.create"));
                buttonActions.add("create");
            }
            
            // Add delete button if player has permission and there are warps
            if (canDelete && hasWarps) {
                formBuilder.button(ecText.getString("cmd.warp.form.menu.delete"));
                buttonActions.add("delete");
            }
            
            // If no buttons were added, show no access message
            if (buttonActions.isEmpty()) {
                formBuilder.content(ecText.getString("cmd.warp.form.menu.no_warps"));
                SimpleForm form = formBuilder.build();
                floodgateApi.sendForm(player.getUUID(), form);
                return;
            }
            
            SimpleForm form = formBuilder
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    if (selectedIndex >= 0 && selectedIndex < buttonActions.size()) {
                        String action = buttonActions.get(selectedIndex);
                        switch (action) {
                            case "teleport":
                                showTeleportWarpForm(player);
                                break;
                            case "create":
                                showCreateWarpForm(player);
                                break;
                            case "delete":
                                showDeleteWarpForm(player);
                                break;
                        }
                    }
                })
                .build();
            
            floodgateApi.sendForm(player.getUUID(), form);
        } catch (Exception e) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Failed to show warp menu form"),
                false);
            e.printStackTrace();
        }
    }
    
    /**
     * Shows form to select and teleport to a warp.
     */
    private void showTeleportWarpForm(ServerPlayer player) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var ecText = ECText.access(player);
            var worldDataManager = ManagerLocator.getInstance().getWorldDataManager();
            List<String> warpNames = worldDataManager.getWarpNames();
            
            // Filter warps that the player has permission for
            List<String> accessibleWarps = new ArrayList<>();
            for (String warpName : warpNames) {
                WarpLocation warp = worldDataManager.getWarp(warpName);
                if (warp != null && warp.hasPermission(player)) {
                    accessibleWarps.add(warpName);
                }
            }
            
            if (accessibleWarps.isEmpty()) {
                player.displayClientMessage(
                    ecText.getText("cmd.warp.form.menu.no_warps", TextFormatType.Error),
                    false);
                return;
            }
            
            SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(ecText.getString("cmd.warp.form.teleport.title"))
                .content(ecText.getString("cmd.warp.form.teleport.content"));
            
            for (String warpName : accessibleWarps) {
                formBuilder.button(warpName);
            }
            
            SimpleForm form = formBuilder
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    if (selectedIndex >= 0 && selectedIndex < accessibleWarps.size()) {
                        String selectedWarp = accessibleWarps.get(selectedIndex);
                        try {
                            var worldDataManager_inner = ManagerLocator.getInstance().getWorldDataManager();
                            WarpLocation loc = worldDataManager_inner.getWarp(selectedWarp);
                            
                            if (loc != null && loc.hasPermission(player)) {
                                var warpNameText = ecText.accent(selectedWarp);
                                PlayerTeleporter.requestTeleport(
                                    player,
                                    loc,
                                    ecText.getText("cmd.warp.location_name", warpNameText));
                            } else {
                                player.displayClientMessage(
                                    ecText.getText(
                                        "cmd.warp.tp.error.not_found",
                                        TextFormatType.Error,
                                        ecText.accent(selectedWarp)),
                                    false);
                            }
                        } catch (Exception e) {
                            player.displayClientMessage(
                                ecText.getText("cmd.warp.form.error", TextFormatType.Error),
                                false);
                            e.printStackTrace();
                        }
                    }
                })
                .build();
            
            floodgateApi.sendForm(player.getUUID(), form);
        } catch (Exception e) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Failed to show teleport warp form"),
                false);
            e.printStackTrace();
        }
    }
    
    /**
     * Shows form to create a new warp.
     */
    private void showCreateWarpForm(ServerPlayer player) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var ecText = ECText.access(player);
            
            org.geysermc.cumulus.form.CustomForm form = org.geysermc.cumulus.form.CustomForm.builder()
                .title(ecText.getString("cmd.warp.form.create.title"))
                .input(ecText.getString("cmd.warp.form.create.input_label"), 
                       ecText.getString("cmd.warp.form.create.input_placeholder"))
                .toggle(ecText.getString("cmd.warp.form.create.require_permission"), false)
                .validResultHandler(response -> {
                    String warpName = response.asInput(0);
                    boolean requiresPermission = response.asToggle(1);
                    
                    if (warpName == null || warpName.trim().isEmpty()) {
                        player.displayClientMessage(
                            ecText.getText("cmd.warp.form.create.error.empty_name", TextFormatType.Error),
                            false);
                        return;
                    }
                    
                    try {
                        var worldDataManager = ManagerLocator.getInstance().getWorldDataManager();
                        var currentLoc = new com.fibermc.essentialcommands.types.MinecraftLocation(player);
                        worldDataManager.setWarp(warpName.trim(), currentLoc, requiresPermission);
                        
                        player.displayClientMessage(
                            ecText.getText("cmd.warp.set.feedback", 
                                net.minecraft.network.chat.Component.literal(warpName.trim())),
                            false);
                    } catch (CommandSyntaxException e) {
                        player.displayClientMessage(
                            ecText.getText("cmd.warp.set.error.exists", TextFormatType.Error,
                                net.minecraft.network.chat.Component.literal(warpName.trim())),
                            false);
                    }
                })
                .build();
            
            floodgateApi.sendForm(player.getUUID(), form);
        } catch (Exception e) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Failed to show create warp form"),
                false);
            e.printStackTrace();
        }
    }
    
    /**
     * Shows form to select and delete a warp.
     */
    private void showDeleteWarpForm(ServerPlayer player) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var ecText = ECText.access(player);
            var worldDataManager = ManagerLocator.getInstance().getWorldDataManager();
            List<String> warpNames = worldDataManager.getWarpNames();
            
            if (warpNames.isEmpty()) {
                player.displayClientMessage(
                    ecText.getText("cmd.warp.form.menu.no_warps", TextFormatType.Error),
                    false);
                return;
            }
            
            SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(ecText.getString("cmd.warp.form.delete.title"))
                .content(ecText.getString("cmd.warp.form.delete.content"));
            
            for (String warpName : warpNames) {
                formBuilder.button(warpName);
            }
            
            SimpleForm form = formBuilder
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    if (selectedIndex >= 0 && selectedIndex < warpNames.size()) {
                        String warpName = warpNames.get(selectedIndex);
                        var worldDataManager_inner = ManagerLocator.getInstance().getWorldDataManager();
                        
                        if (worldDataManager_inner.delWarp(warpName)) {
                            player.displayClientMessage(
                                ecText.getText("cmd.warp.delete.feedback",
                                    net.minecraft.network.chat.Component.literal(warpName)),
                                false);
                        } else {
                            player.displayClientMessage(
                                ecText.getText("cmd.warp.delete.error", TextFormatType.Error,
                                    net.minecraft.network.chat.Component.literal(warpName)),
                                false);
                        }
                    }
                })
                .build();
            
            floodgateApi.sendForm(player.getUUID(), form);
        } catch (Exception e) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("Failed to show delete warp form"),
                false);
            e.printStackTrace();
        }
    }}