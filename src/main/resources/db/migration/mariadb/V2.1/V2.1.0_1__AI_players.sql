-- v2.1.0_1 - Jugadores IA (mariadb)
--
-- La V2 ya está desplegada: sus migraciones no se tocan. Este DDL es la diferencia entre la
-- baseline y lo que genera SchemaGenerator con el modelo actual, copiada con sus mismos tipos.
--
-- El default es lo que permite añadir la columna 'not null' a una tabla que ya tiene filas: los
-- jugadores de las partidas en curso son todos humanos.

alter table player add column ai bit default false not null;
