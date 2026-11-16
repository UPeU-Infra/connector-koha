package com.identicum.connectors.services;

import com.identicum.connectors.sync.ActionLogEvent;
import com.identicum.connectors.sync.KohaLiveSync;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Lectura de {@code action_logs} de Koha por JDBC para el live sync (solo SELECT).
 *
 * <p>Requiere {@code BorrowersLog=1} en Koha (si no, Koha no registra los {@code MEMBERS/MODIFY})
 * y {@code GRANT SELECT ON <bd>.action_logs} para el usuario JDBC del conector. Sin ese permiso
 * se lanza {@link PermissionDeniedException} con el GRANT exacto que hay que ejecutar.</p>
 *
 * <h2>Ventana de seguridad</h2>
 * <p>{@code action_id} es AUTO_INCREMENT y Koha escribe el log DENTRO de la transaccion del
 * {@code store()} del patron. Una fila con id menor puede hacerse visible despues que otra con id
 * mayor; si el cursor saltara por encima de ella, ese cambio se perderia para siempre. Por eso
 * {@link #safeUpperBound(int)} calcula un tope: no se lee mas alla de la primera fila con menos de
 * {@code safetyLagSeconds} de antiguedad. Mientras ninguna transaccion de Koha dure mas que esa
 * ventana, no se pierde nada.</p>
 */
public class ActionLogSyncService implements KohaLiveSync.EventSource {

    private static final Log LOG = Log.getLog(ActionLogSyncService.class);

    static final String SQL_MAX_ID = "SELECT MAX(action_id) FROM action_logs";
    static final String SQL_FIRST_YOUNG_ID =
            "SELECT MIN(action_id) FROM action_logs WHERE `timestamp` > (NOW() - INTERVAL ? SECOND)";
    static final String SQL_BATCH =
            "SELECT action_id, `user`, `object`, `interface`, info, diff FROM action_logs"
                    + " WHERE action_id > ? AND action_id <= ? AND module = 'MEMBERS' AND action = 'MODIFY'"
                    + " ORDER BY action_id LIMIT ?";
    /** Koha anterior a la columna {@code diff}: misma consulta sin ella. */
    static final String SQL_BATCH_NO_DIFF =
            "SELECT action_id, `user`, `object`, `interface`, info, NULL AS diff FROM action_logs"
                    + " WHERE action_id > ? AND action_id <= ? AND module = 'MEMBERS' AND action = 'MODIFY'"
                    + " ORDER BY action_id LIMIT ?";

    // Codigos de error de MariaDB/MySQL
    static final int ER_DBACCESS_DENIED = 1044;
    static final int ER_TABLEACCESS_DENIED = 1142;
    static final int ER_COLUMNACCESS_DENIED = 1143;
    static final int ER_BAD_FIELD = 1054;
    static final int ER_NO_SUCH_TABLE = 1146;

    private final JdbcConnectionProvider connectionProvider;
    private final String dbName;
    private final String dbUser;
    private volatile boolean diffColumnAvailable = true;

    public ActionLogSyncService(JdbcConnectionProvider connectionProvider, String dbName, String dbUser) {
        this.connectionProvider = connectionProvider;
        this.dbName = dbName;
        this.dbUser = dbUser;
    }

    /** {@code MAX(action_id)} actual: el token inicial, para no reprocesar la historia. */
    @Override
    public long getMaxActionId() {
        try (Connection conn = connectionProvider.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(SQL_MAX_ID)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw translate(e, "leer MAX(action_id) de action_logs");
        }
    }

    /**
     * Ultimo {@code action_id} que es seguro leer: {@code MAX(action_id)} o, si hay filas con menos
     * de {@code safetyLagSeconds} de antiguedad, el id anterior a la primera de ellas.
     */
    @Override
    public long safeUpperBound(int safetyLagSeconds) {
        try (Connection conn = connectionProvider.getConnection()) {
            long max;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(SQL_MAX_ID)) {
                max = rs.next() ? rs.getLong(1) : 0L;
            }
            long upper = max;
            if (safetyLagSeconds > 0) {
                try (PreparedStatement ps = conn.prepareStatement(SQL_FIRST_YOUNG_ID)) {
                    ps.setInt(1, safetyLagSeconds);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long firstYoung = rs.getLong(1);
                            if (!rs.wasNull()) {
                                upper = Math.min(max, firstYoung - 1);
                            }
                        }
                    }
                }
            }
            return upper;
        } catch (SQLException e) {
            throw translate(e, "leer la ventana de action_logs");
        }
    }

    /**
     * Filas {@code MEMBERS/MODIFY} con {@code afterId < action_id <= upperBound}, en orden, como
     * maximo {@code limit}.
     */
    @Override
    public List<ActionLogEvent> fetchBatch(long afterId, long upperBound, int limit) {
        try (Connection conn = connectionProvider.getConnection()) {
            try {
                return runBatch(conn, diffColumnAvailable ? SQL_BATCH : SQL_BATCH_NO_DIFF, afterId, upperBound, limit);
            } catch (SQLException e) {
                if (diffColumnAvailable && e.getErrorCode() == ER_BAD_FIELD) {
                    LOG.warn("action_logs no tiene la columna diff (Koha antiguo); se lee solo info.");
                    diffColumnAvailable = false;
                    return runBatch(conn, SQL_BATCH_NO_DIFF, afterId, upperBound, limit);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw translate(e, "leer action_logs (action_id > " + afterId + ")");
        }
    }

    private static List<ActionLogEvent> runBatch(Connection conn, String sql, long afterId, long upperBound, int limit)
            throws SQLException {
        List<ActionLogEvent> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, afterId);
            ps.setLong(2, upperBound);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long object = rs.getLong("object");
                    Long objectOrNull = rs.wasNull() ? null : object;
                    out.add(new ActionLogEvent(
                            rs.getLong("action_id"),
                            rs.getLong("user"),
                            objectOrNull,
                            rs.getString("interface"),
                            rs.getString("info"),
                            rs.getString("diff")));
                }
            }
        }
        return out;
    }

    public void testAccess() {
        getMaxActionId();
    }

    RuntimeException translate(SQLException e, String action) {
        int code = e.getErrorCode();
        if (code == ER_TABLEACCESS_DENIED || code == ER_COLUMNACCESS_DENIED || code == ER_DBACCESS_DENIED) {
            String msg = "Live sync de Koha: el usuario JDBC '" + dbUser + "' no puede leer "
                    + dbName + ".action_logs (" + e.getMessage() + "). "
                    + "Un administrador de la BD de Koha debe ejecutar: "
                    + grantStatement(dbName, dbUser, "<host_del_conector>")
                    + " — el host es el que muestra el propio error de MariaDB (usuario@host).";
            LOG.error(e, msg);
            return new PermissionDeniedException(msg, e);
        }
        if (code == ER_NO_SUCH_TABLE) {
            return new ConnectorIOException("Live sync de Koha: no existe la tabla " + dbName
                    + ".action_logs. Compruebe dbName. Detalle: " + e.getMessage(), e);
        }
        LOG.error(e, "Error JDBC al {0}. SQLState={1}, errorCode={2}.", action, e.getSQLState(), code);
        if (e instanceof SQLTransientConnectionException
                || e instanceof SQLNonTransientConnectionException
                || (e.getSQLState() != null && e.getSQLState().startsWith("08"))) {
            return new ConnectionFailedException("Fallo la conexion JDBC al " + action + ": " + e.getMessage(), e);
        }
        return new ConnectorIOException("Error JDBC al " + action + ": " + e.getMessage(), e);
    }

    /** El GRANT minimo que necesita el live sync. */
    public static String grantStatement(String dbName, String dbUser, String host) {
        return "GRANT SELECT ON `" + dbName + "`.`action_logs` TO '" + dbUser + "'@'" + host + "';";
    }
}
