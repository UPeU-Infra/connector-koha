package com.identicum.connectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v1.7.1 — conjunto final de extended_attributes en updateDelta.
 * Caso medido en PROD el 28-oct-2026: al dejar de ser estudiante, MidPoint da de baja TODOS los
 * STUDY_LEVEL/STUDYCYCLE; con el merge-preserve la baja nunca se aplicaba y se reenviaba siempre.
 */
class KohaConnectorExtendedAttributesTest {

    private static JSONArray current() {
        return new JSONArray()
                .put(new JSONObject().put("extended_attribute_id", 1).put("type", "DNI").put("value", "12345678"))
                .put(new JSONObject().put("extended_attribute_id", 2).put("type", "COD_UPEU").put("value", "202612813"))
                .put(new JSONObject().put("extended_attribute_id", 3).put("type", "STUDY_LEVEL").put("value", "posgrado"))
                .put(new JSONObject().put("extended_attribute_id", 4).put("type", "STUDYCYCLE").put("value", "1"))
                .put(new JSONObject().put("extended_attribute_id", 5).put("type", "STUDYCYCLE").put("value", "2"));
    }

    private static Set<String> keys(JSONArray arr) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            out.add(e.getString("type") + "=" + e.getString("value"));
        }
        return out;
    }

    @Test
    void bajaDeTodosLosValoresDeUnTipoSeAplica() {
        JSONArray result = KohaConnector.resolveExtendedAttributes(current(),
                Arrays.asList(
                        "{\"type\":\"STUDY_LEVEL\",\"value\":\"posgrado\"}",
                        "{\"type\":\"STUDYCYCLE\",\"value\":\"1\"}",
                        "{\"type\":\"STUDYCYCLE\",\"value\":\"2\"}"),
                null);
        assertEquals(new HashSet<>(Arrays.asList("DNI=12345678", "COD_UPEU=202612813")), keys(result),
                "Solo deben quedar los tipos que MidPoint no dio de baja");
    }

    @Test
    void bajaCasaAunqueLasClavesVenganEnOtroOrden() {
        JSONArray result = KohaConnector.resolveExtendedAttributes(current(),
                Collections.singletonList("{\"value\":\"posgrado\",\"type\":\"STUDY_LEVEL\"}"), null);
        assertEquals(4, result.length());
        assertEquals(false, keys(result).contains("STUDY_LEVEL=posgrado"));
    }

    @Test
    void cambioDeValorSustituyeSinDuplicar() {
        JSONArray result = KohaConnector.resolveExtendedAttributes(current(),
                Collections.singletonList("{\"type\":\"STUDY_LEVEL\",\"value\":\"posgrado\"}"),
                Collections.singletonList("{\"type\":\"STUDY_LEVEL\",\"value\":\"pregrado\"}"));
        long niveles = keys(result).stream().filter(k -> k.startsWith("STUDY_LEVEL=")).count();
        assertEquals(1, niveles, "Koha rechaza dos STUDY_LEVEL (409 non-repeatable)");
        assertEquals(true, keys(result).contains("STUDY_LEVEL=pregrado"));
    }

    @Test
    void altaRepetidaNoDuplica() {
        JSONArray result = KohaConnector.resolveExtendedAttributes(current(), null,
                Collections.singletonList("{\"type\":\"DNI\",\"value\":\"12345678\"}"));
        assertEquals(5, result.length());
    }

    @Test
    void elResultadoNoLlevaExtendedAttributeId() {
        JSONArray result = KohaConnector.resolveExtendedAttributes(current(), null, null);
        for (int i = 0; i < result.length(); i++) {
            assertEquals(false, result.getJSONObject(i).has("extended_attribute_id"),
                    "Koha rechaza extended_attribute_id en el PUT");
        }
    }
}
