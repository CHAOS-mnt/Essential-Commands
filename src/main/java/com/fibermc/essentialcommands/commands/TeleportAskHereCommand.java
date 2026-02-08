package com.fibermc.essentialcommands.commands;

import com.fibermc.essentialcommands.ManagerLocator;
import com.fibermc.essentialcommands.playerdata.PlayerData;
import com.fibermc.essentialcommands.teleportation.TeleportManager;
import com.fibermc.essentialcommands.teleportation.TeleportRequest;
import com.fibermc.essentialcommands.text.ChatConfirmationPrompt;
import com.fibermc.essentialcommands.text.ECText;
import com.fibermc.essentialcommands.playerdata.PlayerProfile;
import com.fibermc.essentialcommands.text.TextFormatType;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.floodgate.api.FloodgateApi;

public class TeleportAskHereCommand implements Command<CommandSourceStack> {

    public TeleportAskHereCommand() {}

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
            sendBedrockTeleportHereRequest(senderPlayer, targetPlayer, tpMgr, senderPlayerData, targetPlayerData);
            return SINGLE_SUCCESS;
        }

        //inform target player of tp request via chat
        var targetPlayerEcText = ECText.access(targetPlayer);
        targetPlayerData.sendMessage(
            "cmd.tpaskhere.receive",
            targetPlayerEcText.accent(senderPlayer.getScoreboardName())
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
        tpMgr.startTpRequest(senderPlayer, targetPlayer, TeleportRequest.Type.TPA_HERE);

        //inform command sender that request has been sent
        var targetPlayerText = ECText.access(senderPlayer).accent(targetPlayer.getScoreboardName());
        senderPlayerData.sendCommandFeedback("cmd.tpask.send", targetPlayerText);

        return SINGLE_SUCCESS;
    }

    private void sendBedrockTeleportHereRequest(ServerPlayer senderPlayer, ServerPlayer targetPlayer,
                                                TeleportManager tpMgr, PlayerData senderPlayerData, PlayerData targetPlayerData) {
        // Start request immediately so accept can teleport
        tpMgr.startTpRequest(senderPlayer, targetPlayer, TeleportRequest.Type.TPA_HERE);
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

        ModalForm form = ModalForm.builder()
            .title(targetEcText.getString("cmd.tpaskhere.form.modal.title"))
            .content(targetEcText.getString("cmd.tpaskhere.form.modal.content").replace("${0}", senderName))
            .button1(targetEcText.getString("cmd.tpaskhere.form.modal.accept"))
            .button2(targetEcText.getString("cmd.tpaskhere.form.modal.deny"))
            .validResultHandler(response -> {
                if (response.clickedButtonId() == 0) {
                    request.queue();
                    request.end();
                    senderPlayerData.sendMessage("cmd.tpaccept.feedback");
                    targetPlayerData.sendMessage("cmd.tpaccept.feedback");
                } else {
                    request.end();
                    senderPlayerData.sendMessage("cmd.tpask.denied", targetPlayer.getDisplayName());
                    targetPlayerData.sendMessage("cmd.tpask.you_denied", senderPlayer.getDisplayName());
                }
            })
            .build();

        boolean formSent = floodgateApi.sendForm(targetPlayer.getUUID(), form);
        if (!formSent) {
            targetPlayerData.sendMessage(
                "cmd.tpaskhere.receive",
                targetEcText.accent(senderPlayer.getScoreboardName())
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

    private boolean isFloodgatePlayer(ServerPlayer player) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            return floodgateApi.isFloodgatePlayer(player.getUUID());
        } catch (Exception e) {
            return false;
        }
    }
}
