package com.identicum.connectors.sync;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.SyncDeltaBuilder;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;

import java.util.List;

/**
 * Orquesta una pasada del live sync: lee {@code action_logs} por lotes desde el token, filtra
 * con {@link ActionLogChangeFilter}, relee el patron actual y emite un {@code SyncDelta}
 * {@code CREATE_OR_UPDATE} por patron.
 *
 * <p><b>Token</b> = {@code action_id} (Long). Cada delta lleva el {@code action_id} del ultimo
 * evento de ese patron; al terminar, el token final es el ultimo {@code action_id} leido, de modo
 * que los eventos ignorados tambien hacen avanzar el cursor y no se releen.</p>
 *
 * <p><b>Patron inexistente</b> (borrado entre el cambio y la lectura): se salta, no se emite
 * {@code DELETE}. Este feed solo transporta cambios de {@code preferred_name}; la existencia de
 * la cuenta la gobierna midPoint (el recurso tiene la capacidad de borrado deshabilitada) y una
 * baja real en Koha la detecta la reconciliacion. Un {@code DELETE} aqui dispararia la reaccion
 * {@code deleted} de midPoint por un evento que no es una baja.</p>
 *
 * <p><b>Errores al leer un patron</b> distintos de «no existe» (Koha caido, 5xx): se propagan.
 * La pasada se corta, midPoint conserva el token del ultimo delta procesado y la siguiente pasada
 * reintenta: no se pierde ningun cambio.</p>
 */
public final class KohaLiveSync {

    private static final Log LOG = Log.getLog(KohaLiveSync.class);

    /** Fuente de eventos (en produccion, {@code ActionLogSyncService} por JDBC). */
    public interface EventSource {
        /** {@code MAX(action_id)} actual (0 si la tabla esta vacia). */
        long getMaxActionId();

        /** Ultimo {@code action_id} que es seguro leer ahora (ventana de seguridad). */
        long safeUpperBound(int safetyLagSeconds);

        List<ActionLogEvent> fetchBatch(long afterId, long upperBound, int limit);
    }

    /** Lee el patron actual; {@code null} o {@link UnknownUidException} si ya no existe. */
    public interface PatronReader {
        ConnectorObject read(long borrowernumber);
    }

    private final EventSource source;
    private final PatronReader reader;
    private final ActionLogChangeFilter filter;
    private final int batchSize;
    private final int safetyLagSeconds;

    public KohaLiveSync(EventSource source, PatronReader reader, ActionLogChangeFilter filter,
                        int batchSize, int safetyLagSeconds) {
        this.source = source;
        this.reader = reader;
        this.filter = filter;
        this.batchSize = batchSize;
        this.safetyLagSeconds = safetyLagSeconds;
    }

    /** Resultado de una pasada. */
    public static final class Result {
        private final long finalToken;
        private final boolean stoppedByHandler;
        private final int emitted;
        private final int ignored;
        private final int missingPatrons;

        Result(long finalToken, boolean stoppedByHandler, int emitted, int ignored, int missingPatrons) {
            this.finalToken = finalToken;
            this.stoppedByHandler = stoppedByHandler;
            this.emitted = emitted;
            this.ignored = ignored;
            this.missingPatrons = missingPatrons;
        }

        /** Token a guardar al final de la pasada (si no la corto el handler). */
        public long getFinalToken() {
            return finalToken;
        }

        /** {@code true} si el handler devolvio {@code false}: no hay que publicar token final. */
        public boolean isStoppedByHandler() {
            return stoppedByHandler;
        }

        public int getEmitted() {
            return emitted;
        }

        public int getIgnored() {
            return ignored;
        }

        public int getMissingPatrons() {
            return missingPatrons;
        }

        @Override
        public String toString() {
            return "Result{token=" + finalToken + ", emitidos=" + emitted + ", ignorados=" + ignored
                    + ", patronsInexistentes=" + missingPatrons + ", cortadoPorHandler=" + stoppedByHandler + "}";
        }
    }

    public static Long parseToken(SyncToken token) {
        if (token == null || token.getValue() == null) {
            return null;
        }
        Object v = token.getValue();
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Token de live sync de Koha no valido (se espera un action_id): '" + v + "'", e);
        }
    }

    public Result run(Long fromToken, SyncResultsHandler handler) {
        if (fromToken == null) {
            // Sin token: no se reprocesa la historia; se arranca desde el presente.
            long latest = source.getMaxActionId();
            LOG.info("Live sync Koha sin token previo: se arranca en action_id={0} sin emitir la historia.", latest);
            return new Result(latest, false, 0, 0, 0);
        }
        long cursor = fromToken;
        long upper = source.safeUpperBound(safetyLagSeconds);
        if (upper <= cursor) {
            LOG.ok("Live sync Koha: nada nuevo (token={0}, tope seguro={1}).", cursor, upper);
            return new Result(cursor, false, 0, 0, 0);
        }
        int emitted = 0;
        int ignored = 0;
        int missing = 0;
        while (true) {
            List<ActionLogEvent> batch = source.fetchBatch(cursor, upper, batchSize);
            ActionLogChangeFilter.BatchPlan plan = filter.plan(batch);
            ignored += batch.size() - plan.count(ActionLogChangeFilter.Decision.EMIT);
            LOG.ok("Live sync Koha: lote desde action_id>{0}: {1} filas, {2}.", cursor, batch.size(), plan);
            for (ActionLogChangeFilter.Emission em : plan.getEmissions()) {
                ConnectorObject co;
                try {
                    co = reader.read(em.getBorrowernumber());
                } catch (UnknownUidException e) {
                    co = null;
                }
                if (co == null) {
                    missing++;
                    LOG.warn("Live sync Koha: el patron {0} (action_id={1}) ya no existe; se salta.",
                            em.getBorrowernumber(), em.getActionId());
                    continue;
                }
                SyncDeltaBuilder b = new SyncDeltaBuilder();
                b.setDeltaType(SyncDeltaType.CREATE_OR_UPDATE);
                b.setToken(new SyncToken(em.getActionId()));
                b.setObjectClass(ObjectClass.ACCOUNT);
                b.setObject(co);
                emitted++;
                if (!handler.handle(b.build())) {
                    LOG.info("Live sync Koha: el handler pidio parar en action_id={0}.", em.getActionId());
                    return new Result(em.getActionId(), true, emitted, ignored, missing);
                }
            }
            if (batch.size() < batchSize) {
                // Leido todo lo visible hasta el tope: los ids intermedios (otros modulos) tampoco
                // hay que releerlos.
                cursor = upper;
                break;
            }
            cursor = plan.getMaxScannedActionId();
        }
        Result r = new Result(cursor, false, emitted, ignored, missing);
        LOG.info("Live sync Koha terminado: {0}", r);
        return r;
    }
}
