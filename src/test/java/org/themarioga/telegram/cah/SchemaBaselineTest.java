package org.themarioga.telegram.cah;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.themarioga.commons.engine.models.Lang;
import org.themarioga.commons.engine.services.intf.I18NService;
import org.themarioga.commons.engine.services.intf.LanguageService;

import java.util.List;

/**
 * Comprueba que el baseline de Flyway y el modelo de entidades dicen lo mismo.
 * <p>
 * El trabajo lo hace {@code spring.jpa.hibernate.ddl-auto=validate}: si al esquema le falta una
 * columna, le sobra, o un tipo no cuadra, el contexto no arranca y el test falla. Es la red que
 * evita que el baseline se quede atrás cuando alguien toque una entidad.
 */
@SpringBootTest
class SchemaBaselineTest {

    /**
     * Textos recuperados de las migraciones antiguas, más los ocho que hubo que escribir y el freno
     * de la difusión (V2.0.0_2), más los dos del privado del presidente de ronda (V2.0.1_1) y los
     * seis de los jugadores IA (V2.1.0_2).
     */
    private static final int EXPECTED_TAGS = 224;

    @Autowired
    private LanguageService languageService;
    @Autowired
    private I18NService i18NService;
    @Autowired
    private EntityManager entityManager;

    private long countTags(String lang) {
        return entityManager.createQuery("SELECT COUNT(t) FROM Tag t WHERE t.lang.id = :lang", Long.class).setParameter("lang", lang).getSingleResult();
    }

    @Test
    void schemaMatchesTheEntityModel() {
        // Si hemos llegado aquí, Flyway migró y Hibernate validó el esquema resultante.
        Assertions.assertNotNull(languageService);
    }

    @Test
    void languagesAreSeeded() {
        List<Lang> langs = languageService.getLangs();

        Assertions.assertEquals(2, langs.size());
        Assertions.assertTrue(langs.stream().anyMatch(l -> "es".equals(l.getId())));
        Assertions.assertTrue(langs.stream().anyMatch(l -> "en".equals(l.getId())));
        Assertions.assertEquals("es", languageService.getDefaultLanguage().getId());
    }

    /**
     * Los textos i18n se recuperaron de las migraciones antiguas: comprobamos que están los dos
     * idiomas y que un tag conocido devuelve su texto y no su propio nombre (que es lo que devuelve
     * {@code I18NService} cuando no encuentra el tag).
     */
    @Test
    void tagsAreSeededInBothLanguages() {
        Assertions.assertEquals("← Volver", i18NService.get("GO_BACK", "es"));
        Assertions.assertEquals("← Go back", i18NService.get("GO_BACK", "en"));

        String welcome = i18NService.get("PLAYER_WELCOME", "es");
        Assertions.assertNotEquals("PLAYER_WELCOME", welcome, "el tag no está en la tabla");
        Assertions.assertTrue(welcome.contains("\n"), "los \\n del SQL deben llegar como saltos de línea");
    }

    /**
     * Los textos del bot de diccionarios estaban en las migraciones de CAH-Engine, no en las de
     * Commons-Engine, y en la primera recuperación se quedaron fuera: el bot habría enseñado el
     * nombre del tag en crudo en casi todas sus pantallas. Este test lo detecta si vuelve a pasar.
     */
    @Test
    void dictionaryBotTagsArePresent() {
        for (String tag : List.of("DICTIONARIES_MAIN_MENU", "DICTIONARY_CREATE", "DICTIONARY_CREATED", "CARDS_MENU", "CARDS_WHITE_CARD_ADD", "COLLABORATORS_MENU", "ERROR_DICTIONARY_NOT_FOUND", "COLLABORATOR_ADD_MAX_REACHED", "UNKNOWN_ERROR")) {
            for (String lang : List.of("es", "en")) {
                Assertions.assertNotEquals(tag, i18NService.get(tag, lang), () -> "falta el tag " + tag + " en " + lang);
            }
        }
    }

    /**
     * Los textos que antes estaban en castellano dentro del código: si vuelven a escribirse en duro,
     * un usuario en inglés los ve en castellano.
     */
    @Test
    void theTextsThatUsedToBeHardcodedAreTags() {
        for (String tag : List.of("DICTIONARY_INFO", "COLLABORATOR_INFO", "COLLABORATORS_LIST_EMPTY", "CARD_INFO", "YES", "NO", "ERROR_SELECTION_INVALID")) {
            for (String lang : List.of("es", "en")) {
                Assertions.assertNotEquals(tag, i18NService.get(tag, lang), () -> "falta " + tag + " en " + lang);
            }
        }

        Assertions.assertEquals("Sí", i18NService.get("YES", "es"));
        Assertions.assertEquals("Yes", i18NService.get("YES", "en"));
    }

    /**
     * Los dos idiomas tienen que estar completos: si a uno le falta un tag, el usuario que lo tenga
     * configurado ve el nombre del tag donde debería ir el texto.
     */
    @Test
    void bothLanguagesHaveTheSameTags() {
        Assertions.assertEquals(EXPECTED_TAGS, countTags("es"));
        Assertions.assertEquals(EXPECTED_TAGS, countTags("en"));
    }

}
