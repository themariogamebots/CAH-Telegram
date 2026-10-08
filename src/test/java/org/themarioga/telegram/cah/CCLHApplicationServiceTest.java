package org.themarioga.telegram.cah;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.themarioga.commons.telegram.services.intf.ApplicationService;

import java.util.Set;

/**
 * Igual que con el bot de diccionarios: los nombres de comando y las claves de callback son
 * contrato con lo que ya está desplegado. Los botones viven dentro de mensajes que Telegram guarda
 * indefinidamente, así que cambiar una clave rompe partidas que ya están en marcha.
 */
@SpringBootTest(properties = {"cclh.bot.enabled=true", "cclh.bot.token=111:fake-token-de-pruebas", "cclh.bot.name=cclhtestbot"})
class CCLHApplicationServiceTest {

    @Autowired
    @Qualifier("cclhBotApplicationService")
    private ApplicationService applicationService;

    private static final Set<String> COMMANDS = Set.of(
            "/start", "/lang", "/create", "/help", "/deletemygames", "/deletegamebyusername", "/deleteallgames", "/sendmessagetoeveryone", "/toggleglobalmessages");

    private static final Set<String> CALLBACKS = Set.of(
            "change_user_lang", "game_menu", "game_configure", "game_sel_mode", "game_sel_point_type", "game_sel_dictionary", "game_sel_max_players", "game_sel_n_rounds", "game_sel_n_points", "game_change_mode", "game_change_dictionary", "game_change_max_players", "game_change_max_rounds", "game_change_max_points", "game_join", "game_leave", "game_start", "game_add_ai", "game_remove_ai", "play_card", "vote_card", "game_delete_group", "game_delete_private");

    @Test
    void theCommandInterfaceIsUnchanged() {
        Assertions.assertEquals(COMMANDS, applicationService.getBotCommands().keySet());
    }

    @Test
    void theCallbackInterfaceIsUnchanged() {
        Assertions.assertEquals(CALLBACKS, applicationService.getCallbackQueries().keySet());
    }

    @Test
    void everyHandlerIsWired() {
        for (String command : COMMANDS) {
            Assertions.assertNotNull(applicationService.getBotCommands().get(command), () -> "falta " + command);
        }
        for (String callback : CALLBACKS) {
            Assertions.assertNotNull(applicationService.getCallbackQueries().get(callback), () -> "falta " + callback);
        }
    }

}
