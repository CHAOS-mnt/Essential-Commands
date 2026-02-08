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
                showRequestTypeSelectionForm(senderPlayer);
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
    private void showRequestTypeSelectionForm(ServerPlayer senderPlayer) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var senderEcText = ECText.access(senderPlayer);

            SimpleForm form = SimpleForm.builder()
                .title(senderEcText.getString("cmd.tpa.form.select_type.title"))
                .content(senderEcText.getString("cmd.tpa.form.select_type.content"))
                .button(senderEcText.getString("cmd.tpa.form.select_type.tpa"))
                .button(senderEcText.getString("cmd.tpa.form.select_type.tpahere"))
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    if (selectedIndex == 0) {
                        showPlayerSelectionForm(senderPlayer, TeleportRequest.Type.TPA_TO);
                    } else if (selectedIndex == 1) {
                        showPlayerSelectionForm(senderPlayer, TeleportRequest.Type.TPA_HERE);
                    }
                })
                .build();

            floodgateApi.sendForm(senderPlayer.getUUID(), form);
        } catch (Exception e) {
            var senderPlayerData = PlayerData.access(senderPlayer);
            senderPlayerData.sendCommandError("cmd.tpask.error.form_failed");
            e.printStackTrace();
        }
    }

    /**
     * Creates and sends a SimpleForm to Bedrock players for selecting a target player.
     */
    private void showPlayerSelectionForm(ServerPlayer senderPlayer, TeleportRequest.Type requestType) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var senderEcText = ECText.access(senderPlayer);
            
            // Get list of online players
            List<ServerPlayer> onlinePlayers = senderPlayer.level().getServer().getPlayerList().getPlayers().stream()
                .collect(Collectors.toList());
            
            // Build the SimpleForm with buttons for each player
            String titleKey = requestType == TeleportRequest.Type.TPA_HERE
                ? "cmd.tpaskhere.form.title"
                : "cmd.tpask.form.title";
            String contentKey = requestType == TeleportRequest.Type.TPA_HERE
                ? "cmd.tpaskhere.form.content"
                : "cmd.tpask.form.content";

            SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(senderEcText.getString(titleKey))
                .content(senderEcText.getString(contentKey));
            
            for (ServerPlayer player : onlinePlayers) {
                formBuilder.button(player.getGameProfile().name());
            }
            
            SimpleForm form = formBuilder
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    if (selectedIndex >= 0 && selectedIndex < onlinePlayers.size()) {
                        ServerPlayer targetPlayer = onlinePlayers.get(selectedIndex);
                        handleFormResponse(senderPlayer, targetPlayer, requestType);
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
    private void handleFormResponse(ServerPlayer senderPlayer, ServerPlayer targetPlayer, TeleportRequest.Type requestType) {
        sendBedrockTeleportRequest(senderPlayer, targetPlayer, requestType);
    }

    /**
     * Sends a Bedrock modal confirmation to the target and processes acceptance/denial.
     */
    private void sendBedrockTeleportRequest(ServerPlayer senderPlayer, ServerPlayer targetPlayer, TeleportRequest.Type requestType) {
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

        // If target isn't Bedrock, use chat flow directly
        if (!isFloodgatePlayer(targetPlayer)) {
            sendChatTeleportRequest(senderPlayer, targetPlayer, tpMgr, senderPlayerData, targetPlayerData, requestType);
            return;
        }

        // Start request immediately so accept can teleport
        tpMgr.startTpRequest(senderPlayer, targetPlayer, requestType);
        var startedRequest = senderPlayerData.getSentTeleportRequests().getRequestToPlayer(targetPlayerData);
        if (startedRequest.isEmpty()) {
            senderPlayerData.sendCommandError("cmd.tpask.error.form_failed");
            return;
        }

        // Inform command sender that request has been sent
        var senderPlayerProfile = PlayerProfile.access(senderPlayer);
        var targetPlayerText = targetPlayer.getDisplayName().copy().withStyle(senderPlayerProfile.getStyle(TextFormatType.Accent));
        senderPlayerData.sendCommandFeedback("cmd.tpask.send", targetPlayerText);

        // Send ModalForm to target player asking for confirmation
        FloodgateApi floodgateApi = FloodgateApi.getInstance();
        String senderName = senderPlayer.getGameProfile().name();
        var targetEcText = ECText.access(targetPlayer);
        var request = startedRequest.get();
        
        String modalTitleKey = requestType == TeleportRequest.Type.TPA_HERE
            ? "cmd.tpaskhere.form.modal.title"
            : "cmd.tpask.form.modal.title";
        String modalContentKey = requestType == TeleportRequest.Type.TPA_HERE
            ? "cmd.tpaskhere.form.modal.content"
            : "cmd.tpask.form.modal.content";
        String modalAcceptKey = requestType == TeleportRequest.Type.TPA_HERE
            ? "cmd.tpaskhere.form.modal.accept"
            : "cmd.tpask.form.modal.accept";
        String modalDenyKey = requestType == TeleportRequest.Type.TPA_HERE
            ? "cmd.tpaskhere.form.modal.deny"
            : "cmd.tpask.form.modal.deny";

        ModalForm form = ModalForm.builder()
            .title(targetEcText.getString(modalTitleKey))
            .content(targetEcText.getString(modalContentKey).replace("${0}", senderName))
            .button1(targetEcText.getString(modalAcceptKey))
            .button2(targetEcText.getString(modalDenyKey))
            .validResultHandler(response -> {
                if (response.clickedButtonId() == 0) {
                    // Player accepted - teleport now
                    request.queue();
                    request.end();
                    senderPlayerData.sendMessage("cmd.tpaccept.feedback");
                    targetPlayerData.sendMessage("cmd.tpaccept.feedback");
                } else {
                    // Player denied
                    request.end();
                    senderPlayerData.sendMessage("cmd.tpask.denied", targetPlayer.getDisplayName());
                    targetPlayerData.sendMessage("cmd.tpask.you_denied", senderPlayer.getDisplayName());
                }
            })
            .build();
        
        boolean formSent = floodgateApi.sendForm(targetPlayer.getUUID(), form);
        if (!formSent) {
            // If form sending fails, use the original chat confirmation method (no new request)
            var targetPlayerProfile = PlayerProfile.access(targetPlayer);
            String receiveKey = requestType == TeleportRequest.Type.TPA_HERE
                ? "cmd.tpaskhere.receive"
                : "cmd.tpask.receive";
            targetPlayerData.sendMessage(
                receiveKey,
                senderPlayer.getDisplayName().copy().withStyle(targetPlayerProfile.getStyle(TextFormatType.Accent))
            );

            new ChatConfirmationPrompt(
                targetPlayer,
                "/tpaccept " + senderName,
                "/tpdeny " + senderName,
                targetEcText.accent("[" + ECText.getInstance().getString("generic.accept") + "]"),
                targetEcText.error("[" + ECText.getInstance().getString("generic.deny") + "]")
            ).send();
        }
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

        // If target is Bedrock, show ModalForm instead of chat
        if (isFloodgatePlayer(targetPlayer)) {
            sendBedrockTeleportRequest(senderPlayer, targetPlayer, TeleportRequest.Type.TPA_TO);
            return SINGLE_SUCCESS;
        }

        sendChatTeleportRequest(senderPlayer, targetPlayer, tpMgr, senderPlayerData, targetPlayerData, TeleportRequest.Type.TPA_TO);

        return SINGLE_SUCCESS;
    }

    private void sendChatTeleportRequest(ServerPlayer senderPlayer, ServerPlayer targetPlayer, TeleportManager tpMgr,
                                         PlayerData senderPlayerData, PlayerData targetPlayerData, TeleportRequest.Type requestType) {
        // Don't allow spamming same target
        var existingTeleportRequest = senderPlayerData.getSentTeleportRequests()
            .getRequestToPlayer(targetPlayerData);
        if (existingTeleportRequest.isPresent()) {
            senderPlayerData.sendCommandError(
                "cmd.tpask.error.exists",
                existingTeleportRequest.get().getTargetPlayer().getDisplayName());
            return;
        }

        // inform target player of tp request via chat
        var targetPlayerEcText = ECText.access(targetPlayer);
        var targetPlayerProfile = PlayerProfile.access(targetPlayer);
        String receiveKey = requestType == TeleportRequest.Type.TPA_HERE
            ? "cmd.tpaskhere.receive"
            : "cmd.tpask.receive";
        targetPlayerData.sendMessage(
            receiveKey,
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

        // Mark TPRequest Sender as having requested a teleport
        tpMgr.startTpRequest(senderPlayer, targetPlayer, requestType);

        // inform command sender that request has been sent
        var senderPlayerProfile = PlayerProfile.access(senderPlayer);
        var targetPlayerText = targetPlayer.getDisplayName().copy().withStyle(senderPlayerProfile.getStyle(TextFormatType.Accent));
        senderPlayerData.sendCommandFeedback("cmd.tpask.send", targetPlayerText);
    }

    private boolean isFloodgatePlayer(ServerPlayer player) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            return floodgateApi.isFloodgatePlayer(player.getUUID());
        } catch (Exception e) {
            return false;
        }
    }
}
