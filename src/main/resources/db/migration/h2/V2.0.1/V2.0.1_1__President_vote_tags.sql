-- v2.0.1_1 - Privado del presidente de ronda
--
-- En CLASSIC y DICTATORSHIP el presidente no juega carta, y los textos de los demás jugadores
-- ('Has elegido la carta blanca...') no le valen: estos son los suyos para votar y para confirmar
-- el voto. Van en su propia versión para poder desplegarse antes que los jugadores IA (V2.1.0).

INSERT INTO tag (tag, lang_id, text) VALUES
    ('PLAYER_PRESIDENT_VOTE_CARD', 'es', '<b>Ronda {0}</b>\n\nLa carta negra de esta ronda es:\n\n<b>{1}</b>\n\nTe toca elegir la carta ganadora de entre las siguientes:'),
    ('PLAYER_PRESIDENT_VOTED_CARD', 'es', '<b>Ronda {0}</b>\n\nLa carta negra de esta ronda es:\n\n<b>{1}</b>\n\nHas elegido como ganadora la carta blanca:\n\n<b>{2}</b>'),
    ('PLAYER_PRESIDENT_VOTE_CARD', 'en', '<b>Round {0}</b>\n\nThe black card of the round is:\n\n<b>{1}</b>\n\nIt is your turn to pick the winning card from the following:'),
    ('PLAYER_PRESIDENT_VOTED_CARD', 'en', '<b>Round {0}</b>\n\nThe black card of the round is:\n\n<b>{1}</b>\n\nYou picked as the winner the white card:\n\n<b>{2}</b>');
