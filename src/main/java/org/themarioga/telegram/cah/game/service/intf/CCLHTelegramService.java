package org.themarioga.telegram.cah.game.service.intf;

/**
 * Comportamiento del bot de juego.
 * <p>
 * Sustituye a {@code CCLHBotService}. Los {@code chatId} son identificadores de chat de Telegram,
 * no del motor: la sala se resuelve por la tabla de equivalencias.
 */
public interface CCLHTelegramService {

    // ///////////// Usuario //////////////////

    void registerUser(org.telegram.telegrambots.meta.api.objects.User from);

    /**
     * Comprueba que la petición trae sesión. Ya no la monta: de eso se encarga el interceptor de
     * updates. Se conserva para que la tabla de comandos siga igual.
     *
     * @param telegramId ignorado; la sesión sale del contexto de seguridad
     */
    void loginUser(long telegramId);

    void changeUserLanguageMessage();

    void changeUserLanguage(int messageId, String lang);

    // ///////////// Creación y configuración //////////////////

    void startCreatingGame(long chatId, String chatTitle);

    void gameMenuQuery(long chatId, String callbackQueryId);

    void gameConfigureQuery(long chatId, String callbackQueryId);

    void gameSelectModeQuery(long chatId, String callbackQueryId);

    void gameSelectPunctuationModeQuery(long chatId, String callbackQueryId);

    void gameSelectNRoundsToEndQuery(long chatId, String callbackQueryId);

    void gameSelectNPointsToWinQuery(long chatId, String callbackQueryId);

    void gameSelectDictionaryQuery(long chatId, String callbackQueryId, String page);

    void gameSelectMaxPlayersQuery(long chatId, String callbackQueryId);

    void gameChangeMode(long chatId, String callbackQueryId, String data);

    void gameChangeDictionary(long chatId, String callbackQueryId, String data);

    void gameChangeMaxPlayers(long chatId, String callbackQueryId, String data);

    void gameChangeNRoundsToEnd(long chatId, String callbackQueryId, String data);

    void gameChangeNCardsToWin(long chatId, String callbackQueryId, String data);

    // ///////////// Jugadores y arranque //////////////////

    void gameJoinQuery(long chatId, String callbackQueryId);

    void leaveGame(String callbackQueryId);

    void gameStartQuery(long chatId, String callbackQueryId);

    void gameAddAIPlayerQuery(long chatId, String callbackQueryId);

    void gameRemoveAIPlayerQuery(long chatId, String callbackQueryId);

    // ///////////// Ronda //////////////////

    void playerPlayCardQuery(String callbackQueryId, String data);

    void playerVoteCardQuery(String callbackQueryId, String data);

    // ///////////// Borrado //////////////////

    /**
     * Botón de borrar del grupo. Para el creador borra la partida; para el resto, con la partida ya
     * en marcha, es un voto para borrarla.
     */
    void gameDeleteGroupQuery(long chatId, String callbackQueryId);

    void gameDeletePrivateQuery(String callbackQueryId);

    // ///////////// Administración //////////////////

    void deleteMyGames();

    void deleteGameByCreatorUsername(String username);

    void deleteAllGames();

    void sendMessageToEveryone(String message);

    void toggleGlobalMessages();

    // ///////////// Ayuda //////////////////

    void sendHelpMessage(long chatId);

}
