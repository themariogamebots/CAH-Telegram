package org.themarioga.telegram.cah;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.commons.engine.models.User;
import org.themarioga.engine.cah.config.GameConfig;
import org.themarioga.engine.cah.enums.CardTypeEnum;
import org.themarioga.engine.cah.models.dictionaries.Dictionary;
import org.themarioga.engine.cah.models.game.Game;
import org.themarioga.engine.cah.services.intf.dictionaries.CardService;
import org.themarioga.engine.cah.services.intf.dictionaries.DictionaryService;
import org.themarioga.engine.cah.services.intf.game.GameService;
import org.themarioga.telegram.cah.game.service.intf.CCLHTelegramService;
import org.themarioga.telegram.cah.services.intf.TelegramGameService;
import org.themarioga.telegram.cah.support.BotFlowTest;
import org.themarioga.telegram.cah.support.RecordingBotMessageService;

import java.util.UUID;

/**
 * Crear partida y unirse, con los envíos asíncronos completándose en otro hilo, como en producción.
 * <p>
 * El resto de flujos corre dentro de la transacción del test y con los envíos completados en el
 * mismo hilo, así que la continuación siempre encontraba una sesión de Hibernate abierta. En
 * producción la continuación corre en un hilo de OkHttp sin transacción, y crear partida y unirse
 * fallaban con {@code LazyInitializationException} sin que ningún test lo viera.
 * <p>
 * Por eso este test no es transaccional: lo que crea se confirma de verdad y se borra al final.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AsyncGameFlowTest extends BotFlowTest {

    private static final long GROUP_CHAT = -100900L;
    private static final long CREATOR = 900L;
    private static final long PLAYER_TWO = 901L;
    private static final long PLAYER_THREE = 902L;

    @Autowired
    private CCLHTelegramService game;
    @Autowired
    private GameService gameService;
    @Autowired
    private DictionaryService dictionaryService;
    @Autowired
    private CardService cardService;
    @Autowired
    private GameConfig gameConfig;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TelegramGameService telegramGameService;

    private final RecordingBotMessageService messages = CCLH_MESSAGES;

    private TransactionTemplate tx;
    private UUID previousDefaultDictionaryId;
    private UUID dictionaryId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        previousDefaultDictionaryId = gameConfig.getDefaultDictionaryId();

        User creator = givenRegisteredUser(CREATOR, "creador_async");
        givenRegisteredUser(PLAYER_TWO, "segundo_async");
        givenRegisteredUser(PLAYER_THREE, "tercero_async");

        dictionaryId = tx.execute(status -> {
            Dictionary dictionary = dictionaryService.create("Diccionario asíncrono", creator);
            for (int i = 0; i < 60; i++) {
                cardService.create(dictionary, CardTypeEnum.WHITE, "Carta blanca asíncrona " + i);
            }
            for (int i = 0; i < 15; i++) {
                cardService.create(dictionary, CardTypeEnum.BLACK, "Carta negra asíncrona " + i);
            }
            dictionaryService.togglePublished(dictionary);

            return dictionary.getId();
        });
        gameConfig.setDefaultDictionaryId(dictionaryId);

        messages.clear();
        messages.completeInBackground(true);
    }

    @AfterEach
    void tearDown() {
        messages.completeInBackground(false);

        logInAs(CREATOR, GROUP_CHAT, "group");
        if (tx.execute(status -> gameService.getByRoom(room())) != null) {
            game.gameDeleteGroupQuery(GROUP_CHAT, "cb");
        }

        tx.executeWithoutResult(status -> {
            entityManager.createQuery("DELETE FROM Card c WHERE c.dictionary.id = :id").setParameter("id", dictionaryId).executeUpdate();
            dictionaryService.delete(dictionaryService.getDictionaryById(dictionaryId));
        });
        gameConfig.setDefaultDictionaryId(previousDefaultDictionaryId);
    }

    @Test
    void creatingAndJoiningWorkWhenTheSendsCompleteInAnotherThread() {
        logInAs(CREATOR, GROUP_CHAT, "group");
        game.startCreatingGame(GROUP_CHAT, "Grupo asíncrono");
        messages.awaitAsync();

        Assertions.assertEquals(1, playersInGame(), "la partida tiene que crearse con su creador");

        logInAs(PLAYER_TWO, GROUP_CHAT, "group");
        game.gameJoinQuery(GROUP_CHAT, "cb");
        messages.awaitAsync();

        Assertions.assertEquals(2, playersInGame(), "el segundo jugador tiene que haber entrado");
        Assertions.assertTrue(messages.lastTo(GROUP_CHAT).text().contains("segundo_async"), "y la lista del grupo tiene que mostrarlo");
    }

    /**
     * Cada paso abre su propia sesión, como cada update en producción: el jugador se carga antes que
     * su partida, que llega como proxy de la clase base de Commons-Engine. Elegir carta fallaba con
     * {@code ClassCastException} al castearla al {@code Game} de CAH.
     */
    @Test
    void aStartedGameRecordsItsRoundMessageAndLetsPlayersPickACard() {
        logInAs(CREATOR, GROUP_CHAT, "group");
        game.startCreatingGame(GROUP_CHAT, "Grupo asíncrono");
        messages.awaitAsync();
        for (long player : new long[]{PLAYER_TWO, PLAYER_THREE}) {
            logInAs(player, GROUP_CHAT, "group");
            game.gameJoinQuery(GROUP_CHAT, "cb");
            messages.awaitAsync();
        }

        logInAs(CREATOR, GROUP_CHAT, "group");
        game.gameStartQuery(GROUP_CHAT, "cb");
        messages.awaitAsync();

        Assertions.assertNotNull(tx.execute(status -> telegramGameService.getByGame(gameService.getByRoom(room())).getCurrentRoundMessageId()), "el mensaje de la carta negra tiene que quedar apuntado");

        // Juega quien no preside la ronda
        long picker = tx.execute(status -> {
            UUID president = gameService.getByRoom(room()).getCurrentRound().getRoundPresident().getUser().getId();
            return telegramUserService.getByTelegramId(PLAYER_TWO).getUser().getId().equals(president) ? PLAYER_THREE : PLAYER_TWO;
        });
        String cardId = tx.execute(status -> {
            UUID user = telegramUserService.getByTelegramId(picker).getUser().getId();
            return gameService.getByRoom(room()).getPlayers().stream().filter(p -> p.getUser().getId().equals(user)).findFirst().orElseThrow().getHand().get(0).getCard().getId().toString();
        });

        logInAs(picker, picker, "private");
        game.playerPlayCardQuery("cb", cardId);
        messages.awaitAsync();

        Assertions.assertEquals(1, (int) tx.execute(status -> gameService.getByRoom(room()).getCurrentRound().getPlayedCards().size()), "la carta tiene que quedar jugada");
    }

    private int playersInGame() {
        Integer players = tx.execute(status -> {
            Game current = gameService.getByRoom(room());
            return current != null ? current.getPlayers().size() : 0;
        });

        return players != null ? players : 0;
    }

    private Room room() {
        return roomResolver.resolveRoom(GROUP_CHAT, "Grupo asíncrono");
    }

}
