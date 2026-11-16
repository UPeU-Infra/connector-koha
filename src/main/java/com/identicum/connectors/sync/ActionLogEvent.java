package com.identicum.connectors.sync;

/**
 * Una fila de {@code action_logs} de Koha con {@code module='MEMBERS' AND action='MODIFY'}.
 *
 * <p>Solo transporta lo que el live sync necesita: el cursor ({@code action_id}), quien hizo el
 * cambio ({@code user} = borrowernumber del usuario autenticado, 0 si no habia sesion), sobre
 * que patron ({@code object}) y por que interfaz, ademas de {@code info}/{@code diff} para saber
 * QUE campos cambiaron. Los valores de esos campos no se usan: el conector relee siempre el
 * estado actual del patron por REST.</p>
 */
public final class ActionLogEvent {

    private final long actionId;
    private final long user;
    private final Long object;
    private final String iface;
    private final String info;
    private final String diff;

    public ActionLogEvent(long actionId, long user, Long object, String iface, String info, String diff) {
        this.actionId = actionId;
        this.user = user;
        this.object = object;
        this.iface = iface;
        this.info = info;
        this.diff = diff;
    }

    public long getActionId() {
        return actionId;
    }

    /** borrowernumber del usuario que hizo el cambio; 0 = sin sesion (cron, linea de comandos). */
    public long getUser() {
        return user;
    }

    /** borrowernumber del patron modificado; {@code null} si la fila no lo trae. */
    public Long getObject() {
        return object;
    }

    /** {@code intranet}, {@code opac}, {@code api}, {@code cron}, {@code commandline}... */
    public String getInterface() {
        return iface;
    }

    public String getInfo() {
        return info;
    }

    public String getDiff() {
        return diff;
    }

    @Override
    public String toString() {
        // Sin info/diff: contienen datos personales (nombres, correos) y esto acaba en logs.
        return "ActionLogEvent{action_id=" + actionId + ", user=" + user + ", object=" + object
                + ", interface=" + iface + "}";
    }
}
