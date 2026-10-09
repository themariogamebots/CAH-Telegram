-- v2.1.1_1 - Tildes y erratas de los textos
--
-- Los textos de la V2 se recuperaron de las migraciones antiguas con tildes perdidas ("Respondeme",
-- "Ya estas registrado"...). La V2 está desplegada y no se puede tocar, así que se corrigen aquí.
-- Cada cambio va acotado a su tag e idioma y sustituye solo el fragmento erróneo, para no tener que
-- repetir textos largos.
--
-- Aprovechando: los tags de las cartas negras hablaban de cartas blancas.
--
-- DICTIONARY_DELETE sigue diciendo "Responde SI" a propósito: el bot compara la respuesta con "SI".

UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_BLACK_CARD_ADD' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_BLACK_CARD_ADD_ANOTHER' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_BLACK_CARD_DELETE' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_BLACK_CARD_EDIT' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_BLACK_CARD_EDIT_NEW_TEXT' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_WHITE_CARD_ADD' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_WHITE_CARD_ADD_ANOTHER' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_WHITE_CARD_DELETE' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_WHITE_CARD_EDIT' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Respondeme', 'Respóndeme') WHERE tag = 'CARDS_WHITE_CARD_EDIT_NEW_TEXT' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'respondeme', 'respóndeme') WHERE tag = 'DICTIONARIES_DELETE_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'respondeme', 'respóndeme') WHERE tag = 'DICTIONARIES_MANAGE_CARDS_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'respondeme', 'respóndeme') WHERE tag = 'DICTIONARIES_MANAGE_COLLABORATORS_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'respondeme', 'respóndeme') WHERE tag = 'DICTIONARIES_RENAME_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'respondeme', 'respóndeme') WHERE tag = 'DICTIONARIES_SHARE_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'respondeme', 'respóndeme') WHERE tag = 'DICTIONARIES_TOGGLE_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Aqui tienes', 'Aquí tienes') WHERE tag = 'CARDS_BLACK_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Aqui tienes', 'Aquí tienes') WHERE tag = 'CARDS_WHITE_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'se envien', 'se envíen') WHERE tag = 'CARDS_BLACK_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'se envien', 'se envíen') WHERE tag = 'CARDS_WHITE_LIST' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Estas gestionando', 'Estás gestionando') WHERE tag = 'CARDS_MENU' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Estas gestionando', 'Estás gestionando') WHERE tag = 'COLLABORATORS_MENU' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'escribiendole', 'escribiéndole') WHERE tag = 'COLLABORATORS_ADD' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'código del lenguage', 'código del idioma') WHERE tag = 'DICTIONARY_CHANGE_LANG' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'añadir mas cartas', 'añadir más cartas') WHERE tag = 'ERROR_DICTIONARY_ALREADY_FILLED' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Despublicalo', 'Despublícalo') WHERE tag = 'ERROR_DICTIONARY_ALREADY_PUBLISHED' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'de algun tipo', 'de algún tipo') WHERE tag = 'ERROR_DICTIONARY_NOT_FILLED' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'aun no', 'aún no') WHERE tag = 'ERROR_GAME_NOT_STARTED' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Ya estas', 'Ya estás') WHERE tag = 'ERROR_PLAYER_ALREADY_JOINED' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Ya estas', 'Ya estás') WHERE tag = 'ERROR_PLAYER_ALREADY_PLAYING' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'No estas', 'No estás') WHERE tag = 'ERROR_PLAYER_DOES_NOT_EXISTS' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Ya estas registrado', 'Ya estás registrado') WHERE tag = 'ERROR_USER_ALREADY_REGISTERED' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'para mas información', 'para más información') WHERE tag = 'ERROR_USER_ALREADY_REGISTERED' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'puntuacion', 'puntuación') WHERE tag = 'GAME_CHANGE_PUNCTUATION_MODE' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'jugaran', 'jugarán') WHERE tag = 'GAME_SELECT_CARD' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'votaran', 'votarán') WHERE tag = 'GAME_VOTE_CARD' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'carta blanca', 'carta negra') WHERE tag = 'CARDS_BLACK_CARD_DELETE' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Carta blanca', 'Carta negra') WHERE tag = 'CARDS_BLACK_CARD_DELETED' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'carta blanca', 'carta negra') WHERE tag = 'CARDS_BLACK_CARD_EDIT' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'Carta blanca', 'Carta negra') WHERE tag = 'CARDS_BLACK_CARD_EDITED' AND lang_id = 'es';
UPDATE tag SET text = REPLACE(text, 'white card', 'black card') WHERE tag = 'CARDS_BLACK_CARD_DELETE' AND lang_id = 'en';
