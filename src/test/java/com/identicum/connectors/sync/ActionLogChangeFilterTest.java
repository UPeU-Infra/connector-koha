package com.identicum.connectors.sync;

import com.identicum.connectors.sync.ActionLogChangeFilter.BatchPlan;
import com.identicum.connectors.sync.ActionLogChangeFilter.Decision;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Filtro del live sync. Datos 100 % ficticios. Los formatos de {@code info}/{@code diff}
 * reproducen los de Koha 25.11 (dev) y 26.05 (PROD), tomados del codigo fuente de Koha.
 */
class ActionLogChangeFilterTest {

    static final long SVC_MIDPOINT = 900001L;
    static final long DISCOVERY = 900002L;
    static final long BIBLIOTECARIO = 900003L;
    static final long PATRON_A = 800001L;
    static final long PATRON_B = 800002L;

    // Koha 25.11: info = {"campo":{"before":..,"after":..}}, diff vacio. Con pretty-print como lo deja Koha.
    static final String INFO_2511_PREFERRED = "{\n   \"preferred_name\" : {\n      \"after\" : \"Lucia\",\n"
            + "      \"before\" : \"Maria\"\n   }\n}\n";
    static final String INFO_2511_SURNAME = "{\n   \"surname\" : {\n      \"after\" : \"PRUEBA DOS\",\n"
            + "      \"before\" : \"PRUEBA\"\n   }\n}\n";
    // Koha 26.05: info = {"campo": valor_nuevo}; diff = Struct::Diff {"D":{"campo":{"N":..,"O":..}}}
    static final String INFO_2605_PREFERRED = "{\"preferred_name\":\"Lucia\",\"surname\":\"PRUEBA\"}";
    static final String DIFF_2605_PREFERRED = "{\"D\":{\"preferred_name\":{\"N\":\"Lucia\",\"O\":\"Maria\"},"
            + "\"surname\":{\"N\":\"PRUEBA\",\"O\":\"PRUEBAS\"}}}";
    static final String INFO_2605_SURNAME = "{\"surname\":\"PRUEBA DOS\"}";
    static final String DIFF_2605_SURNAME = "{\"D\":{\"surname\":{\"N\":\"PRUEBA DOS\",\"O\":\"PRUEBA\"}}}";
    // Koha 26.05 al vaciar preferred_name: store() lo rellena con firstname -> sigue siendo un cambio del campo.
    static final String INFO_2605_PREFERRED_BACK_TO_FIRSTNAME = "{\"preferred_name\":\"Maria\"}";
    // Atributo extendido (ambas versiones): clave "attribute.CODE"
    static final String INFO_ATTRIBUTE = "{\"attribute.STUDY_LEVEL\":[\"pregrado\"]}";

    private static ActionLogChangeFilter filter() {
        return new ActionLogChangeFilter(Collections.singleton(SVC_MIDPOINT), null);
    }

    private static ActionLogEvent ev(long id, long user, Long object, String iface, String info, String diff) {
        return new ActionLogEvent(id, user, object, iface, info, diff);
    }

    // --- parseo del diff de info ---

    @Test
    void campos2511SeLeenDeLasClavesDeInfo() {
        assertEquals(set("preferred_name"), ActionLogChangeFilter.changedFields(INFO_2511_PREFERRED, null));
    }

    @Test
    void campos2605SeLeenDeLasClavesDeInfo() {
        assertEquals(set("preferred_name", "surname"),
                ActionLogChangeFilter.changedFields(INFO_2605_PREFERRED, DIFF_2605_PREFERRED));
    }

    @Test
    void siInfoNoEsJsonSeUsaDiff() {
        assertEquals(set("preferred_name", "surname"),
                ActionLogChangeFilter.changedFields("texto libre que no es JSON", DIFF_2605_PREFERRED));
    }

    @Test
    void infoYDiffIlegiblesDevuelvenNull() {
        assertNull(ActionLogChangeFilter.changedFields("{\"preferred_name\": ", null));
        assertNull(ActionLogChangeFilter.changedFields(null, null));
        assertNull(ActionLogChangeFilter.changedFields("", "[]"));
        assertNull(ActionLogChangeFilter.changedFields("{\"a\":1} basura", null));
    }

    @Test
    void textoQueMencionaPreferredNameComoValorNoCuenta() {
        // Un valor que contiene la palabra no es un cambio del campo: se mira la CLAVE, no un LIKE.
        assertEquals(Decision.IGNORED_NO_TRIGGER_FIELD, filter().classify(
                ev(1, BIBLIOTECARIO, PATRON_A, "intranet", "{\"borrowernotes\":\"cambiar preferred_name\"}", null)));
    }

    // --- antieco por usuario ---

    @Test
    void cambioDePreferredNamePorSvcMidpointSeIgnora() {
        assertEquals(Decision.IGNORED_EXCLUDED_USER,
                filter().classify(ev(1, SVC_MIDPOINT, PATRON_A, "api", INFO_2605_PREFERRED, DIFF_2605_PREFERRED)));
    }

    @Test
    void cambioPorLaApiDeOtroUsuarioSeEmite() {
        // Discovery escribe por la API con su propio usuario: NO se excluye interface='api' en bloque.
        assertEquals(Decision.EMIT,
                filter().classify(ev(1, DISCOVERY, PATRON_A, "api", INFO_2605_PREFERRED, DIFF_2605_PREFERRED)));
    }

    @Test
    void cambiosPorIntranetOpacYSinSesionSeEmiten() {
        ActionLogChangeFilter f = filter();
        assertEquals(Decision.EMIT, f.classify(ev(1, BIBLIOTECARIO, PATRON_A, "intranet", INFO_2511_PREFERRED, null)));
        // Aprobacion automatica del OPAC (AutoApprovePatronProfileSettings=1): user = el propio lector.
        assertEquals(Decision.EMIT, f.classify(ev(2, PATRON_A, PATRON_A, "opac", INFO_2511_PREFERRED, null)));
        // Sin sesion (cron, linea de comandos): user = 0.
        assertEquals(Decision.EMIT, f.classify(ev(3, 0, PATRON_A, "commandline", INFO_2605_PREFERRED, null)));
    }

    @Test
    void sinExclusionesTodoCambioDePreferredNameSeEmite() {
        ActionLogChangeFilter f = new ActionLogChangeFilter(null, null);
        assertEquals(Decision.EMIT, f.classify(ev(1, SVC_MIDPOINT, PATRON_A, "api", INFO_2605_PREFERRED, null)));
    }

    // --- evento ajeno (R5) ---

    @Test
    void cambioSoloDeSurnameSeIgnoraAunqueSeaDelMismoPatron() {
        ActionLogChangeFilter f = filter();
        assertEquals(Decision.IGNORED_NO_TRIGGER_FIELD,
                f.classify(ev(1, BIBLIOTECARIO, PATRON_A, "intranet", INFO_2511_SURNAME, null)));
        assertEquals(Decision.IGNORED_NO_TRIGGER_FIELD,
                f.classify(ev(2, DISCOVERY, PATRON_A, "api", INFO_2605_SURNAME, DIFF_2605_SURNAME)));
        assertEquals(Decision.IGNORED_NO_TRIGGER_FIELD,
                f.classify(ev(3, BIBLIOTECARIO, PATRON_A, "intranet", INFO_ATTRIBUTE, null)));
    }

    @Test
    void preferredNameVaciadoQueKohaRellenaConFirstnameSeEmite() {
        assertEquals(Decision.EMIT, filter().classify(
                ev(1, DISCOVERY, PATRON_A, "api", INFO_2605_PREFERRED_BACK_TO_FIRSTNAME, null)));
    }

    @Test
    void camposVigiladosConfigurables() {
        ActionLogChangeFilter f = new ActionLogChangeFilter(null, Arrays.asList(" surname ", ""));
        assertEquals(set("surname"), f.getTriggerFields());
        assertEquals(Decision.EMIT, f.classify(ev(1, BIBLIOTECARIO, PATRON_A, "intranet", INFO_2605_SURNAME, null)));
        assertEquals(set("preferred_name"), new ActionLogChangeFilter(null, Collections.<String>emptyList()).getTriggerFields());
    }

    @Test
    void filaSinObjectSeIgnora() {
        assertEquals(Decision.IGNORED_NO_OBJECT,
                filter().classify(ev(1, BIBLIOTECARIO, null, "intranet", INFO_2511_PREFERRED, null)));
    }

    @Test
    void filaIlegibleSeIgnoraSinExcepcion() {
        assertEquals(Decision.IGNORED_UNPARSEABLE,
                filter().classify(ev(1, BIBLIOTECARIO, PATRON_A, "intranet", "{roto", null)));
    }

    // --- plan del lote: coalescencia y avance ---

    @Test
    void planCoalesceAlUltimoEventoDeCadaPatronYCuentaIgnorados() {
        List<ActionLogEvent> batch = Arrays.asList(
                ev(10, DISCOVERY, PATRON_A, "api", INFO_2605_PREFERRED, null),
                ev(11, SVC_MIDPOINT, PATRON_B, "api", INFO_2605_PREFERRED, null),
                ev(12, BIBLIOTECARIO, PATRON_B, "intranet", INFO_2511_PREFERRED, null),
                ev(13, BIBLIOTECARIO, PATRON_A, "intranet", INFO_2511_SURNAME, null),
                ev(14, DISCOVERY, PATRON_A, "api", INFO_2605_PREFERRED_BACK_TO_FIRSTNAME, null),
                ev(15, BIBLIOTECARIO, PATRON_A, "intranet", "{roto", null));
        BatchPlan plan = filter().plan(batch);
        assertEquals(2, plan.getEmissions().size());
        // Orden por el ultimo evento: B (12) antes que A (14).
        assertEquals(PATRON_B, plan.getEmissions().get(0).getBorrowernumber());
        assertEquals(12, plan.getEmissions().get(0).getActionId());
        assertEquals(PATRON_A, plan.getEmissions().get(1).getBorrowernumber());
        assertEquals(14, plan.getEmissions().get(1).getActionId());
        assertEquals(15, plan.getMaxScannedActionId(), "el lote se lee hasta el final aunque el ultimo se ignore");
        assertEquals(3, plan.count(Decision.EMIT));
        assertEquals(1, plan.count(Decision.IGNORED_EXCLUDED_USER));
        assertEquals(1, plan.count(Decision.IGNORED_NO_TRIGGER_FIELD));
        assertEquals(1, plan.count(Decision.IGNORED_UNPARSEABLE));
    }

    @Test
    void planVacio() {
        BatchPlan plan = filter().plan(Collections.<ActionLogEvent>emptyList());
        assertEquals(0, plan.getEmissions().size());
        assertEquals(-1, plan.getMaxScannedActionId());
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
