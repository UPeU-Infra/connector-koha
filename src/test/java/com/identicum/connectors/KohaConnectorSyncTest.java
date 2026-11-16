package com.identicum.connectors;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.operations.SyncApiOp;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfo;
import org.identityconnectors.framework.common.objects.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.8.0 — SyncOp: errores claros sin canal JDBC y esquema que lo anuncia solo para cuentas. */
class KohaConnectorSyncTest {

    private final KohaConnector connector = new KohaConnector();

    private static KohaConfiguration restOnly() {
        KohaConfiguration c = new KohaConfiguration();
        c.setServiceAddress("http://koha.invalid");
        c.setAuthenticationMethodStrategy("BASIC");
        c.setUsername("npsync-ficticio");
        c.setPassword(new GuardedString("x".toCharArray()));
        c.setDbEnabled(false);
        return c;
    }

    @AfterEach
    void tearDown() {
        connector.dispose();
    }

    @Test
    void sinCanalJdbcElTokenDaErrorDeConfiguracionNoNpe() {
        connector.init(restOnly());
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> connector.getLatestSyncToken(ObjectClass.ACCOUNT));
        assertTrue(e.getMessage().contains("dbEnabled=true"), e.getMessage());
        assertThrows(ConfigurationException.class,
                () -> connector.sync(ObjectClass.ACCOUNT, null, d -> true, null));
    }

    @Test
    void syncDeGruposNoSoportado() {
        connector.init(restOnly());
        assertThrows(UnsupportedOperationException.class,
                () -> connector.getLatestSyncToken(ObjectClass.GROUP));
    }

    @Test
    void esquemaAnunciaSyncSoloParaCuentas() {
        connector.init(restOnly());
        Schema schema = connector.schema();
        Set<ObjectClassInfo> withSync = schema.getSupportedObjectClassesByOperation(SyncApiOp.class);
        assertTrue(withSync.contains(schema.findObjectClassInfo(ObjectClass.ACCOUNT_NAME)));
        assertFalse(withSync.contains(schema.findObjectClassInfo(ObjectClass.GROUP_NAME)));
    }
}
