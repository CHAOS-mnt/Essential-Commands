package com.fibermc.essentialcommands.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fibermc.essentialcommands.access.ServerPlayerEntityAccess;
import com.fibermc.essentialcommands.commands.suggestions.ListSuggestion;
import com.fibermc.essentialcommands.playerdata.PlayerData;
import com.fibermc.essentialcommands.teleportation.PlayerTeleporter;
import com.fibermc.essentialcommands.text.ECText;
import com.fibermc.essentialcommands.text.TextFormatType;
import com.fibermc.essentialcommands.types.MinecraftLocation;
import com.fibermc.essentialcommands.types.NamedMinecraftLocation;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;

public class HomeCommand implements Command<CommandSourceStack> {

    public HomeCommand() {}

    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        PlayerData senderPlayerData = ((ServerPlayerEntityAccess) context.getSource().getPlayerOrException()).ec$getPlayerData();
        String homeName = StringArgumentType.getString(context, "home_name");

        return exec(senderPlayerData, homeName);
    }

    private static PlayerData getTargetPlayerData(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return ((ServerPlayerEntityAccess) context.getSource().getPlayerOrException()).ec$getPlayerData();
    }

    // TODO: Ideally the styling here should come from a context, intead of from the player we're
    //  accessing, but I don't think it matters, practically speaking, for now.
    public static String getSoleHomeName(PlayerData playerData) throws CommandSyntaxException {
        Set<String> homeNames = playerData.getHomeNames();
        var ecText = ECText.access(playerData.getPlayer());
        if (homeNames.size() > 1) {
            throw CommandUtil.createSimpleException(
                ecText.getText("cmd.home.tp.error.shortcut_more_than_one", TextFormatType.Error));
        } else if (homeNames.isEmpty()) {
            throw CommandUtil.createSimpleException(
                ecText.getText("cmd.home.tp.error.shortcut_none_exist", TextFormatType.Error));
        }

        return homeNames.stream().findAny().get();
    }

    public int runDefault(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PlayerData playerData = ((ServerPlayerEntityAccess) player).ec$getPlayerData();
        UUID playerUUID = player.getUUID();
        
        // Check if the player is a Bedrock player using Floodgate API
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            if (floodgateApi.isFloodgatePlayer(playerUUID)) {
                // Player is from Bedrock Edition, show main menu form
                showHomeMenuForm(player, playerData);
                return SINGLE_SUCCESS;
            }
        } catch (Exception e) {
            // Floodgate API not available or error occurred, treat as Java player
        }
        
        // For Java players, use original logic
        return exec(
            playerData,
            getSoleHomeName(playerData)
        );
    }
    
    /**
     * Shows the main home menu form to Bedrock players.
     */
    private void showHomeMenuForm(ServerPlayer player, PlayerData playerData) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var ecText = ECText.access(player);
            Set<String> homeNames = playerData.getHomeNames();
            boolean hasHomes = !homeNames.isEmpty();
            
            SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(ecText.getString("cmd.home.form.menu.title"))
                .content(ecText.getString("cmd.home.form.menu.content"));
            
            // If user has no homes, only show create button
            if (!hasHomes) {
                SimpleForm form = formBuilder
                    .button(ecText.getString("cmd.home.form.menu.create"))
                    .validResultHandler(response -> {
                        showCreateHomeForm(player, playerData);
                    })
                    .build();
                floodgateApi.sendForm(player.getUUID(), form);
                return;
            }
            
            // If user has homes, show all options
            SimpleForm form = formBuilder
                .button(ecText.getString("cmd.home.form.menu.teleport"))
                .button(ecText.getString("cmd.home.form.menu.create"))
                .button(ecText.getString("cmd.home.form.menu.delete"))
                .button(ecText.getString("cmd.home.form.menu.update"))
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    switch (selectedIndex) {
                        case 0: // Teleport to home
                            showTeleportHomeForm(player, playerData);
                            break;
                        case 1: // Create new home
                            showCreateHomeForm(player, playerData);
                            break;
                        case 2: // Delete home
                            showDeleteHomeForm(player, playerData);
                            break;
                        case 3: // Update home
                            showUpdateHomeForm(player, playerData);
                            break;
                    }
                })
                .build();
            
            floodgateApi.sendForm(player.getUUID(), form);
        } catch (Exception e) {
            playerData.sendCommandError("cmd.home.error.form_failed");
            e.printStackTrace();
        }
    }
    
    /**
     * Shows form to select and teleport to a home.
     */
    private void showTeleportHomeForm(ServerPlayer player, PlayerData playerData) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var ecText = ECText.access(player);
            Set<String> homeNames = playerData.getHomeNames();
            
            if (homeNames.isEmpty()) {
                playerData.sendCommandError("cmd.home.tp.error.shortcut_none_exist");
                return;
            }
            
            List<String> homeList = new ArrayList<>(homeNames);
            SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(ecText.getString("cmd.home.form.teleport.title"))
                .content(ecText.getString("cmd.home.form.teleport.content"));
            
            for (String homeName : homeList) {
                formBuilder.button(homeName);
            }
            
            SimpleForm form = formBuilder
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    if (selectedIndex >= 0 && selectedIndex < homeList.size()) {
                        String homeName = homeList.get(selectedIndex);
                        try {
                            exec(playerData, homeName);
                        } catch (CommandSyntaxException e) {
                            playerData.sendCommandError("cmd.home.tp.error.not_found", Component.literal(homeName));
                        }
                    }
                })
                .build();
            
            floodgateApi.sendForm(player.getUUID(), form);
        } catch (Exception e) {
            playerData.sendCommandError("cmd.home.error.form_failed");
            e.printStackTrace();
        }
    }
    
    /**
     * Shows form to create a new home with text input.
     */
    private void showCreateHomeForm(ServerPlayer player, PlayerData playerData) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var ecText = ECText.access(player);
            
            CustomForm form = CustomForm.builder()
                .title(ecText.getString("cmd.home.form.create.title"))
                .input(ecText.getString("cmd.home.form.create.input_label"), ecText.getString("cmd.home.form.create.input_placeholder"))
                .validResultHandler(response -> {
                    String homeName = response.asInput(0);
                    if (homeName == null || homeName.trim().isEmpty()) {
                        playerData.sendCommandError("cmd.home.form.create.error.empty_name");
                        return;
                    }
                    
                    MinecraftLocation currentLoc = new MinecraftLocation(player);
                    try {
                        playerData.addHome(homeName.trim(), currentLoc);
                        playerData.sendCommandFeedback("cmd.home.set.feedback", Component.literal(homeName.trim()));
                    } catch (CommandSyntaxException e) {
                        playerData.sendCommandError("cmd.home.set.error.limit");
                    }
                })
                .build();
            
            floodgateApi.sendForm(player.getUUID(), form);
        } catch (Exception e) {
            playerData.sendCommandError("cmd.home.error.form_failed");
            e.printStackTrace();
        }
    }
    
    /**
     * Shows form to select and delete a home.
     */
    private void showDeleteHomeForm(ServerPlayer player, PlayerData playerData) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var ecText = ECText.access(player);
            Set<String> homeNames = playerData.getHomeNames();
            
            if (homeNames.isEmpty()) {
                playerData.sendCommandError("cmd.home.tp.error.shortcut_none_exist");
                return;
            }
            
            List<String> homeList = new ArrayList<>(homeNames);
            SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(ecText.getString("cmd.home.form.delete.title"))
                .content(ecText.getString("cmd.home.form.delete.content"));
            
            for (String homeName : homeList) {
                formBuilder.button(homeName);
            }
            
            SimpleForm form = formBuilder
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    if (selectedIndex >= 0 && selectedIndex < homeList.size()) {
                        String homeName = homeList.get(selectedIndex);
                        if (playerData.removeHome(homeName)) {
                            playerData.sendCommandFeedback("cmd.home.delete.feedback", Component.literal(homeName));
                        } else {
                            playerData.sendCommandError("cmd.home.delete.error", Component.literal(homeName));
                        }
                    }
                })
                .build();
            
            floodgateApi.sendForm(player.getUUID(), form);
        } catch (Exception e) {
            playerData.sendCommandError("cmd.home.error.form_failed");
            e.printStackTrace();
        }
    }
    
    /**
     * Shows form to select and update a home location.
     */
    private void showUpdateHomeForm(ServerPlayer player, PlayerData playerData) {
        try {
            FloodgateApi floodgateApi = FloodgateApi.getInstance();
            var ecText = ECText.access(player);
            Set<String> homeNames = playerData.getHomeNames();
            
            if (homeNames.isEmpty()) {
                playerData.sendCommandError("cmd.home.tp.error.shortcut_none_exist");
                return;
            }
            
            List<String> homeList = new ArrayList<>(homeNames);
            SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(ecText.getString("cmd.home.form.update.title"))
                .content(ecText.getString("cmd.home.form.update.content"));
            
            for (String homeName : homeList) {
                formBuilder.button(homeName);
            }
            
            SimpleForm form = formBuilder
                .validResultHandler(response -> {
                    int selectedIndex = response.clickedButtonId();
                    if (selectedIndex >= 0 && selectedIndex < homeList.size()) {
                        String homeName = homeList.get(selectedIndex);
                        MinecraftLocation currentLoc = new MinecraftLocation(player);
                        try {
                            playerData.removeHome(homeName);
                            playerData.addHome(homeName, currentLoc);
                            playerData.sendCommandFeedback("cmd.overwritehome.feedback", Component.literal(homeName));
                        } catch (CommandSyntaxException e) {
                            playerData.sendCommandError("cmd.home.set.error.limit", Component.literal(homeName));
                        }
                    }
                })
                .build();
            
            floodgateApi.sendForm(player.getUUID(), form);
        } catch (Exception e) {
            playerData.sendCommandError("cmd.home.error.form_failed");
            e.printStackTrace();
        }
    }

    private static int exec(PlayerData senderPlayerData, String homeName) throws CommandSyntaxException {
        return exec(senderPlayerData, senderPlayerData, homeName);
    }

    public static int exec(PlayerData senderPlayerData, PlayerData targetPlayerData, String homeName) throws CommandSyntaxException {
        //Get home location
        MinecraftLocation loc = targetPlayerData.getHomeLocation(homeName);
        var ecText = ECText.access(senderPlayerData.getPlayer());
        if (loc == null) {
            Message msg = ecText.getText(
                "cmd.home.tp.error.not_found",
                TextFormatType.Error,
                Component.literal(homeName));
            throw new CommandSyntaxException(new SimpleCommandExceptionType(msg), msg);
        }

        // Teleport & chat message
        var homeNameText = ecText.getText(
            "cmd.home.location_name",
            TextFormatType.Default,
            ecText.accent(homeName));

        PlayerTeleporter.requestTeleport(senderPlayerData, loc, homeNameText);
        return SINGLE_SUCCESS;
    }

    public static class Suggestion {
        //Brigader Suggestions
        public static final SuggestionProvider<CommandSourceStack> LIST_SUGGESTION_PROVIDER
            = ListSuggestion.ofContext(Suggestion::getSuggestionsList);

        /**
         * Gets a list of suggested strings to be used with Brigader
         */
        public static List<String> getSuggestionsList(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            return new ArrayList<>(HomeCommand.getTargetPlayerData(context).getHomeNames());
        }

        /**
         * Gets a set of suggestion entries to be used with ListCommandFactory
         */
        public static Set<Map.Entry<String, NamedMinecraftLocation>> getSuggestionEntries(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            return HomeCommand.getTargetPlayerData(context).getHomeEntries();
        }
    }
}
