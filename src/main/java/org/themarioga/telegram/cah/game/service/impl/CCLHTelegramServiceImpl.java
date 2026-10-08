package org.themarioga.telegram.cah.game.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.themarioga.commons.engine.enums.GameStatusEnum;
import org.themarioga.commons.engine.exceptions.ApplicationException;
import org.themarioga.commons.engine.exceptions.game.GameDoesntExistsException;
import org.themarioga.commons.engine.exceptions.game.GameOnlyCreatorCanPerformActionException;
import org.themarioga.commons.engine.exceptions.player.PlayerDoesntExistsException;
import org.themarioga.engine.cah.enums.RoundStatusEnum;
import org.themarioga.engine.cah.models.game.PlayerHandCard;
import org.themarioga.engine.cah.models.game.Round;
import org.themarioga.engine.cah.services.intf.dictionaries.CardService;
import org.themarioga.engine.cah.exceptions.card.CardDoesntExistsException;
import org.themarioga.engine.cah.models.dictionaries.Card;
import org.themarioga.engine.cah.models.game.PlayedCard;
import org.themarioga.engine.cah.services.intf.game.PlayerService;
import org.themarioga.engine.cah.services.intf.game.RoundService;
import org.themarioga.telegram.cah.models.TelegramPlayer;
import org.themarioga.commons.engine.exceptions.user.UserAlreadyExistsException;
import org.themarioga.commons.engine.exceptions.user.UserDoesntExistsException;
import org.themarioga.commons.engine.models.Lang;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.commons.engine.models.User;
import org.themarioga.commons.engine.security.SecurityUtils;
import org.themarioga.commons.engine.services.intf.I18NService;
import org.themarioga.commons.engine.services.intf.RoomService;
import org.themarioga.commons.engine.services.intf.UserService;
import org.themarioga.commons.telegram.config.TelegramAdmins;
import org.themarioga.commons.telegram.models.TelegramUser;
import org.themarioga.commons.telegram.util.TelegramUserUtils;
import org.themarioga.commons.telegram.security.TelegramSecurityUtils;
import org.themarioga.commons.telegram.security.TelegramSession;
import org.themarioga.commons.telegram.services.intf.BotMessageService;
import org.themarioga.commons.telegram.services.intf.TelegramRoomResolver;
import org.themarioga.commons.telegram.services.intf.TelegramUserService;
import org.themarioga.engine.cah.config.GameConfig;
import org.themarioga.engine.cah.enums.PunctuationModeEnum;
import org.themarioga.engine.cah.enums.VotationModeEnum;
import org.themarioga.engine.cah.models.dictionaries.Dictionary;
import org.themarioga.engine.cah.models.game.Game;
import org.themarioga.engine.cah.models.game.Player;
import org.themarioga.engine.cah.services.intf.CAHService;
import org.themarioga.engine.cah.services.intf.dictionaries.DictionaryService;
import org.themarioga.engine.cah.services.intf.game.GameService;
import org.themarioga.telegram.cah.config.BotProperties;
import org.themarioga.telegram.cah.config.ErrorMessageResolver;
import org.themarioga.telegram.cah.game.service.intf.CCLHTelegramService;
import org.themarioga.telegram.cah.models.TelegramGame;
import org.themarioga.telegram.cah.services.intf.TelegramGameService;

import java.text.MessageFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bot de juego.
 * <p>
 * Porte de {@code CCLHBotServiceImpl}. Además de las sustituciones que ya traía el bot de
 * diccionarios (identificadores UUID, el chat que sale de la sesión, errores traducidos en un
 * sitio), aquí hay dos cosas propias:
 * <ul>
 * <li>Se trabaja sobre <b>dos chats a la vez</b>: el grupo donde va la partida y el privado de
 * cada jugador. Cada mensaje que se edita o se borra hay que dirigirlo al chat correcto, y
 * ninguno de esos identificadores está ya en las entidades del motor.</li>
 * <li>El flujo de creación <b>encadena envíos asíncronos</b> para quedarse con los identificadores
 * de los mensajes. La continuación corre en otro hilo, así que la sesión se lleva con
 * {@link TelegramSession}.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "cclh.bot", name = "enabled", havingValue = "true")
public class CCLHTelegramServiceImpl implements CCLHTelegramService {

    private static final Logger logger = LoggerFactory.getLogger(CCLHTelegramServiceImpl.class);

    /** Lo que admite {@code sendMessage} de Telegram. Solo la difusión puede acercarse: la escribe una persona. */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    private final BotMessageService botMessageService;
    private final CAHService cahService;
    private final GameService gameService;
    private final PlayerService playerService;
    private final RoundService roundService;
    private final CardService cardService;
    private final DictionaryService dictionaryService;
    private final UserService userService;
    private final TelegramUserService telegramUserService;
    private final TelegramGameService telegramGameService;
    private final TelegramRoomResolver roomResolver;
    private final I18NService i18NService;
    private final ErrorMessageResolver errorMessageResolver;
    private final GameConfig gameConfig;
    private final BotProperties botProperties;
    private final RoomService roomService;
    private final TelegramAdmins admins;

    /** Interruptor de los envíos masivos; se conmuta con /toggleglobalmessages. */
    private Boolean canSendGlobalMessages = Boolean.TRUE;

    @Autowired
    public CCLHTelegramServiceImpl(@Qualifier("cclhBotMessageService") BotMessageService botMessageService, CAHService cahService, GameService gameService, PlayerService playerService, RoundService roundService, CardService cardService, DictionaryService dictionaryService, UserService userService, TelegramUserService telegramUserService, TelegramGameService telegramGameService, TelegramRoomResolver roomResolver, I18NService i18NService, ErrorMessageResolver errorMessageResolver, GameConfig gameConfig, BotProperties botProperties, RoomService roomService, TelegramAdmins admins) {
        this.botMessageService = botMessageService;
        this.cahService = cahService;
        this.gameService = gameService;
        this.playerService = playerService;
        this.roundService = roundService;
        this.cardService = cardService;
        this.dictionaryService = dictionaryService;
        this.userService = userService;
        this.telegramUserService = telegramUserService;
        this.telegramGameService = telegramGameService;
        this.roomResolver = roomResolver;
        this.i18NService = i18NService;
        this.errorMessageResolver = errorMessageResolver;
        this.gameConfig = gameConfig;
        this.botProperties = botProperties;
        this.roomService = roomService;
        this.admins = admins;
    }

    // ///////////// Usuario //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void registerUser(org.telegram.telegrambots.meta.api.objects.User from) {
        try {
            telegramUserService.register(from);

            botMessageService.sendMessage(from.getId(), i18NService.get("PLAYER_WELCOME", from.getLanguageCode()));
        } catch (UserAlreadyExistsException e) {
            logger.warn("El usuario {} ya estaba registrado en el otro bot.", from.getId());

            botMessageService.sendMessage(from.getId(), i18NService.get("PLAYER_WELCOME", from.getLanguageCode()));
        }
    }

    @Override
    public void loginUser(long telegramId) {
        requireSession();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void changeUserLanguageMessage() {
        requireSession();

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboardBuilder = InlineKeyboardMarkup.builder();
        for (Lang lang : i18NService.getLanguages()) {
            keyboardBuilder.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(lang.getName()).callbackData("change_user_lang__" + lang.getId()).build()));
        }

        botMessageService.sendMessage(chatId(), i18NService.get("USER_LANG_CHANGE"), keyboardBuilder.build());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void changeUserLanguage(int messageId, String lang) {
        User user = requireSession();

        userService.setLanguage(user, i18NService.getLanguage(lang));

        botMessageService.deleteMessage(chatId(), messageId);
        botMessageService.sendMessage(chatId(), i18NService.get("USER_LANG_CHANGED"));
    }

    // ///////////// Creación //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void startCreatingGame(long chatId, String chatTitle) {
        requireSession();

        long creatorChatId = chatId();
        String creating = i18NService.get("GAME_CREATING");

        // La partida no se crea hasta tener los identificadores de los tres mensajes, porque son los
        // que luego se editan según avanza. Se piden encadenados y sin bloquear el hilo que atiende
        // los updates; la sesión se lleva a la continuación, que corre en otro hilo.
        TelegramSession session = TelegramSession.capture();

        botMessageService.sendMessageAsync(chatId, creating).thenCompose(group -> botMessageService.sendMessageAsync(creatorChatId, creating).thenCompose(creatorMessage -> botMessageService.sendMessageAsync(creatorChatId, i18NService.get("PLAYER_JOINING")).thenAccept(playerMessage -> session.run(() -> createGame(chatId, chatTitle, group.getMessageId(), creatorMessage.getMessageId(), playerMessage.getMessageId()))))).exceptionally(e -> {
            logger.error("No se ha podido crear la partida en el chat {}: {}", chatId, e.getMessage(), e);

            return null;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = ApplicationException.class)
    protected void createGame(long chatId, String chatTitle, int groupMessageId, int creatorMessageId, int playerMessageId) {
        try {
            Room room = roomResolver.resolveRoom(chatId, chatTitle);

            Game game = cahService.createGame(room);

            TelegramGame telegramGame = telegramGameService.create(game, groupMessageId, creatorMessageId);
            telegramGameService.createPlayer(playerOf(game, requireSession()), playerMessageId);

            sendMainMenu(telegramGame);
            sendCreatorPrivateMenu(telegramGame);

            botMessageService.editMessage(chatId(), playerMessageId, i18NService.get("PLAYER_JOINED"));
        } catch (ApplicationException e) {
            logger.error("No se ha podido crear la partida en el chat {}: {}", chatId, e.getMessage());

            String message = errorMessageResolver.resolve(e);
            botMessageService.editMessage(chatId, groupMessageId, message);
            botMessageService.editMessage(chatId(), creatorMessageId, message);
        }
    }

    // ///////////// Configuración //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameMenuQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> sendMainMenu(getGameAndCheckCreator(chatId)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameConfigureQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> sendConfigMenu(getGameAndCheckCreator(chatId)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameSelectModeQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);

            editGroupMessage(telegramGame, getGameCreatedGroupMessage(telegramGame) + i18NService.get("GAME_SELECT_MODE"), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("GAME_MODE_DEMOCRACY", "game_change_mode__0"))).keyboardRow(new InlineKeyboardRow(button("GAME_MODE_CLASSIC", "game_change_mode__1"))).keyboardRow(new InlineKeyboardRow(button("GAME_MODE_DICTATORSHIP", "game_change_mode__2"))).keyboardRow(new InlineKeyboardRow(button("GO_BACK", "game_configure"))).build());
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameSelectPunctuationModeQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);

            editGroupMessage(telegramGame, getGameCreatedGroupMessage(telegramGame) + i18NService.get("GAME_PUNCTUATION_MODE"), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("GAME_TYPE_ROUNDS", "game_sel_n_rounds"))).keyboardRow(new InlineKeyboardRow(button("GAME_TYPE_POINTS", "game_sel_n_points"))).keyboardRow(new InlineKeyboardRow(button("GO_BACK", "game_configure"))).build());
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameSelectNRoundsToEndQuery(long chatId, String callbackQueryId) {
        selectNumber(chatId, "game_change_max_rounds__", 1, 9, "GAME_TYPE_ROUNDS_SELECT");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameSelectNPointsToWinQuery(long chatId, String callbackQueryId) {
        selectNumber(chatId, "game_change_max_points__", 1, 9, "GAME_TYPE_POINTS_SELECT");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameSelectMaxPlayersQuery(long chatId, String callbackQueryId) {
        selectNumber(chatId, "game_change_max_players__", gameConfig.getDefaultMinNumberOfPlayers(), gameConfig.getDefaultMaxNumberOfPlayers(), "GAME_SELECT_MAX_PLAYERS");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameSelectDictionaryQuery(long chatId, String callbackQueryId, String page) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);

            int pageNumber = Integer.parseInt(page);
            int perPage = botProperties.getDictionariesPerPage();
            long total = dictionaryService.getDictionaryCountForTable(user);
            List<Dictionary> dictionaries = dictionaryService.getDictionariesPaginatedForTable(user, (pageNumber - 1) * perPage, perPage);

            InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
            if (pageNumber > 1) {
                keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text("⬅").callbackData("game_sel_dictionary__" + (pageNumber - 1)).build()));
            }
            for (Dictionary dictionary : dictionaries) {
                keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(dictionary.getName()).callbackData("game_change_dictionary__" + dictionary.getId()).build()));
            }
            if (total > (long) pageNumber * perPage) {
                keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text("➡").callbackData("game_sel_dictionary__" + (pageNumber + 1)).build()));
            }
            keyboard.keyboardRow(new InlineKeyboardRow(button("GO_BACK", "game_configure")));

            editGroupMessage(telegramGame, getGameCreatedGroupMessage(telegramGame) + i18NService.get("GAME_DICTIONARY_SELECT"), keyboard.build());
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameChangeMode(long chatId, String callbackQueryId, String data) {
        changeSetting(chatId, game -> cahService.setVotationMode(game.getRoom(), VotationModeEnum.values()[Integer.parseInt(data)]));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameChangeDictionary(long chatId, String callbackQueryId, String data) {
        changeSetting(chatId, game -> cahService.setDictionary(game.getRoom(), dictionaryService.getDictionaryById(UUID.fromString(data))));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameChangeMaxPlayers(long chatId, String callbackQueryId, String data) {
        changeSetting(chatId, game -> cahService.setMaxNumberOfPlayers(game.getRoom(), Integer.parseInt(data)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameChangeNRoundsToEnd(long chatId, String callbackQueryId, String data) {
        changeSetting(chatId, game -> cahService.setNumberOfRoundsToEnd(game.getRoom(), Integer.parseInt(data)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameChangeNCardsToWin(long chatId, String callbackQueryId, String data) {
        changeSetting(chatId, game -> cahService.setNumberOfPointsToWin(game.getRoom(), Integer.parseInt(data)));
    }

    // ///////////// Jugadores y arranque //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameJoinQuery(long chatId, String callbackQueryId) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByChatId(chatId);

            if (Objects.equals(telegramGame.getGame().getCreator().getId(), user.getId())) {
                botMessageService.answerCallbackQuery(callbackQueryId, i18NService.get("ERROR_PLAYER_ALREADY_JOINED"));
                return;
            }

            // El mensaje privado del jugador se envía antes de unirle, porque su identificador es lo
            // que hace falta para luego editarlo con su mano de cartas.
            long playerChatId = chatId();
            TelegramSession session = TelegramSession.capture();

            botMessageService.sendMessageAsync(playerChatId, i18NService.get("PLAYER_JOINING")).thenAccept(joining -> session.run(() -> joinGame(chatId, joining.getMessageId(), callbackQueryId))).exceptionally(e -> {
                logger.error("No se ha podido unir al jugador {}: {}", playerChatId, e.getMessage(), e);

                return null;
            });
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = ApplicationException.class)
    protected void joinGame(long chatId, int playerMessageId, String callbackQueryId) {
        guarded(() -> {
            TelegramGame telegramGame = getGameByChatId(chatId);

            Game game = cahService.addPlayer(telegramGame.getGame().getRoom());

            telegramGameService.createPlayer(playerOf(game, requireSession()), playerMessageId);

            botMessageService.editMessage(chatId(), playerMessageId, i18NService.get("PLAYER_JOINED"), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("GAME_LEAVE", "game_leave"))).build());

            sendMainMenu(telegramGame);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void leaveGame(String callbackQueryId) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);

            // Hay que quedarse con el mensaje antes de que el motor borre al jugador
            TelegramPlayer telegramPlayer = telegramGameService.getByPlayer(playerOf(telegramGame.getGame(), user));
            Integer playerMessageId = telegramPlayer != null ? telegramPlayer.getHandMessageId() : null;

            cahService.leavePlayer(telegramGame.getGame().getRoom());

            if (telegramPlayer != null) telegramGameService.deletePlayer(telegramPlayer);

            botMessageService.answerCallbackQuery(callbackQueryId, i18NService.get("GAME_LEFT"));
            if (playerMessageId != null) botMessageService.deleteMessage(chatId(), playerMessageId);

            sendMainMenu(telegramGame);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameStartQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);

            // startGame arranca ya la primera ronda: el motor no deja la partida a medias
            cahService.startGame(telegramGame.getGame().getRoom());

            sendMainMenu(telegramGame);
            sendRound(telegramGame);
        });
    }

    // ///////////// Ronda //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void playerPlayCardQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);

            // El motor añade la carta y, si ya han jugado todos, pasa la ronda a votación
            Game game = cahService.playCard(telegramGame.getGame().getRoom(), cardOf(data));
            Round round = game.getCurrentRound();

            if (round.getStatus() == RoundStatusEnum.PLAYING) {
                showPlayedCardToItsPlayer(telegramGame, playerOf(game, user));
            } else if (round.getStatus() == RoundStatusEnum.VOTING) {
                openVoting(telegramGame, game, round);
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void playerVoteCardQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);

            // El motor registra el voto y, si ya han votado todos, puntúa y cierra la ronda
            Game game = cahService.voteCard(telegramGame.getGame().getRoom(), cardOf(data));
            Round round = game.getCurrentRound();

            showVoteToItsVoter(telegramGame, playerOf(game, user));

            if (round.getStatus() != RoundStatusEnum.ENDING) return;

            endRound(telegramGame, game, round);
        });
    }

    /**
     * Abre la votación: en democracia votan todos y las cartas se enseñan también en el grupo; en
     * los otros modos decide el presidente de la ronda.
     */
    private void openVoting(TelegramGame telegramGame, Game game, Round round) {
        if (game.getVotationMode() == VotationModeEnum.DEMOCRACY) {
            editRoundMessage(telegramGame, getGameVoteCardMessage(round));

            for (TelegramPlayer telegramPlayer : telegramGameService.getPlayers(game)) {
                sendVoteOptions(telegramGame, telegramPlayer, round);
            }

            return;
        }

        // El anterior se lo mandaba al creador de la partida; quien vota es el presidente de la
        // ronda, que va rotando.
        Player president = round.getRoundPresident();
        if (president == null) {
            logger.error("La ronda {} no tiene presidente", round.getId());
            return;
        }

        TelegramPlayer telegramPresident = telegramGameService.getByPlayer(president);
        if (telegramPresident != null) sendVoteOptions(telegramGame, telegramPresident, round);
    }

    private void endRound(TelegramGame telegramGame, Game game, Round round) {
        PlayedCard winningCard = roundService.getPlayedCardByCard(round, roundService.getMostVotedCard(round));
        if (winningCard == null) {
            logger.error("No se ha encontrado la carta ganadora de la ronda {}", round.getId());
            return;
        }

        editRoundMessage(telegramGame, getGameEndRoundMessage(round, winningCard));

        if (game.getStatus() == GameStatusEnum.STARTED) {
            cahService.nextRound(game);

            sendMainMenu(telegramGame);
            sendRound(telegramGame);
        } else if (game.getStatus() == GameStatusEnum.ENDING) {
            endGame(telegramGame, game);
        }
    }

    private void endGame(TelegramGame telegramGame, Game game) {
        Player winner = cahService.getWinner(game);

        Long groupChatId = telegramGameService.getChatId(game.getRoom());
        if (groupChatId != null && winner != null) {
            botMessageService.sendMessage(groupChatId, MessageFormat.format(i18NService.get("GAME_END_GAME"), winner.getUser().getName(), winner.getPoints()));
        }

        List<PlayerMessage> playerMessages = collectPlayerMessages(game);
        Long creatorChatId = chatIdOf(game.getCreator());
        Integer firstMessageId = telegramGame.getFirstMessageId();
        Integer creatorMessageId = telegramGame.getCreatorMessageId();

        telegramGameService.deleteGameData(game);
        // No deleteGameByCreator: la última ronda la puede cerrar cualquiera, no solo el creador
        cahService.endGame(game);

        for (PlayerMessage playerMessage : playerMessages) {
            botMessageService.deleteMessage(playerMessage.chatId(), playerMessage.messageId());
        }
        if (groupChatId != null) botMessageService.deleteMessage(groupChatId, firstMessageId);
        if (creatorChatId != null) botMessageService.deleteMessage(creatorChatId, creatorMessageId);
    }

    private void showPlayedCardToItsPlayer(TelegramGame telegramGame, Player player) {
        TelegramPlayer telegramPlayer = telegramGameService.getByPlayer(player);
        Long playerChatId = chatIdOf(player.getUser());
        Round round = telegramGame.getGame().getCurrentRound();
        Card playedCard = playedCardOf(round, player);
        if (telegramPlayer == null || playerChatId == null || playedCard == null) return;

        botMessageService.editMessage(playerChatId, telegramPlayer.getHandMessageId(), MessageFormat.format(i18NService.get("PLAYER_SELECTED_CARD"), round.getRoundNumber(), round.getRoundBlackCard().getText(), playedCard.getText()));
    }

    /**
     * Confirma el voto en el privado. El presidente de la ronda no ha jugado carta, así que tiene su
     * propio texto.
     */
    private void showVoteToItsVoter(TelegramGame telegramGame, Player player) {
        TelegramPlayer telegramPlayer = telegramGameService.getByPlayer(player);
        Long playerChatId = chatIdOf(player.getUser());
        Round round = telegramGame.getGame().getCurrentRound();
        Card votedCard = votedCardOf(round, player);
        if (telegramPlayer == null || playerChatId == null || votedCard == null) return;

        Card playedCard = playedCardOf(round, player);
        String message = playedCard != null
                ? MessageFormat.format(i18NService.get("PLAYER_VOTED_CARD"), round.getRoundNumber(), round.getRoundBlackCard().getText(), playedCard.getText(), votedCard.getText())
                : MessageFormat.format(i18NService.get("PLAYER_PRESIDENT_VOTED_CARD"), round.getRoundNumber(), round.getRoundBlackCard().getText(), votedCard.getText());

        botMessageService.editMessage(playerChatId, telegramPlayer.getHandMessageId(), message);
    }

    /**
     * Enseña a un jugador las cartas que puede votar, que son todas menos la suya. El presidente de
     * la ronda no ha jugado carta, así que tiene su propio texto.
     */
    private void sendVoteOptions(TelegramGame telegramGame, TelegramPlayer telegramPlayer, Round round) {
        Player player = telegramPlayer.getPlayer();

        Long playerChatId = chatIdOf(player.getUser());
        if (playerChatId == null) return;

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
        for (PlayedCard playedCard : round.getPlayedCards()) {
            if (Objects.equals(playedCard.getPlayer().getId(), player.getId())) continue;

            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(playedCard.getCard().getText()).callbackData("vote_card__" + playedCard.getCard().getId()).build()));
        }

        Card playedCard = playedCardOf(round, player);
        String message = playedCard != null
                ? MessageFormat.format(i18NService.get("PLAYER_VOTE_CARD"), round.getRoundNumber(), round.getRoundBlackCard().getText(), playedCard.getText())
                : MessageFormat.format(i18NService.get("PLAYER_PRESIDENT_VOTE_CARD"), round.getRoundNumber(), round.getRoundBlackCard().getText());

        botMessageService.editMessage(playerChatId, telegramPlayer.getHandMessageId(), message, keyboard.build());
    }

    /**
     * La carta que ha jugado {@code player} en la ronda, o {@code null}. Sale de la ronda y no de
     * {@code Player.getPlayedCard()}, que el motor no rellena nunca.
     */
    private Card playedCardOf(Round round, Player player) {
        return round.getPlayedCards().stream().filter(playedCard -> Objects.equals(playedCard.getPlayer().getId(), player.getId())).map(PlayedCard::getCard).findFirst().orElse(null);
    }

    /**
     * La carta que ha votado {@code player} en la ronda, o {@code null}. Sale de la ronda y no de
     * {@code Player.getVotedCard()}, que el motor no rellena nunca.
     */
    private Card votedCardOf(Round round, Player player) {
        return round.getVotedCards().stream().filter(votedCard -> Objects.equals(votedCard.getPlayer().getId(), player.getId())).map(org.themarioga.engine.cah.models.game.VotedCard::getCard).findFirst().orElse(null);
    }

    private void editRoundMessage(TelegramGame telegramGame, String message) {
        Long groupChatId = telegramGameService.getChatId(telegramGame.getGame().getRoom());
        if (groupChatId == null || telegramGame.getCurrentRoundMessageId() == null) return;

        botMessageService.editMessage(groupChatId, telegramGame.getCurrentRoundMessageId(), message);
    }

    private Card cardOf(String data) {
        Card card = cardService.getCardById(UUID.fromString(data));
        if (card == null) throw new CardDoesntExistsException();

        return card;
    }

    // ///////////// Borrado //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameDeleteGroupQuery(long chatId, String callbackQueryId) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByChatId(chatId);
            Game game = telegramGame.getGame();

            if (Objects.equals(game.getCreator().getId(), user.getId())) {
                deleteGame(telegramGame);
                return;
            }

            // Quien no es el creador solo puede pedir el borrado, y solo con la partida en marcha.
            // El código anterior llegaba aquí capturando la excepción de "no eres el dueño".
            if (game.getStatus() != GameStatusEnum.STARTED) {
                botMessageService.answerCallbackQuery(callbackQueryId, i18NService.get("ERROR_GAME_ONLY_CREATOR_CAN_DELETE"));
                return;
            }

            cahService.voteForDeletion(game.getRoom());

            botMessageService.answerCallbackQuery(callbackQueryId, i18NService.get("PLAYER_VOTED_DELETION"));

            if (game.getStatus() == GameStatusEnum.DELETING) {
                deleteGame(telegramGame);
            } else {
                sendMainMenu(telegramGame);
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameDeletePrivateQuery(String callbackQueryId) {
        User user = requireSession();

        guarded(() -> deleteGame(getGameByCreator(user)));
    }

    // ///////////// Administración //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteMyGames() {
        User user = requireSession();

        guarded(() -> deleteGame(getGameByCreator(user)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteGameByCreatorUsername(String username) {
        guarded(() -> {
            requireAdmin();

            // El alias se resuelve contra la identidad del motor, normalizando lo que teclee el
            // administrador (con o sin arroba, en cualquier combinación de mayúsculas)
            User creator = userService.getByUsername(TelegramUserUtils.normalizeUsername(username));
            if (creator == null) throw new UserDoesntExistsException();

            deleteGame(getGameByCreator(creator));

            notifyAdmins(MessageFormat.format(i18NService.get("GAME_DELETION_USER"), username));
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteAllGames() {
        guarded(() -> {
            requireAdmin();

            for (TelegramGame telegramGame : telegramGameService.getAll()) {
                // Una partida que falle no puede impedir que se borren las demás: el anterior
                // propagaba la excepción y dejaba el resto sin tocar
                try {
                    Long creatorChatId = chatIdOf(telegramGame.getGame().getCreator());

                    deleteGame(telegramGame);

                    if (creatorChatId != null) {
                        botMessageService.sendMessage(creatorChatId, i18NService.get("GAME_DELETION_FORCED"));
                    }
                } catch (ApplicationException e) {
                    logger.error("No se ha podido borrar la partida {}: {}", telegramGame.getGame().getId(), e.getMessage(), e);
                }
            }

            notifyAdmins(i18NService.get("GAME_DELETION_ALL"));
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void sendMessageToEveryone(String message) {
        guarded(() -> {
            requireAdmin();

            if (Boolean.FALSE.equals(canSendGlobalMessages)) {
                logger.info("Los mensajes globales están desactivados");

                notifyAdmins(i18NService.get("GAME_GLOBAL_MESSAGES_OFF"));

                return;
            }

            // El texto se mide antes de empezar, y no por pulcritud: un envío que Telegram rechaza
            // marca ese chat como inactivo, así que un mensaje vacío —/sendmessagetoeveryone sin
            // texto— o más largo de la cuenta daría de baja a toda la base de datos de una tacada
            if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
                logger.warn("Difusión rechazada: el mensaje mide {} caracteres", message == null ? 0 : message.length());

                notifyAdmins(i18NService.get("ERROR_MESSAGE_TOO_LONG"));

                return;
            }

            for (User user : userService.getAllUsers()) {
                Long userChatId = chatIdOf(user);
                if (userChatId == null) continue;

                sendGlobalMessage(userChatId, message, active -> userService.setActive(user, active));
            }

            for (Room room : roomService.getAllRooms()) {
                Long roomChatId = telegramGameService.getChatId(room);
                if (roomChatId == null) continue;

                sendGlobalMessage(roomChatId, message, active -> roomService.setActive(room, active));
            }

            notifyAdmins(i18NService.get("ALL_MESSAGES_SENT"));
        });
    }

    /**
     * Envía y, según el resultado, marca al destinatario como activo o inactivo: si Telegram
     * rechaza el envío es que el usuario bloqueó al bot o el bot ya no está en el grupo.
     */
    private void sendGlobalMessage(long chatId, String message, java.util.function.Consumer<Boolean> setActive) {
        TelegramSession session = TelegramSession.capture();

        botMessageService.sendMessageAsync(chatId, message).thenAccept(sent -> session.run(() -> setActive.accept(true))).exceptionally(e -> {
            logger.warn("Desactivando el chat {} tras fallar el envío: {}", chatId, e.getMessage());

            session.run(() -> setActive.accept(false));

            return null;
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void toggleGlobalMessages() {
        guarded(() -> {
            requireAdmin();

            canSendGlobalMessages = !canSendGlobalMessages;

            notifyAdmins(i18NService.get(Boolean.TRUE.equals(canSendGlobalMessages) ? "GAME_GLOBAL_MESSAGES_ON" : "GAME_GLOBAL_MESSAGES_OFF"));
        });
    }

    private void notifyAdmins(String message) {
        for (Long adminChatId : admins.getIds()) {
            botMessageService.sendMessage(adminChatId, message);
        }
    }

    /**
     * Va <b>dentro</b> del {@code guarded}, no delante: si no, la excepción se escapa a la capa de
     * arriba, que solo la apunta en el log, y quien ha tecleado el comando no ve nada.
     */
    private void requireAdmin() {
        requireSession();

        if (!SecurityUtils.isAdmin()) throw new GameOnlyCreatorCanPerformActionException();
    }

    // ///////////// Ayuda //////////////////

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void sendHelpMessage(long chatId) {
        BotProperties.Bot bot = botProperties.getGame();

        botMessageService.sendMessage(chatId, MessageFormat.format(i18NService.get("GAME_HELP"), bot.getDisplayName() + " (" + bot.getAlias() + ")", bot.getVersion(), bot.getHelpUrl(), bot.getOwnerAlias()));
    }

    // ///////////// Flujos compartidos //////////////////

    private void selectNumber(long chatId, String callbackPrefix, int from, int to, String messageTag) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);

            InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
            InlineKeyboardRow row = new InlineKeyboardRow();
            for (int number = from; number <= to; number++) {
                row.add(InlineKeyboardButton.builder().text(String.valueOf(number)).callbackData(callbackPrefix + number).build());

                // De tres en tres, que es como caben en la pantalla de un móvil
                if (row.size() == 3) {
                    keyboard.keyboardRow(row);
                    row = new InlineKeyboardRow();
                }
            }
            if (!row.isEmpty()) keyboard.keyboardRow(row);
            keyboard.keyboardRow(new InlineKeyboardRow(button("GO_BACK", "game_configure")));

            editGroupMessage(telegramGame, getGameCreatedGroupMessage(telegramGame) + i18NService.get(messageTag), keyboard.build());
        });
    }

    private void changeSetting(long chatId, java.util.function.UnaryOperator<Game> change) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);

            change.apply(telegramGame.getGame());

            sendConfigMenu(telegramGame);
        });
    }

    // ///////////// Menús //////////////////

    private void sendMainMenu(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
        if (game.getStatus() == GameStatusEnum.CREATED) {
            if (game.getPlayers().size() < game.getMaxNumberOfPlayers()) {
                keyboard.keyboardRow(new InlineKeyboardRow(button("GAME_JOIN_BUTTON", "game_join")));
            }

            keyboard.keyboardRow(new InlineKeyboardRow(button("GAME_CONFIGURE_BUTTON", "game_configure")));

            if (game.getPlayers().size() >= gameConfig.getDefaultMinNumberOfPlayers()) {
                keyboard.keyboardRow(new InlineKeyboardRow(button("GAME_START_BUTTON", "game_start")));
            }
        }
        keyboard.keyboardRow(new InlineKeyboardRow(button("GAME_DELETE_BUTTON", "game_delete_group")));

        String message = getGameCreatedGroupMessage(telegramGame);
        if (game.getStatus() != GameStatusEnum.STARTED) {
            if (game.getPlayers().size() > 1) {
                message += "\n\n" + getCurrentPlayerNumberMessage(telegramGame);
            }
        } else if (!game.getDeletionVotes().isEmpty()) {
            message += "\n\n" + getCurrentVoteDeletionNumberMessage(telegramGame);
        }

        editGroupMessage(telegramGame, message, keyboard.build());
    }

    private void sendCreatorPrivateMenu(TelegramGame telegramGame) {
        Long creatorChatId = chatIdOf(telegramGame.getGame().getCreator());
        if (creatorChatId == null) return;

        botMessageService.editMessage(creatorChatId, telegramGame.getCreatorMessageId(), i18NService.get("PLAYER_CREATED_GAME"), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("GAME_DELETE_BUTTON", "game_delete_private"))).build());
    }

    private void sendConfigMenu(TelegramGame telegramGame) {
        editGroupMessage(telegramGame, getGameCreatedGroupMessage(telegramGame), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("GAME_CHANGE_GAME_MODE", "game_sel_mode"))).keyboardRow(new InlineKeyboardRow(button("GAME_CHANGE_PUNCTUATION_MODE", "game_sel_point_type"))).keyboardRow(new InlineKeyboardRow(button("GAME_CHANGE_DICTIONARY", "game_sel_dictionary__1"))).keyboardRow(new InlineKeyboardRow(button("GAME_CHANGE_MAX_N_PLAYERS", "game_sel_max_players"))).keyboardRow(new InlineKeyboardRow(button("GO_BACK", "game_menu"))).build());
    }

    /**
     * Pinta la ronda en curso: la carta negra en el grupo y, en el privado de cada jugador, su mano
     * con un botón por carta.
     */
    private void sendRound(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();
        Round round = game.getCurrentRound();

        if (round == null || round.getStatus() != RoundStatusEnum.PLAYING) {
            logger.error("La ronda de la partida {} no está en juego", game.getId());
            return;
        }

        Long groupChatId = telegramGameService.getChatId(game.getRoom());
        if (groupChatId != null) {
            TelegramSession session = TelegramSession.capture();

            botMessageService.sendMessageAsync(groupChatId, MessageFormat.format(i18NService.get("GAME_SELECT_CARD"), round.getRoundNumber(), round.getRoundBlackCard().getText())).thenAccept(blackCard -> session.run(() -> telegramGameService.setCurrentRoundMessageId(telegramGame, blackCard.getMessageId()))).exceptionally(e -> {
                logger.error("No se ha podido enviar la carta negra: {}", e.getMessage(), e);

                return null;
            });
        }

        for (TelegramPlayer telegramPlayer : telegramGameService.getPlayers(game)) {
            Player player = telegramPlayer.getPlayer();

            // En democracia juegan todos; en el resto de modos, el presidente de la ronda no juega
            if (game.getVotationMode() != VotationModeEnum.DEMOCRACY && round.getRoundPresident() != null && Objects.equals(round.getRoundPresident().getId(), player.getId())) {
                continue;
            }

            Long playerChatId = chatIdOf(player.getUser());
            if (playerChatId == null) continue;

            InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
            for (PlayerHandCard handCard : player.getHand()) {
                keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(handCard.getCard().getText()).callbackData("play_card__" + handCard.getCard().getId()).build()));
            }

            botMessageService.editMessage(playerChatId, telegramPlayer.getHandMessageId(), MessageFormat.format(i18NService.get("PLAYER_SELECT_CARD"), round.getRoundNumber(), round.getRoundBlackCard().getText()), keyboard.build());
        }
    }

    /**
     * Borra la partida y limpia los mensajes que dejó por los chats.
     * <p>
     * Los identificadores se recogen <b>antes</b> de que el motor borre la partida: después, las
     * relaciones ya no se pueden recorrer.
     */
    private void deleteGame(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();
        boolean started = game.getStatus() == GameStatusEnum.STARTED;

        List<PlayerMessage> playerMessages = collectPlayerMessages(game);
        Long groupChatId = telegramGameService.getChatId(game.getRoom());
        Long creatorChatId = chatIdOf(game.getCreator());
        Integer firstMessageId = telegramGame.getFirstMessageId();
        Integer creatorMessageId = telegramGame.getCreatorMessageId();
        Integer roundMessageId = telegramGame.getCurrentRoundMessageId();

        telegramGameService.deleteGameData(game);
        cahService.deleteGameByCreator(game.getRoom());

        for (PlayerMessage playerMessage : playerMessages) {
            botMessageService.deleteMessage(playerMessage.chatId(), playerMessage.messageId());
        }
        if (started && groupChatId != null && roundMessageId != null) {
            botMessageService.deleteMessage(groupChatId, roundMessageId);
        }

        String deleted = i18NService.get("GAME_DELETED");
        if (groupChatId != null) botMessageService.editMessage(groupChatId, firstMessageId, deleted);
        if (creatorChatId != null) botMessageService.editMessage(creatorChatId, creatorMessageId, deleted);
    }

    private record PlayerMessage(long chatId, int messageId) {
    }

    private List<PlayerMessage> collectPlayerMessages(Game game) {
        return telegramGameService.getPlayers(game).stream().map(telegramPlayer -> {
            Long playerChatId = chatIdOf(telegramPlayer.getPlayer().getUser());

            return playerChatId != null ? new PlayerMessage(playerChatId, telegramPlayer.getHandMessageId()) : null;
        }).filter(Objects::nonNull).toList();
    }

    /**
     * Edita el mensaje de la partida en el grupo. El chat no está en la entidad del motor: hay que
     * resolverlo por la tabla de equivalencias.
     */
    private void editGroupMessage(TelegramGame telegramGame, String message, InlineKeyboardMarkup keyboard) {
        Long groupChatId = telegramGameService.getChatId(telegramGame.getGame().getRoom());
        if (groupChatId == null) {
            logger.error("La sala {} no tiene chat de Telegram asociado", telegramGame.getGame().getRoom().getId());
            return;
        }

        botMessageService.editMessage(groupChatId, telegramGame.getFirstMessageId(), message, keyboard);
    }

    private InlineKeyboardButton button(String textTag, String callbackData) {
        return InlineKeyboardButton.builder().text(i18NService.get(textTag)).callbackData(callbackData).build();
    }

    // ///////////// Sesión, partida y permisos //////////////////

    private long chatId() {
        Long telegramId = TelegramSecurityUtils.getTelegramId();
        if (telegramId == null) throw new UserDoesntExistsException();

        return telegramId;
    }

    private User requireSession() {
        User user = SecurityUtils.getUser();
        if (user == null) throw new UserDoesntExistsException();

        return user;
    }

    /**
     * Chat privado de un usuario, o {@code null} si no lo tiene (por ejemplo si viene de otra
     * plataforma).
     */
    private Long chatIdOf(User user) {
        TelegramUser telegramUser = telegramUserService.getByUser(user);
        if (telegramUser == null) {
            logger.warn("El usuario {} no tiene chat de Telegram asociado", user.getId());
            return null;
        }

        return telegramUser.getId();
    }

    private TelegramGame getGameByChatId(long chatId) {
        Room room = roomResolver.resolveRoom(chatId, null);

        Game game = gameService.getByRoom(room);
        if (game == null) throw new GameDoesntExistsException();

        TelegramGame telegramGame = telegramGameService.getByGame(game);
        if (telegramGame == null) throw new GameDoesntExistsException();

        return telegramGame;
    }

    private TelegramGame getGameByCreator(User creator) {
        TelegramGame telegramGame = telegramGameService.getByCreator(creator);
        if (telegramGame == null) throw new GameDoesntExistsException();

        return telegramGame;
    }

    private TelegramGame getGameByPlayer(User user) {
        Player player = playerService.findByUser(user);
        if (player == null) throw new PlayerDoesntExistsException();

        TelegramGame telegramGame = telegramGameService.getByGame(player.getGame());
        if (telegramGame == null) throw new GameDoesntExistsException();

        return telegramGame;
    }

    private Player playerOf(Game game, User user) {
        Player player = playerService.findPlayerByGameAndUser(game, user);
        if (player == null) throw new PlayerDoesntExistsException();

        return player;
    }

    private TelegramGame getGameAndCheckCreator(long chatId) {
        TelegramGame telegramGame = getGameByChatId(chatId);

        if (!Objects.equals(telegramGame.getGame().getCreator().getId(), requireSession().getId()))
            throw new GameOnlyCreatorCanPerformActionException();

        return telegramGame;
    }

    // ///////////// Errores //////////////////

    /**
     * Ejecuta la acción y, si el motor la rechaza, se lo cuenta al usuario.
     * <p>
     * Si el update venía de un botón se contesta a la propia pulsación, que es como avisa este bot
     * sin ensuciar el grupo con mensajes de error; si venía de un comando, con un mensaje normal.
     */
    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (ApplicationException e) {
            String message = errorMessageResolver.resolve(e);

            String callbackQueryId = TelegramSecurityUtils.getCallbackQueryId();
            if (callbackQueryId != null) {
                botMessageService.answerCallbackQuery(callbackQueryId, message);
            } else {
                botMessageService.sendMessage(chatId(), message);
            }
        }
    }

    // ///////////// Textos //////////////////

    private String getGameCreatedGroupMessage(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();

        StringBuilder message = new StringBuilder(i18NService.get("GAME_CREATED_GROUP"));

        message.append("\n").append(MessageFormat.format(i18NService.get("GAME_SELECTED_MODE"), i18NService.get(votationModeTag(game.getVotationMode()))));

        message.append("\n").append(MessageFormat.format(i18NService.get("GAME_SELECTED_DICTIONARY"), game.getDictionary().getName()));

        message.append("\n").append(game.getPunctuationMode() == PunctuationModeEnum.ROUNDS ? MessageFormat.format(i18NService.get("GAME_SELECTED_ROUNDS_TO_END"), game.getNumberOfRoundsToEnd()) : MessageFormat.format(i18NService.get("GAME_SELECTED_POINTS_TO_WIN"), game.getNumberOfPointsToWin()));

        message.append("\n").append(MessageFormat.format(i18NService.get("GAME_SELECTED_MAX_PLAYER_NUMBER"), game.getMaxNumberOfPlayers()));

        return message.toString();
    }

    /**
     * El original metía los nombres de los jugadores dentro de la cadena de formato y luego la
     * pasaba por {@code MessageFormat}, así que un nombre con llaves rompía el mensaje. Aquí se
     * formatea primero y se añaden los nombres después.
     */
    private String getGameVoteCardMessage(Round round) {
        StringBuilder playedCards = new StringBuilder();
        for (PlayedCard playedCard : round.getPlayedCards()) {
            playedCards.append("<b>").append(playedCard.getCard().getText()).append("</b>").append("\n");
        }

        return MessageFormat.format(i18NService.get("GAME_VOTE_CARD"), round.getRoundNumber(), round.getRoundBlackCard().getText(), playedCards);
    }

    private String getGameEndRoundMessage(Round round, PlayedCard winningCard) {
        StringBuilder playedCards = new StringBuilder();
        for (PlayedCard playedCard : round.getPlayedCards()) {
            playedCards.append("<b>").append(playedCard.getCard().getText()).append("</b>").append(" - ").append(playedCard.getPlayer().getUser().getName()).append("\n");
        }

        StringBuilder points = new StringBuilder();
        for (Player player : round.getGame().getPlayers()) {
            points.append("<b>").append(player.getUser().getName()).append("</b>").append(": ").append(player.getPoints()).append("\n");
        }

        return MessageFormat.format(i18NService.get("GAME_END_ROUND"), winningCard.getPlayer().getUser().getName(), winningCard.getCard().getText(), round.getRoundNumber(), round.getRoundBlackCard().getText(), playedCards, points);
    }

    private String getCurrentPlayerNumberMessage(TelegramGame telegramGame) {
        StringBuilder message = new StringBuilder(
                MessageFormat.format(i18NService.get("GAME_CREATED_CURRENT_PLAYER_NUMBER"), telegramGame.getGame().getPlayers().size()));

        for (Player player : telegramGame.getGame().getPlayers()) {
            message.append("\n").append(player.getUser().getName());
        }

        return message.toString();
    }

    private String getCurrentVoteDeletionNumberMessage(TelegramGame telegramGame) {
        return MessageFormat.format(i18NService.get("GAME_CREATED_CURRENT_VOTE_DELETION_NUMBER"), telegramGame.getGame().getDeletionVotes().size());
    }

    private static String votationModeTag(VotationModeEnum mode) {
        return switch (mode) {
            case DEMOCRACY -> "GAME_MODE_DEMOCRACY";
            case CLASSIC -> "GAME_MODE_CLASSIC";
            case DICTATORSHIP -> "GAME_MODE_DICTATORSHIP";
        };
    }

    private static String punctuationModeTag(PunctuationModeEnum mode) {
        return mode == PunctuationModeEnum.ROUNDS ? "GAME_TYPE_ROUNDS" : "GAME_TYPE_POINTS";
    }

}
