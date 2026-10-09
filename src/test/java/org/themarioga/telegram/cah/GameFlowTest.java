package org.themarioga.telegram.cah;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.themarioga.commons.engine.enums.GameStatusEnum;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.commons.engine.models.User;
import org.themarioga.engine.cah.enums.CardTypeEnum;
import org.themarioga.engine.cah.enums.RoundStatusEnum;
import org.themarioga.engine.cah.enums.VotationModeEnum;
import org.themarioga.engine.cah.models.dictionaries.Card;
import org.themarioga.engine.cah.models.dictionaries.Dictionary;
import org.themarioga.engine.cah.models.game.Game;
import org.themarioga.engine.cah.models.game.Player;
import org.themarioga.engine.cah.models.game.PlayerHandCard;
import org.themarioga.engine.cah.services.intf.CAHService;
import org.themarioga.engine.cah.services.intf.dictionaries.CardService;
import org.themarioga.engine.cah.services.intf.dictionaries.DictionaryService;
import org.themarioga.engine.cah.config.GameConfig;
import org.themarioga.engine.cah.services.intf.game.GameService;
import org.themarioga.telegram.cah.game.service.intf.CCLHTelegramService;
import org.themarioga.telegram.cah.support.BotFlowTest;
import org.themarioga.telegram.cah.support.RecordingBotMessageService;

import java.util.List;

/**
 * Ejercita el bot de juego contra la base de datos: crear partida en un grupo, unirse, arrancar y
 * jugar una ronda. Es la parte con más riesgo del porte, porque trabaja sobre dos chats a la vez.
 */
class GameFlowTest extends BotFlowTest {

    private static final long GROUP_CHAT = -100500L;
    private static final long CREATOR = 500L;
    private static final long PLAYER_TWO = 501L;
    private static final long PLAYER_THREE = 502L;

    @Autowired
    private CCLHTelegramService game;
    @Autowired
    private CAHService cahService;
    @Autowired
    private GameService gameService;
    @Autowired
    private DictionaryService dictionaryService;
    @Autowired
    private CardService cardService;
    @Autowired
    private GameConfig gameConfig;
    @Autowired
    private org.themarioga.engine.cah.services.intf.game.RoundResultService roundResultService;
    @Autowired
    private org.themarioga.commons.engine.services.intf.UserService userService;
    @Autowired
    private org.themarioga.commons.engine.services.intf.I18NService i18NService;

    private final RecordingBotMessageService messages = CCLH_MESSAGES;

    private User creator;

    @BeforeEach
    void setUp() {
        creator = givenRegisteredUser(CREATOR, "creador");
        givenRegisteredUser(PLAYER_TWO, "segundo");
        givenRegisteredUser(PLAYER_THREE, "tercero");

        givenAPlayableDictionary();

        messages.clear();
    }

    // ///////////// Registro //////////////////

    @Test
    void startWelcomesANewUser() {
        game.registerUser(telegramUser(600L, "nuevo"));

        Assertions.assertEquals(i18NService.get("PLAYER_WELCOME", "es"), messages.lastTo(600L).text());
    }

    @Test
    void startTellsARegisteredUserThatTheyAlreadyExist() {
        game.registerUser(telegramUser(PLAYER_TWO, "segundo"));

        Assertions.assertEquals(i18NService.get("ERROR_USER_ALREADY_REGISTERED", "es"), messages.lastTo(PLAYER_TWO).text());
    }

    @Test
    void startReactivatesADisabledUserAndWelcomesThem() {
        User disabled = telegramUserService.getByTelegramId(PLAYER_TWO).getUser();
        userService.setActive(disabled, false);

        game.registerUser(telegramUser(PLAYER_TWO, "segundo"));

        Assertions.assertTrue(telegramUserService.getByTelegramId(PLAYER_TWO).getUser().getActive(), "/start es lo que se le pide para volver");
        Assertions.assertEquals(i18NService.get("PLAYER_WELCOME", "es"), messages.lastTo(PLAYER_TWO).text());
    }

    @Test
    void aButtonPressedBySomeoneWithoutStartTellsThemToRegister() {
        asUnregisteredUser(700L, GROUP_CHAT, "group", true);

        Assertions.assertThrows(org.themarioga.commons.engine.exceptions.user.UserDoesntExistsException.class, () -> game.loginUser(700L));
        Assertions.assertEquals(List.of(i18NService.get("ERROR_GAME_USER_DOESNT_EXISTS", "es")), messages.answeredCallbacks(), "se le contesta a la propia pulsación");
    }

    @Test
    void aCommandSentBySomeoneWithoutStartTellsThemToRegister() {
        asUnregisteredUser(700L, GROUP_CHAT, "group", false);

        Assertions.assertThrows(org.themarioga.commons.engine.exceptions.user.UserDoesntExistsException.class, () -> game.loginUser(700L));
        Assertions.assertEquals(i18NService.get("ERROR_GAME_USER_DOESNT_EXISTS", "es"), messages.lastTo(GROUP_CHAT).text(), "en el chat donde escribió");
    }

    // ///////////// Partida //////////////////

    @Test
    void creatingAGameWritesToTheGroupAndToTheCreator() {
        createGame();

        Assertions.assertFalse(messages.sentTo(GROUP_CHAT).isEmpty(), "el grupo tiene que enterarse");
        Assertions.assertFalse(messages.sentTo(CREATOR).isEmpty(), "y el creador por privado");

        Game created = gameService.getByRoom(room());
        Assertions.assertNotNull(created);
        Assertions.assertEquals(GameStatusEnum.CREATED, created.getStatus());
        Assertions.assertEquals(1, created.getPlayers().size(), "el creador entra ya como jugador");
    }

    @Test
    void theGroupMenuOffersJoiningAndConfiguring() {
        createGame();

        RecordingBotMessageService.Sent groupMenu = messages.lastTo(GROUP_CHAT);
        Assertions.assertNotNull(groupMenu);
        Assertions.assertTrue(groupMenu.callbackData().contains("game_join"));
        Assertions.assertTrue(groupMenu.callbackData().contains("game_configure"));
        Assertions.assertFalse(groupMenu.callbackData().contains("game_start"), "con un solo jugador todavía no se puede empezar");
    }

    @Test
    void othersJoinAndThenTheGameCanStart() {
        createGame();
        joinAs(PLAYER_TWO);
        joinAs(PLAYER_THREE);

        Assertions.assertEquals(3, gameService.getByRoom(room()).getPlayers().size());

        logInAs(CREATOR, GROUP_CHAT, "group");
        messages.clear();
        game.gameMenuQuery(GROUP_CHAT, "cb");

        Assertions.assertTrue(messages.lastTo(GROUP_CHAT).callbackData().contains("game_start"), "con tres jugadores ya se puede empezar");
    }

    @Test
    void onlyTheCreatorConfiguresTheGame() {
        createGame();
        joinAs(PLAYER_TWO);

        logInAsCallback(PLAYER_TWO, GROUP_CHAT, "group");
        messages.clear();
        game.gameConfigureQuery(GROUP_CHAT, "cb");

        Assertions.assertFalse(messages.answeredCallbacks().isEmpty(), "al que no es creador hay que contestarle a la pulsación");
        Assertions.assertFalse(messages.answeredCallbacks().get(0).startsWith("ERROR_"), "el aviso no está traducido");
    }

    /**
     * El reparto: la carta negra va al grupo y la mano de cada jugador a su privado.
     */
    @Test
    void startingDealsTheBlackCardAndEveryHand() {
        startedGame();

        Game started = gameService.getByRoom(room());
        Assertions.assertEquals(GameStatusEnum.STARTED, started.getStatus());
        Assertions.assertNotNull(started.getCurrentRound());
        Assertions.assertEquals(RoundStatusEnum.PLAYING, started.getCurrentRound().getStatus());

        Assertions.assertFalse(messages.sentTo(GROUP_CHAT).isEmpty(), "la carta negra va al grupo");

        for (long player : List.of(CREATOR, PLAYER_TWO, PLAYER_THREE)) {
            RecordingBotMessageService.Sent hand = messages.lastTo(player);
            Assertions.assertNotNull(hand, () -> "el jugador " + player + " no ha recibido su mano");
            Assertions.assertFalse(hand.callbackData().isEmpty(), "la mano son botones de jugar carta");
            Assertions.assertTrue(hand.callbackData().get(0).startsWith("play_card__"));
        }
    }

    /**
     * En modo democracia juegan todos, incluido el presidente de la ronda.
     */
    @Test
    void aFullRoundIsPlayedAndVoted() {
        Game started = startedGame();

        for (long player : List.of(CREATOR, PLAYER_TWO, PLAYER_THREE)) {
            playFirstCardAs(player);
        }

        Game voting = gameService.getByRoom(room());
        Assertions.assertEquals(RoundStatusEnum.VOTING, voting.getCurrentRound().getStatus(), "cuando juegan todos, la ronda pasa a votación");
        Assertions.assertEquals(3, voting.getCurrentRound().getPlayedCards().size());
    }

    /**
     * Las opciones de voto salían de {@code Player.getPlayedCard()}, que el motor no rellena nunca:
     * no le llegaban a nadie y la partida se quedaba parada en la primera votación.
     */
    @Test
    void everyoneGetsTheVoteOptionsAndTheRoundEnds() {
        startedGame();
        java.util.UUID blackCardId = gameService.getByRoom(room()).getCurrentRound().getRoundBlackCard().getId();

        for (long player : List.of(CREATOR, PLAYER_TWO, PLAYER_THREE)) {
            playFirstCardAs(player);
        }

        for (long player : List.of(CREATOR, PLAYER_TWO, PLAYER_THREE)) {
            Assertions.assertEquals(2, messages.lastTo(player).callbackData().size(), () -> "el jugador " + player + " tiene que poder votar las otras dos cartas");
            voteFirstOptionAs(player);
        }

        Game next = gameService.getByRoom(room());
        Assertions.assertEquals(1, next.getCurrentRound().getRoundNumber(), "con todos los votos, la ronda se cierra y empieza la siguiente");
        Assertions.assertEquals(RoundStatusEnum.PLAYING, next.getCurrentRound().getStatus());
        Assertions.assertEquals(3, roundResultService.getByBlackCardId(blackCardId).size(), "la ronda cerrada deja una fila de histórico por carta");
    }

    /**
     * Al acabar, la partida se borraba con el permiso del creador, pero la última ronda la puede cerrar
     * cualquiera: si el último voto no era suyo, la partida se quedaba colgada en ENDING.
     */
    @Test
    void theGameEndsEvenIfTheLastVoteIsNotTheCreators() {
        createGame();
        joinAs(PLAYER_TWO);
        joinAs(PLAYER_THREE);

        logInAs(CREATOR, GROUP_CHAT, "group");
        cahService.setVotationMode(room(), VotationModeEnum.DEMOCRACY);
        cahService.setNumberOfRoundsToEnd(room(), 1);
        game.gameStartQuery(GROUP_CHAT, "cb");

        for (long player : List.of(CREATOR, PLAYER_TWO, PLAYER_THREE)) {
            playFirstCardAs(player);
        }
        for (long player : List.of(CREATOR, PLAYER_TWO, PLAYER_THREE)) {
            voteFirstOptionAs(player);
        }

        Assertions.assertNull(gameService.getByRoom(room()), "la partida tiene que borrarse al terminar");
    }

    @Test
    void playingTwiceIsRefusedWithAnExplanation() {
        startedGame();

        playFirstCardAs(CREATOR);
        messages.clear();

        logInAsCallback(CREATOR, CREATOR, "private");
        Game current = gameService.getByRoom(room());
        Card alreadyPlayed = current.getCurrentRound().getPlayedCards().get(0).getCard();

        game.playerPlayCardQuery("cb", alreadyPlayed.getId().toString());

        Assertions.assertFalse(messages.answeredCallbacks().isEmpty(), "hay que decirle que ya jugó");
        Assertions.assertFalse(messages.answeredCallbacks().get(0).startsWith("ERROR_"));
    }

    @Test
    void deletingTheGameCleansUpItsMessages() {
        startedGame();
        messages.clear();

        logInAs(CREATOR, GROUP_CHAT, "group");
        game.gameDeleteGroupQuery(GROUP_CHAT, "cb");

        Assertions.assertNull(gameService.getByRoom(room()), "la partida tiene que desaparecer");
        Assertions.assertTrue(messages.deletedFrom().containsAll(List.of(CREATOR, PLAYER_TWO, PLAYER_THREE)), "hay que borrar la mano de cada jugador de su privado");
    }

    // ///////////// Jugadores IA //////////////////

    @Test
    void theCreatorAddsAndRemovesAIPlayers() {
        createGame();
        joinAs(PLAYER_TWO);

        addAIPlayer();

        Game withAi = gameService.getByRoom(room());
        Assertions.assertEquals(3, withAi.getPlayers().size());
        Assertions.assertEquals(1, withAi.getPlayers().stream().filter(Player::isAi).count());
        Assertions.assertEquals("IA 1", withAi.getPlayers().stream().filter(Player::isAi).findFirst().orElseThrow().getUser().getName());

        RecordingBotMessageService.Sent menu = messages.lastTo(GROUP_CHAT);
        Assertions.assertTrue(menu.callbackData().contains("game_remove_ai"));
        Assertions.assertTrue(menu.callbackData().contains("game_start"), "dos humanos y una IA ya pueden empezar");

        logInAsCallback(CREATOR, GROUP_CHAT, "group");
        game.gameRemoveAIPlayerQuery(GROUP_CHAT, "cb");

        Assertions.assertEquals(2, gameService.getByRoom(room()).getPlayers().size());
        Assertions.assertFalse(messages.lastTo(GROUP_CHAT).callbackData().contains("game_remove_ai"));
    }

    @Test
    void onlyTheCreatorAddsAIPlayers() {
        createGame();
        joinAs(PLAYER_TWO);

        logInAsCallback(PLAYER_TWO, GROUP_CHAT, "group");
        messages.clear();
        game.gameAddAIPlayerQuery(GROUP_CHAT, "cb");

        Assertions.assertEquals(2, gameService.getByRoom(room()).getPlayers().size());
        Assertions.assertFalse(messages.answeredCallbacks().isEmpty());
        Assertions.assertFalse(messages.answeredCallbacks().get(0).startsWith("ERROR_"), "el aviso no está traducido");
    }

    /**
     * Un humano con dos IAs llega al mínimo de jugadores, pero no al de humanos: no se ofrece empezar.
     */
    @Test
    void oneHumanAndTwoAIPlayersCannotStart() {
        createGame();
        addAIPlayer();
        addAIPlayer();

        Assertions.assertEquals(3, gameService.getByRoom(room()).getPlayers().size());
        Assertions.assertFalse(messages.lastTo(GROUP_CHAT).callbackData().contains("game_start"));
    }

    /**
     * Partida CLASSIC de tres rondas con el creador, otro humano y una IA: la presidencia pasa por los
     * tres. En las rondas de los humanos el grupo ve las cartas mientras decide el presidente; en la
     * de la IA, la ronda se cierra sola con la jugada del último humano y la partida termina.
     */
    /**
     * Al último en jugar la ronda pasa a votación y, fuera de democracia, solo se le escribe al
     * presidente: su mensaje privado se quedaba con los botones de las cartas.
     */
    @Test
    void theLastPlayerToPickACardLosesTheCardButtons() {
        createGame();
        joinAs(PLAYER_TWO);
        addAIPlayer();

        logInAs(CREATOR, GROUP_CHAT, "group");
        cahService.setVotationMode(room(), VotationModeEnum.CLASSIC);
        game.gameStartQuery(GROUP_CHAT, "cb");

        // Preside el creador y la IA ya ha jugado: el segundo jugador es el último
        messages.clear();
        playFirstCardAs(PLAYER_TWO);

        Assertions.assertEquals(RoundStatusEnum.VOTING, gameService.getByRoom(room()).getCurrentRound().getStatus());
        RecordingBotMessageService.Sent hand = messages.lastTo(PLAYER_TWO);
        Assertions.assertNotNull(hand, "hay que actualizarle el mensaje");
        Assertions.assertTrue(hand.callbackData().isEmpty(), "no puede seguir viendo botones para elegir carta");
    }

    @Test
    void aClassicGameWithAnAIPlayerIsPlayedToTheEnd() {
        createGame();
        joinAs(PLAYER_TWO);
        addAIPlayer();

        logInAs(CREATOR, GROUP_CHAT, "group");
        cahService.setVotationMode(room(), VotationModeEnum.CLASSIC);
        cahService.setNumberOfRoundsToEnd(room(), 3);
        game.gameStartQuery(GROUP_CHAT, "cb");

        // Ronda 0: preside el creador
        Assertions.assertEquals(1, gameService.getByRoom(room()).getCurrentRound().getPlayedCards().size(), "la IA juega en cuanto empieza la ronda");
        messages.clear();
        playFirstCardAs(PLAYER_TWO);

        Game voting = gameService.getByRoom(room());
        Assertions.assertEquals(RoundStatusEnum.VOTING, voting.getCurrentRound().getStatus());
        assertTheGroupSeesThePlayedCards(voting);
        Assertions.assertTrue(messages.lastTo(CREATOR).callbackData().stream().allMatch(data -> data.startsWith("vote_card__")), "el presidente vota por privado");

        voteFirstOptionAs(CREATOR);

        // Ronda 1: preside el otro humano
        Assertions.assertEquals(1, gameService.getByRoom(room()).getCurrentRound().getRoundNumber());
        playFirstCardAs(CREATOR);
        voteFirstOptionAs(PLAYER_TWO);

        // Ronda 2: preside la IA
        Game lastRound = gameService.getByRoom(room());
        Assertions.assertEquals(2, lastRound.getCurrentRound().getRoundNumber());
        Assertions.assertTrue(lastRound.getCurrentRound().getRoundPresident().isAi());

        playFirstCardAs(CREATOR);
        messages.clear();
        playFirstCardAs(PLAYER_TWO);

        Assertions.assertNull(gameService.getByRoom(room()), "la IA ha cerrado la última ronda y con ella la partida");
        Assertions.assertTrue(messages.sentTo(GROUP_CHAT).stream().anyMatch(sent -> !sent.edited()), "el ganador se anuncia en el grupo");
        Assertions.assertTrue(userService.getAllUsers().stream().noneMatch(user -> user.getUsername().startsWith(CAHService.AI_USERNAME_PREFIX)), "el usuario de la IA desaparece con la partida");
    }

    /**
     * En democracia la IA vota en cuanto se abre la votación, y el grupo y los privados enseñan las
     * cartas en el mismo orden.
     */
    @Test
    void inDemocracyTheAIPlayerVotesAndTheOrderIsTheSameEverywhere() {
        createGame();
        joinAs(PLAYER_TWO);
        addAIPlayer();

        logInAs(CREATOR, GROUP_CHAT, "group");
        cahService.setVotationMode(room(), VotationModeEnum.DEMOCRACY);
        game.gameStartQuery(GROUP_CHAT, "cb");

        playFirstCardAs(CREATOR);
        messages.clear();
        playFirstCardAs(PLAYER_TWO);

        Game voting = gameService.getByRoom(room());
        Assertions.assertEquals(RoundStatusEnum.VOTING, voting.getCurrentRound().getStatus());
        Assertions.assertEquals(1, voting.getCurrentRound().getVotedCards().size(), "la IA ya ha votado");

        String groupText = assertTheGroupSeesThePlayedCards(voting);
        for (long player : List.of(CREATOR, PLAYER_TWO)) {
            List<String> options = messages.lastTo(player).buttonTexts();
            List<Integer> positions = options.stream().map(groupText::indexOf).toList();
            Assertions.assertEquals(positions.stream().sorted().toList(), positions, () -> "el privado de " + player + " no sigue el orden del grupo");
        }
    }

    private void addAIPlayer() {
        logInAsCallback(CREATOR, GROUP_CHAT, "group");

        game.gameAddAIPlayerQuery(GROUP_CHAT, "cb");
    }

    private String assertTheGroupSeesThePlayedCards(Game game) {
        RecordingBotMessageService.Sent roundMessage = messages.sentTo(GROUP_CHAT).stream().filter(RecordingBotMessageService.Sent::edited).reduce((first, second) -> second).orElseThrow(() -> new AssertionError("no se ha editado el mensaje de la ronda"));

        for (var playedCard : game.getCurrentRound().getPlayedCards()) {
            Assertions.assertTrue(roundMessage.text().contains(playedCard.getCard().getText()), () -> "falta la carta " + playedCard.getCard().getText());
        }

        return roundMessage.text();
    }

    // ///////////// Apoyo //////////////////

    private void voteFirstOptionAs(long telegramId) {
        logInAs(telegramId, telegramId, "private");

        String option = messages.lastTo(telegramId).callbackData().get(0);
        Assertions.assertTrue(option.startsWith("vote_card__"), "no le han llegado las opciones de voto");

        game.playerVoteCardQuery("cb", option.substring("vote_card__".length()));
    }

    private Room room() {
        return roomResolver.resolveRoom(GROUP_CHAT, "Grupo de pruebas");
    }

    private void createGame() {
        logInAs(CREATOR, GROUP_CHAT, "group");

        game.startCreatingGame(GROUP_CHAT, "Grupo de pruebas");
    }

    private void joinAs(long telegramId) {
        logInAs(telegramId, GROUP_CHAT, "group");

        game.gameJoinQuery(GROUP_CHAT, "cb");
    }

    private Game startedGame() {
        createGame();
        joinAs(PLAYER_TWO);
        joinAs(PLAYER_THREE);

        logInAs(CREATOR, GROUP_CHAT, "group");
        // En democracia juegan todos, que es lo que hace el test predecible
        cahService.setVotationMode(room(), VotationModeEnum.DEMOCRACY);

        messages.clear();
        game.gameStartQuery(GROUP_CHAT, "cb");

        return gameService.getByRoom(room());
    }

    private void playFirstCardAs(long telegramId) {
        logInAs(telegramId, telegramId, "private");

        Game current = gameService.getByRoom(room());
        var player = current.getPlayers().stream().filter(p -> p.getUser().getUsername().equals("u" + telegramId) || p.getUser().getUsername().equals(aliasOf(telegramId))).findFirst().orElseThrow();

        List<PlayerHandCard> hand = player.getHand();
        Assertions.assertFalse(hand.isEmpty(), "el jugador no tiene cartas en la mano");

        game.playerPlayCardQuery("cb", hand.get(0).getCard().getId().toString());
    }

    private String aliasOf(long telegramId) {
        if (telegramId == CREATOR) return "creador";
        if (telegramId == PLAYER_TWO) return "segundo";

        return "tercero";
    }

    /**
     * El motor exige un diccionario publicado con cartas suficientes para poder jugar.
     */
    private void givenAPlayableDictionary() {
        Dictionary dictionary = dictionaryService.create("Diccionario de pruebas", creator);

        for (int i = 0; i < 60; i++) {
            cardService.create(dictionary, CardTypeEnum.WHITE, "Carta blanca " + i);
        }
        for (int i = 0; i < 15; i++) {
            cardService.create(dictionary, CardTypeEnum.BLACK, "Carta negra " + i);
        }

        dictionaryService.togglePublished(dictionary);

        // Es con el que arrancan las partidas que no eligen otro
        gameConfig.setDefaultDictionaryId(dictionary.getId());
    }

}
