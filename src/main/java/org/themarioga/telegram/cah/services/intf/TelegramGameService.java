package org.themarioga.telegram.cah.services.intf;

import org.themarioga.engine.cah.models.game.Game;
import org.themarioga.engine.cah.models.game.Player;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.commons.engine.models.User;
import org.themarioga.telegram.cah.models.TelegramGame;
import org.themarioga.telegram.cah.models.TelegramPlayer;

import java.util.List;

/**
 * Guarda la correspondencia entre lo que el motor entiende (partidas y jugadores) y lo que Telegram
 * necesita para pintarlo (identificadores de mensaje y de chat).
 * <p>
 * Aquí no hay reglas de juego: eso vive en {@code CAHService}.
 */
public interface TelegramGameService {

    TelegramGame create(Game game, int firstMessageId, int creatorMessageId);

    TelegramGame getByGame(Game game);

    TelegramGame getByCreator(User creator);

    List<TelegramGame> getAll();

    /**
     * Apunta el mensaje de la ronda en curso. Se llama desde la continuación de un envío asíncrono,
     * así que trabaja sobre su propia copia de la partida y no sobre la del update que la lanzó.
     */
    void setCurrentRoundMessageId(Game game, int messageId);

    /**
     * Id del chat de Telegram en el que se juega una partida. Es el camino que permite escribir al
     * grupo cuando la acción llega por el chat privado de un jugador.
     */
    Long getChatId(Room room);

    TelegramPlayer createPlayer(Player player, int handMessageId);

    TelegramPlayer getByPlayer(Player player);

    List<TelegramPlayer> getPlayers(Game game);

    void deletePlayer(TelegramPlayer telegramPlayer);

    /**
     * Borra las filas de Telegram de una partida terminada. No toca la partida en el motor.
     */
    void deleteGameData(Game game);

}
