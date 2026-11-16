package com.identicum.connectors.services;

import com.identicum.connectors.sync.ActionLogChangeFilter;
import com.identicum.connectors.sync.ActionLogChangeFilter.BatchPlan;
import com.identicum.connectors.sync.ActionLogChangeFilter.Decision;
import com.identicum.connectors.sync.ActionLogEvent;
import com.identicum.connectors.sync.KohaLiveSync;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba contra una BD real de Koha (SOLO desarrollo; solo SELECT). Se salta si no hay
 * {@code KOHA_SYNC_IT_DB_HOST}. Variables: {@code KOHA_SYNC_IT_DB_HOST}, {@code _DB_PORT},
 * {@code _DB_NAME}, {@code _DB_USER}, {@code _DB_PASS}, {@code KOHA_SYNC_IT_FROM} (action_id de
 * partida, por defecto 0), {@code KOHA_SYNC_IT_EXCLUDE} (borrowernumber separados por coma) y,
 * opcional, {@code KOHA_SYNC_IT_NOGRANT_USER}/{@code _PASS}: un usuario SIN SELECT sobre action_logs
 * para comprobar el error.
 *
 * <p>Imprime solo recuentos: nunca nombres ni el contenido de {@code info}.</p>
 */
@EnabledIfEnvironmentVariable(named = "KOHA_SYNC_IT_DB_HOST", matches = ".+")
class ActionLogSyncDevIT {

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? def : v;
    }

    private static JdbcConnectionProvider provider(String user, String pass) {
        return new JdbcConnectionProvider(env("KOHA_SYNC_IT_DB_HOST", null),
                Integer.parseInt(env("KOHA_SYNC_IT_DB_PORT", "3306")), env("KOHA_SYNC_IT_DB_NAME", null),
                user, pass, 1);
    }

    private static Set<Long> excluded() {
        Set<Long> out = new LinkedHashSet<>();
        for (String s : env("KOHA_SYNC_IT_EXCLUDE", "").split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(Long.parseLong(s.trim()));
            }
        }
        return out;
    }

    @Test
    void recorreLaHistoriaRealSinErrores() {
        JdbcConnectionProvider p = provider(env("KOHA_SYNC_IT_DB_USER", null), env("KOHA_SYNC_IT_DB_PASS", null));
        try {
            ActionLogSyncService service = new ActionLogSyncService(p, env("KOHA_SYNC_IT_DB_NAME", null),
                    env("KOHA_SYNC_IT_DB_USER", null));
            long max = service.getMaxActionId();
            long upper = service.safeUpperBound(10);
            System.out.println("[IT] MAX(action_id)=" + max + " tope_seguro(10s)=" + upper);
            assertTrue(max > 0 && upper <= max);

            ActionLogChangeFilter filter = new ActionLogChangeFilter(excluded(), null);
            long from = Long.parseLong(env("KOHA_SYNC_IT_FROM", "0"));

            // 1) Recorrido crudo con recuentos por decision y por interfaz.
            Map<Decision, Integer> byDecision = new EnumMap<>(Decision.class);
            Map<String, Integer> byInterface = new java.util.TreeMap<>();
            long cursor = from;
            int rows = 0;
            while (true) {
                List<ActionLogEvent> batch = service.fetchBatch(cursor, upper, 1000);
                BatchPlan plan = filter.plan(batch);
                for (Decision d : Decision.values()) {
                    byDecision.merge(d, plan.count(d), Integer::sum);
                }
                for (ActionLogEvent e : batch) {
                    byInterface.merge(String.valueOf(e.getInterface()), 1, Integer::sum);
                }
                rows += batch.size();
                if (batch.size() < 1000) {
                    break;
                }
                cursor = plan.getMaxScannedActionId();
            }
            System.out.println("[IT] filas MEMBERS/MODIFY leidas desde " + from + ": " + rows
                    + " | por decision: " + byDecision + " | por interfaz: " + byInterface);

            // 2) La misma ventana con KohaLiveSync (lector de patrons simulado: sin REST).
            List<SyncDelta> deltas = new ArrayList<>();
            KohaLiveSync sync = new KohaLiveSync(service, bn -> new ConnectorObjectBuilder()
                    .setObjectClass(ObjectClass.ACCOUNT).setUid(String.valueOf(bn)).setName("x" + bn).build(),
                    filter, 500, 10);
            KohaLiveSync.Result r = sync.run(from, deltas::add);
            System.out.println("[IT] KohaLiveSync: " + r + " deltas=" + deltas.size());
            assertEquals(upper, r.getFinalToken(), "el token llega al tope aunque todo se ignore");
            String onlyIds = System.getenv("KOHA_SYNC_IT_SHOW_DELTAS");
            if (onlyIds != null) {
                for (SyncDelta d : deltas) {
                    System.out.println("[IT] delta uid=" + d.getUid().getUidValue() + " token=" + d.getToken().getValue());
                }
            }
        } finally {
            p.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KOHA_SYNC_IT_NOGRANT_USER", matches = ".+")
    void sinGrantErrorClaro() {
        JdbcConnectionProvider p = provider(env("KOHA_SYNC_IT_NOGRANT_USER", null), env("KOHA_SYNC_IT_NOGRANT_PASS", null));
        try {
            ActionLogSyncService service = new ActionLogSyncService(p, env("KOHA_SYNC_IT_DB_NAME", null),
                    env("KOHA_SYNC_IT_NOGRANT_USER", null));
            PermissionDeniedException e = assertThrows(PermissionDeniedException.class, service::getMaxActionId);
            System.out.println("[IT] sin GRANT -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            p.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KOHA_SYNC_IT_GRANTED_USER", matches = ".+")
    void conGrantMinimoFunciona() {
        // Usuario con SOLO "GRANT SELECT ON <bd>.action_logs": demuestra que el GRANT basta.
        JdbcConnectionProvider p = provider(env("KOHA_SYNC_IT_GRANTED_USER", null), env("KOHA_SYNC_IT_GRANTED_PASS", null));
        try {
            ActionLogSyncService service = new ActionLogSyncService(p, env("KOHA_SYNC_IT_DB_NAME", null),
                    env("KOHA_SYNC_IT_GRANTED_USER", null));
            long max = service.getMaxActionId();
            long upper = service.safeUpperBound(10);
            int n = service.fetchBatch(Math.max(0, upper - 50000), upper, 10).size();
            System.out.println("[IT] con GRANT minimo: MAX=" + max + " tope=" + upper + " filas_lote=" + n);
            assertTrue(max > 0);
        } finally {
            p.close();
        }
    }
}
