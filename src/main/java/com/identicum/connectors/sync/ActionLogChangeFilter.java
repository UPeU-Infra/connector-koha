package com.identicum.connectors.sync;

import org.identityconnectors.common.logging.Log;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decide, sin tocar la red ni la BD, que filas de {@code action_logs} deben convertirse en un
 * {@code SyncDelta} del live sync.
 *
 * <p>Reglas (riesgo R5 del diseno «nombre preferido»: un evento ajeno reintrodujo un
 * {@code preferred_name} viejo en el laboratorio):</p>
 * <ol>
 *   <li><b>Antieco por USUARIO, no por interfaz.</b> Se ignoran los cambios hechos por los
 *       borrowernumber excluidos (la cuenta de API de midPoint). NO se excluye
 *       {@code interface='api'} en bloque, porque Discovery tambien escribe por la API con su
 *       propio usuario.</li>
 *   <li><b>Solo si el cambio toca un campo vigilado</b> (por defecto {@code preferred_name}). Un
 *       cambio de {@code surname} del mismo patron se ignora.</li>
 *   <li><b>Una fila ilegible no aborta nada</b> (riesgo R12: un error detiene el live sync): se
 *       registra y se salta.</li>
 *   <li><b>Coalescencia:</b> si varios eventos validos tocan el mismo patron en un lote, se emite
 *       uno solo, en la posicion (y con el token) del ultimo.</li>
 * </ol>
 *
 * <h2>Formato de {@code info} segun la version de Koha (medido en el codigo fuente)</h2>
 * <ul>
 *   <li><b>Koha 25.11</b> ({@code Koha/Patron.pm} de koha-common 25.11.03-2): {@code info} =
 *       {@code {"campo":{"before":..,"after":..}, ...}}; columna {@code diff} vacia.</li>
 *   <li><b>Koha 26.05</b> ({@code Koha/Patron.pm} v26.05.05-1, {@code logaction(..., \%log_to,
 *       undef, \%log_from)}): {@code info} = {@code {"campo": valor_nuevo, ...}} y el antes/despues
 *       va en {@code diff} con formato Struct::Diff: {@code {"D":{"campo":{"O":..,"N":..}}}}.</li>
 * </ul>
 * <p>En ambos casos las <b>claves</b> del objeto JSON de {@code info} son exactamente los campos
 * cambiados, y eso es lo unico que se mira. Si {@code info} no se puede leer, se intenta con las
 * claves de {@code diff.D}.</p>
 */
public final class ActionLogChangeFilter {

    private static final Log LOG = Log.getLog(ActionLogChangeFilter.class);

    public static final String DEFAULT_TRIGGER_FIELD = "preferred_name";

    /** Motivo por el que una fila se emite o se ignora. */
    public enum Decision {
        EMIT,
        IGNORED_EXCLUDED_USER,
        IGNORED_NO_TRIGGER_FIELD,
        IGNORED_UNPARSEABLE,
        IGNORED_NO_OBJECT
    }

    private final Set<Long> excludedUsers;
    private final Set<String> triggerFields;

    public ActionLogChangeFilter(Collection<Long> excludedUsers, Collection<String> triggerFields) {
        this.excludedUsers = excludedUsers == null
                ? Collections.<Long>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(excludedUsers));
        Set<String> fields = new LinkedHashSet<>();
        if (triggerFields != null) {
            for (String f : triggerFields) {
                if (f != null && !f.trim().isEmpty()) {
                    fields.add(f.trim());
                }
            }
        }
        if (fields.isEmpty()) {
            fields.add(DEFAULT_TRIGGER_FIELD);
        }
        this.triggerFields = Collections.unmodifiableSet(fields);
    }

    public Set<Long> getExcludedUsers() {
        return excludedUsers;
    }

    public Set<String> getTriggerFields() {
        return triggerFields;
    }

    /**
     * Campos cambiados segun la fila, o {@code null} si ni {@code info} ni {@code diff} se pueden
     * interpretar como un objeto JSON.
     */
    static Set<String> changedFields(String info, String diff) {
        JSONObject infoObj = parseObject(info);
        if (infoObj != null) {
            return new LinkedHashSet<>(infoObj.keySet());
        }
        JSONObject diffObj = parseObject(diff);
        if (diffObj != null) {
            JSONObject d = diffObj.optJSONObject("D");
            if (d != null) {
                return new LinkedHashSet<>(d.keySet());
            }
        }
        return null;
    }

    private static JSONObject parseObject(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JSONTokener tokener = new JSONTokener(trimmed);
            Object value = tokener.nextValue();
            // Basura detras del objeto = fila corrupta o truncada; mejor no fiarse.
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
                return null;
            }
            return (JSONObject) value;
        } catch (JSONException e) {
            return null;
        }
    }

    public Decision classify(ActionLogEvent event) {
        if (excludedUsers.contains(event.getUser())) {
            return Decision.IGNORED_EXCLUDED_USER;
        }
        if (event.getObject() == null || event.getObject() <= 0) {
            return Decision.IGNORED_NO_OBJECT;
        }
        Set<String> changed = changedFields(event.getInfo(), event.getDiff());
        if (changed == null) {
            return Decision.IGNORED_UNPARSEABLE;
        }
        for (String field : triggerFields) {
            if (changed.contains(field)) {
                return Decision.EMIT;
            }
        }
        return Decision.IGNORED_NO_TRIGGER_FIELD;
    }

    /**
     * Plan de un lote: a quien emitir (coalescido), hasta que {@code action_id} se ha leido y
     * cuantos eventos se ignoraron por cada motivo.
     *
     * @param events filas ordenadas por {@code action_id} ascendente
     */
    public BatchPlan plan(List<ActionLogEvent> events) {
        Map<Long, Long> lastEventByPatron = new LinkedHashMap<>();
        Map<Decision, Integer> counts = new EnumMap<>(Decision.class);
        long maxScanned = -1;
        for (ActionLogEvent e : events) {
            maxScanned = Math.max(maxScanned, e.getActionId());
            Decision d = classify(e);
            counts.merge(d, 1, Integer::sum);
            if (d == Decision.EMIT) {
                // remove + put: el patron pasa al final, en la posicion de su ultimo evento.
                lastEventByPatron.remove(e.getObject());
                lastEventByPatron.put(e.getObject(), e.getActionId());
            } else if (d == Decision.IGNORED_UNPARSEABLE) {
                LOG.warn("Live sync Koha: action_logs.action_id={0} (patron {1}) tiene info/diff que no es JSON; "
                        + "se salta el evento y el token avanza igual.", e.getActionId(), e.getObject());
            } else if (LOG.isOk()) {
                LOG.ok("Live sync Koha: action_id={0} ignorado ({1}).", e.getActionId(), d);
            }
        }
        List<Emission> emissions = new ArrayList<>(lastEventByPatron.size());
        for (Map.Entry<Long, Long> entry : lastEventByPatron.entrySet()) {
            emissions.add(new Emission(entry.getKey(), entry.getValue()));
        }
        return new BatchPlan(emissions, maxScanned, counts);
    }

    /** Un patron a releer y emitir, con el token ({@code action_id}) de su ultimo evento. */
    public static final class Emission {
        private final long borrowernumber;
        private final long actionId;

        Emission(long borrowernumber, long actionId) {
            this.borrowernumber = borrowernumber;
            this.actionId = actionId;
        }

        public long getBorrowernumber() {
            return borrowernumber;
        }

        public long getActionId() {
            return actionId;
        }

        @Override
        public String toString() {
            return "Emission{patron=" + borrowernumber + ", action_id=" + actionId + "}";
        }
    }

    public static final class BatchPlan {
        private final List<Emission> emissions;
        private final long maxScannedActionId;
        private final Map<Decision, Integer> counts;

        BatchPlan(List<Emission> emissions, long maxScannedActionId, Map<Decision, Integer> counts) {
            this.emissions = Collections.unmodifiableList(emissions);
            this.maxScannedActionId = maxScannedActionId;
            this.counts = Collections.unmodifiableMap(counts);
        }

        /** En orden ascendente de {@code action_id}. */
        public List<Emission> getEmissions() {
            return emissions;
        }

        /** Mayor {@code action_id} leido en el lote, o -1 si el lote estaba vacio. */
        public long getMaxScannedActionId() {
            return maxScannedActionId;
        }

        public int count(Decision decision) {
            Integer n = counts.get(decision);
            return n == null ? 0 : n;
        }

        @Override
        public String toString() {
            return "BatchPlan{emisiones=" + emissions.size() + ", maxActionId=" + maxScannedActionId
                    + ", cuentas=" + counts + "}";
        }
    }
}
