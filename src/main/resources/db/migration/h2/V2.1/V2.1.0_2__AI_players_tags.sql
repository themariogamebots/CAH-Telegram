-- v2.1.0_2 - Textos de los jugadores IA
--
-- Sin emojis a propósito: no hay ninguno en el resto de textos y no está garantizado que la
-- base de datos desplegada use utf8mb4.

INSERT INTO tag (tag, lang_id, text) VALUES
    ('AI_PLAYER_NAME', 'es', 'IA {0}'),
    ('ERROR_AI_PLAYER_NOT_FOUND', 'es', 'No hay ningún jugador IA que quitar.'),
    ('ERROR_GAME_NOT_ENOUGH_HUMANS', 'es', 'Hacen falta al menos dos jugadores humanos.'),
    ('GAME_ADD_AI_BUTTON', 'es', 'Añadir jugador IA'),
    ('GAME_REMOVE_AI_BUTTON', 'es', 'Quitar jugador IA'),
    ('GAME_VOTE_CARD_PRESIDENT', 'es', '<b>Ronda {0}</b>\n\nLa carta negra de esta ronda es <b>{1}</b>\n\nLos jugadores eligieron las siguientes cartas blancas:\n\n<b>{2}</b>\n\nAhora <b>{3}</b> elegirá la ganadora.'),
    ('AI_PLAYER_NAME', 'en', 'AI {0}'),
    ('ERROR_AI_PLAYER_NOT_FOUND', 'en', 'There is no AI player to remove.'),
    ('ERROR_GAME_NOT_ENOUGH_HUMANS', 'en', 'At least two human players are needed.'),
    ('GAME_ADD_AI_BUTTON', 'en', 'Add AI player'),
    ('GAME_REMOVE_AI_BUTTON', 'en', 'Remove AI player'),
    ('GAME_VOTE_CARD_PRESIDENT', 'en', '<b>Round {0}</b>\n\nThe black card of this round is <b>{1}</b>\n\nThe players chose the following white cards:\n\n<b>{2}</b>\n\nNow <b>{3}</b> will choose the winner.');
