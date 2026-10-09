package org.themarioga.telegram.cah;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Fija el contenido de las migraciones que ya están desplegadas.
 * <p>
 * Flyway guarda el checksum de cada migración aplicada y se niega a arrancar si el fichero cambia
 * después: en producción eso es el bot caído. Ya pasó una vez (un tag añadido a V2.0.0_2 con la V2
 * desplegada) y no se vio hasta el despliegue, porque en los tests la BD se crea de cero y Flyway
 * no tiene contra qué comparar.
 * <p>
 * Si este test falla, no se actualiza el hash: se deshace el cambio en la migración y lo nuevo va
 * en una versión nueva. Al desplegar una versión nueva, sus ficheros se añaden aquí.
 */
class DeployedMigrationsTest {

    /** SHA-256 de cada migración desplegada, en los dos dialectos. */
    private static final Map<String, String> DEPLOYED = Map.ofEntries(
            Map.entry("h2/V2/V2.0.0_1__Baseline.sql", "3d804523b59e46ac4a78cb7febfc7f64a8db4d18fe94f1fc63566d22f3dea4c9"),
            Map.entry("h2/V2/V2.0.0_2__Languages_and_tags.sql", "2d6c6a761d3e7cfbd3973626138453ecdba4af49b386f8f7314efa55535b3746"),
            Map.entry("h2/V2.0.1/V2.0.1_1__President_vote_tags.sql", "b8ff9b7cb62e510e4c3b8232551576c11acf680ecba839d447782b5c04bdccfc"),
            Map.entry("h2/V2.0.1/V2.0.1_2__Message_too_long_tag.sql", "b71001bde46165da8ddfd82bcb8b9a52e893df8855bb7541e061ea1929193dbf"),
            Map.entry("h2/V2.1/V2.1.0_1__AI_players.sql", "98a822fdd320155f0197f6e7539b4b488ee6313065e85d90bdf9fd9ac9dfad16"),
            Map.entry("h2/V2.1/V2.1.0_2__AI_players_tags.sql", "141044745d8d89aac49fbb2749069136fc8bd46bd19b2c7267802cb544ad394d"),
            Map.entry("h2/V2.1/V2.1.0_3__Round_results.sql", "307fcef55eeab2cc5efb2e0b090ec6d7a04a50c7f4dc7d746928f21104798584"),
            Map.entry("mariadb/V2/V2.0.0_1__Baseline.sql", "23025c0e3f3fda96c350cc5f433f2a26e490b30b181dc9602a9a45f2616d78ea"),
            Map.entry("mariadb/V2/V2.0.0_2__Languages_and_tags.sql", "2d6c6a761d3e7cfbd3973626138453ecdba4af49b386f8f7314efa55535b3746"),
            Map.entry("mariadb/V2.0.1/V2.0.1_1__President_vote_tags.sql", "b8ff9b7cb62e510e4c3b8232551576c11acf680ecba839d447782b5c04bdccfc"),
            Map.entry("mariadb/V2.0.1/V2.0.1_2__Message_too_long_tag.sql", "b71001bde46165da8ddfd82bcb8b9a52e893df8855bb7541e061ea1929193dbf"),
            Map.entry("mariadb/V2.1/V2.1.0_1__AI_players.sql", "710cf2d6ea0b77d8dbf9fd9f9efe78eb633ff8e1dbb3e16cead956018d68c134"),
            Map.entry("mariadb/V2.1/V2.1.0_2__AI_players_tags.sql", "141044745d8d89aac49fbb2749069136fc8bd46bd19b2c7267802cb544ad394d"),
            Map.entry("mariadb/V2.1/V2.1.0_3__Round_results.sql", "c7de6d26e24477ef6b6af40fe7492857551650fb756690346a113e8729cfc76f")
    );

    @Test
    void deployedMigrationsAreUnchanged() throws IOException, NoSuchAlgorithmException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> migration : DEPLOYED.entrySet()) {
            String path = "db/migration/" + migration.getKey();
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    problems.add(path + ": ya no existe");
                    continue;
                }
                String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
                if (!sha256.equals(migration.getValue())) {
                    problems.add(path + ": ha cambiado (" + sha256 + ")");
                }
            }
        }

        Assertions.assertTrue(problems.isEmpty(), "Migraciones desplegadas modificadas; Flyway no arrancará en producción:\n" + String.join("\n", problems));
    }

}
