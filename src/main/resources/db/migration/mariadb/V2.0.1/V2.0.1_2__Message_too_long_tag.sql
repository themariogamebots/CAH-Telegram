-- v2.0.1_2 - Aviso de mensaje vacío o demasiado largo
--
-- Este tag se añadió en su día dentro de V2.0.0_2 cuando la V2 ya estaba desplegada, y Flyway se
-- negaba a arrancar por el checksum. V2.0.0_2 ha vuelto a su contenido original y el tag vive aquí.

INSERT INTO tag (tag, lang_id, text) VALUES
    ('ERROR_MESSAGE_TOO_LONG', 'es', 'El mensaje está vacío o es demasiado largo: no se ha enviado a nadie.'),
    ('ERROR_MESSAGE_TOO_LONG', 'en', 'The message is empty or too long: it has not been sent to anyone.');
