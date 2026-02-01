package com.fibermc.essentialcommands.commands;

import com.fibermc.essentialcommands.ManagerLocator;
import com.fibermc.essentialcommands.playerdata.PlayerData;
import com.fibermc.essentialcommands.playerdata.PlayerProfile;
import com.fibermc.essentialcommands.teleportation.TeleportManager;
import com.fibermc.essentialcommands.teleportation.TeleportRequest;
import com.fibermc.essentialcommands.text.ChatConfirmationPrompt;
import com.fibermc.essentialcommands.text.ECText;
import com.fibermc.essentialcommands.text.TextFormatType;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;
import java.util.List;
import java.util.stream.Collectors;

public class TeleportAskCommand implements Command<CommandSourceStack> {

    public TeleportAskCommand() {}

    /**
     * Handles the TPA command when no target player is specified (no parameter).
     * For Bedrock players, shows a SimpleForm to select a player.
     * For Java players, sends an error message.
     */
    public int runWithoutTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer senderPlayer = context.getSource().getPlayerOrException();
        UUID playerUUID = senderPlayer.getUUID();
        
        // Check if the player is a Bedrock player using Floodgate API
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            if (floodgateApi.isFloodgatePlayer(playerUUID)) {
                // Player is from Bedrock Edition, show form
                showPlayerSelectionForm(senderPlayer);
                return SINGLE_SUCCESS;
            }
        } catch (Exception e) {
            // Floodgate API not available or error occurred, treat as Java player
        }
        
        // For Java players, send an error message indicating they need to specify a target
        var senderPlayerData = PlayerData.access(senderPlayer);
        senderPlayerData.sendCommandError("cmd.tpask.error.no_target");
        return 0;
    }

    /**
     * Creates and sends a SimpleForm to Bedrock players for selecting a target player.
     */
    private void showPlayerSelectionForm(ServerPlayer senderPlayer) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var senderEcText = ECText.access(senderPlayer);
            
            // Get list of online players
            List<ServerPlayer> onlinePlayers = senderPlayer.level().getServer().getPlayerList().getPlayers().stream()
                .collect(Collectors.toList());
            
            // Build the SimpleForm with buttons for each player
            SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(senderEcText.getString("cmd.tpask.form.title"))
                .content(senderEcText.getString("cmd.tpask.form.content"));
            
            for (ServerPlayer player : onlinePlayers) {
                formBuilder.button(player.getGameProfile().name());
            }
            
            SimpleForm form = formBuilder
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    if (selectedIndex >= 0 && selectedIndex < onlinePlayers.size()) {
                        ServerPlayer targetPlayer = onlinePlayers.get(selectedIndex);
                        handleFormResponse(senderPlayer, targetPlayer);
                    }
                })
                .build();
            
            // Send the form to the player
            floodgateApi.sendForm(senderPlayer.getUUID(), form);
        } catch (Exception e) {
            // Error handling - fallback to error message
            var senderPlayerData = PlayerData.access(senderPlayer);
            senderPlayerData.sendCommandError("cmd.tpask.error.form_failed");
            e.printStackTrace();
        }
    }

    /**
     * Handles the response from the SimpleForm and sends a ModalForm to the target player.
     */
    private void handleFormResponse(ServerPlayer senderPlayer, ServerPlayer targetPlayer) {
        TeleportManager tpMgr = ManagerLocator.getInstance().getTpManager();
        var senderPlayerData = PlayerData.access(senderPlayer);
        var targetPlayerData = PlayerData.access(targetPlayer);

        // Don't allow spamming same target
        var existingTeleportRequest = senderPlayerData.getSentTeleportRequests()
            .getRequestToPlayer(targetPlayerData);
        if (existingTeleportRequest.isPresent()) {
            senderPlayerData.sendCommandError(
                "cmd.tpask.error.exists",
                existingTeleportRequest.get().getTargetPlayer().getDisplayName());
            return;
        }

        // Send ModalForm to target player asking for confirmation
        FloodgateApi floodgateApi = FloodgateApi.getInstance();
        String senderName = senderPlayer.getGameProfile().name();
        var targetEcText = ECText.access(targetPlayer);
        
        ModalForm form = ModalForm.builder()
            .title(targetEcText.getString("cmd.tpask.form.modal.title"))
            .content(targetEcText.getString("cmd.tpask.form.modal.content").replace("${0}", senderName))
            .button1(targetEcText.getString("cmd.tpask.form.modal.accept"))
            .button2(targetEcText.getString("cmd.tpask.form.modal.deny"))
            .validResultHandler(response -> {
                if (response.clickedButtonId() == 0) {
                    // Player accepted - proceed with teleport request
                    proceedWithTeleportRequest(senderPlayer, targetPlayer, tpMgr, senderPlayerData, targetPlayerData);
                } else {
                    // Player denied
                    senderPlayerData.sendMessage("cmd.tpask.denied", targetPlayer.getDisplayName());
                    targetPlayerData.sendMessage("cmd.tpask.you_denied", senderPlayer.getDisplayName());
                }
            })
            .build();
        
        boolean formSent = floodgateApi.sendForm(targetPlayer.getUUID(), form);
        if (!formSent) {
            // If form sending fails, use the original chat confirmation method
            var targetPlayerProfile = PlayerProfile.access(targetPlayer);
            targetPlayerData.sendMessage(
                "cmd.tpask.receive",
                senderPlayer.getDisplayName().copy().withStyle(targetPlayerProfile.getStyle(TextFormatType.Accent))
            );

            new ChatConfirmationPrompt(
                targetPlayer,
                "/tpaccept " + senderName,
                "/tpdeny " + senderName,
                targetEcText.accent("[" + ECText.getInstance().getString("generic.accept") + "]"),
                targetEcText.error("[" + ECText.getInstance().getString("generic.deny") + "]")
            ).send();

            tpMgr.startTpRequest(senderPlayer, targetPlayer, TeleportRequest.Type.TPA_TO);
            var senderPlayerProfile = PlayerProfile.access(senderPlayer);
            var targetPlayerText = targetPlayer.getDisplayName().copy().withStyle(senderPlayerProfile.getStyle(TextFormatType.Accent));
            senderPlayerData.sendCommandFeedback("cmd.tpask.send", targetPlayerText);
        }
    }

    /**
     * Proceeds with the teleport request after confirmation.
     */
    private void proceedWithTeleportRequest(ServerPlayer senderPlayer, ServerPlayer targetPlayer, TeleportManager tpMgr,
                                           PlayerData senderPlayerData, PlayerData targetPlayerData) {
        // Mark TPRequest Sender as having requested a teleport
        tpMgr.startTpRequest(senderPlayer, targetPlayer, TeleportRequest.Type.TPA_TO);

        // Inform command sender that request has been sent
        var senderPlayerProfile = PlayerProfile.access(senderPlayer);
        var targetPlayerText = targetPlayer.getDisplayName().copy().withStyle(senderPlayerProfile.getStyle(TextFormatType.Accent));
        senderPlayerData.sendCommandFeedback("cmd.tpask.send", targetPlayerText);
        
        // Inform target player
        targetPlayerData.sendMessage("cmd.tpask.accepted", senderPlayer.getDisplayName());
    }

    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        TeleportManager tpMgr = ManagerLocator.getInstance().getTpManager();
        ServerPlayer senderPlayer = context.getSource().getPlayerOrException();
        ServerPlayer targetPlayer = EntityArgument.getPlayer(context, "target_player");
        var senderPlayerData = PlayerData.access(senderPlayer);
        var targetPlayerData = PlayerData.access(targetPlayer);

        // Don't allow spamming same target.
        {
            var existingTeleportRequest = senderPlayerData.getSentTeleportRequests()
                .getRequestToPlayer(targetPlayerData);
            if (existingTeleportRequest.isPresent()) {
                PlayerData.access(senderPlayer).sendCommandError(
                    "cmd.tpask.error.exists",
                    existingTeleportRequest.get().getTargetPlayer().getDisplayName());
                return 0;
            }
        }

        //inform target player of tp request via chat
        var targetPlayerEcText = ECText.access(targetPlayer);
        var targetPlayerProfile = PlayerProfile.access(targetPlayer);
        targetPlayerData.sendMessage(
            "cmd.tpask.receive",
            senderPlayer.getDisplayName().copy().withStyle(targetPlayerProfile.getStyle(TextFormatType.Accent))
        );

        String senderName = senderPlayer.getGameProfile().name();
        new ChatConfirmationPrompt(
            targetPlayer,
            "/tpaccept " + senderName,
            "/tpdeny " + senderName,
            targetPlayerEcText.accent("[" + ECText.getInstance().getString("generic.accept") + "]"),
            targetPlayerEcText.error("[" + ECText.getInstance().getString("generic.deny") + "]")
        ).send();

        //Mark TPRequest Sender as having requested a teleport
        tpMgr.startTpRequest(senderPlayer, targetPlayer, TeleportRequest.Type.TPA_TO);

        //inform command sender that request has been sent
        var senderPlayerProfile = PlayerProfile.access(senderPlayer);
        var targetPlayerText = targetPlayer.getDisplayName().copy().withStyle(senderPlayerProfile.getStyle(TextFormatType.Accent));
        senderPlayerData.sendCommandFeedback("cmd.tpask.send", targetPlayerText);

        return SINGLE_SUCCESS;
    }
}
