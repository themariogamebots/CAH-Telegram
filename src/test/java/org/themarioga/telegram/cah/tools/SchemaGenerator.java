package org.themarioga.telegram.cah.tools;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.MappingSettings;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.tool.schema.spi.DelayedDropRegistryNotAvailableImpl;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.springframework.boot.hibernate.SpringImplicitNamingStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Genera el DDL del baseline a partir de las entidades, para MariaDB y para H2.
 * <p>
 * No es un test: es una herramienta de un solo uso que se ejecuta a mano cuando cambia el modelo.
 * Escribir el esquema a mano con la herencia TABLE_PER_CLASS que usan Game y Player es pedir
 * problemas; quien manda es Hibernate.
 * <p>
 * Aplica las mismas naming strategies que Spring Boot 4.1 usa en tiempo de ejecución
 * ({@code SpringImplicitNamingStrategy} + {@code PhysicalNamingStrategySnakeCaseImpl}), que es lo
 * que hace que el esquema generado y el que la aplicación espera coincidan.
 * <p>
 * <b>La versión de MariaDB va fijada a propósito.</b> Nombrar el dialecto por su clase equivale a
 * {@code new MariaDBDialect()}, que asume {@code MINIMUM_VERSION} (10.6), y en 10.6 un {@code UUID}
 * se mapea a {@code binary(16)}. En tiempo de ejecución Hibernate no asume nada: pregunta la versión
 * al servidor por JDBC y, a partir de 10.7, el mismo {@code UUID} pasa a ser el tipo nativo
 * {@code uuid}. Generar con una versión y validar con otra rompe el arranque entero con
 * "wrong column type encountered in column [id] in table [card]". Fijando aquí 10.7 el DDL vale para
 * cualquier MariaDB de esa versión en adelante (10.7 y 11.4 generan exactamente el mismo esquema).
 * <p>
 * Contrapartida: el esquema generado exige MariaDB &gt;= 10.7. Para desplegar en una anterior hay que
 * bajar {@link #MARIADB_VERSION} y regenerar.
 * <p>
 * Uso: {@code java -cp <classpath de test> org.themarioga.telegram.cah.tools.SchemaGenerator <dir>}
 */
public class SchemaGenerator {

    private static final List<Class<?>> ENTITIES = List.of(
            org.themarioga.commons.engine.models.Lang.class, org.themarioga.commons.engine.models.Tag.class, org.themarioga.commons.engine.models.User.class, org.themarioga.commons.engine.models.Room.class, org.themarioga.commons.engine.models.Game.class, org.themarioga.commons.engine.models.Player.class, org.themarioga.engine.cah.models.dictionaries.Dictionary.class, org.themarioga.engine.cah.models.dictionaries.DictionaryCollaborator.class, org.themarioga.engine.cah.models.dictionaries.Card.class, org.themarioga.engine.cah.models.game.Game.class, org.themarioga.engine.cah.models.game.Player.class, org.themarioga.engine.cah.models.game.Round.class, org.themarioga.engine.cah.models.game.PlayedCard.class, org.themarioga.engine.cah.models.game.PlayerHandCard.class, org.themarioga.engine.cah.models.game.VotedCard.class, org.themarioga.engine.cah.models.game.RoundResult.class, org.themarioga.commons.telegram.models.TelegramUser.class, org.themarioga.telegram.cah.models.TelegramRoom.class, org.themarioga.telegram.cah.models.TelegramGame.class, org.themarioga.telegram.cah.models.TelegramPlayer.class);

    /** Versión mínima de MariaDB para la que se genera el esquema. Ver el javadoc de la clase. */
    private static final DatabaseVersion MARIADB_VERSION = DatabaseVersion.make(10, 7);

    private static final Map<String, Dialect> DIALECTS = Map.of(
            "mariadb", new MariaDBDialect(MARIADB_VERSION), "h2", new H2Dialect());

    public static void main(String[] args) throws Exception {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "target/schema");
        Files.createDirectories(outputDir);

        for (Map.Entry<String, Dialect> dialect : DIALECTS.entrySet()) {
            Path target = outputDir.resolve("schema-" + dialect.getKey() + ".sql");
            Files.deleteIfExists(target);

            export(dialect.getValue(), target);

            System.out.println("Generado " + target.toAbsolutePath());
        }
    }

    private static void export(Dialect dialect, Path target) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.dialect", dialect);
        // Sin esto Hibernate intenta abrir una conexión para inspeccionar la BD: aquí solo queremos
        // el DDL, y el dialecto ya se lo damos explícitamente.
        settings.put("hibernate.boot.allow_jdbc_metadata_access", "false");
        settings.put(MappingSettings.IMPLICIT_NAMING_STRATEGY, SpringImplicitNamingStrategy.class.getName());
        settings.put(MappingSettings.PHYSICAL_NAMING_STRATEGY, PhysicalNamingStrategySnakeCaseImpl.class.getName());
        settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
        settings.put("jakarta.persistence.schema-generation.scripts.create-target", target.toString());
        settings.put("hibernate.hbm2ddl.delimiter", ";");
        settings.put("hibernate.format_sql", "true");

        StandardServiceRegistryBuilder registryBuilder = new StandardServiceRegistryBuilder();
        settings.forEach(registryBuilder::applySetting);
        StandardServiceRegistry registry = registryBuilder.build();

        try {
            MetadataSources sources = new MetadataSources(registry);
            ENTITIES.forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.buildMetadata();

            SchemaManagementToolCoordinator.process(metadata, registry, settings, DelayedDropRegistryNotAvailableImpl.INSTANCE);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

}
