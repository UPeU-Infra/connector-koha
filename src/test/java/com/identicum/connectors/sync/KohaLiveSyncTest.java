package com.identicum.connectors.sync;

import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.identicum.connectors.sync.ActionLogChangeFilterTest.BIBLIOTECARIO;
import static com.identicum.connectors.sync.ActionLogChangeFilterTest.DISCOVERY;
import static com.identicum.connectors.sync.ActionLogChangeFilterTest.INFO_2511_SURNAME;
import static com.identicum.connectors.sync.ActionLogChangeFilterTest.INFO_2605_PREFERRED;
import static com.identicum.connectors.sync.ActionLogChangeFilterTest.PATRON_A;
import static com.identicum.connectors.sync.ActionLogChangeFilterTest.PATRON_B;
import static com.identicum.connectors.sync.ActionLogChangeFilterTest.SVC_MIDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Avance del token y emision de deltas, con una fuente de eventos en memoria. */
class KohaLiveSyncTest {

    /** action_logs en memoria: solo las filas MEMBERS/MODIFY, como devuelve la consulta real. */
    static final class FakeSource implements KohaLiveSync.EventSource {
        final List<ActionLogEvent> rows = new ArrayList<>();
        long max;
        long upper = Long.MAX_VALUE;
        int fetches;

        @Override
        public long getMaxActionId() {
            return max;
        }

        @Override
        public long safeUpperBound(int safetyLagSeconds) {
            return Math.min(max, upper);
        }

        @Override
        public List<ActionLogEvent> fetchBatch(long afterId, long upperBound, int limit) {
            fetches++;
            List<ActionLogEvent> out = new ArrayList<>();
            for (ActionLogEvent e : rows) {
                if (e.getActionId() > afterId && e.getActionId() <= upperBound && out.size() < limit) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    static final class Reader implements KohaLiveSync.PatronReader {
        final Set<Long> missing = new HashSet<>();
        final Set<Long> unknownUid = new HashSet<>();
        final List<Long> reads = new ArrayList<>();

        @Override
        public ConnectorObject read(long bn) {
            reads.add(bn);
            if (missing.contains(bn)) {
                return null;
            }
            if (unknownUid.contains(bn)) {
                throw new UnknownUidException("no existe " + bn);
            }
            return new ConnectorObjectBuilder()
                    .setObjectClass(ObjectClass.ACCOUNT)
                    .setUid(String.valueOf(bn))
                    .setName("npsync" + bn)
                    .addAttribute(AttributeBuilder.build("preferred_name", "Ficticio"))
                    .build();
        }
    }

    private static ActionLogEvent ev(long id, long user, long object, String info) {
        return new ActionLogEvent(id, user, object, "api", info, null);
    }

    private static KohaLiveSync sync(FakeSource s, Reader r, int batch) {
        return new KohaLiveSync(s, r, new ActionLogChangeFilter(Collections.singleton(SVC_MIDPOINT), null), batch, 10);
    }

    @Test
    void sinTokenNoSeReprocesaLaHistoria() {
        FakeSource s = new FakeSource();
        s.rows.add(ev(5, DISCOVERY, PATRON_A, INFO_2605_PREFERRED));
        s.max = 120;
        List<SyncDelta> deltas = new ArrayList<>();
        KohaLiveSync.Result r = sync(s, new Reader(), 10).run(null, deltas::add);
        assertEquals(120, r.getFinalToken());
        assertTrue(deltas.isEmpty());
        assertEquals(0, s.fetches);
    }

    @Test
    void eventosIgnoradosTambienAvanzanElToken() {
        FakeSource s = new FakeSource();
        s.rows.add(ev(101, SVC_MIDPOINT, PATRON_A, INFO_2605_PREFERRED));   // eco de midPoint
        s.rows.add(ev(102, BIBLIOTECARIO, PATRON_B, INFO_2511_SURNAME));    // evento ajeno
        s.max = 150; // hay filas de otros modulos hasta 150
        List<SyncDelta> deltas = new ArrayList<>();
        KohaLiveSync.Result r = sync(s, new Reader(), 10).run(100L, deltas::add);
        assertTrue(deltas.isEmpty());
        assertEquals(150, r.getFinalToken(), "lote incompleto: se avanza hasta el tope, sin releer nada");
        assertEquals(2, r.getIgnored());
    }

    @Test
    void cadaDeltaLlevaElActionIdDeSuEventoYElObjetoCompleto() {
        FakeSource s = new FakeSource();
        s.rows.add(ev(201, DISCOVERY, PATRON_A, INFO_2605_PREFERRED));
        s.rows.add(ev(202, SVC_MIDPOINT, PATRON_A, INFO_2605_PREFERRED));
        s.max = 205;
        List<SyncDelta> deltas = new ArrayList<>();
        Reader reader = new Reader();
        KohaLiveSync.Result r = sync(s, reader, 10).run(200L, deltas::add);
        assertEquals(1, deltas.size());
        SyncDelta d = deltas.get(0);
        assertEquals(SyncDeltaType.CREATE_OR_UPDATE, d.getDeltaType());
        assertEquals(201L, d.getToken().getValue());
        assertEquals(String.valueOf(PATRON_A), d.getUid().getUidValue());
        assertEquals(ObjectClass.ACCOUNT, d.getObjectClass());
        assertEquals("Ficticio", d.getObject().getAttributeByName("preferred_name").getValue().get(0));
        assertEquals(Collections.singletonList(PATRON_A), reader.reads);
        assertEquals(205, r.getFinalToken());
    }

    @Test
    void lotesCompletosSeEncadenanHastaElTope() {
        FakeSource s = new FakeSource();
        for (long id = 301; id <= 307; id++) {
            s.rows.add(ev(id, DISCOVERY, 800000 + id, INFO_2605_PREFERRED));
        }
        s.max = 310;
        List<SyncDelta> deltas = new ArrayList<>();
        KohaLiveSync.Result r = sync(s, new Reader(), 3).run(300L, deltas::add);
        assertEquals(7, deltas.size());
        assertEquals(3, s.fetches, "3 + 3 + 1 filas");
        assertEquals(310, r.getFinalToken());
        long previous = 0;
        for (SyncDelta d : deltas) {
            long token = (Long) d.getToken().getValue();
            assertTrue(token > previous, "tokens crecientes");
            previous = token;
        }
    }

    @Test
    void laVentanaDeSeguridadNoDejaPasarDelTope() {
        FakeSource s = new FakeSource();
        s.rows.add(ev(401, DISCOVERY, PATRON_A, INFO_2605_PREFERRED));
        s.rows.add(ev(405, DISCOVERY, PATRON_B, INFO_2605_PREFERRED)); // demasiado reciente
        s.max = 406;
        s.upper = 403;
        List<SyncDelta> deltas = new ArrayList<>();
        KohaLiveSync.Result r = sync(s, new Reader(), 10).run(400L, deltas::add);
        assertEquals(1, deltas.size());
        assertEquals(403, r.getFinalToken());
        // Siguiente pasada, ya fuera de la ventana: aparece el 405.
        s.upper = Long.MAX_VALUE;
        deltas.clear();
        r = sync(s, new Reader(), 10).run(r.getFinalToken(), deltas::add);
        assertEquals(1, deltas.size());
        assertEquals(405L, deltas.get(0).getToken().getValue());
        assertEquals(406, r.getFinalToken());
    }

    @Test
    void tokenPorDelanteDelTopeNoRetrocede() {
        FakeSource s = new FakeSource();
        s.max = 500;
        s.upper = 490;
        KohaLiveSync.Result r = sync(s, new Reader(), 10).run(495L, d -> true);
        assertEquals(495, r.getFinalToken());
        assertEquals(0, s.fetches);
    }

    @Test
    void patronInexistenteSeSaltaSinAbortar() {
        FakeSource s = new FakeSource();
        s.rows.add(ev(601, DISCOVERY, PATRON_A, INFO_2605_PREFERRED));
        s.rows.add(ev(602, DISCOVERY, PATRON_B, INFO_2605_PREFERRED));
        s.rows.add(ev(603, DISCOVERY, 800003L, INFO_2605_PREFERRED));
        s.max = 603;
        Reader reader = new Reader();
        reader.missing.add(PATRON_A);
        reader.unknownUid.add(PATRON_B);
        List<SyncDelta> deltas = new ArrayList<>();
        KohaLiveSync.Result r = sync(s, reader, 10).run(600L, deltas::add);
        assertEquals(1, deltas.size());
        assertEquals("800003", deltas.get(0).getUid().getUidValue());
        assertEquals(2, r.getMissingPatrons());
        assertEquals(603, r.getFinalToken());
    }

    @Test
    void filaIlegibleNoAbortaYElTokenLaSupera() {
        FakeSource s = new FakeSource();
        s.rows.add(new ActionLogEvent(701, BIBLIOTECARIO, PATRON_A, "intranet", "{roto", "tambien roto"));
        s.rows.add(ev(702, DISCOVERY, PATRON_B, INFO_2605_PREFERRED));
        s.max = 702;
        List<SyncDelta> deltas = new ArrayList<>();
        KohaLiveSync.Result r = sync(s, new Reader(), 10).run(700L, deltas::add);
        assertEquals(1, deltas.size());
        assertEquals(702, r.getFinalToken());
    }

    @Test
    void siElHandlerPideParar() {
        FakeSource s = new FakeSource();
        s.rows.add(ev(801, DISCOVERY, PATRON_A, INFO_2605_PREFERRED));
        s.rows.add(ev(802, DISCOVERY, PATRON_B, INFO_2605_PREFERRED));
        s.max = 900;
        List<SyncDelta> deltas = new ArrayList<>();
        KohaLiveSync.Result r = sync(s, new Reader(), 10).run(800L, d -> {
            deltas.add(d);
            return false;
        });
        assertEquals(1, deltas.size());
        assertTrue(r.isStoppedByHandler());
        assertEquals(801, r.getFinalToken());
    }

    @Test
    void errorDeLecturaDistintoDeNoExisteSePropaga() {
        FakeSource s = new FakeSource();
        s.rows.add(ev(901, DISCOVERY, PATRON_A, INFO_2605_PREFERRED));
        s.max = 901;
        KohaLiveSync ls = new KohaLiveSync(s, bn -> {
            throw new ConnectorIOException("Koha caido");
        }, new ActionLogChangeFilter(null, null), 10, 10);
        assertThrows(ConnectorIOException.class, () -> ls.run(900L, d -> true));
    }

    @Test
    void parseoDelToken() {
        assertNull(KohaLiveSync.parseToken(null));
        assertEquals(42L, KohaLiveSync.parseToken(new SyncToken(42)));
        assertEquals(42L, KohaLiveSync.parseToken(new SyncToken(42L)));
        assertEquals(42L, KohaLiveSync.parseToken(new SyncToken(" 42 ")));
        assertThrows(IllegalArgumentException.class, () -> KohaLiveSync.parseToken(new SyncToken("abc")));
    }
}
