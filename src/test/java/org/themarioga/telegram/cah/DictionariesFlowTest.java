package org.themarioga.telegram.cah;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.themarioga.commons.engine.models.User;
import org.themarioga.engine.cah.enums.CardTypeEnum;
import org.themarioga.engine.cah.models.dictionaries.Dictionary;
import org.themarioga.engine.cah.services.intf.dictionaries.CardService;
import org.themarioga.engine.cah.services.intf.dictionaries.DictionaryService;
import org.themarioga.telegram.cah.dictionaries.service.intf.DictionariesTelegramService;
import org.themarioga.telegram.cah.support.BotFlowTest;
import org.themarioga.telegram.cah.support.RecordingBotMessageService;

import java.util.List;

/**
 * Ejercita el bot de diccionarios contra la base de datos. Hasta ahora la lógica portada solo se
 * había compilado.
 */
class DictionariesFlowTest extends BotFlowTest {

    private static final long OWNER = 100L;

    @Autowired
    private DictionariesTelegramService dictionaries;
    @Autowired
    private DictionaryService dictionaryService;
    @Autowired
    private CardService cardService;
    @Autowired
    private org.themarioga.commons.engine.services.intf.I18NService i18NService;
    private final RecordingBotMessageService messages = DICTIONARIES_MESSAGES;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = givenRegisteredUser(OWNER, "duenyo");
        messages.clear();
    }

    @Test
    void registeringGreetsTheUser() {
        messages.clear();
        dictionaries.registerUser(telegramUser(200L, "otro"));

        Assertions.assertEquals(1, messages.sentTo(200L).size());
        Assertions.assertFalse(messages.lastTo(200L).text().startsWith("PLAYER_"), "el texto no está traducido");
    }

    @Test
    void theMainMenuOffersItsButtons() {
        dictionaries.mainMenu();

        RecordingBotMessageService.Sent menu = messages.lastTo(OWNER);
        Assertions.assertNotNull(menu);
        Assertions.assertTrue(menu.callbackData().contains("dictionary_create"));
        Assertions.assertTrue(menu.callbackData().contains("dictionary_manage_cards"));
    }

    @Test
    void createsADictionary() {
        dictionaries.createDictionary("Mi diccionario");

        List<Dictionary> mine = dictionaryService.getDictionariesByCreator(owner);
        Assertions.assertEquals(1, mine.size());
        Assertions.assertEquals("Mi diccionario", mine.get(0).getName());
        Assertions.assertEquals(Boolean.FALSE, mine.get(0).getPublished());
    }

    /**
     * El caso que motivó los números cortos: el usuario contesta "1", no un UUID de 36 caracteres.
     */
    @Test
    void listsAreNumberedAndTheNumberResolves() {
        dictionaries.createDictionary("Primero");
        messages.clear();

        dictionaries.renameDictionaryMessage(1);

        RecordingBotMessageService.Sent list = messages.lastTo(OWNER);
        Assertions.assertNotNull(list);
        Assertions.assertTrue(list.text().contains("1 - Primero"), () -> "la lista debería ir numerada: " + list.text());

        messages.clear();
        dictionaries.selectDictionaryToRename(1, "1");

        Assertions.assertEquals("/rename__" + dictionaryNamed("Primero").getId(), messages.pendingReplyFor(OWNER), "el número corto tiene que resolver al diccionario que se enseñó");
    }

    @Test
    void answeringSomethingNotInTheListIsExplained() {
        dictionaries.createDictionary("Primero");
        dictionaries.renameDictionaryMessage(1);
        messages.clear();

        dictionaries.selectDictionaryToRename(1, "99");

        RecordingBotMessageService.Sent answer = messages.lastTo(OWNER);
        Assertions.assertNotNull(answer, "antes no se contestaba nada a un identificador inválido");
        Assertions.assertFalse(answer.text().startsWith("ERROR_"), "el error no está traducido");
    }

    @Test
    void addsCardsAndKeepsAskingForMore() {
        Dictionary dictionary = createDictionaryWith("Con cartas");
        messages.clear();

        dictionaries.addWhiteCard(dictionary.getId(), "Una carta blanca");

        Assertions.assertEquals(1, cardService.countCardsByDictionaryAndType(dictionary, CardTypeEnum.WHITE));
        Assertions.assertEquals("/add_white_card__" + dictionary.getId(), messages.pendingReplyFor(OWNER), "tras añadir una carta debe quedar preparada la siguiente");
    }

    @Test
    void cancellingStopsTheChainAndShowsTheMenu() {
        Dictionary dictionary = createDictionaryWith("Con cartas");
        messages.clear();

        dictionaries.addWhiteCard(dictionary.getId(), ":cancel:");

        Assertions.assertEquals(0, cardService.countCardsByDictionaryAndType(dictionary, CardTypeEnum.WHITE));
        Assertions.assertTrue(messages.lastTo(OWNER).callbackData().contains("add_white_cards__" + dictionary.getId()), "al cancelar vuelve el menú de cartas");
    }

    /**
     * Un diccionario de otro no se puede tocar.
     */
    @Test
    void anotherPersonsDictionaryIsRefused() {
        Dictionary mine = createDictionaryWith("Mío");

        givenRegisteredUser(300L, "intruso");
        messages.clear();

        dictionaries.renameDictionary(mine.getId(), "Robado");

        Assertions.assertEquals("Mío", dictionaryService.getDictionaryById(mine.getId()).getName());
        Assertions.assertNotNull(messages.lastTo(300L), "hay que decirle por qué no puede");
    }

    private Dictionary createDictionaryWith(String name) {
        dictionaries.createDictionary(name);

        return dictionaryNamed(name);
    }

    private Dictionary dictionaryNamed(String name) {
        return dictionaryService.getDictionariesByCreator(owner).stream().filter(d -> d.getName().equals(name)).findFirst().orElseThrow();
    }


    @Test
    void anActionBySomeoneWithoutStartTellsThemToRegister() {
        asUnregisteredUser(700L, 700L, "private", false);

        Assertions.assertThrows(org.themarioga.commons.engine.exceptions.user.UserDoesntExistsException.class, () -> dictionaries.loginUser(700L));
        Assertions.assertEquals(i18NService.get("ERROR_GAME_USER_DOESNT_EXISTS", "es"), messages.lastTo(700L).text());
    }

}
