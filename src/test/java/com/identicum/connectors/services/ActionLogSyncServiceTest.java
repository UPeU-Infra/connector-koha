package com.identicum.connectors.services;

import com.identicum.connectors.sync.ActionLogEvent;
import org.identityconnectors.framework.common.exceptions.ConnectionFailedException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.PermissionDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionLogSyncServiceTest {

    @Mock private JdbcConnectionProvider provider;
    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private PreparedStatement ps;
    @Mock private ResultSet rs;

    private ActionLogSyncService service;

    @BeforeEach
    void setUp() {
        service = new ActionLogSyncService(provider, "koha_upeu", "koha_connector");
    }

    /** Lo que devuelve MariaDB 11 cuando falta el GRANT (errorCode 1142, SQLState 42000). */
    private static SQLException selectDenied() {
        return new SQLSyntaxErrorException(
                "SELECT command denied to user 'koha_connector'@'10.0.0.1' for table `koha_upeu`.`action_logs`",
                "42000", 1142);
    }

    @Test
    void sinGrantDaPermissionDeniedConElGrantExacto() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(selectDenied());

        PermissionDeniedException e = assertThrows(PermissionDeniedException.class, service::getMaxActionId);
        assertTrue(e.getMessage().contains("GRANT SELECT ON `koha_upeu`.`action_logs` TO 'koha_connector'@"),
                e.getMessage());
        assertTrue(e.getMessage().contains("'koha_connector'@'10.0.0.1'"), "conserva el usuario@host real del error");
    }

    @Test
    void sinGrantEnElLoteTambienEsPermissionDenied() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenThrow(selectDenied());
        assertThrows(PermissionDeniedException.class, () -> service.fetchBatch(0, 10, 5));
    }

    @Test
    void conexionCaidaEsConnectionFailed() throws SQLException {
        when(provider.getConnection()).thenThrow(new SQLNonTransientConnectionException("down", "08000"));
        assertThrows(ConnectionFailedException.class, service::getMaxActionId);
    }

    @Test
    void tablaInexistenteEsErrorClaro() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLSyntaxErrorException("no table", "42S02", 1146));
        ConnectorIOException e = assertThrows(ConnectorIOException.class, service::getMaxActionId);
        assertTrue(e.getMessage().contains("action_logs"));
    }

    @Test
    void maxActionIdDeTablaVaciaEsCero() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(ActionLogSyncService.SQL_MAX_ID)).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(0L); // MAX() de tabla vacia = NULL -> getLong = 0
        assertEquals(0L, service.getMaxActionId());
    }

    @Test
    void topeSeguroSeDetieneAntesDeLaPrimeraFilaReciente() throws SQLException {
        ResultSet rsMax = org.mockito.Mockito.mock(ResultSet.class);
        when(provider.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(ActionLogSyncService.SQL_MAX_ID)).thenReturn(rsMax);
        when(rsMax.next()).thenReturn(true);
        when(rsMax.getLong(1)).thenReturn(1000L);
        when(connection.prepareStatement(ActionLogSyncService.SQL_FIRST_YOUNG_ID)).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(990L);
        when(rs.wasNull()).thenReturn(false);

        assertEquals(989L, service.safeUpperBound(10));
        verify(ps).setInt(1, 10);
    }

    @Test
    void sinFilasRecientesElTopeEsElMaximo() throws SQLException {
        ResultSet rsMax = org.mockito.Mockito.mock(ResultSet.class);
        when(provider.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(ActionLogSyncService.SQL_MAX_ID)).thenReturn(rsMax);
        when(rsMax.next()).thenReturn(true);
        when(rsMax.getLong(1)).thenReturn(1000L);
        when(connection.prepareStatement(ActionLogSyncService.SQL_FIRST_YOUNG_ID)).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(0L);
        when(rs.wasNull()).thenReturn(true);

        assertEquals(1000L, service.safeUpperBound(10));
    }

    @Test
    void loteMapeaColumnasYRespetaObjectNulo() throws SQLException {
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(ActionLogSyncService.SQL_BATCH)).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong("object")).thenReturn(0L);
        when(rs.wasNull()).thenReturn(true);
        when(rs.getLong("action_id")).thenReturn(77L);
        when(rs.getLong("user")).thenReturn(0L);
        when(rs.getString("interface")).thenReturn("commandline");
        when(rs.getString("info")).thenReturn("{\"preferred_name\":\"X\"}");
        when(rs.getString("diff")).thenReturn(null);

        List<ActionLogEvent> out = service.fetchBatch(70, 80, 5);
        assertEquals(1, out.size());
        assertEquals(77L, out.get(0).getActionId());
        assertNull(out.get(0).getObject());
        verify(ps).setLong(1, 70);
        verify(ps).setLong(2, 80);
        verify(ps).setInt(3, 5);
    }

    @Test
    void sinColumnaDiffReintentaSinElla() throws SQLException {
        PreparedStatement psNoDiff = org.mockito.Mockito.mock(PreparedStatement.class);
        when(provider.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(ActionLogSyncService.SQL_BATCH))
                .thenThrow(new SQLSyntaxErrorException("Unknown column 'diff'", "42S22", 1054));
        when(connection.prepareStatement(ActionLogSyncService.SQL_BATCH_NO_DIFF)).thenReturn(psNoDiff);
        when(psNoDiff.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        assertEquals(0, service.fetchBatch(0, 10, 5).size());
    }

    @Test
    void grantStatement() {
        assertEquals("GRANT SELECT ON `koha_upeu`.`action_logs` TO 'koha_connector'@'10.1.2.3';",
                ActionLogSyncService.grantStatement("koha_upeu", "koha_connector", "10.1.2.3"));
    }
}
