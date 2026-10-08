package org.themarioga.telegram.cah.game.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.themarioga.telegram.cah.game.service.intf.CCLHTelegramService;
import org.themarioga.commons.telegram.models.CallbackQueryHandler;
import org.themarioga.commons.telegram.models.CommandHandler;
import org.themarioga.commons.telegram.services.intf.ApplicationService;
import org.themarioga.commons.telegram.services.intf.BotMessageService;
import org.themarioga.commons.engine.services.intf.I18NService;
import org.themarioga.commons.telegram.util.BotMessageUtils;

import java.util.HashMap;
import java.util.Map;

@Service("cclhBotApplicationService")
@ConditionalOnProperty(prefix = "cclh.bot", name = "enabled", havingValue = "true")
public class CCLHApplicationServiceImpl implements ApplicationService {

    private static final Logger logger = LoggerFactory.getLogger(CCLHApplicationServiceImpl.class);

    private final BotMessageService cclhBotMessageService;
    private final CCLHTelegramService cclhTelegramService;
    private final I18NService i18NService;

    public CCLHApplicationServiceImpl(@Qualifier("cclhBotMessageService") BotMessageService cclhBotMessageService, CCLHTelegramService cclhTelegramService, I18NService i18NService) {
        this.cclhTelegramService = cclhTelegramService;
        this.i18NService = i18NService;
        this.cclhBotMessageService = cclhBotMessageService;
    }

    @Override
    public Map<String, CommandHandler> getBotCommands() {
        Map<String, CommandHandler> commands = new HashMap<>();

        commands.put("/start", (message, data) -> {
            if (!BotMessageUtils.isMessagePrivate(message)) {
                logger.error("Comando /start enviado en lugar incorrecto por {}", BotMessageUtils.getUserInfo(message.getFrom()));

                cclhBotMessageService.sendMessage(message.getChat().getId(), i18NService.get("ERROR_COMMAND_SHOULD_BE_ON_PRIVATE", message.getFrom().getLanguageCode()));

                return;
            }

            try {
                cclhTelegramService.registerUser(message.getFrom());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        commands.put("/lang", (message, data) -> {
            if (!BotMessageUtils.isMessagePrivate(message)) {
                logger.error("Comando /lang enviado en lugar incorrecto por {}", BotMessageUtils.getUserInfo(message.getFrom()));

                cclhBotMessageService.sendMessage(message.getChat().getId(), i18NService.get("ERROR_COMMAND_SHOULD_BE_ON_PRIVATE", message.getFrom().getLanguageCode()));

                return;
            }

            try {
                cclhTelegramService.loginUser(message.getFrom().getId());

                cclhTelegramService.changeUserLanguageMessage();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        commands.put("/create", (message, data) -> {
            if (BotMessageUtils.isMessagePrivate(message)) {
                logger.error("Comando /create enviado en lugar incorrecto por {}", BotMessageUtils.getUserInfo(message.getFrom()));

                cclhBotMessageService.sendMessage(message.getChat().getId(), i18NService.get("ERROR_COMMAND_SHOULD_BE_ON_GROUP", message.getFrom().getLanguageCode()));

                return;
            }

            try {
                cclhTelegramService.loginUser(message.getFrom().getId());

                cclhTelegramService.startCreatingGame(message.getChat().getId(), message.getChat().getTitle());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        commands.put("/deletemygames", (message, data) -> {
            if (!BotMessageUtils.isMessagePrivate(message)) {
                logger.error("Comando /deletemygames enviado en lugar incorrecto por {}", BotMessageUtils.getUserInfo(message.getFrom()));

                cclhBotMessageService.sendMessage(message.getChat().getId(), i18NService.get("ERROR_COMMAND_SHOULD_BE_ON_PRIVATE", message.getFrom().getLanguageCode()));

                return;
            }

            try {
                cclhTelegramService.loginUser(message.getFrom().getId());

                cclhTelegramService.deleteMyGames();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        commands.put("/deletegamebyusername", (message, data) -> {
            if (!BotMessageUtils.isMessagePrivate(message)) {
                logger.error("Comando /deletegamebyusername enviado en lugar incorrecto por {}", BotMessageUtils.getUserInfo(message.getFrom()));

                cclhBotMessageService.sendMessage(message.getChat().getId(), i18NService.get("ERROR_COMMAND_SHOULD_BE_ON_PRIVATE", message.getFrom().getLanguageCode()));

                return;
            }

            try {
                cclhTelegramService.loginUser(message.getFrom().getId());

                cclhTelegramService.deleteGameByCreatorUsername(
                        cclhBotMessageService.sanitizeTextFromCommand("/deletegamebyusername", message.getText()));
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        commands.put("/deleteallgames", (message, data) -> {
            if (!BotMessageUtils.isMessagePrivate(message)) {
                logger.error("Comando /deleteallgames enviado en lugar incorrecto por {}", BotMessageUtils.getUserInfo(message.getFrom()));

                cclhBotMessageService.sendMessage(message.getChat().getId(), i18NService.get("ERROR_COMMAND_SHOULD_BE_ON_PRIVATE", message.getFrom().getLanguageCode()));

                return;
            }

            try {
                cclhTelegramService.loginUser(message.getFrom().getId());

                cclhTelegramService.deleteAllGames();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        commands.put("/sendmessagetoeveryone", (message, data) -> {
            if (!BotMessageUtils.isMessagePrivate(message)) {
                logger.error("Comando /sendMessage enviado en lugar incorrecto por {}", BotMessageUtils.getUserInfo(message.getFrom()));

                cclhBotMessageService.sendMessage(message.getChat().getId(), i18NService.get("ERROR_COMMAND_SHOULD_BE_ON_PRIVATE", message.getFrom().getLanguageCode()));

                return;
            }

            try {
                cclhTelegramService.loginUser(message.getFrom().getId());

                // El "se han enviado todos" lo da ahora el servicio, y solo cuando de verdad ha
                // enviado algo: aquí se decía siempre, incluso cuando la difusión se rechazaba
                cclhTelegramService.sendMessageToEveryone(
                        cclhBotMessageService.sanitizeTextFromCommand("/sendmessagetoeveryone", message.getText()));
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        commands.put("/toggleglobalmessages", (message, data) -> {
            if (!BotMessageUtils.isMessagePrivate(message)) {
                logger.error("Comando /sendMessage enviado en lugar incorrecto por {}", BotMessageUtils.getUserInfo(message.getFrom()));

                cclhBotMessageService.sendMessage(message.getChat().getId(), i18NService.get("ERROR_COMMAND_SHOULD_BE_ON_PRIVATE", message.getFrom().getLanguageCode()));

                return;
            }

            try {
                cclhTelegramService.loginUser(message.getFrom().getId());

                cclhTelegramService.toggleGlobalMessages();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });

        commands.put("/help", (message, data) -> cclhTelegramService.sendHelpMessage(message.getChatId()));

        return commands;
    }

    @Override
    public Map<String, CallbackQueryHandler> getCallbackQueries() {
        Map<String, CallbackQueryHandler> callbackQueryHandlerMap = new HashMap<>();

        callbackQueryHandlerMap.put("change_user_lang", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.changeUserLanguage(callbackQuery.getMessage().getMessageId(), data != null && !data.isBlank() ? data : callbackQuery.getFrom().getLanguageCode());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_menu", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameMenuQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_configure", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameConfigureQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_sel_mode", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameSelectModeQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_sel_point_type", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameSelectPunctuationModeQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_sel_dictionary", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameSelectDictionaryQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId(), data);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_sel_max_players", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameSelectMaxPlayersQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_sel_n_rounds", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameSelectNRoundsToEndQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_sel_n_points", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameSelectNPointsToWinQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_change_mode", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameChangeMode(callbackQuery.getMessage().getChatId(), callbackQuery.getId(), data);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_change_dictionary", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameChangeDictionary(callbackQuery.getMessage().getChatId(), callbackQuery.getId(), data);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_change_max_players", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameChangeMaxPlayers(callbackQuery.getMessage().getChatId(), callbackQuery.getId(), data);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_change_max_rounds", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameChangeNRoundsToEnd(callbackQuery.getMessage().getChatId(), callbackQuery.getId(), data);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_change_max_points", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameChangeNCardsToWin(callbackQuery.getMessage().getChatId(), callbackQuery.getId(), data);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_join", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameJoinQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_leave", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.leaveGame(callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_start", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameStartQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_add_ai", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameAddAIPlayerQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_remove_ai", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameRemoveAIPlayerQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("play_card", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.playerPlayCardQuery(callbackQuery.getId(), data);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("vote_card", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.playerVoteCardQuery(callbackQuery.getId(), data);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_delete_group", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameDeleteGroupQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        callbackQueryHandlerMap.put("game_delete_private", (callbackQuery, data) -> {
            try {
                cclhTelegramService.loginUser(callbackQuery.getFrom().getId());

                cclhTelegramService.gameDeletePrivateQuery(callbackQuery.getId());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            cclhBotMessageService.answerCallbackQuery(callbackQuery.getId());
        });

        return callbackQueryHandlerMap;
    }

}
