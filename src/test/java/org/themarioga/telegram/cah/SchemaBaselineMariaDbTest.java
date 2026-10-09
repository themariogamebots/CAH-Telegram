package org.themarioga.telegram.cah;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.themarioga.commons.engine.models.Lang;
import org.themarioga.commons.engine.services.intf.I18NService;
import org.themarioga.commons.engine.services.intf.LanguageService;

import java.util.List;

/**
 * El mismo contrato que {@link SchemaBaselineTest} pero contra MariaDB de verdad.
 * <p>
 * Hacía falta porque el baseline que se despliega es el de MariaDB y el único test que lo cubría
 * corría sobre H2: cualquier diferencia propia de MariaDB pasaba entera hasta producción. Pasó de
 * verdad. {@code SchemaGenerator} nombraba el dialecto por su clase, lo que equivale a
 * {@code new MariaDBDialect()} y asume su {@code MINIMUM_VERSION} (10.6); en 10.6 un {@code UUID}
 * se mapea a {@code binary(16)}, pero a partir de 10.7 MariaDB tiene tipo {@code uuid} nativo y es
 * el que Hibernate espera cuando le pregunta la versión al servidor por JDBC. Resultado: el
 * baseline creaba 43 columnas {@code binary(16)} y el arranque moría con
 * <em>"wrong column type encountered in column [id] in table [card]"</em>. Con H2 no se veía nada,
 * porque H2 tiene {@code uuid} nativo desde siempre.
 * <p>
 * Igual que en la versión de H2, el trabajo lo hace {@code spring.jpa.hibernate.ddl-auto=validate}:
 * si el esquema que deja Flyway no cuadra con las entidades, el contexto no arranca y todos los
 * tests de la clase fallan. Los métodos de abajo solo fijan los detalles que más caro salen.
 */
@Testcontainers
@EnabledIfDockerAvailable
@SpringBootTest(properties = {
        // El application.properties de test apunta a H2; aquí hay que validar el otro baseline.
        "spring.flyway.locations=classpath:db/migration/mariadb"})
class SchemaBaselineMariaDbTest {

    /**
     * Debe ser >= la versión fijada en {@code SchemaGenerator.MARIADB_VERSION} (10.7), que es la que
     * decide si los UUID se generan como {@code uuid} o como {@code binary(16)}. Conviene que sea
     * además la de producción (11.8): 10.7 y 11.4 generan el mismo esquema, pero una versión futura
     * podría cambiar algún otro mapeo y este test solo lo detectará si prueba con esa versión. Ya
     * hubo una diferencia de comportamiento: desde 11.6.2 {@code innodb_snapshot_isolation} viene
     * activado y dos transacciones que escriben la misma fila fallan con el error 1020.
     */
    private static final String IMAGE = "mariadb:11.8";

    @Container
    @ServiceConnection
    static MariaDBContainer mariadb = new MariaDBContainer(IMAGE);

    @Autowired
    private LanguageService languageService;
    @Autowired
    private I18NService i18NService;
    @Autowired
    private EntityManager entityManager;

    @Test
    void schemaMatchesTheEntityModel() {
        // Si hemos llegado aquí, Flyway migró contra MariaDB y Hibernate validó el resultado.
        Assertions.assertNotNull(languageService);
    }

    /**
     * El servidor tiene que ser al menos la versión para la que se generó el esquema; por debajo,
     * Hibernate esperaría {@code binary(16)} y la validación fallaría con un mensaje que no dice
     * nada de versiones.
     */
    @Test
    void serverIsRecentEnoughForNativeUuid() {
        String version = (String) entityManager.createNativeQuery("SELECT VERSION()").getSingleResult();
        String[] parts = version.split("[.-]");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);

        Assertions.assertTrue(major > 10 || (major == 10 && minor >= 7), () -> "el esquema se genera para MariaDB >= 10.7 y el contenedor trae " + version);
    }

    /**
     * Fija la regresión concreta: ninguna columna debe ser {@code binary}. Es lo que salía cuando el
     * DDL se generaba asumiendo MariaDB 10.6, y en este modelo no hay ningún uso legítimo de binary.
     */
    @Test
    void noColumnFallsBackToBinary() {
        @SuppressWarnings("unchecked")
        List<String> binaryColumns = entityManager.createNativeQuery("SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns WHERE table_schema = DATABASE() AND data_type = 'binary' ORDER BY 1").getResultList();

        Assertions.assertTrue(binaryColumns.isEmpty(), () -> "columnas binary en el esquema (¿se regeneró el baseline con una versión de MariaDB anterior a la 10.7?): " + binaryColumns);
    }

    /**
     * Y la otra mitad: que los ids sean de verdad {@code uuid}. Sin esto, un baseline que se quedara
     * sin las tablas afectadas pasaría el test anterior por no tener ninguna columna binary.
     */
    @Test
    void idColumnsUseTheNativeUuidType() {
        for (String[] column : List.of(new String[] {"card", "id"}, new String[] {"card", "dictionary_id"}, new String[] {"users", "id"}, new String[] {"dictionary", "creator_id"})) {
            String type = (String) entityManager.createNativeQuery("SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = :t AND column_name = :c").setParameter("t", column[0]).setParameter("c", column[1]).getSingleResult();

            Assertions.assertEquals("uuid", type, () -> column[0] + "." + column[1] + " debería ser uuid");
        }
    }

    /**
     * Las migraciones de datos van por dialecto igual que el baseline, así que la semilla de idiomas
     * y tags también hay que comprobarla aquí y no solo en H2.
     */
    @Test
    void seedDataIsLoaded() {
        List<Lang> langs = languageService.getLangs();

        Assertions.assertEquals(2, langs.size());
        Assertions.assertEquals("es", languageService.getDefaultLanguage().getId());

        Assertions.assertEquals("← Volver", i18NService.get("GO_BACK", "es"));
        Assertions.assertEquals("← Go back", i18NService.get("GO_BACK", "en"));

        String welcome = i18NService.get("PLAYER_WELCOME", "es");
        Assertions.assertNotEquals("PLAYER_WELCOME", welcome, "el tag no está en la tabla");
        Assertions.assertTrue(welcome.contains("\n"), "los \\n del SQL deben llegar como saltos de línea");
    }

    /**
     * Además de que V2.1.1_1 se aplique, comprueba que las tildes que mete llegan enteras: el
     * {@code REPLACE} corre en el servidor, con su juego de caracteres.
     */
    @Test
    void spanishTextsHaveTheirAccents() {
        SchemaBaselineTest.assertTyposAreFixed(entityManager);

        Assertions.assertEquals("Ya estás registrado. Consulta /help para más información.", i18NService.get("ERROR_USER_ALREADY_REGISTERED", "es"));
    }

}
