package com.identicum.connectors;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.spi.SyncTokenResultsHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prueba del live sync con el conector COMPLETO (REST por OAuth2 + JDBC) contra el Koha de
 * DESARROLLO, despues de escrituras reales hechas fuera del test (por la API con las cuentas
 * sinteticas, o por {@code Koha::Patron->store} en koha-shell). El test solo LEE: llama a
 * {@code getLatestSyncToken} y a {@code sync} desde {@code KOHA_LIVE_IT_FROM} e imprime lo que
 * emite el conector (token, uid, nombres). Se salta si no hay {@code KOHA_LIVE_IT_URL}.
 *
 * <p>Variables (pasarlas con {@code --env-file}, nunca en argv): {@code KOHA_LIVE_IT_URL},
 * {@code _CLIENT_ID}, {@code _SECRET} (cuenta de API del conector), {@code _DB_HOST},
 * {@code _DB_PORT}, {@code _DB_NAME}, {@code _DB_USER}, {@code _DB_PASS}, {@code _FROM} (token de
 * partida; vacio = sin token), {@code _EXCLUDE_LOGINS} (userid excluidos, separados por coma),
 * {@code _LAG} (ventana de seguridad, por defecto 10) y, para la alerta de Discovery,
 * {@code _ALERT_USER} (borrowernumber de la cuenta de Discovery).</p>
 *
 * <p>Solo para datos SINTETICOS: imprime nombres de los patrons emitidos.</p>
 */
@EnabledIfEnvironmentVariable(named = "KOHA_LIVE_IT_URL", matches = ".+")
class KohaLiveSyncWriteDevIT {

    /**
     * Alerta (mitigacion 3 del diseno): todo lo que registre la cuenta de Discovery en
     * {@code action_logs} que NO sea un {@code MEMBERS/MODIFY} de solo {@code preferred_name}
     * (otro campo, varios campos, un borrado, un cambio de clave...). Misma consulta que documenta
     * {@code doc/livesync-preferred-name.md}. Sirve para el formato de {@code info} de 25.11 y de
     * 26.05 (en ambos las claves son los campos cambiados).
     */
    static final String SQL_ALERT_DISCOVERY =
            "SELECT action_id, `timestamp`, module, action, `object`, `interface`,"
                    + "       IF(COALESCE(JSON_VALID(info), 0) = 1, JSON_KEYS(info), NULL) AS campos"
                    + " FROM action_logs"
                    + " WHERE `user` = ? AND action_id > ?"
                    + "   AND NOT (module = 'MEMBERS' AND action = 'MODIFY'"
                    + "            AND COALESCE(JSON_VALID(info), 0) = 1"
                    + "            AND JSON_LENGTH(info) = 1"
                    + "            AND JSON_CONTAINS_PATH(info, 'one', '$.preferred_name') = 1)"
                    + " ORDER BY action_id";

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? def : v;
    }

    private static KohaConnector connector() {
        KohaConfiguration c = new KohaConfiguration();
        c.setServiceAddress(env("KOHA_LIVE_IT_URL", null));
        c.setAuthenticationMethodStrategy("OAUTH2");
        c.setClientId(env("KOHA_LIVE_IT_CLIENT_ID", null));
        c.setClientSecret(new GuardedString(env("KOHA_LIVE_IT_SECRET", "").toCharArray()));
        c.setDbEnabled(true);
        c.setDbHost(env("KOHA_LIVE_IT_DB_HOST", null));
        c.setDbPort(Integer.parseInt(env("KOHA_LIVE_IT_DB_PORT", "3306")));
        c.setDbName(env("KOHA_LIVE_IT_DB_NAME", null));
        c.setDbUser(env("KOHA_LIVE_IT_DB_USER", null));
        c.setDbPassword(new GuardedString(env("KOHA_LIVE_IT_DB_PASS", "").toCharArray()));
        c.setDbPoolSize(1);
        String logins = env("KOHA_LIVE_IT_EXCLUDE_LOGINS", "");
        c.setSyncExcludeUserLogins(logins.isEmpty() ? new String[0] : logins.split(","));
        c.setSyncSafetyLagSeconds(Integer.parseInt(env("KOHA_LIVE_IT_LAG", "10")));
        KohaConnector k = new KohaConnector();
        k.init(c);
        return k;
    }

    private static String attr(ConnectorObject co, String name) {
        Attribute a = co.getAttributeByName(name);
        return a == null || a.getValue() == null || a.getValue().isEmpty() ? "null" : "'" + a.getValue().get(0) + "'";
    }

    @Test
    void syncDesdeElToken() {
        KohaConnector k = connector();
        try {
            SyncToken latest = k.getLatestSyncToken(ObjectClass.ACCOUNT);
            System.out.println("[LIVE-IT] getLatestSyncToken = " + latest.getValue());
            String from = env("KOHA_LIVE_IT_FROM", "");
            SyncToken token = from.isEmpty() ? null : new SyncToken(Long.parseLong(from));
            List<SyncDelta> deltas = new ArrayList<>();
            AtomicReference<Object> finalToken = new AtomicReference<>();
            SyncTokenResultsHandler handler = new SyncTokenResultsHandler() {
                @Override
                public boolean handle(SyncDelta delta) {
                    deltas.add(delta);
                    return true;
                }

                @Override
                public void handleResult(SyncToken t) {
                    finalToken.set(t == null ? null : t.getValue());
                }
            };
            k.sync(ObjectClass.ACCOUNT, token, handler, new OperationOptionsBuilder().build());
            System.out.println("[LIVE-IT] sync desde " + (token == null ? "<sin token>" : token.getValue())
                    + ": " + deltas.size() + " delta(s), token final = " + finalToken.get());
            for (SyncDelta d : deltas) {
                ConnectorObject co = d.getObject();
                System.out.println("[LIVE-IT]   " + d.getDeltaType() + " token=" + d.getToken().getValue()
                        + " uid=" + d.getUid().getUidValue() + " name=" + co.getName().getNameValue()
                        + " preferred_name=" + attr(co, "preferred_name") + " firstname=" + attr(co, "firstname")
                        + " surname=" + attr(co, "surname"));
            }
            assertNotNull(finalToken.get(), "el conector publica siempre el token final");
        } finally {
            k.dispose();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KOHA_LIVE_IT_ALERT_USER", matches = "\\d+")
    void alertaCambiosDeDiscoveryFueraDePreferredName() throws Exception {
        String url = "jdbc:mariadb://" + env("KOHA_LIVE_IT_DB_HOST", null) + ":" + env("KOHA_LIVE_IT_DB_PORT", "3306")
                + "/" + env("KOHA_LIVE_IT_DB_NAME", null);
        try (Connection conn = DriverManager.getConnection(url, env("KOHA_LIVE_IT_DB_USER", null),
                env("KOHA_LIVE_IT_DB_PASS", null));
             PreparedStatement ps = conn.prepareStatement(SQL_ALERT_DISCOVERY)) {
            ps.setLong(1, Long.parseLong(env("KOHA_LIVE_IT_ALERT_USER", null)));
            ps.setLong(2, Long.parseLong(env("KOHA_LIVE_IT_ALERT_FROM", "0")));
            int n = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++;
                    System.out.println("[ALERTA] action_id=" + rs.getLong("action_id") + " ts=" + rs.getString("timestamp")
                            + " " + rs.getString("module") + "/" + rs.getString("action") + " patron=" + rs.getLong("object") + " interface=" + rs.getString("interface")
                            + " campos=" + rs.getString("campos"));
                }
            }
            System.out.println("[ALERTA] filas que no son solo preferred_name: " + n);
        }
    }
}
