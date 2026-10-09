package org.themarioga.telegram.cah.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.themarioga.commons.engine.models.User;
import org.themarioga.commons.engine.security.SecurityUtils;
import org.themarioga.commons.engine.security.UserRole;
import org.themarioga.commons.telegram.config.TelegramAdmins;
import org.themarioga.commons.telegram.models.TelegramUser;
import org.themarioga.commons.telegram.security.TelegramContext;
import org.themarioga.commons.telegram.security.TelegramContextHolder;
import org.themarioga.commons.telegram.security.TelegramUserDetails;
import org.themarioga.commons.telegram.services.impl.PendingReplyRegistry;
import org.themarioga.commons.telegram.services.intf.BotMessageService;
import org.themarioga.commons.telegram.services.intf.TelegramRoomResolver;
import org.themarioga.commons.telegram.services.intf.TelegramUserService;

/**
 * Base para ejercitar los bots de punta a punta: base de datos real (H2 con el baseline y el
 * catálogo i18n) y la mensajería sustituida por una que apunta lo que se envía.
 * <p>
 * La sesión se monta a mano, que es lo que hace el interceptor de updates en producción.
 */
@SpringBootTest(properties = {"cclh.bot.enabled=true", "cclh.bot.token=111:fake-token-de-pruebas", "cclh.bot.name=cclhtestbot", "dictionaries.bot.enabled=true", "dictionaries.bot.token=222:fake-token-de-pruebas", "dictionaries.bot.name=dictionariestestbot", "telegram.bots.admin-ids=1"})
@Transactional
public abstract class BotFlowTest {

    protected static final long ADMIN_TELEGRAM_ID = 1L;

    @Autowired
    protected TelegramUserService telegramUserService;
    @Autowired
    protected TelegramRoomResolver roomResolver;
    @Autowired
    protected PendingReplyRegistry pendingReplies;
    @Autowired
    protected TelegramAdmins admins;

    /**
     * La mensajería real se sustituye por la que apunta lo que se envía. Se usa {@code @TestBean},
     * que es el mecanismo previsto para reemplazar un bean concreto: registrar otro con el mismo
     * nombre depende del orden en que Spring los procese y no es fiable.
     */
    protected static final RecordingBotMessageService CCLH_MESSAGES = new RecordingBotMessageService("cclhtestbot", new PendingReplyRegistry());
    protected static final RecordingBotMessageService DICTIONARIES_MESSAGES = new RecordingBotMessageService("dictionariestestbot", new PendingReplyRegistry());

    @TestBean(name = "cclhBotMessageService")
    private BotMessageService cclhMessaging;
    @TestBean(name = "dictionariesBotMessageService")
    private BotMessageService dictionariesMessaging;

    static BotMessageService cclhMessaging() {
        return CCLH_MESSAGES;
    }

    static BotMessageService dictionariesMessaging() {
        return DICTIONARIES_MESSAGES;
    }

    @AfterEach
    void clearSession() {
        CCLH_MESSAGES.clear();
        DICTIONARIES_MESSAGES.clear();
        SecurityContextHolder.clearContext();
        TelegramContextHolder.clear();
    }

    // ///////////// Sesión //////////////////

    /**
     * Da de alta a un usuario de Telegram y deja su sesión puesta, como haría el interceptor.
     */
    protected User givenRegisteredUser(long telegramId, String alias) {
        TelegramUser telegramUser = telegramUserService.register(telegramUser(telegramId, alias));

        logIn(telegramUser, telegramId, null, "private");

        return telegramUser.getUser();
    }

    protected void logInAs(long telegramId, Long chatId, String chatType) {
        TelegramUser telegramUser = telegramUserService.getByTelegramId(telegramId);

        logIn(telegramUser, telegramId, chatId, chatType);
    }

    /**
     * Como {@link #logInAs}, pero simulando la pulsación de un botón: el bot avisa contestando a la
     * propia pulsación en vez de mandando un mensaje.
     */
    protected void logInAsCallback(long telegramId, Long chatId, String chatType) {
        TelegramUser telegramUser = telegramUserService.getByTelegramId(telegramId);

        SecurityUtils.setUserDetails(new TelegramUserDetails(telegramUser, admins.contains(telegramId) ? UserRole.ADMIN : UserRole.USER));

        Message message = Message.builder().messageId(1).from(telegramUser(telegramId, "u" + telegramId)).chat(Chat.builder().id(chatId).type(chatType).title("Grupo de pruebas").build()).build();

        CallbackQuery callbackQuery = new CallbackQuery();
        callbackQuery.setId("callback-de-pruebas");
        callbackQuery.setFrom(telegramUser(telegramId, "u" + telegramId));
        callbackQuery.setMessage(message);
        callbackQuery.setData("noop");

        Update update = new Update();
        update.setCallbackQuery(callbackQuery);

        TelegramContextHolder.set(TelegramContext.from(update, "cclhtestbot", roomResolver));
    }

    /**
     * Alguien que no ha hecho /start: hay contexto de Telegram pero no sesión, que es lo que deja el
     * interceptor cuando no encuentra al usuario.
     *
     * @param callback true para simular la pulsación de un botón en vez de un comando
     */
    protected void asUnregisteredUser(long telegramId, long chatId, String chatType, boolean callback) {
        SecurityContextHolder.clearContext();

        Update update;
        if (callback) {
            CallbackQuery callbackQuery = new CallbackQuery();
            callbackQuery.setId("callback-de-pruebas");
            callbackQuery.setFrom(telegramUser(telegramId, "u" + telegramId));
            callbackQuery.setMessage(Message.builder().messageId(1).from(telegramUser(telegramId, "u" + telegramId)).chat(Chat.builder().id(chatId).type(chatType).title("Grupo de pruebas").build()).build());
            callbackQuery.setData("noop");

            update = new Update();
            update.setCallbackQuery(callbackQuery);
        } else {
            update = update(telegramId, chatId, chatType);
        }

        TelegramContextHolder.set(TelegramContext.from(update, "cclhtestbot", roomResolver));
    }

    private void logIn(TelegramUser telegramUser, long telegramId, Long chatId, String chatType) {
        SecurityUtils.setUserDetails(new TelegramUserDetails(telegramUser, admins.contains(telegramId) ? UserRole.ADMIN : UserRole.USER));

        TelegramContextHolder.set(TelegramContext.from(
                update(telegramId, chatId != null ? chatId : telegramId, chatType), "cclhtestbot", roomResolver));
    }

    protected org.telegram.telegrambots.meta.api.objects.User telegramUser(long telegramId, String alias) {
        return org.telegram.telegrambots.meta.api.objects.User.builder().id(telegramId).isBot(false).firstName(alias).userName(alias).languageCode("es").build();
    }

    private Update update(long telegramId, long chatId, String chatType) {
        Message message = Message.builder().messageId(1).from(telegramUser(telegramId, "u" + telegramId)).chat(Chat.builder().id(chatId).type(chatType).title("Grupo de pruebas").build()).text("/noop").build();

        Update update = new Update();
        update.setMessage(message);

        return update;
    }

}
