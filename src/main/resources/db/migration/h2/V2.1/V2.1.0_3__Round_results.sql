-- v2.1.0_3 - Histórico de resultados de ronda (h2)
--
-- Una fila por carta jugada, escrita al cerrarse la votación: de aquí aprenderá la IA qué cartas
-- suelen ganar. DDL copiado de lo que genera SchemaGenerator para la entidad RoundResult.
--
-- Sin claves ajenas a propósito: las cartas y los diccionarios se pueden borrar, y el histórico no
-- tiene por qué perderse con ellos ni impedir que se borren.

create table round_result (
    id uuid not null,
    creation_date timestamp(6) not null,
    ai_player boolean not null,
    ai_votes integer not null,
    black_card_id uuid not null,
    candidates integer not null,
    dictionary_id uuid not null,
    votation_mode tinyint not null check ((votation_mode between 0 and 2)),
    votes integer not null,
    white_card_id uuid not null,
    won boolean not null,
    primary key (id)
);

create index IDX1uxnc51khrde0i0s7f3tivnf8
   on round_result (black_card_id, white_card_id);

create index IDXofxj5c3ps1b00xkgwja0bgvq2
   on round_result (white_card_id);
