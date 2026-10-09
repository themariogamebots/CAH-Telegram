package org.themarioga.telegram.cah.dictionaries.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.themarioga.commons.engine.exceptions.ApplicationException;
import org.themarioga.commons.engine.exceptions.user.UserAlreadyExistsException;
import org.themarioga.commons.engine.exceptions.user.UserDoesntExistsException;
import org.themarioga.commons.engine.models.Lang;
import org.themarioga.commons.engine.models.User;
import org.themarioga.commons.engine.security.SecurityUtils;
import org.themarioga.commons.engine.services.intf.I18NService;
import org.themarioga.commons.engine.services.intf.UserService;
import org.themarioga.commons.engine.util.StringUtils;
import org.themarioga.commons.telegram.config.TelegramAdmins;
import org.themarioga.commons.telegram.models.TelegramUser;
import org.themarioga.commons.telegram.security.TelegramSecurityUtils;
import org.themarioga.commons.telegram.services.impl.SelectionRegistry;
import org.themarioga.commons.telegram.services.intf.BotMessageService;
import org.themarioga.commons.telegram.services.intf.TelegramUserService;
import org.themarioga.commons.telegram.util.TelegramUserUtils;
import org.themarioga.engine.cah.config.DictionariesConfig;
import org.themarioga.engine.cah.enums.CardTypeEnum;
import org.themarioga.engine.cah.exceptions.card.CardDoesntExistsException;
import org.themarioga.engine.cah.exceptions.dictionary.DictionaryAlreadyPublishedException;
import org.themarioga.engine.cah.exceptions.dictionary.DictionaryAlreadySharedException;
import org.themarioga.engine.cah.exceptions.dictionary.DictionaryDoesntExistsException;
import org.themarioga.engine.cah.exceptions.dictionary.DictionaryNotYoursException;
import org.themarioga.engine.cah.models.dictionaries.Card;
import org.themarioga.engine.cah.models.dictionaries.Dictionary;
import org.themarioga.engine.cah.models.dictionaries.DictionaryCollaborator;
import org.themarioga.engine.cah.services.intf.dictionaries.CardService;
import org.themarioga.engine.cah.services.intf.dictionaries.DictionaryService;
import org.themarioga.telegram.cah.config.BotProperties;
import org.themarioga.telegram.cah.config.ErrorMessageResolver;
import org.themarioga.telegram.cah.dictionaries.service.intf.DictionariesTelegramService;
import org.themarioga.telegram.cah.exceptions.SelectionNotFoundException;

import java.text.MessageFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bot de diccionarios.
 * <p>
 * Es el porte de {@code DictionariesBotServiceImpl}, con tres sustituciones que atraviesan todo:
 * <ul>
 * <li>Los identificadores del motor son {@link UUID}, no {@code long}.</li>
 * <li>El chat al que se responde sale de {@link TelegramSecurityUtils#getTelegramId()}. Antes se
 * usaba {@code SecurityUtils.getId()} porque el id del usuario <em>era</em> el de Telegram;
 * ahora son cosas distintas y confundirlas es escribirle a un chat que no existe.</li>
 * <li>Los errores los traduce {@link ErrorMessageResolver} en un único sitio, en vez de una
 * escalera de {@code catch} por excepción repetida en cada método.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "dictionaries.bot", name = "enabled", havingValue = "true")
public class DictionariesTelegramServiceImpl implements DictionariesTelegramService {

    private static final Logger logger = LoggerFactory.getLogger(DictionariesTelegramServiceImpl.class);

    /** Palabra con la que el usuario cancela una cadena de altas de cartas. */
    private static final String CANCEL_KEYWORD = ":cancel:";
    /** Confirmación para borrar un diccionario publicado. */
    private static final String DELETE_CONFIRMATION = "SI";

    private final BotMessageService botMessageService;
    private final DictionaryService dictionaryService;
    private final CardService cardService;
    private final UserService userService;
    private final TelegramUserService telegramUserService;
    private final I18NService i18NService;
    private final ErrorMessageResolver errorMessageResolver;
    private final DictionariesConfig dictionariesConfig;
    private final BotProperties botProperties;
    private final TelegramAdmins admins;
    private final SelectionRegistry selectionRegistry;
    private final String botName;

    @Autowired
    public DictionariesTelegramServiceImpl(@Qualifier("dictionariesBotMessageService") BotMessageService botMessageService, DictionaryService dictionaryService, CardService cardService, UserService userService, TelegramUserService telegramUserService, I18NService i18NService, ErrorMessageResolver errorMessageResolver, DictionariesConfig dictionariesConfig, BotProperties botProperties, TelegramAdmins admins, SelectionRegistry selectionRegistry, @Value("${dictionaries.bot.name}") String botName) {
        this.botMessageService = botMessageService;
        this.dictionaryService = dictionaryService;
        this.cardService = cardService;
        this.userService = userService;
        this.telegramUserService = telegramUserService;
        this.i18NService = i18NService;
        this.errorMessageResolver = errorMessageResolver;
        this.dictionariesConfig = dictionariesConfig;
        this.botProperties = botProperties;
        this.admins = admins;
        this.selectionRegistry = selectionRegistry;
        this.botName = botName;
    }

    // ///////////// Usuario //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void registerUser(org.telegram.telegrambots.meta.api.objects.User from) {
        try {
            telegramUserService.register(from);

            botMessageService.sendMessage(from.getId(), i18NService.get("PLAYER_WELCOME"));
        } catch (UserAlreadyExistsException e) {
            logger.warn("El usuario {} ya estaba registrado en el otro bot.", from.getId());

            botMessageService.sendMessage(from.getId(), i18NService.get("PLAYER_WELCOME"));
        }
    }

    @Override
    public void loginUser(long telegramId) {
        // Es lo primero que hace cada comando y cada botón: si quien lo usa no ha hecho /start, se
        // le dice aquí, porque los handlers solo dejan el error en el log
        if (SecurityUtils.getUser() == null) {
            tellUnregisteredUser();

            throw new UserDoesntExistsException();
        }
    }

    /**
     * Sin sesión no hay idioma del usuario ni chat privado conocido: se contesta en el idioma de su
     * Telegram, a la propia pulsación si venía de un botón y, si no, en el chat donde escribió.
     */
    private void tellUnregisteredUser() {
        String message = i18NService.get("ERROR_GAME_USER_DOESNT_EXISTS", TelegramSecurityUtils.getLanguageCode());

        String callbackQueryId = TelegramSecurityUtils.getCallbackQueryId();
        if (callbackQueryId != null) {
            botMessageService.answerCallbackQuery(callbackQueryId, message);
            return;
        }

        Long chatId = TelegramSecurityUtils.getChatId();
        if (chatId != null) botMessageService.sendMessage(chatId, message);
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

    // ///////////// Menú y diccionarios //////////////////

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void mainMenu() {
        requireSession();

        botMessageService.sendMessage(chatId(), i18NService.get("DICTIONARIES_MAIN_MENU"), getMainMenuKeyboard());
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void mainMenu(int messageId) {
        requireSession();

        botMessageService.editMessage(chatId(), messageId, i18NService.get("DICTIONARIES_MAIN_MENU"), getMainMenuKeyboard());
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void listDictionaries(int messageId) {
        User user = requireSession();

        List<Dictionary> dictionaries = dictionaryService.getDictionariesByCollaborator(user);

        botMessageService.editMessage(chatId(), messageId, getDictionaryListMessage(dictionaries), goBackTo("menu"));
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void createDictionaryMessage(int messageId) {
        requireSession();

        botMessageService.deleteMessage(chatId(), messageId);
        askFor("/create", i18NService.get("DICTIONARY_CREATE"));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void createDictionary(String name) {
        User user = requireSession();

        guarded(() -> {
            dictionaryService.create(name, user);

            botMessageService.sendMessage(chatId(), i18NService.get("DICTIONARY_CREATED"));
            sendMainMenu();
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void renameDictionaryMessage(int messageId) {
        askToPickOwnDictionary(messageId, "/rename_select", "DICTIONARIES_RENAME_LIST");
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void selectDictionaryToRename(int messageId, String selection) {
        requireSession();

        guarded(() -> {
            UUID dictionaryId = resolveSelection(selection);
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);
            checkNotShared(dictionary);

            botMessageService.deleteMessage(chatId(), messageId);
            askFor("/rename__" + dictionary.getId(), i18NService.get("DICTIONARY_RENAME"));
        }, sharedOverride());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void renameDictionary(UUID dictionaryId, String newName) {
        requireSession();

        guarded(() -> {
            dictionaryService.setName(getDictionaryAndCheckCreator(dictionaryId), newName);

            botMessageService.sendMessage(chatId(), i18NService.get("DICTIONARY_RENAMED"));
            sendMainMenu();
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void changeDictionaryLangMessage(int messageId) {
        askToPickOwnDictionary(messageId, "/change_lang_select", "DICTIONARIES_CHANGE_LANG_LIST");
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void selectDictionaryToChangeLang(int messageId, String selection) {
        requireSession();

        guarded(() -> {
            UUID dictionaryId = resolveSelection(selection);
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);
            checkNotShared(dictionary);

            botMessageService.deleteMessage(chatId(), messageId);
            askFor("/change_lang__" + dictionary.getId(), changeDictionariesLanguageListMessage());
        }, sharedOverride());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void changeDictionaryLang(UUID dictionaryId, String language) {
        requireSession();

        guarded(() -> {
            dictionaryService.setLanguage(getDictionaryAndCheckCreator(dictionaryId), i18NService.getLanguage(language));

            botMessageService.sendMessage(chatId(), i18NService.get("DICTIONARY_LANG_CHANGED"));
            sendMainMenu();
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void deleteDictionaryMessage(int messageId) {
        askToPickOwnDictionary(messageId, "/delete_select", "DICTIONARIES_DELETE_LIST");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void selectDictionaryToDelete(int messageId, String selection) {
        requireSession();

        guarded(() -> {
            UUID dictionaryId = resolveSelection(selection);
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);

            if (Boolean.TRUE.equals(dictionary.getShared())) {
                botMessageService.sendMessage(chatId(), getDeleteSharedErrorMessage());
                return;
            }

            // Un diccionario publicado lo puede estar usando gente, así que se pide confirmación
            if (Boolean.TRUE.equals(dictionary.getPublished())) {
                botMessageService.deleteMessage(chatId(), messageId);
                askFor("/delete__" + dictionaryId, i18NService.get("DICTIONARY_DELETE"));
            } else {
                deleteAndConfirm(dictionary);
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteDictionary(UUID dictionaryId, String text) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);

            if (Boolean.TRUE.equals(dictionary.getShared())) {
                botMessageService.sendMessage(chatId(), getDeleteSharedErrorMessage());
                return;
            }

            if (text == null || !text.equals(DELETE_CONFIRMATION)) {
                botMessageService.sendMessage(chatId(), i18NService.get("DICTIONARY_DELETE_CANCELLED"));
            } else {
                deleteAndConfirm(dictionary);
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void toggleDictionaryMessage(int messageId) {
        askToPickOwnDictionary(messageId, "/toggle_select", "DICTIONARIES_TOGGLE_LIST");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void toggleDictionary(int messageId, String selection) {
        requireSession();

        guarded(() -> {
            UUID dictionaryId = resolveSelection(selection);
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);

            if (Boolean.TRUE.equals(dictionary.getShared())) {
                botMessageService.sendMessage(chatId(), getDeleteSharedErrorMessage());
                return;
            }

            dictionaryService.togglePublished(dictionary);

            botMessageService.sendMessage(chatId(), getDictionaryPublishedMessage(dictionary));
            sendMainMenu();
        }, sharedOverride());
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void shareDictionaryMessage(int messageId) {
        askToPickOwnDictionary(messageId, "/share_select", "DICTIONARIES_SHARE_LIST");
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void requestShareDictionary(int messageId, String selection) {
        requireSession();

        guarded(() -> {
            UUID dictionaryId = resolveSelection(selection);
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);

            if (Boolean.FALSE.equals(dictionary.getPublished())) {
                botMessageService.sendMessage(chatId(), i18NService.get("ERROR_DICTIONARY_NOT_PUBLISHED"));
                return;
            }

            // El dueño del bot revisa el contenido antes de aceptar compartirlo
            sendCardList(dictionary, CardTypeEnum.BLACK);
            sendCardList(dictionary, CardTypeEnum.WHITE);

            InlineKeyboardMarkup shareKeyboard = InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(i18NService.get("ACCEPT")).callbackData("share_accept__" + dictionaryId).build())).keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(i18NService.get("CANCEL")).callbackData("share_decline__" + dictionaryId).build())).build();

            sendToBotOwner(getDictionaryRequestShareMessage(dictionary), shareKeyboard);

            botMessageService.sendMessage(chatId(), getDictionaryRequestShareMessage(dictionary));
            sendMainMenu();
        }, sharedOverride());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void acceptShareDictionary(int messageId, UUID dictionaryId) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getDictionaryAndCheckBotOwner(dictionaryId);

            dictionaryService.toggleShared(dictionary);

            botMessageService.editMessage(chatId(), messageId, getDictionaryShareMessage(dictionary));
            sendTo(dictionary.getCreator(), getDictionaryShareMessage(dictionary));
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void rejectShareDictionary(int messageId, UUID dictionaryId) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getDictionaryAndCheckBotOwner(dictionaryId);

            botMessageService.editMessage(chatId(), messageId, getShareDictionaryRejectedMessage(dictionary));
            sendTo(dictionary.getCreator(), getShareDictionaryRejectedMessage(dictionary));
        });
    }

    // ///////////// Cartas //////////////////

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void manageCardsMessage(int messageId) {
        User user = requireSession();

        List<Dictionary> dictionaries = dictionaryService.getDictionariesByCollaborator(user);

        botMessageService.deleteMessage(chatId(), messageId);
        askFor("/manage_cards_select", format("DICTIONARIES_MANAGE_CARDS_LIST", getDictionaryList(dictionaries)));
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void selectDictionaryToManageCards(Integer messageId, String selection) {
        requireSession();

        guarded(() -> {
            UUID dictionaryId = resolveSelection(selection);
            Dictionary dictionary = getEditableDictionary(dictionaryId);

            if (messageId == null) {
                botMessageService.sendMessage(chatId(), getCardMenuMessage(dictionary), getCardKeyboardMenu(dictionaryId));
            } else {
                botMessageService.editMessage(chatId(), messageId, getCardMenuMessage(dictionary), getCardKeyboardMenu(dictionaryId));
            }
        }, publishedOverride());
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void listWhiteCardsMessage(int messageId, UUID dictionaryId) {
        listCards(messageId, dictionaryId, CardTypeEnum.WHITE);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void listBlackCardsMessage(int messageId, UUID dictionaryId) {
        listCards(messageId, dictionaryId, CardTypeEnum.BLACK);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void addWhiteCardsMessage(int messageId, UUID dictionaryId) {
        askForNewCard(messageId, dictionaryId, CardTypeEnum.WHITE);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void addBlackCardsMessage(int messageId, UUID dictionaryId) {
        askForNewCard(messageId, dictionaryId, CardTypeEnum.BLACK);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void addWhiteCard(UUID dictionaryId, String text) {
        addCard(dictionaryId, text, CardTypeEnum.WHITE);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void addBlackCard(UUID dictionaryId, String text) {
        addCard(dictionaryId, text, CardTypeEnum.BLACK);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void editWhiteCardsMessage(int messageId, UUID dictionaryId) {
        askWhichCard(messageId, dictionaryId, CardTypeEnum.WHITE, "edit");
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void editBlackCardsMessage(int messageId, UUID dictionaryId) {
        askWhichCard(messageId, dictionaryId, CardTypeEnum.BLACK, "edit");
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void editWhiteCardSelect(UUID dictionaryId, String cardSelection) {
        selectCardToEdit(dictionaryId, cardSelection, CardTypeEnum.WHITE);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void editBlackCardSelect(UUID dictionaryId, String cardSelection) {
        selectCardToEdit(dictionaryId, cardSelection, CardTypeEnum.BLACK);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void editWhiteCard(UUID cardId, String newText) {
        editCard(cardId, newText, CardTypeEnum.WHITE);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void editBlackCard(UUID cardId, String newText) {
        editCard(cardId, newText, CardTypeEnum.BLACK);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void deleteWhiteCardsMessage(int messageId, UUID dictionaryId) {
        askWhichCard(messageId, dictionaryId, CardTypeEnum.WHITE, "delete");
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void deleteBlackCardsMessage(int messageId, UUID dictionaryId) {
        askWhichCard(messageId, dictionaryId, CardTypeEnum.BLACK, "delete");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteWhiteCard(UUID dictionaryId, String cardSelection) {
        deleteCard(dictionaryId, cardSelection, CardTypeEnum.WHITE);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteBlackCard(UUID dictionaryId, String cardSelection) {
        deleteCard(dictionaryId, cardSelection, CardTypeEnum.BLACK);
    }

    // ///////////// Colaboradores //////////////////

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void manageCollaboratorsMessage(int messageId) {
        askToPickOwnDictionary(messageId, "/manage_collabs_select", "DICTIONARIES_MANAGE_COLLABORATORS_LIST");
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void selectDictionaryToManageCollaborators(Integer messageId, String selection) {
        requireSession();

        guarded(() -> {
            UUID dictionaryId = resolveSelection(selection);
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);

            if (messageId == null) {
                botMessageService.sendMessage(chatId(), getCollaboratorMenuMessage(dictionary), getDictionaryCollaboratorMenu(dictionaryId));
            } else {
                botMessageService.editMessage(chatId(), messageId, getCollaboratorMenuMessage(dictionary), getDictionaryCollaboratorMenu(dictionaryId));
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void listCollaboratorsMessage(int messageId, UUID dictionaryId) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);

            botMessageService.editMessage(chatId(), messageId, getCollaboratorListMessage(dictionary.getCollaborators()), goBackTo("manage_collabs_select__" + dictionaryId));
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void addCollaboratorsMessage(int messageId, UUID dictionaryId) {
        askAboutCollaborator(messageId, dictionaryId, "/add_collab__", "COLLABORATORS_ADD");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void addCollaborator(UUID dictionaryId, String nameOrId) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);

            DictionaryCollaborator collaborator = dictionaryService.addCollaborator(dictionary, getUserByNameOrId(nameOrId));

            botMessageService.sendMessage(chatId(), getAddedCollaboratorMessage(collaborator));

            InlineKeyboardMarkup acceptKeyboard = InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(i18NService.get("ACCEPT")).callbackData("collaborator_accept__" + dictionaryId).build())).keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(i18NService.get("CANCEL")).callbackData("collaborator_decline__" + dictionaryId).build())).build();
            sendTo(collaborator.getUser(), getAddCollaboratorAcceptMessage(collaborator), acceptKeyboard);

            botMessageService.sendMessage(chatId(), getCollaboratorMenuMessage(dictionary), getDictionaryCollaboratorMenu(dictionaryId));
        }, override(UserDoesntExistsException.class, () -> i18NService.get("COLLABORATOR_ADD_USER_DOESNT_EXISTS")));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void acceptCollaborator(int messageId, UUID dictionaryId) {
        User user = requireSession();

        guarded(() -> {
            Dictionary dictionary = getDictionaryAndCheckCollaborator(dictionaryId);

            dictionaryService.toggleAcceptedCollaborator(dictionary, user);

            botMessageService.editMessage(chatId(), messageId, i18NService.get("COLLABORATORS_ACCEPTED_MESSAGE"));
            sendTo(dictionary.getCreator(), getCollaboratorAcceptedMessage(user.getName()));
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void rejectCollaborator(int messageId, UUID dictionaryId) {
        User user = requireSession();

        guarded(() -> {
            Dictionary dictionary = getDictionaryAndCheckCollaborator(dictionaryId);

            dictionaryService.removeCollaborator(dictionary, user);

            botMessageService.editMessage(chatId(), messageId, i18NService.get("COLLABORATORS_REJECTED_MESSAGE"));
            sendTo(dictionary.getCreator(), getCollaboratorRejectedMessage(user.getName()));
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void removeCollaboratorsMessage(int messageId, UUID dictionaryId) {
        askAboutCollaborator(messageId, dictionaryId, "/delete_collab__", "COLLABORATORS_DELETE");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteCollaborator(UUID dictionaryId, String nameOrId) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);
            User user = getUserByNameOrId(nameOrId);

            dictionaryService.removeCollaborator(dictionary, user);

            botMessageService.sendMessage(chatId(), i18NService.get("COLLABORATORS_DELETED"));
            sendTo(user, getDeleteCollaboratorInfoMessage(dictionary));

            botMessageService.sendMessage(chatId(), getCollaboratorMenuMessage(dictionary), getDictionaryCollaboratorMenu(dictionaryId));
        }, override(UserDoesntExistsException.class, () -> i18NService.get("ERROR_COLLABORATOR_REMOVE_USER_DOESNT_EXISTS")));
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void toggleCollaboratorsMessage(int messageId, UUID dictionaryId) {
        askAboutCollaborator(messageId, dictionaryId, "/toggle_collab__", "COLLABORATORS_TOGGLE");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void toggleCollaborator(UUID dictionaryId, String nameOrId) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getDictionaryAndCheckCreator(dictionaryId);

            DictionaryCollaborator collaborator = dictionaryService.toggleCanEditCollaborator(dictionary, getUserByNameOrId(nameOrId));

            sendTo(collaborator.getUser(), getToggledCollaboratorPrivateMessage(collaborator));
            botMessageService.sendMessage(chatId(), getToggledCollaboratorMessage(collaborator));

            botMessageService.sendMessage(chatId(), getCollaboratorMenuMessage(dictionary), getDictionaryCollaboratorMenu(dictionaryId));
        });
    }

    // ///////////// Ayuda //////////////////

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void sendHelpMessage(long chatId) {
        BotProperties.Bot bot = botProperties.getDictionaries();

        botMessageService.sendMessage(chatId, MessageFormat.format(i18NService.get("DICTIONARIES_HELP"), bot.getDisplayName() + " (" + bot.getAlias() + ")", bot.getVersion(), bot.getHelpUrl(), bot.getOwnerAlias()));
    }

    // ///////////// Flujos compartidos por tipo de carta //////////////////

    private void listCards(int messageId, UUID dictionaryId, CardTypeEnum type) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getEditableDictionary(dictionaryId);

            botMessageService.editMessage(chatId(), messageId, i18NService.get(tag(type, "LIST")));

            sendCardList(dictionary, type);

            botMessageService.sendMessage(chatId(), i18NService.get(tag(type, "LIST_END")), goBackTo("manage_cards_select__" + dictionaryId));
        }, publishedOverride());
    }

    private void askForNewCard(int messageId, UUID dictionaryId, CardTypeEnum type) {
        requireSession();

        guarded(() -> {
            getEditableDictionary(dictionaryId);

            botMessageService.deleteMessage(chatId(), messageId);
            askFor(command(type, "add") + "__" + dictionaryId, i18NService.get(tag(type, "CARD_ADD")));
        }, publishedOverride());
    }

    private void addCard(UUID dictionaryId, String text, CardTypeEnum type) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getEditableDictionary(dictionaryId);

            // El usuario encadena altas hasta que escribe :cancel:
            if (CANCEL_KEYWORD.equals(text)) {
                botMessageService.sendMessage(chatId(), getCardMenuMessage(dictionary), getCardKeyboardMenu(dictionaryId));
                return;
            }

            cardService.create(dictionary, type, text);

            botMessageService.sendMessage(chatId(), getCardAddedMessage(dictionary, type));
            askFor(command(type, "add") + "__" + dictionaryId, i18NService.get(tag(type, "CARD_ADD_ANOTHER")));
        }, publishedOverride(), cardLengthOverride(type));
    }

    private void askWhichCard(int messageId, UUID dictionaryId, CardTypeEnum type, String action) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getEditableDictionary(dictionaryId);

            botMessageService.editMessage(chatId(), messageId, i18NService.get(tag(type, "LIST")));

            sendCardList(dictionary, type);

            String command = "edit".equals(action) ? command(type, "edit") + "_sel__" + dictionaryId : command(type, "delete") + "__" + dictionaryId;
            askFor(command, i18NService.get(tag(type, "edit".equals(action) ? "CARD_EDIT" : "CARD_DELETE")));
        }, publishedOverride());
    }

    private void selectCardToEdit(UUID dictionaryId, String cardSelection, CardTypeEnum type) {
        requireSession();

        guarded(() -> {
            getEditableDictionary(dictionaryId);

            askFor(command(type, "edit") + "__" + resolveSelection(cardSelection), i18NService.get(tag(type, "CARD_EDIT_NEW_TEXT")));
        }, publishedOverride());
    }

    private void editCard(UUID cardId, String newText, CardTypeEnum type) {
        User user = requireSession();

        guarded(() -> {
            Card card = cardService.getCardById(cardId);
            if (card == null) throw new CardDoesntExistsException();

            if (!dictionaryService.isDictionaryEditor(card.getDictionary(), user))
                throw new DictionaryNotYoursException();
            if (Boolean.TRUE.equals(card.getDictionary().getShared()))
                throw new DictionaryAlreadySharedException();

            cardService.changeText(card, newText);

            botMessageService.sendMessage(chatId(), i18NService.get(tag(type, "CARD_EDITED")));
            botMessageService.sendMessage(chatId(), getCardMenuMessage(card.getDictionary()), getCardKeyboardMenu(card.getDictionary().getId()));
        }, override(DictionaryNotYoursException.class, () -> i18NService.get("ERROR_CARD_NOT_YOURS")), override(DictionaryAlreadySharedException.class, () -> i18NService.get("ERROR_DICTIONARY_SHARED")));
    }

    private void deleteCard(UUID dictionaryId, String cardSelection, CardTypeEnum type) {
        requireSession();

        guarded(() -> {
            Dictionary dictionary = getEditableDictionary(dictionaryId);

            Card card = cardService.getCardById(resolveSelection(cardSelection));
            if (card == null || !card.getDictionary().getId().equals(dictionary.getId()))
                throw new CardDoesntExistsException();

            cardService.delete(card);

            botMessageService.sendMessage(chatId(), i18NService.get(tag(type, "CARD_DELETED")));
            botMessageService.sendMessage(chatId(), getCardMenuMessage(dictionary), getCardKeyboardMenu(dictionaryId));
        }, publishedOverride(), override(CardDoesntExistsException.class, () -> i18NService.get("ERROR_CARD_NOT_YOURS")));
    }

    private static String tag(CardTypeEnum type, String suffix) {
        return (type == CardTypeEnum.WHITE ? "CARDS_WHITE_" : "CARDS_BLACK_") + suffix;
    }

    private static String command(CardTypeEnum type, String action) {
        return "/" + action + (type == CardTypeEnum.WHITE ? "_white_card" : "_black_card");
    }

    // ///////////// Preguntas al usuario //////////////////

    private void askToPickOwnDictionary(int messageId, String command, String listTag) {
        User user = requireSession();

        List<Dictionary> dictionaries = dictionaryService.getDictionariesByCreator(user);

        botMessageService.deleteMessage(chatId(), messageId);
        askFor(command, format(listTag, getDictionaryList(dictionaries)));
    }

    private void askAboutCollaborator(int messageId, UUID dictionaryId, String command, String promptTag) {
        requireSession();

        guarded(() -> {
            getDictionaryAndCheckCreator(dictionaryId);

            botMessageService.deleteMessage(chatId(), messageId);
            askFor(command + dictionaryId, i18NService.get(promptTag));
        });
    }

    /**
     * Deja un comando esperando la respuesta del usuario y le abre el cuadro de contestación.
     */
    private void askFor(String command, String question) {
        botMessageService.setPendingReply(chatId(), command);
        botMessageService.sendMessageWithForceReply(chatId(), question);
    }

    private void sendMainMenu() {
        botMessageService.sendMessage(chatId(), i18NService.get("DICTIONARIES_MAIN_MENU"), getMainMenuKeyboard());
    }

    private void deleteAndConfirm(Dictionary dictionary) {
        dictionaryService.delete(dictionary);

        botMessageService.sendMessage(chatId(), i18NService.get("DICTIONARY_DELETED"));
        sendMainMenu();
    }

    // ///////////// Sesión y permisos //////////////////

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

    private Dictionary getDictionary(UUID dictionaryId) {
        Dictionary dictionary = dictionaryService.getDictionaryById(dictionaryId);
        if (dictionary == null) throw new DictionaryDoesntExistsException();

        return dictionary;
    }

    private Dictionary getDictionaryAndCheckCreator(UUID dictionaryId) {
        Dictionary dictionary = getDictionary(dictionaryId);

        if (!Objects.equals(dictionary.getCreator().getId(), requireSession().getId()))
            throw new DictionaryNotYoursException();

        return dictionary;
    }

    private Dictionary getDictionaryAndCheckCollaborator(UUID dictionaryId) {
        Dictionary dictionary = getDictionary(dictionaryId);

        if (!dictionaryService.isDictionaryCollaborator(dictionary, requireSession()))
            throw new DictionaryNotYoursException();

        return dictionary;
    }

    /**
     * Diccionario sobre el que la sesión puede editar cartas: hay que ser colaborador activo y el
     * diccionario no puede estar ya publicado ni compartido.
     */
    private Dictionary getEditableDictionary(UUID dictionaryId) {
        Dictionary dictionary = getDictionary(dictionaryId);

        if (!dictionaryService.isDictionaryEditor(dictionary, requireSession()))
            throw new DictionaryNotYoursException();

        if (Boolean.TRUE.equals(dictionary.getPublished()) || Boolean.TRUE.equals(dictionary.getShared()))
            throw new DictionaryAlreadyPublishedException();

        return dictionary;
    }

    private Dictionary getDictionaryAndCheckBotOwner(UUID dictionaryId) {
        Dictionary dictionary = getDictionary(dictionaryId);

        // Compartir un diccionario con todo el mundo lo aprueba quien administra el bot
        if (!SecurityUtils.isAdmin()) throw new DictionaryNotYoursException();

        return dictionary;
    }

    private void checkNotShared(Dictionary dictionary) {
        if (Boolean.TRUE.equals(dictionary.getShared())) throw new DictionaryAlreadySharedException();
    }

    private User getUserByNameOrId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) throw new UserDoesntExistsException();

        // Un id numérico es de Telegram, no del motor: hay que pasar por la tabla de equivalencias
        if (StringUtils.isNumeric(nameOrId.trim())) {
            TelegramUser telegramUser = telegramUserService.getByTelegramId(Long.parseLong(nameOrId.trim()));
            if (telegramUser == null) throw new UserDoesntExistsException();

            return telegramUser.getUser();
        }

        return userService.getByUsername(TelegramUserUtils.normalizeUsername(nameOrId));
    }

    // ///////////// Mensajes a otros usuarios //////////////////

    /**
     * Escribe al chat privado de otro usuario. Antes bastaba con {@code user.getId()} porque era su
     * id de Telegram; ahora hay que resolverlo por la tabla de equivalencias, y puede no existir
     * (por ejemplo, un usuario que venga de otra plataforma).
     */
    private void sendTo(User user, String text) {
        sendTo(user, text, null);
    }

    private void sendTo(User user, String text, InlineKeyboardMarkup keyboard) {
        TelegramUser telegramUser = telegramUserService.getByUser(user);
        if (telegramUser == null) {
            logger.warn("No se puede avisar al usuario {}: no tiene chat de Telegram asociado", user.getId());
            return;
        }

        if (keyboard == null) {
            botMessageService.sendMessage(telegramUser.getId(), text);
        } else {
            botMessageService.sendMessage(telegramUser.getId(), text, keyboard);
        }
    }

    private void sendToBotOwner(String text, InlineKeyboardMarkup keyboard) {
        Long owner = admins.first();
        if (owner == null) {
            logger.warn("Nadie que administre el bot configurado: no se puede pedir la revisión del diccionario");
            return;
        }

        botMessageService.sendMessage(owner, text, keyboard);
    }

    // ///////////// Errores //////////////////

    private record ErrorOverride(Class<? extends ApplicationException> type, Supplier<String> message) {
    }

    private ErrorOverride override(Class<? extends ApplicationException> type, Supplier<String> message) {
        return new ErrorOverride(type, message);
    }

    private ErrorOverride sharedOverride() {
        return override(DictionaryAlreadySharedException.class, this::getDictionaryAlreadySharedError);
    }

    private ErrorOverride publishedOverride() {
        return override(DictionaryAlreadyPublishedException.class, this::getDeleteSharedErrorMessage);
    }

    private ErrorOverride cardLengthOverride(CardTypeEnum type) {
        return override(org.themarioga.engine.cah.exceptions.card.CardTextExcededLength.class, () -> MessageFormat.format(i18NService.get("ERROR_CARD_EXCEEDED_LENGTH"), type == CardTypeEnum.WHITE ? dictionariesConfig.getMaxWhiteCardLength() : dictionariesConfig.getMaxBlackCardLength()));
    }

    /**
     * Ejecuta la acción y, si el motor la rechaza, le cuenta al usuario por qué.
     * <p>
     * Sustituye a la escalera de {@code catch} por excepción que el código anterior repetía en cada
     * método. Los {@code overrides} son los casos en los que el mensaje no es el genérico del error
     * (porque lleva datos dentro, o porque en ese contexto se dice otra cosa).
     */
    private void guarded(Runnable action, ErrorOverride... overrides) {
        try {
            action.run();
        } catch (ApplicationException e) {
            for (ErrorOverride override : overrides) {
                if (override.type().isInstance(e)) {
                    botMessageService.sendMessage(chatId(), override.message().get());
                    return;
                }
            }

            botMessageService.sendMessage(chatId(), errorMessageResolver.resolve(e));
        }
    }

    // ///////////// Teclados //////////////////

    private InlineKeyboardMarkup getMainMenuKeyboard() {
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("DICTIONARIES_LIST_BUTTON", "dictionary_list"))).keyboardRow(new InlineKeyboardRow(button("DICTIONARIES_CREATE_BUTTON", "dictionary_create"))).keyboardRow(new InlineKeyboardRow(button("DICTIONARIES_RENAME_BUTTON", "dictionary_rename"), button("DICTIONARIES_LANG_BUTTON", "dictionary_lang"))).keyboardRow(new InlineKeyboardRow(button("DICTIONARIES_DELETE_BUTTON", "dictionary_delete"))).keyboardRow(new InlineKeyboardRow(button("DICTIONARIES_TOGGLE_PUBLISH_BUTTON", "dictionary_toggle"), button("DICTIONARIES_TOGGLE_SHARE_BUTTON", "dictionary_share"))).keyboardRow(new InlineKeyboardRow(button("DICTIONARIES_MANAGE_CARDS_BUTTON", "dictionary_manage_cards"), button("DICTIONARIES_MANAGE_COLLABS_BUTTON", "dictionary_manage_collabs"))).build();
    }

    private InlineKeyboardMarkup getDictionaryCollaboratorMenu(UUID dictionaryId) {
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("COLLABORATORS_LIST_BUTTON", "list_collabs__" + dictionaryId))).keyboardRow(new InlineKeyboardRow(button("COLLABORATORS_ADD_BUTTON", "add_collabs__" + dictionaryId))).keyboardRow(new InlineKeyboardRow(button("COLLABORATORS_DELETE_BUTTON", "delete_collabs__" + dictionaryId))).keyboardRow(new InlineKeyboardRow(button("COLLABORATORS_TOGGLE_BUTTON", "toggle_collabs__" + dictionaryId))).keyboardRow(new InlineKeyboardRow(button("GO_BACK", "menu"))).build();
    }

    private InlineKeyboardMarkup getCardKeyboardMenu(UUID dictionaryId) {
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("CARDS_WHITE_LIST_BUTTON", "list_white_cards__" + dictionaryId), button("CARDS_BLACK_LIST_BUTTON", "list_black_cards__" + dictionaryId))).keyboardRow(new InlineKeyboardRow(button("CARDS_WHITE_ADD_BUTTON", "add_white_cards__" + dictionaryId), button("CARDS_BLACK_ADD_BUTTON", "add_black_cards__" + dictionaryId))).keyboardRow(new InlineKeyboardRow(button("CARDS_WHITE_EDIT_BUTTON", "edit_white_cards__" + dictionaryId), button("CARDS_BLACK_EDIT_BUTTON", "edit_black_cards__" + dictionaryId))).keyboardRow(new InlineKeyboardRow(button("CARDS_WHITE_REMOVE_BUTTON", "delete_white_cards__" + dictionaryId), button("CARDS_BLACK_REMOVE_BUTTON", "delete_black_cards__" + dictionaryId))).keyboardRow(new InlineKeyboardRow(button("GO_BACK", "menu"))).build();
    }

    private InlineKeyboardMarkup goBackTo(String callbackData) {
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("GO_BACK", callbackData))).build();
    }

    private InlineKeyboardButton button(String textTag, String callbackData) {
        return InlineKeyboardButton.builder().text(i18NService.get(textTag)).callbackData(callbackData).build();
    }

    // ///////////// Textos //////////////////

    private String format(String tag, Object... args) {
        return MessageFormat.format(i18NService.get(tag), args);
    }

    private String getDictionaryListMessage(List<Dictionary> dictionaries) {
        return format("DICTIONARIES_LIST", getDictionaryList(dictionaries));
    }

    /**
     * Pinta la lista numerada y recuerda el orden, para que el usuario pueda contestar "3" en vez de
     * copiar un identificador de 36 caracteres.
     */
    private String getDictionaryList(List<Dictionary> dictionaries) {
        selectionRegistry.remember(botName, chatId(), dictionaries.stream().map(Dictionary::getId).toList());

        UUID currentUserId = requireSession().getId();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < dictionaries.size(); i++) {
            Dictionary dictionary = dictionaries.get(i);

            builder.append(format("DICTIONARY_INFO", i + 1, dictionary.getName(), yesOrNo(Objects.equals(dictionary.getCreator().getId(), currentUserId)), yesOrNo(dictionary.getPublished()), yesOrNo(dictionary.getShared()), dictionary.getLang().getName())).append("\n");
        }

        return builder.toString();
    }

    private String yesOrNo(Boolean value) {
        return i18NService.get(Boolean.TRUE.equals(value) ? "YES" : "NO");
    }

    private String getCollaboratorListMessage(List<DictionaryCollaborator> collaborators) {
        StringBuilder builder = new StringBuilder();
        UUID currentUserId = requireSession().getId();

        List<DictionaryCollaborator> others = collaborators.stream().filter(c -> !Objects.equals(c.getUser().getId(), currentUserId)).toList();

        if (others.isEmpty()) {
            builder.append("\n").append(i18NService.get("COLLABORATORS_LIST_EMPTY")).append("\n");
        } else {
            for (DictionaryCollaborator collaborator : others) {
                builder.append(format("COLLABORATOR_INFO", collaborator.getUser().getName(), yesOrNo(collaborator.getAccepted()), yesOrNo(collaborator.getCanEdit()))).append("\n");
            }
        }

        return format("COLLABORATORS_LIST", builder.toString());
    }

    private void sendCardList(Dictionary dictionary, CardTypeEnum type) {
        List<Card> cards = cardService.findCardsByDictionaryAndType(dictionary, type);

        selectionRegistry.remember(botName, chatId(), cards.stream().map(Card::getId).toList());

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            // Telegram corta los mensajes largos, así que se trocea
            if (builder.length() > 4000) {
                botMessageService.sendMessage(chatId(), builder.toString());
                builder = new StringBuilder();
            }

            builder.append(format("CARD_INFO", i + 1, cards.get(i).getText())).append("\n");
        }

        if (!builder.isEmpty()) {
            botMessageService.sendMessage(chatId(), builder.toString());
        }
    }

    /**
     * Traduce lo que ha contestado el usuario (el número de la lista, o un identificador completo)
     * al identificador del motor.
     */
    private UUID resolveSelection(String input) {
        UUID id = selectionRegistry.resolve(botName, chatId(), input);
        if (id == null) throw new SelectionNotFoundException();

        return id;
    }

    private String changeDictionariesLanguageListMessage() {
        StringBuilder builder = new StringBuilder();
        for (Lang lang : i18NService.getLanguages()) {
            builder.append("<b>").append(lang.getId()).append("</b>").append(" - ").append(lang.getName()).append("\n");
        }

        return format("DICTIONARY_CHANGE_LANG", builder.toString());
    }

    private String getCardAddedMessage(Dictionary dictionary, CardTypeEnum type) {
        int count = cardService.countCardsByDictionaryAndType(dictionary, type);
        int min = type == CardTypeEnum.WHITE ? dictionariesConfig.getMinNumberOfWhiteCards() : dictionariesConfig.getMinNumberOfBlackCards();
        int max = type == CardTypeEnum.WHITE ? dictionariesConfig.getMaxNumberOfWhiteCards() : dictionariesConfig.getMaxNumberOfBlackCards();

        // Hasta llegar al mínimo se enseña ese objetivo, y a partir de ahí el techo real
        return format(tag(type, "CARD_ADDED"), count, count < min ? min : max);
    }

    private String getCardMenuMessage(Dictionary dictionary) {
        return format("CARDS_MENU", dictionary.getName());
    }

    private String getCollaboratorMenuMessage(Dictionary dictionary) {
        return format("COLLABORATORS_MENU", dictionary.getName());
    }

    private String getAddedCollaboratorMessage(DictionaryCollaborator collaborator) {
        return format("COLLABORATORS_ADDED", collaborator.getUser().getName());
    }

    private String getAddCollaboratorAcceptMessage(DictionaryCollaborator collaborator) {
        return format("COLLABORATORS_ACCEPT_MESSAGE", collaborator.getDictionary().getCreator().getName(), collaborator.getDictionary().getName());
    }

    private String getDeleteCollaboratorInfoMessage(Dictionary dictionary) {
        return format("COLLABORATORS_DELETED_MESSAGE", dictionary.getName());
    }

    private String getCollaboratorAcceptedMessage(String name) {
        return format("COLLABORATORS_ACCEPTED_CREATOR", name);
    }

    private String getCollaboratorRejectedMessage(String name) {
        return format("COLLABORATORS_REJECTED_CREATOR", name);
    }

    private String getToggledCollaboratorMessage(DictionaryCollaborator collaborator) {
        return format(Boolean.TRUE.equals(collaborator.getCanEdit()) ? "COLLABORATORS_TOGGLED_ON" : "COLLABORATORS_TOGGLED_OFF", collaborator.getUser().getName());
    }

    private String getToggledCollaboratorPrivateMessage(DictionaryCollaborator collaborator) {
        return format(Boolean.TRUE.equals(collaborator.getCanEdit()) ? "COLLABORATORS_TOGGLED_ON_MESSAGE" : "COLLABORATORS_TOGGLED_OFF_MESSAGE", collaborator.getDictionary().getName());
    }

    private String getDictionaryPublishedMessage(Dictionary dictionary) {
        return format(Boolean.TRUE.equals(dictionary.getPublished()) ? "DICTIONARY_TOGGLED_ON" : "DICTIONARY_TOGGLED_OFF", dictionary.getName());
    }

    private String getDictionaryRequestShareMessage(Dictionary dictionary) {
        return format(Boolean.FALSE.equals(dictionary.getShared()) ? "DICTIONARY_SHARED_ON_REQUEST" : "DICTIONARY_SHARED_OFF_REQUEST", dictionary.getName());
    }

    private String getDictionaryShareMessage(Dictionary dictionary) {
        return format(Boolean.TRUE.equals(dictionary.getShared()) ? "DICTIONARY_SHARED_ON" : "DICTIONARY_SHARED_OFF", dictionary.getName());
    }

    private String getShareDictionaryRejectedMessage(Dictionary dictionary) {
        return format("DICTIONARY_SHARED_REJECTED", dictionary.getName());
    }

    private String getDeleteSharedErrorMessage() {
        return format("ERROR_DICTIONARY_SHARED", botProperties.getDictionaries().getOwnerAlias());
    }

    private String getDictionaryAlreadySharedError() {
        return format("ERROR_DICTIONARY_ALREADY_SHARED", botProperties.getDictionaries().getOwnerAlias());
    }

}
